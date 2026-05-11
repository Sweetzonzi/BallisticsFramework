package io.github.sweetzonzi.ballistics_framework.api;

import io.github.sweetzonzi.ballistics_framework.internal.BFArmorAdapter;
import io.github.sweetzonzi.ballistics_framework.internal.BFContextStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 协议层对外入口。
 * <p>
 * 武器模组调用 {@link #hurt(Object, BFDamageContext)} 发起协议伤害；
 * mixin 实现者调用 {@link #hasContextFor(Object)} 判断重入；
 * BFHurtTarget 实现者可在 {@code hurt()} 或其它管线方法内调用
 * {@link #getContextFor(Object)} 获取当前攻击上下文。
 */
public final class BFDamageApi {

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
     * 对于普通 {@link Entity}（无协议感知），返回值等价于
     * {@code entity.hurt(source, baseDamage)} 的成功与否，成功则返回 baseDamage。
     *
     * @param target 伤害目标（{@link BFHurtTarget} 或普通 {@link Entity}）
     * @param ctx    完整命中上下文
     * @return 协议层认为已造成的伤害量。注意：由于原版护甲二次减免，
     *         此值 ≥ 实体实际减少的 HP。调用方如需精确记录伤害数值，
     *         建议在 {@link BFDamageHandler#onPenetrated} 回调中获取。
     */
    public static float hurt(Object target, BFDamageContext ctx) {
        BFDamageHandler handler = ctx.getHandler();

        // 栈 target 优先取 BFHurtTarget.getBFEntity()（若非空），确保委托到 entity.hurt()
        // 时 mixin 的 hasContextFor 能正确匹配放行
        Object stackTarget = target;
        if (target instanceof BFHurtTarget bfTarget && bfTarget.getBFEntity() != null) {
            stackTarget = bfTarget.getBFEntity();
        }
        BFContextStack.INSTANCE.push(stackTarget, ctx);
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

                // 构造子上下文——未击穿/跳弹时穿深传 0 表示仅钝伤
                float childPen = armorResult == PenetrationResult.PENETRATED
                        ? residualPen : 0f;
                BFDamageContext childCtx = ctx.childContext(residualDmg, childPen);

                // 第二层：本体始终执行完整管线
                PenetrationResult entityResult = bfTarget.resolvePenetration(childCtx);
                float finalDmg = bfTarget.calculateFinalDamage(childCtx, entityResult);
                boolean success = bfTarget.hurt(ctx.source(), finalDmg);
                float dealt = success ? finalDmg : 0f;

                // 回调在本体层的最终结果上触发
                if (handler != null) {
                    triggerCallbacks(handler, bfTarget, childCtx, entityResult);
                }
                return dealt;
            }

            // 分支1：BFHurtTarget（实体或独立对象直接声明协议感知）
            if (target instanceof BFHurtTarget bfTarget) {
                PenetrationResult result = bfTarget.resolvePenetration(ctx);
                float finalDmg = bfTarget.calculateFinalDamage(ctx, result);
                boolean success = bfTarget.hurt(ctx.source(), finalDmg);
                float dealt = success ? finalDmg : 0f;

                if (handler != null) {
                    triggerCallbacks(handler, bfTarget, ctx, result);
                }
                return dealt;
            }
            // 分支2：LivingEntity 穿戴了 BFArmorMaterial 护甲 → 适配器模式
            if (target instanceof LivingEntity living && BFArmorAdapter.hasBFArmor(living)) {
                BFArmorAdapter adapter = new BFArmorAdapter(living);
                PenetrationResult result = adapter.resolvePenetration(ctx);
                float finalDmg = adapter.calculateFinalDamage(ctx, result);
                boolean success = adapter.hurt(ctx.source(), finalDmg);
                float dealt = success ? finalDmg : 0f;

                if (handler != null) {
                    triggerCallbacks(handler, adapter, ctx, result);
                }
                return dealt;
            }
            // 分支3：普通 Entity → 原版回退
            if (target instanceof Entity entity) {
                return entity.hurt(ctx.source(), ctx.baseDamage()) ? ctx.baseDamage() : 0f;
            }
            return 0f;
        } finally {
            BFContextStack.INSTANCE.pop();
        }
    }

    /**
     * 根据穿甲结果触发 handler 上的回调。
     * <p>
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
}
