package io.github.sweetzonzi.terminal_ballistics.api;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 协议伤害目标接口。
 * <p>
 * 实现此接口的类型将参与完整的协议穿甲判定流程。
 * 实现者必须提供：护甲厚度、伤害执行逻辑、协议外伤害转换逻辑。
 * 穿甲判定和伤害修正可通过覆写默认方法自定义。
 * <p>
 * 对于同时是 {@code LivingEntity} 的实现者，{@link #hurt} 可选择
 * {@code return super.hurt(source, amount)} 委托给原版管线，也可完全自定义。
 * <p>
 * 协议层调用顺序：
 * {@link #getRHA} → {@link #modifyPenetration} → {@link #isArmorPenetrated}
 * → {@link #calculateFinalDamage} → {@link #hurt}
 */
public interface TBHurtTarget {

    /**
     * 返回命中部位的垂直 RHA 等效厚度（mm）。
     * <p>
     * 实现者应根据命中几何信息推算部位并返回该部位的基础装甲厚度。
     *
     * @param ctx 命中上下文
     * @return RHA 等效厚度（mm）。0 表示无装甲；{@code Float.MAX_VALUE} 表示绝对不可穿透
     */
    float getRHA(TBDamageContext ctx);

    /**
     * 修正穿深，默认按入射角做斜穿修正。
     * <p>
     * 默认实现：{@code ctx.penetration / cos(θ)}，θ 为速度与法线反方向的夹角。
     * 可覆写以实现间隙衰减、爆反拦截、复合装甲等逻辑。
     *
     * @param ctx 命中上下文
     * @return 修正后的有效穿深
     */
    default float modifyPenetration(TBDamageContext ctx) {
        Vec3 vel = ctx.hitVelocity();
        Vec3 normal = ctx.hitNormal();

        double velLen = vel.length();
        double normalLen = normal.length();
        if (velLen < 1e-6 || normalLen < 1e-6) {
            return ctx.penetration();
        }

        double cosTheta = Math.abs(vel.dot(normal)) / (velLen * normalLen);
        if (cosTheta < 1e-6) {
            return Float.MAX_VALUE;
        }
        return (float) (ctx.penetration() / cosTheta);
    }

    /**
     * 判断是否击穿装甲。
     * <p>
     * 默认比较 {@link #modifyPenetration} 与 {@link #getRHA}。
     * 可覆写实现多层部分穿透等非二元判定。
     *
     * @param ctx 命中上下文
     * @return true 表示击穿
     */
    default boolean isArmorPenetrated(TBDamageContext ctx) {
        return modifyPenetration(ctx) > getRHA(ctx);
    }

    /**
     * 根据击穿结果计算最终伤害量。
     * <p>
     * 默认：击穿返回 {@code ctx.baseDamage}，未击穿返回 0。
     * 可覆写实现部分穿透、超匹配加成等。
     *
     * @param ctx 命中上下文
     * @return 最终伤害量
     */
    default float calculateFinalDamage(TBDamageContext ctx) {
        return isArmorPenetrated(ctx) ? ctx.baseDamage() : 0f;
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
