package io.github.sweetzonzi.terminal_ballistics.api;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * 一次完整命中行为的上下文数据。
 * <p>
 * 武器模组构造此对象后传入 {@link TBDamageApi#hurt(Object, TBDamageContext)}。
 * 构造方式：{@code TBDamageContext.builder().source(source).xxx().build()}。
 * 构造完成后各字段只读。
 *
 * @param source      伤害来源。attacker、projectile、damage type 等均从中获取
 * @param baseDamage  标称伤害量，即原版 {@code hurt(DamageSource, float)} 的 amount
 * @param hitVelocity 命中速度矢量（世界坐标），需要方向时自行 normalize
 * @param hitPoint    命中点世界坐标
 * @param hitNormal   命中面法线，指向面外侧。用于计算入射角
 * @param penetration 理论穿深（垂直入射 RHA 等效厚度，单位 mm）
 * @param extensions  类型安全扩展容器。供高级模组携带核心字段以外的任意结构化数据
 */
public record TBDamageContext(
        DamageSource source,
        float baseDamage,
        Vec3 hitVelocity,
        Vec3 hitPoint,
        Vec3 hitNormal,
        float penetration,
        TBDamageExtensions extensions
) {

    /**
     * 紧凑构造器，校验所有必需的引用类型不为 null。
     *
     * @throws NullPointerException 当 source、hitVelocity、hitPoint、hitNormal、extensions 任一为 null
     */
    public TBDamageContext {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(hitVelocity, "hitVelocity");
        Objects.requireNonNull(hitPoint, "hitPoint");
        Objects.requireNonNull(hitNormal, "hitNormal");
        Objects.requireNonNull(extensions, "extensions");
    }

    /** @return 一个新的 Builder */
    public static TBDamageContextBuilder builder() {
        return new TBDamageContextBuilder();
    }
}
