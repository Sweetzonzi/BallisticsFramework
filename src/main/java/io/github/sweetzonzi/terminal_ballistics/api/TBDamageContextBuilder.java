package io.github.sweetzonzi.terminal_ballistics.api;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * {@link TBDamageContext} 的构建器。
 * <p>
 * 通过 {@code TBDamageContext.builder()} 获取实例。
 * 使用示例：{@code TBDamageContext.builder().source(src).baseDamage(35f).hitVelocity(vel).penetration(120f).extensions(exts).build()}
 * <p>
 * 除 {@code source} 为必须设置外，其余字段均有安全的默认值。
 * 对于 {@code TBHurtTarget.createContextFromVanilla} 等缺少弹道信息的回退路径，
 * 仅设置 source 和 baseDamage 即可得到一个有效的上下文。
 */
public final class TBDamageContextBuilder {

    private DamageSource source;
    private float baseDamage;
    private Vec3 hitVelocity;
    private Vec3 hitPoint;
    private Vec3 hitNormal;
    private float penetration;
    private TBDamageExtensions extensions;
    @Nullable
    private TBDamageHandler handler;

    TBDamageContextBuilder() {
        this.baseDamage = 0f;
        this.hitVelocity = Vec3.ZERO;
        this.hitPoint = Vec3.ZERO;
        this.hitNormal = new Vec3(0, 1, 0);
        this.penetration = 0f;
    }

    /** @param source 伤害来源（唯一必须字段） */
    public TBDamageContextBuilder source(DamageSource source) {
        this.source = source;
        return this;
    }

    /** @param baseDamage 标称伤害量，默认 0 */
    public TBDamageContextBuilder baseDamage(float baseDamage) {
        this.baseDamage = baseDamage;
        return this;
    }

    /** @param hitVelocity 命中速度矢量，默认 {@link Vec3#ZERO} */
    public TBDamageContextBuilder hitVelocity(Vec3 hitVelocity) {
        this.hitVelocity = hitVelocity;
        return this;
    }

    /** @param hitPoint 命中点世界坐标，默认 {@link Vec3#ZERO} */
    public TBDamageContextBuilder hitPoint(Vec3 hitPoint) {
        this.hitPoint = hitPoint;
        return this;
    }

    /** @param hitNormal 命中面法线，默认朝上 {@code (0, 1, 0)}，此时斜穿修正按垂直入射处理 */
    public TBDamageContextBuilder hitNormal(Vec3 hitNormal) {
        this.hitNormal = hitNormal;
        return this;
    }

    /** @param penetration 理论穿深，默认 0 */
    public TBDamageContextBuilder penetration(float penetration) {
        this.penetration = penetration;
        return this;
    }

    /** @param extensions 扩展容器，未设置时自动新建空实例 */
    public TBDamageContextBuilder extensions(TBDamageExtensions extensions) {
        this.extensions = extensions;
        return this;
    }

    /** @param handler 伤害发起方回调接口，默认 null（无回调） */
    public TBDamageContextBuilder handler(@Nullable TBDamageHandler handler) {
        this.handler = handler;
        return this;
    }

    /**
     * 构建上下文。
     *
     * @return 构造完成的上下文
     * @throws NullPointerException source 为 null 时抛出
     */
    public TBDamageContext build() {
        if (extensions == null) {
            extensions = new TBDamageExtensions();
        }
        return new TBDamageContext(
                Objects.requireNonNull(source, "source 为必须字段"),
                baseDamage,
                hitVelocity,
                hitPoint,
                hitNormal,
                penetration,
                extensions,
                handler
        );
    }
}
