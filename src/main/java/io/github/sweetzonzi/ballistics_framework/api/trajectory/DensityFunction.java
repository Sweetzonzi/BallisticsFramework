package io.github.sweetzonzi.ballistics_framework.api.trajectory;

import net.minecraft.world.phys.Vec3;

/**
 * 空气密度函数——根据世界坐标返回当前空气密度。
 * <p>
 * 函数式接口，lambda 友好。解算器每积分步调用一次以获取该位置密度。
 * <p>
 * 常见构造方式：
 * <pre>{@code
 * // 海平面常数
 * DensityFunction constant = DensityFunction.constant(1.225f);
 *
 * // 标准大气指数衰减（闭包捕获 Level 信息）
 * Level level = ...;
 * DensityFunction mcAtmo = pos ->
 *     DensityFunction.standardBarometric(pos.y, level.getSeaLevel(), 8500);
 * }</pre>
 */
@FunctionalInterface
public interface DensityFunction {

    /**
     * 根据世界坐标返回空气密度（kg/m³），标准海平面 ≈ 1.225。
     * 下界等无大气维度可返回 0。
     *
     * @param position 世界坐标（m）
     * @return 该位置的空气密度（kg/m³）
     */
    float getDensity(Vec3 position);

    /** 恒定密度，不随位置变化 */
    static DensityFunction constant(float value) {
        return pos -> value;
    }

    /**
     * 标准大气指数衰减模型：ρ = ρ₀ · exp(-(y - seaLevel) / scaleHeight)。
     * <p>
     * ρ₀ = 1.225 kg/m³（海平面标准密度）。
     * scaleHeight 为标高（m）——密度每衰减为 1/e ≈ 37% 所需的高度差。
     * 地球标准大气的标高约 8500m。
     *
     * @param seaLevel    海平面 Y 坐标
     * @param scaleHeight 标高（m）
     * @return 指数衰减密度函数
     */
    static DensityFunction standardBarometric(float seaLevel, float scaleHeight) {
        return pos -> 1.225f * (float) Math.exp(-(pos.y - seaLevel) / scaleHeight);
    }

    /** MC 主世界参考模型（海平面 Y=62，标高 8500m） */
    DensityFunction MC_OVERWORLD = standardBarometric(62f, 8500f);

    /** 海平面常数 1.225（低弹道快捷方式） */
    DensityFunction SEA_LEVEL = constant(1.225f);

    /** 真空（无阻力，用于激光/近光速武器或测试） */
    DensityFunction VACUUM = constant(0f);
}
