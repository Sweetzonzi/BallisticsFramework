package io.github.sweetzonzi.ballistics_framework.api;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import org.jetbrains.annotations.Nullable;

/**
 * 协议伤害目标接口。
 * <p>
 * 默认实现基于离散的 {@link ArmorLevel 穿甲/护甲等级}体系，简单模组只需实现
 * {@link #getArmorLevel} 即可获得完整的穿甲判定行为。
 * 需要精确数值判定的模组应同时覆写 {@link #getRHA}、{@link #modifyPenetration}、
 * {@link #isArmorPenetrated}、{@link #resolvePenetration}、{@link #calculateFinalDamage}。
 * <p>
 * 穿深的角度效应等应由弹头/武器模组在构造 {@link BFDamageContext} 前计算，
 * <p>
 * 协议层调用顺序：
 * {@link #getRHA} → {@link #modifyPenetration} → {@link #resolvePenetration}
 * → {@link #calculateFinalDamage} → {@link #hurt}
 */
public interface BFHurtTarget {

    /**
     * 返回此命中部位对应的离散护甲等级。
     * <p>
     * 简单模组只需实现此方法即可，默认的穿甲判定、伤害计算均基于此等级进行。
     *
     * @param ctx 命中上下文
     * @return 此命中部位对应的护甲等级
     */
    ArmorLevel getArmorLevel(BFDamageContext ctx);

    /**
     * 返回命中部位的垂直 RHA 等效厚度（mm）。
     * <p>
     * 默认实现取 {@link #getArmorLevel} 的中位值。
     * 需要精确数值判定的模组应覆写此方法。
     * 注意即使覆写了此方法，也应保持 {@link #getArmorLevel} 的正确实现——
     * 因为默认的 {@link #calculateFinalDamage} 仍依赖等级做同级/越级判定区分，
     * 且等级也用于 HUD 显示。
     * 推荐在 {@code getArmorLevel} 中调用
     * {@link ArmorLevel#fromRha(float) ArmorLevel.fromRha}(getRHA(ctx))，
     * 让等级始终与精确 RHA 值保持一致。
     * <p>
     * 对于绝对防御场景，可在 {@code getArmorLevel} 中直接返回
     * {@link ArmorLevel#UNPENETRABLE}，或在覆写中返回
     * {@code Float.MAX_VALUE}。两种方式均保证不可击穿。
     *
     * @param ctx 命中上下文
     * @return RHA 等效厚度（mm）。0 表示无装甲；{@code Float.MAX_VALUE} 表示绝对不可穿透
     */
    default float getRHA(BFDamageContext ctx) {
        return getArmorLevel(ctx).medianRha();
    }

    /**
     * 修正穿深，默认直接返回原始值。
     * <p>
     * 穿深的角度效应等应由弹头/武器模组在构造 {@link BFDamageContext} 前计算。
     * 此方法作为 hook 保留，供护甲模组实现爆反拦截、间隙衰减等减效逻辑。
     *
     * @param ctx 命中上下文
     * @return 修正后的有效穿深。默认直接返回 {@code ctx.penetration()}
     */
    default float modifyPenetration(BFDamageContext ctx) {
        return ctx.penetration();
    }

    /**
     * 判断是否能够击穿装甲（纯击穿判定，不含跳弹）。
     * <p>
     * 默认基于离散等级比较：先调用 {@link #modifyPenetration} 获取修正后的有效穿深，
     * 再映射到穿甲等级并与目标护甲等级比较（等于算击穿）。
     * 精密模组应覆写为精确 float 比较。
     * 此方法作为 {@link #resolvePenetration} 的默认委托。
     *
     * @param ctx 命中上下文
     * @return true 表示穿深足以击穿装甲
     */
    default boolean isArmorPenetrated(BFDamageContext ctx) {
        float effectivePen = modifyPenetration(ctx);
        return ArmorLevel.fromRha(effectivePen).canDefeat(getArmorLevel(ctx));
    }

    /**
     * 解析本次命中的最终穿甲结果。
     * <p>
     * 默认实现直接委托 {@link #isArmorPenetrated}，返回 PENETRATED 或 BLOCKED。
     * 需要跳弹判定的护甲模组应覆写此方法——
     * 在入射角过大时返回 RICOCHET。
     * <p>
     * 此方法由 BFDamageApi 在管线中单次调用，其返回值作为
     * calculateFinalDamage 的入参和回调触发的唯一依据。
     *
     * @param ctx 命中上下文
     * @return 穿甲结果（PENETRATED / BLOCKED / RICOCHET，三者互斥）
     */
    default PenetrationResult resolvePenetration(BFDamageContext ctx) {
        return isArmorPenetrated(ctx) ? PenetrationResult.PENETRATED : PenetrationResult.BLOCKED;
    }

    /**
     * 根据穿甲结果计算最终伤害量。
     * <p>
     * 默认分三级（基于离散等级）：
     * <ul>
     *   <li>PENETRATED + 同级击穿（刚好击穿）：标称伤害 × 65%</li>
     *   <li>PENETRATED + 越级击穿：标称伤害 × 100%</li>
     *   <li>BLOCKED / RICOCHET：0</li>
     * </ul>
     * <p>
     * 需要非零未击穿伤害（如钝伤）、跳弹贯穿、超匹配(碾压)加成的模组应覆写此方法。
     * 不是"未击穿 / 跳弹就不造成伤害"——护甲侧可在覆写中根据 result 自由决定。
     *
     * @param ctx    命中上下文
     * @param result 由 {@link #resolvePenetration} 返回的穿甲结果
     * @return 最终伤害量
     */
    default float calculateFinalDamage(BFDamageContext ctx, PenetrationResult result) {
        if (result != PenetrationResult.PENETRATED) return 0f;
        if (ctx.getPenetrationLevel() == getArmorLevel(ctx)) return ctx.baseDamage() * 0.65f;
        return ctx.baseDamage();
    }

    /**
     * 执行实际伤害。
     * <p>
     * 由协议层在穿甲判定和伤害计算完成后调用。
     *
     * @param source 伤害来源
     * @param amount 最终伤害量（由 {@link #calculateFinalDamage} 计算得出）
     * @return 是否成功造成伤害
     */
    boolean hurt(DamageSource source, float amount);

    /**
     * 被命中后的回调，在 {@link #hurt} 执行完毕后调用。
     * <p>
     * 与 {@link #hurt} 的职责区分：
     * <ul>
     *   <li>{@code hurt} — 执行实际的伤害施加，签名只有 {@code (DamageSource, float)}，
     *       是必要的原版兼容接口</li>
     *   <li>{@code afterHurt} — 伤害已执行完毕，提供完整上下文供后效处理。
     *       可在此方法中调用 {@link BFDamageApi#getContextFor(Object)} 获取命中几何信息，
     *       实现命中点音效、粒子、触发技能等效果</li>
     * </ul>
     * 默认空实现。
     *
     * @param ctx         完整命中上下文
     * @param result      穿甲结果（PENETRATED / BLOCKED / RICOCHET）
     * @param finalDamage {@link #calculateFinalDamage} 计算出的最终伤害量
     */
    default void afterHurt(BFDamageContext ctx, PenetrationResult result, float finalDamage) {
        // 默认空实现
    }

    /**
     * 获取此协议伤害目标对应的实体引用。
     * <p>
     * 必须优先返回非 null 的实体引用以配合上下文栈 target 选取规则——
     * 若实现者委托实体 {@code entity.hurt()} 却返回 null，会导致 mixin 重入守卫失效。
     * <p>
     * 默认实现：若自身是 Entity 子类则返回 {@code this}，否则返回 null。
     * 适配器（如 {@code BFArmorAdapter}）应覆写此方法返回被包裹的实体。
     * <p>
     * 供 {@link BFDamageHandler} 回调中使用——武器模组可通过
     * {@code target.getBFEntity()} 安全地获取被命中的实体以播放音效、粒子或触发额外逻辑。
     *
     * @return 目标对应的实体；不应为 null，但允许非 Entity 实现返回 null
     */
    @Nullable
    default Entity getBFEntity() {
        return this instanceof Entity e ? e : null;
    }

    /**
     * 将原版伤害转换为协议上下文。
     * <p>
     * 当 mixin 拦截到非协议来源的伤害命中此目标时调用。
     * 返回 null 表示此伤害不走协议管线，退回原版。
     *
     * @param source 原版 DamageSource
     * @param amount 原版伤害量
     * @return 协议上下文，或 null 退回原版
     */
    @Nullable
    BFDamageContext createContextFromVanilla(DamageSource source, float amount);
}
