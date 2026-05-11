package io.github.sweetzonzi.ballistics_framework.api;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 一次完整命中行为的上下文数据。
 * <p>
 * 武器模组构造此对象后传入 {@link BFDamageApi#hurt(Object, BFDamageContext)}。
 * 构造方式：{@code BFDamageContext.builder().source(source).xxx().build()}。
 * 构造完成后各字段只读。
 *
 * @param source      伤害来源。attacker、projectile、damage type 等均从中获取
 * @param baseDamage  标称伤害量，即原版 {@code hurt(DamageSource, float)} 的 amount
 * @param hitVelocity 命中速度矢量（世界坐标，单位 m/s），需要方向时自行 normalize
 * @param hitPoint    命中点世界坐标
 * @param hitNormal   命中面法线，指向面外侧。用于计算入射角
 * @param penetration 理论穿深（垂直入射 RHA 等效厚度，单位 mm）
 * @param extensions  类型安全扩展容器。供高级模组携带核心字段以外的任意结构化数据
 * @param handler     伤害发起方回调接口，可选（null 表示无回调）
 */
public record BFDamageContext(
        DamageSource source,
        float baseDamage,
        Vec3 hitVelocity,
        Vec3 hitPoint,
        Vec3 hitNormal,
        float penetration,
        BFDamageExtensions extensions,
        @Nullable BFDamageHandler handler
) {

    /**
     * 紧凑构造器，校验所有必需的引用类型不为 null。
     * handler 可为 null（无回调时）。
     *
     * @throws NullPointerException 当 source、hitVelocity、hitPoint、hitNormal、extensions 任一为 null
     */
    public BFDamageContext {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(hitVelocity, "hitVelocity");
        Objects.requireNonNull(hitPoint, "hitPoint");
        Objects.requireNonNull(hitNormal, "hitNormal");
        Objects.requireNonNull(extensions, "extensions");
    }

    /** @return 一个新的 Builder */
    public static BFDamageContextBuilder builder() {
        return new BFDamageContextBuilder();
    }

    /**
     * 获取伤害发起方回调接口。
     * <p>
     * 护甲侧可在 {@link BFHurtTarget#getRHA} 等方法内通过此方法查询"谁在打我"，
     * 做精细判定（如爆反是否对该弹药类型生效）。
     *
     * @return handler，未设置时为 null
     */
    @Nullable
    public BFDamageHandler getHandler() {
        return handler;
    }

    /**
     * 返回一个替换了 handler 的新上下文实例（其余字段不变）。
     *
     * @param handler 新的 handler，可为 null
     * @return 新上下文实例
     */
    public BFDamageContext withHandler(@Nullable BFDamageHandler handler) {
        return new BFDamageContext(source, baseDamage, hitVelocity,
                hitPoint, hitNormal, penetration, extensions, handler);
    }

    /**
     * 构造子上下文，用于双层串联管线中护甲层向本体层传递修正后的弹头状态。
     * <p>
     * 仅替换 baseDamage 和 penetration，其余字段（source、hitVelocity、hitPoint、hitNormal、
     * extensions、handler）与原上下文相同。
     * <p>
     * 典型用法：护甲适配器完成 {@code modifyPenetration}（爆反拦截后残余穿深）、
     * {@code resolvePenetration}（跳弹判定）、{@code calculateFinalDamage}（穿过护甲后的残余伤害量）
     * 后，调用此方法构造子上下文传入本体层。
     * <p>
     * 击穿时：{@code ctx.childContext(residualDmg, residualPen)}，本体基于残余穿深做二次判定。
     * 未击穿/跳弹时：{@code ctx.childContext(residualDmg, 0f)}，穿深传 0 表示仅钝伤，
     * 本体始终执行完整管线自主决定钝伤值。
     *
     * @param newBaseDamage  护甲层 {@code calculateFinalDamage} 输出的残余伤害量
     * @param newPenetration 护甲层穿透后的残余穿深（mm RHA）；未击穿/跳弹时传 0 表示仅钝伤
     * @return 新上下文实例
     */
    public BFDamageContext childContext(float newBaseDamage, float newPenetration) {
        return new BFDamageContext(source, newBaseDamage, hitVelocity,
                hitPoint, hitNormal, newPenetration, extensions, handler);
    }

    /**
     * 获取此上下文的穿深所对应的穿甲等级。
     * <p>
     * 等同于 {@code ArmorLevel.fromRha(this.penetration)}。
     * 用于在离散等级视角下理解武器的穿甲能力。
     *
     * @return 穿甲等级
     */
    public ArmorLevel getPenetrationLevel() {
        return ArmorLevel.fromRha(this.penetration);
    }
}
