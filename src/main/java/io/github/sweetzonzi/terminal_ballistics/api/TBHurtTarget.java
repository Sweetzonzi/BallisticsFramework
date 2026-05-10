package io.github.sweetzonzi.terminal_ballistics.api;

import net.minecraft.world.damagesource.DamageSource;
import org.jetbrains.annotations.Nullable;

/**
 * 协议伤害目标接口。
 * <p>
 * 默认实现基于离散的 {@link ArmorLevel 穿甲/护甲等级}体系，简单模组只需实现
 * {@link #getArmorLevel} 即可获得完整的穿甲判定行为。
 * 需要精确数值判定的模组应同时覆写 {@link #getRHA}、{@link #modifyPenetration}、
 * {@link #isArmorPenetrated}、{@link #calculateFinalDamage}。
 * <p>
 * 穿深的角度效应等应由弹头/武器模组在构造 {@link TBDamageContext} 前计算，
 * <p>
 * 协议层调用顺序：
 * {@link #getRHA} → {@link #modifyPenetration} → {@link #isArmorPenetrated}
 * → {@link #calculateFinalDamage} → {@link #hurt}
 */
public interface TBHurtTarget {

    /**
     * 返回此命中部位对应的离散护甲等级。
     * <p>
     * 简单模组只需实现此方法即可，默认的穿甲判定、伤害计算均基于此等级进行。
     *
     * @param ctx 命中上下文
     * @return 此命中部位对应的护甲等级
     */
    ArmorLevel getArmorLevel(TBDamageContext ctx);

    /**
     * 返回命中部位的垂直 RHA 等效厚度（mm）。
     * <p>
     * 默认实现取 {@link #getArmorLevel} 的中位值。
     * 需要精确数值判定的模组应覆写此方法。
     *
     * @param ctx 命中上下文
     * @return RHA 等效厚度（mm）。0 表示无装甲；{@code Float.MAX_VALUE} 表示绝对不可穿透
     */
    default float getRHA(TBDamageContext ctx) {
        return getArmorLevel(ctx).medianRha();
    }

    /**
     * 修正穿深，默认直接返回原始值。
     * <p>
     * 穿深的角度效应等应由弹头/武器模组在构造 {@link TBDamageContext} 前计算。
     * 此方法作为 hook 保留，供护甲模组实现爆反拦截、间隙衰减等减效逻辑。
     *
     * @param ctx 命中上下文
     * @return 修正后的有效穿深。默认直接返回 {@code ctx.penetration()}
     */
    default float modifyPenetration(TBDamageContext ctx) {
        return ctx.penetration();
    }

    /**
     * 判断是否击穿装甲。
     * <p>
     * 默认基于离散等级比较（等于算击穿）。
     * 需要精确浮点比较的模组应覆写此方法。
     *
     * @param ctx 命中上下文
     * @return true 表示击穿
     */
    default boolean isArmorPenetrated(TBDamageContext ctx) {
        return ctx.getPenetrationLevel().canDefeat(getArmorLevel(ctx));
    }

    /**
     * 根据击穿结果计算最终伤害量。
     * <p>
     * 默认分三级：
     * <ul>
     *   <li>未击穿：0</li>
     *   <li>击穿但同级（刚好击穿）：标称伤害的 65%</li>
     *   <li>完全击穿（穿甲等级高于护甲等级）：标称伤害的 100%</li>
     * </ul>
     * 需要精确伤害曲线的模组应覆写此方法。
     *
     * @param ctx 命中上下文
     * @return 最终伤害量
     */
    default float calculateFinalDamage(TBDamageContext ctx) {
        if (!isArmorPenetrated(ctx)) return 0f;
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
    TBDamageContext createContextFromVanilla(DamageSource source, float amount);
}
