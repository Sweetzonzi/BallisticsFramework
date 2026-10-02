package io.github.sweetzonzi.ballistics_framework.api;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.internal.BFArmorAdapter;
import io.github.sweetzonzi.ballistics_framework.internal.BFContextStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Objects;

/**
 * 协议层对外入口。
 * <p>
 * 两个伤害入口分工如下：
 * <ul>
 *   <li><b>发起</b>：{@link #hurt(Object, BFDamageContext)} —— 按目标身份选取管线，
 *       完成穿甲判定并把伤害交给目标；</li>
 *   <li><b>投递</b>：{@link #deliverTo(Entity, BFDamageContext)} —— 把一次已经结算过的
 *       伤害交给它的承载实体，不再做穿甲判定，只跑承载者穿戴的护甲层。</li>
 * </ul>
 * mixin 实现者调用 {@link #hasContextFor(Object)} 判断重入；
 * BFHurtTarget 实现者可在 {@code hurt()} 或其它管线方法内调用
 * {@link #getContextFor(Object)} 获取当前攻击上下文。
 */
public final class BFDamageApi {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BFDamageApi() {}

    /**
     * 发起一次协议伤害。
     * <p>
     * 自动完成穿甲判定并执行最终伤害。调用方只需构造上下文后传入即可。
     * <p>
     * 返回值说明：对于非适配器路径（直接实现 {@link BFHurtTarget} 的实体），
     * 返回值是协议计算出的最终伤害量（等价于 {@code calculateFinalDamage} 的返回值），
     * 但该值不一定是实体实际减少的 HP——因为如果目标在 {@link BFHurtTarget#hurt} 中
     * 委托了原版 {@code Entity#hurt}，原版护甲减免会在此之上二次生效。
     * 对于适配器路径（穿戴 {@link BFArmorMaterial} 护甲的普通 {@link LivingEntity}），
     * 此行为由 {@code BFArmorAdapter.hurt()} 的 Javadoc 详细说明。
     * <p>
     * 目标是纯解析器（实现 {@link BFHitResolver} 但不实现 {@link BFHurtTarget}）时，
     * 先用上下文自带的命中几何（{@link BFDamageContext#hitPoint()} 与
     * {@link BFHitResolver#searchDelta(Vec3) searchDelta(ctx.hitVelocity())}）解析出实际目标，
     * 再按 {@link #contextForResolvedTarget} 用解析结果重建上下文并转发——
     * 解析器不承载伤害，只回答"打中了谁"，而它给出的修正几何与扩展数据会被应用到转发上下文。
     * 解析判定为未命中时返回 0f；转发出去的伤害由 {@link BFHurtTarget} 分支承接。
     * <p>
     * 对于普通 {@link Entity}（无协议感知），直接调用原版 {@code entity.hurt(source, baseDamage)}。
     * 若上下文中有 handler，会通过 {@link BFDamageHandler#onNormalEntityHit} 回调告知原始伤害和成功标志
     * <p>
     * 目标既不是 {@link BFHurtTarget} 也不是 {@link Entity} 时没有任何分支能承接这次伤害，
     * 记录 error 日志后返回 0f。
     *
     * @param target 伤害目标（{@link BFHurtTarget}、{@link BFHitResolver} 或普通 {@link Entity}）
     * @param ctx    完整命中上下文
     * @return 协议层认为已造成的伤害量。注意：由于原版护甲二次减免，
     *         此值 ≥ 实体实际减少的 HP。调用方如需精确记录伤害数值，
     *         建议在 {@link BFDamageHandler#onPenetrated} 回调中获取。
     */
    public static float hurt(Object target, BFDamageContext ctx) {
        BFDamageHandler handler = ctx.getHandler();
        BFContextStack.INSTANCE.push(stackTargetOf(target), ctx);
        try {
            // ================================================================
            // 分支0：复合目标 — BFHurtTarget + BFArmorMaterial 护甲
            //   护甲层先拦截 → childCtx → 本体层始终执行完整管线
            // ================================================================
            if (target instanceof LivingEntity living
                    && target instanceof BFHurtTarget bfTarget
                    && BFArmorAdapter.hasBFArmor(living)) {

                // 第一层：护甲管线
                BFArmorAdapter adapter = new BFArmorAdapter(living);
                float residualPen = adapter.modifyPenetration(ctx);
                PenetrationResult armorResult = adapter.resolvePenetration(ctx);
                float residualDmg = adapter.calculateFinalDamage(ctx, armorResult);
                // 护甲层回调——告知 handler 护甲层的穿甲结果（击穿/阻挡/跳弹）
                if (handler != null) {
                    triggerBeforeCallbacks(handler, adapter, ctx, armorResult);
                }
                // 护甲层 afterHurt（在 callFinalDmg 之后、hurt 之前触发）
                adapter.armorAfterHurt(ctx, armorResult, residualDmg);
                // 护甲层回调——告知 handler 护甲层的穿甲结果（击穿/阻挡/跳弹）
                if (handler != null) {
                    triggerCallbacks(handler, adapter, ctx, armorResult);
                }

                // 构造子上下文——未击穿/跳弹时穿深传 0 表示仅钝伤
                float childPen = armorResult == PenetrationResult.PENETRATED
                        ? residualPen : 0f;
                BFDamageContext childCtx = ctx.childContext(residualDmg, childPen);

                // 第二层：本体始终执行完整管线
                PenetrationResult entityResult = bfTarget.resolvePenetration(childCtx);
                float finalDmg = bfTarget.calculateFinalDamage(childCtx, entityResult);
                // 本体层伤害前回调（在本体 hurt 之前触发，此时穿甲判定已完成）
                if (handler != null) {
                    triggerBeforeCallbacks(handler, bfTarget, childCtx, entityResult);
                }
                boolean success = bfTarget.hurt(ctx.source(), finalDmg);
                float dealt = success ? finalDmg : 0f;
                // 本体层 afterHurt
                bfTarget.afterHurt(childCtx, entityResult, finalDmg);

                // 本体层伤害后回调
                if (handler != null) {
                    triggerCallbacks(handler, bfTarget, childCtx, entityResult);
                }
                return dealt;
            }

            // 分支1：BFHurtTarget（实体或独立对象直接声明协议感知）
            if (target instanceof BFHurtTarget bfTarget) {
                PenetrationResult result = bfTarget.resolvePenetration(ctx);
                float finalDmg = bfTarget.calculateFinalDamage(ctx, result);
                // 伤害前回调（在 hurt 之前触发）
                if (handler != null) {
                    triggerBeforeCallbacks(handler, bfTarget, ctx, result);
                }
                boolean success = bfTarget.hurt(ctx.source(), finalDmg);
                float dealt = success ? finalDmg : 0f;
                // 实体本体 afterHurt
                bfTarget.afterHurt(ctx, result, finalDmg);

                if (handler != null) {
                    triggerCallbacks(handler, bfTarget, ctx, result);
                }
                return dealt;
            }
            // 分支1.5：纯解析器 — 实现 BFHitResolver 但不实现 BFHurtTarget
            //   走到这里说明目标已声明"我不承载伤害，但我知道打中了谁"：先解析再转发。
            //   解析结果的实际目标静态类型即 BFHurtTarget，转发后由分支1 承接，递归深度恒为 1。
            //   本判据排在 BFHurtTarget 分支之后，与 BFHurtInterceptor 的分支优先级一致。
            if (target instanceof BFHitResolver resolver) {
                BFHitResolveResult resolved = resolver.resolveHit(
                        ctx.hitPoint(), BFHitResolver.searchDelta(ctx.hitVelocity()));
                // 解析判定为未命中（拦截器情况 3 的假阳性出口同义）：本次协议调用不造成伤害。
                // 不回退到"伤害落在解析器自身"——协议伤害没有可回退的原版语义。
                if (resolved == null) return 0f;
                return hurt(resolved.actualTarget(), contextForResolvedTarget(ctx, resolved));
            }
            // 分支2：LivingEntity 穿戴了 BFArmorMaterial 护甲 → 适配器模式
            if (target instanceof LivingEntity living && BFArmorAdapter.hasBFArmor(living)) {
                BFArmorAdapter adapter = new BFArmorAdapter(living);
                PenetrationResult result = adapter.resolvePenetration(ctx);
                float finalDmg = adapter.calculateFinalDamage(ctx, result);
                // 伤害前回调（在适配器 hurt 之前触发）
                if (handler != null) {
                    triggerBeforeCallbacks(handler, adapter, ctx, result);
                }
                // 护甲层 afterHurt（在 hurt 之前触发）
                adapter.armorAfterHurt(ctx, result, finalDmg);
                boolean success = adapter.hurt(ctx.source(), finalDmg);
                float dealt = success ? finalDmg : 0f;

                if (handler != null) {
                    triggerCallbacks(handler, adapter, ctx, result);
                }
                return dealt;
            }
            // 分支3：普通 Entity → 原版回退，但有 handler 时照样触发回调
            if (target instanceof Entity entity) {
                // 伤害前回调（普通实体，无穿甲判定）
                if (handler != null) {
                    handler.beforeNormalEntityHit(entity, ctx, ctx.baseDamage());
                }
                boolean success = entity.hurt(ctx.source(), ctx.baseDamage());
                if (handler != null) {
                    handler.onNormalEntityHit(entity, ctx, ctx.baseDamage(), success);
                }
                return success ? ctx.baseDamage() : 0f;
            }
            // 分支4：既不是 BFHurtTarget、也不是 Entity — 没有任何分支能承接这次伤害。
            // 记录 error 而非静默返回：目标类型错误是配置问题，无声丢弃会让调用方以为伤害已生效。
            LOGGER.error("BFDamageApi.hurt 的目标既不是 BFHurtTarget 也不是 Entity，本次伤害被丢弃："
                            + "class={}。请让目标实现 BFHurtTarget，"
                            + "或先经 BFDamageApi.resolveHitTarget 解析出实际目标",
                    target.getClass().getName());
            return 0f;
        } finally {
            BFContextStack.INSTANCE.pop();
        }
    }

    /**
     * 选取压栈目标——两个伤害入口共用（{@link #hurt} 与 {@link #deliverTo}）。
     * <p>
     * 栈 target 优先取 {@link BFHurtTarget#getBFEntity()}（若非空），确保目标委托到
     * {@code entity.hurt()} 时 mixin 的 {@link #hasContextFor(Object)} 能正确匹配放行。
     * 非 {@link BFHurtTarget} 的对象（普通实体、任意包装体）取自身。
     *
     * @param target 本次协议调用的目标
     * @return 应压入上下文栈的对象
     */
    private static Object stackTargetOf(Object target) {
        if (target instanceof BFHurtTarget bfTarget && bfTarget.getBFEntity() != null) {
            return bfTarget.getBFEntity();
        }
        return target;
    }

    /**
     * 承载者投递：把一次已结算的伤害交给它的承载实体。承载者穿戴的
     * {@link BFArmorMaterial} 护甲参与本次判定。
     * <p>
     * 等价于 {@code deliverTo(carrier, ctx, false)}。
     *
     * @param carrier 承载实体；调用期内被压为上下文栈顶
     * @param ctx     已修正的投递上下文
     * @return 一次落地的结果，见 {@link #deliverTo(Entity, BFDamageContext, boolean)}
     */
    public static boolean deliverTo(Entity carrier, BFDamageContext ctx) {
        return deliverTo(carrier, ctx, false);
    }

    /**
     * 承载者投递：把一次已结算的伤害交给它的承载实体。
     * <p>
     * <b>不做穿甲判定，也不修正数值。</b>{@code ctx} 必须由调用者以穿透后的修正值构造：
     * {@code baseDamage} 是要投递的伤害量，{@code penetration} 是到达承载者的残余穿深。
     * 本方法只做三件事：把承载者压为上下文栈顶 → 若承载者穿戴 {@link BFArmorMaterial}
     * 护甲且 {@code ignoreBFArmor} 为 false 则过一遍它的护甲层 → 交给承载者的原版
     * {@code hurt}。
     * <p>
     * <b>绕行范围</b>：本方法不判定目标身份、不选取分支、不执行承载者作为
     * {@link BFHurtTarget} 的本体层（{@code resolvePenetration} /
     * {@code calculateFinalDamage}），也不经过 {@link BFHitResolver} 的路由——承载者
     * 即使是解析器也不会被重新解析。这是本方法与发起入口 {@link #hurt(Object, BFDamageContext)}
     * 的分工所在：发起的语义是"把这次伤害交给它应该落到的目标"，因此目标是纯解析器时
     * 会先解析再转发；投递的语义是"这次结算结果由这个承载实体落地"，承载者已在上一趟
     * 承担过解析职责，再路由一次就会回到触发它的那个对象。
     * 两个重载的差别只有一处：{@code ignoreBFArmor} 为
     * true 时再额外跳过承载者穿戴的 {@link BFArmorMaterial} 护甲层及其回调。
     * <p>
     * 落地仍走原版 {@code hurt}，因此无敌帧、原版护甲、附魔、荆棘反伤与伤害事件
     * 全部保留。
     * <p>
     * <b>调用约束</b>：调用时上下文栈顶不得已存在承载者自身——承载者已在栈顶时，
     * 本次投递被拒绝（返回 false）并记录警告。栈顶是其它目标时，本次投递仍会落地，
     * 但会把那一帧压在承载者帧之下，使外层目标在投递期内的 {@link #hasContextFor(Object)}
     * 恒为 false，因此调用方应让投递发生在栈为空时（例如结算相位）。
     *
     * @param carrier       承载实体；调用期内被压为上下文栈顶，压栈目标取
     *                      {@code carrier.getBFEntity()}（与 {@link #hurt} 同一规则）
     * @param ctx           已修正的投递上下文。source、命中几何、extensions、handler
     *                      均取自此处
     * @param ignoreBFArmor true 表示在"投递已绕开判定"的基础上，再跳过承载者穿戴的
     *                      {@code BFArmorMaterial} 护甲层：不跑三件套、不触发任何穿甲
     *                      回调、不消耗护甲耐久，伤害直接交给原版 {@code hurt}。
     *                      false 表示护甲层照常参与
     * @return 一次落地的结果。false 表示未落地——贴身护甲判定为未击穿/跳弹且
     *         {@code calculateFinalDamage} 返回 0，或原版拒绝（无敌帧内且未超过上次
     *         伤害、已死亡、免疫、玩家受到的伤害量恰为 0）。
     *         true 不保证扣了血：原版护甲、附魔、吸收都可能把伤害削到 0
     */
    public static boolean deliverTo(Entity carrier, BFDamageContext ctx, boolean ignoreBFArmor) {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(ctx, "ctx");
        // 零伤害量：既不是一次被挡下的命中，也不构成一次穿甲事件——无副作用、无回调
        if (ctx.baseDamage() <= 0f) return false;
        // 承载者是实体，其 hurt 在客户端不产生伤害结算
        if (carrier.level().isClientSide()) return false;
        // 入口自检：承载者已在自己的管线内（例如承载者的 hurt 内再投递一次自己）
        if (hasContextFor(carrier)) {
            LOGGER.warn("deliverTo 被拒绝：承载者已在上下文栈中，class={}", carrier.getClass().getName());
            return false;
        }

        BFDamageHandler handler = ctx.getHandler();
        BFContextStack.INSTANCE.push(stackTargetOf(carrier), ctx);
        try {
            // ---- 护甲层：与 hurt 的护甲分支同构，只跑承载者穿戴的这一层 ----
            float amount = ctx.baseDamage();
            if (!ignoreBFArmor
                    && carrier instanceof LivingEntity living
                    && BFArmorAdapter.hasBFArmor(living)) {
                BFArmorAdapter adapter = new BFArmorAdapter(living);
                BFArmorAdapter.CarrierArmorResult r = BFArmorAdapter.reduceForCarrier(adapter, ctx);
                // 伤害前回调——护甲层判定已确认
                if (handler != null) triggerBeforeCallbacks(handler, adapter, ctx, r.armorResult());
                // 护甲层 afterHurt（耐久损耗等，在 hurt 之前触发）；未击穿时同样触发
                adapter.armorAfterHurt(ctx, r.armorResult(), r.delivered());
                // 伤害后回调——护甲层处理完毕
                if (handler != null) triggerCallbacks(handler, adapter, ctx, r.armorResult());

                // 护甲挡下：回调已发、耐久已扣
                if (r.delivered() <= 0f) return false;
                amount = r.delivered();
            }
            // ---- 本体层：不执行。直接落地 ----
            return carrier.hurt(ctx.source(), amount);
        } finally {
            BFContextStack.INSTANCE.pop();
        }
    }

    /**
     * 由一个解析结果构造"交给实际目标"的上下文——{@link #hurt} 的分支1.5 与
     * {@code BFHurtInterceptor} 的情况3 共用，使"框架代替调用方解析"与"调用方自行解析"
     * 走同一套几何修正与扩展合并规则。
     * <p>
     * 本方法按 {@link #resolveHitTarget(Object, Vec3, Vec3)} 的 Javadoc 所载标准用法重建上下文：
     * <ol>
     *   <li><b>命中点</b>取 {@link BFHitResolveResult#correctedHitPoint()}，无条件替换。
     *       该字段恒为有效的世界坐标——{@code resolveHit} 的实现惯例是"无修正时原样回传入参"，
     *       而 {@link #resolveHitTarget(Object, Vec3, Vec3)} 对纯 {@link BFHurtTarget}
     *       也是原样包装给定命中点。命中点是 {@link BFArmorMaterial#mapHitToSlot}
     *       判定着弹槽位的输入，代理的 AABB 交点通常不是子部件上的真实着弹点，故不可沿用旧值。</li>
     *   <li><b>命中面法线</b>取 {@link BFHitResolveResult#correctedHitNormal()}，
     *       仅当其非零时替换。零矢量是既有的"未修正"哨兵值
     *       （{@link #resolveHitTarget(Object, Vec3, Vec3)} 对纯 {@link BFHurtTarget} 即填
     *       {@link Vec3#ZERO}），直接写入会让读取者拿到零矢量而算出无意义的入射角，
     *       此时保留原上下文的法线。</li>
     *   <li><b>扩展数据</b>以原容器的拷贝为底，再并入解析结果的容器
     *       （{@link BFDamageExtensions#mergeFrom}，后者覆盖同名键）。先拷贝保证不修改调用方
     *       传入的容器；解析器携带的命中特定字段（如部件标识）因此对下游回调可见，
     *       而调用方原有的字段不丢。</li>
     * </ol>
     * source、baseDamage、hitVelocity、penetration、handler 原样保留。
     * <p>
     * 本方法是公开的，因为 {@code BFHurtInterceptor} 位于 {@code internal} 包、需要跨包调用；
     * 它服务于框架自身的两条转发路径。外部模组一般不需要调用——按
     * {@link #resolveHitTarget(Object, Vec3, Vec3)} 的示例自行构造上下文即可，两者语义一致。
     *
     * @param ctx      调用方传入的上下文（几何未经修正）
     * @param resolved 解析结果
     * @return 应用了修正几何与合并扩展的新上下文
     */
    public static BFDamageContext contextForResolvedTarget(BFDamageContext ctx, BFHitResolveResult resolved) {
        Vec3 normal = resolved.correctedHitNormal();
        Vec3 hitNormal = normal.lengthSqr() > 0.0 ? normal : ctx.hitNormal();
        BFDamageExtensions extensions = ctx.extensions().copy();
        extensions.mergeFrom(resolved.extensions());
        return BFDamageContext.builder()
                .source(ctx.source())
                .baseDamage(ctx.baseDamage())
                .hitVelocity(ctx.hitVelocity())
                .hitPoint(resolved.correctedHitPoint())
                .hitNormal(hitNormal)
                .penetration(ctx.penetration())
                .extensions(extensions)
                .handler(ctx.getHandler())
                .build();
    }

    /**
     * 根据穿甲结果触发 handler 上的伤害前回调。
     * <p>
     * 在穿甲判定完成、最终伤害已计算后、{@code hurt()} 执行前调用。
     * 超匹配(碾压)与破片由 handler 的默认方法动态判定：
     * <ul>
     *   <li>PENETRATED：超匹配(碾压)优先于破片，二者互斥</li>
     *   <li>BLOCKED：仅可能触发破片</li>
     *   <li>RICOCHET：仅跳弹回调，无超匹配(碾压)或破片</li>
     * </ul>
     */
    private static void triggerBeforeCallbacks(BFDamageHandler handler, BFHurtTarget target,
                                               BFDamageContext ctx, PenetrationResult result) {
        switch (result) {
            case PENETRATED -> {
                handler.beforePenetrated(target, ctx);
                if (handler.isOvermatch(target, ctx, result)) {
                    handler.beforeOvermatch(target, ctx);
                } else if (handler.isSpall(target, ctx, result)) {
                    handler.beforeSpall(target, ctx);
                }
            }
            case BLOCKED -> {
                handler.beforeBlocked(target, ctx);
                if (handler.isSpall(target, ctx, result)) {
                    handler.beforeSpall(target, ctx);
                }
            }
            case RICOCHET -> handler.beforeRicochet(target, ctx);
        }
    }

    /**
     * 根据穿甲结果触发 handler 上的伤害后回调。
     * <p>
     * 在 {@code hurt()} 执行后调用。
     * 超匹配(碾压)与破片由 handler 的默认方法动态判定：
     * <ul>
     *   <li>PENETRATED：超匹配(碾压)优先于破片，二者互斥</li>
     *   <li>BLOCKED：仅可能触发破片</li>
     *   <li>RICOCHET：仅跳弹回调，无超匹配(碾压)或破片</li>
     * </ul>
     */
    private static void triggerCallbacks(BFDamageHandler handler, BFHurtTarget target,
                                         BFDamageContext ctx, PenetrationResult result) {
        switch (result) {
            case PENETRATED -> {
                handler.onPenetrated(target, ctx);
                if (handler.isOvermatch(target, ctx, result)) {
                    handler.onOvermatch(target, ctx);
                } else if (handler.isSpall(target, ctx, result)) {
                    handler.onSpall(target, ctx);
                }
            }
            case BLOCKED -> {
                handler.onBlocked(target, ctx);
                if (handler.isSpall(target, ctx, result)) {
                    handler.onSpall(target, ctx);
                }
            }
            case RICOCHET -> handler.onRicochet(target, ctx);
        }
    }

    /**
     * 判断当前线程中是否已有针对指定目标的协议上下文。
     * <p>
     * 用于 mixin 重入守卫：若已在同一目标的协议管线内，应放行原版流程。
     *
     * @param target 要检查的目标
     * @return true 表示该目标正处于协议伤害管线中
     */
    public static boolean hasContextFor(Object target) {
        return BFContextStack.INSTANCE.hasContextFor(target);
    }

    /**
     * 获取当前线程中指定目标的协议上下文。
     * <p>
     * 在 {@link BFHurtTarget#hurt BFHurtTarget.hurt()} 或
     * {@link BFHurtTarget#calculateFinalDamage calculateFinalDamage()} 等
     * 管线方法内调用，以获取完整的命中上下文（命中位置、速度、穿透、侧信道扩展等），
     * 从而基于这些信息做额外后效处理，如播放不同位置的中弹音效、产生破片粒子等。
     * <p>
     * 调用示例：
     * <pre>{@code
     * public boolean hurt(DamageSource source, float amount) {
     *     BFDamageContext ctx = BFDamageApi.getContextFor(this);
     *     if (ctx != null) {
     *         playHitSound(ctx.hitPoint());
     *     }
     *     return super.hurt(source, amount);
     * }
     * }</pre>
     *
     * @param target 要获取上下文的目标（通常传入 {@code this}）
     * @return 当前协议上下文；如果不在协议管线内、或栈顶目标不匹配则返回 null
     */
    @Nullable
    public static BFDamageContext getContextFor(Object target) {
        return BFContextStack.INSTANCE.getContextFor(target);
    }

    // ======================== 命中前目标解析 ========================

    /**
     * 判断命中对象是否具有协议感知能力。
     * <p>
     * 对象实现 {@link BFHitResolver} 或 {@link BFHurtTarget} 时返回 true。
     * 调用方应在调用 {@link #resolveHitTarget} 之前使用此方法分支：
     * 协议感知对象走完整管线；普通实体回退原版 {@code entity.hurt()}。
     *
     * @param hit 命中对象（实体、物理体属主或其他包装体）
     * @return true 表示可走协议解析
     */
    public static boolean isProtocolAware(Object hit) {
        return hit instanceof BFHitResolver || hit instanceof BFHurtTarget;
    }

    /**
     * {@link #isProtocolAware(Object)} 的实体版重载。
     *
     * @deprecated 请改用 {@link #isProtocolAware(Object)}；本重载仅作转发，
     *             保留是为了让已编译的下游模组按实体描述符查找方法时不抛
     *             {@code NoSuchMethodError}。
     */
    @Deprecated(since = "1.0.0.alpha.11")
    public static boolean isProtocolAware(Entity entity) {
        return isProtocolAware((Object) entity);
    }

    /**
     * 解析命中目标。
     * <p>
     * 若命中对象实现了 {@link BFHitResolver}，执行精确验证并返回修正后的目标与几何；
     * 否则若其自身是 {@link BFHurtTarget}，直接包装返回；两者都不是时返回 null。
     * 返回 null 表示未命中（投射物应继续飞行）。
     * <p>
     * 典型调用模式（武器模组侧）：
     * <pre>{@code
     * var resolved = BFDamageApi.resolveHitTarget(hitEntity, hitPoint, delta);
     * if (resolved == null) {
     *     event.setCanceled(true); // 假阳性，投射物继续飞行
     *     return;
     * }
     * var ctx = BFDamageContext.builder()
     *     .hitPoint(resolved.correctedHitPoint())
     *     .hitNormal(resolved.correctedHitNormal())
     *     .extensions(resolved.extensions().copy())
     *     // ...
     *     .build();
     * BFDamageApi.hurt(resolved.actualTarget(), ctx);
     * }</pre>
     *
     * @param hit      命中对象（实体、物理体属主或其他包装体）
     * @param hitPoint 原版报告的命中点
     * @param delta    搜索矢量，其模为搜索距离上限（m），方向为命中方向
     * @return 解析结果；{@code null} 表示未命中
     */
    @Nullable
    public static BFHitResolveResult resolveHitTarget(Object hit, Vec3 hitPoint, Vec3 delta) {
        if (hit instanceof BFHitResolver resolver) {
            return resolver.resolveHit(hitPoint, delta);
        }
        if (hit instanceof BFHurtTarget bf) {
            return new BFHitResolveResult(bf, hitPoint, Vec3.ZERO);
        }
        return null;
    }

    /**
     * {@link #resolveHitTarget(Object, Vec3, Vec3)} 的实体版重载。
     *
     * @deprecated 请改用 {@link #resolveHitTarget(Object, Vec3, Vec3)}；本重载仅作转发，
     *             保留是为了让已编译的下游模组按实体描述符查找方法时不抛
     *             {@code NoSuchMethodError}。
     */
    @Deprecated(since = "1.0.0.alpha.11")
    @Nullable
    public static BFHitResolveResult resolveHitTarget(Entity hitEntity, Vec3 hitPoint, Vec3 delta) {
        return resolveHitTarget((Object) hitEntity, hitPoint, delta);
    }

    /**
     * 从原版 HitResult 解析命中目标。
     * <p>
     * 相比 {@link #resolveHitTarget(Object, Vec3, Vec3)}，
     * 本重载自动从 EntityHitResult 中提取命中实体和命中点。
     * 非 EntityHitResult（如方块命中）返回 null。
     *
     * @param hitResult 原版命中结果
     * @param delta     搜索矢量
     * @return 解析结果；null 表示未命中或无效命中类型
     */
    @Nullable
    public static BFHitResolveResult resolveHitTarget(
            HitResult hitResult, Vec3 delta) {
        if (hitResult instanceof EntityHitResult ehr) {
            return resolveHitTarget((Object) ehr.getEntity(), hitResult.getLocation(), delta);
        }
        return null;
    }
}
