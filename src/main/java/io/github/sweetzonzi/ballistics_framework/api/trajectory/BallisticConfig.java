package io.github.sweetzonzi.ballistics_framework.api.trajectory;

import io.github.sweetzonzi.ballistics_framework.api.BFDamageExtensions;

/**
 * 二次阻力模型下的弹丸固有参数。
 * <p>
 * 注意：空气密度不由此配置控制——弹道解算时通过 {@link DensityFunction}
 * 逐位置获取，以适应不同维度/高度的气压变化。
 * <p>
 * 所有值均使用国际单位制（SI）：质量 kg，长度 m，速度 m/s，加速度 m/s²。
 * <p>
 * 便捷工厂方法可从 {@code BFDamageExtensions} 预定义 key 构造，
 * 使武器模组只需额外提供 Cd 即可使用拟真解算器。
 *
 * @param dragCoefficient  阻力系数 Cd（无量纲），典型弹丸 0.2~0.5
 * @param mass             弹丸质量 m（kg），可从 BFDamageExtensions.MASS 读取
 * @param crossSectionArea 弹丸横截面积 A（m²），可从 BFDamageExtensions.CALIBER 计算
 * @param gravity          重力加速度 g（m/s²），MC 主世界 ≈ 0.04×20 = 0.8
 * @param timeStep         时间步长 Δt（s），默认 0.05（= 1 MC tick）
 * @param maxSteps         最大模拟步数
 */
public record BallisticConfig(
        float dragCoefficient,
        float mass,
        float crossSectionArea,
        float gravity,
        float timeStep,
        int maxSteps
) {
    /**
     * 从 BFDamageExtensions 预定义 key 读取 MASS 和 CALIBER，自动计算截面积。
     * 其余参数需手动指定。
     *
     * @param exts            命中上下文扩展容器
     * @param dragCoefficient 阻力系数 Cd
     * @param gravity         重力加速度（m/s²）
     * @param timeStep        时间步长（s）
     * @param maxSteps        最大步数
     * @return 构造完成的 BallisticConfig
     */
    public static BallisticConfig fromExtensions(
            BFDamageExtensions exts, float dragCoefficient,
            float gravity, float timeStep, int maxSteps
    ) {
        float mass = exts.get(BFDamageExtensions.MASS);
        float caliber = exts.get(BFDamageExtensions.CALIBER);
        float radius = caliber / 2f;
        float area = (float) (Math.PI * radius * radius);
        return new BallisticConfig(dragCoefficient, mass, area, gravity, timeStep, maxSteps);
    }

    /**
     * 便捷构造：仅需 Cd + 口径 + 质量 + 重力（其余使用推荐默认值）。
     *
     * @param dragCoefficient 阻力系数 Cd
     * @param caliber         弹丸口径（m）
     * @param mass            弹丸质量（kg）
     * @param gravity         重力加速度（m/s²）
     * @return 构造完成的 BallisticConfig，时间步长 0.05s，最大步数 200
     */
    public static BallisticConfig simple(
            float dragCoefficient, float caliber, float mass, float gravity
    ) {
        float radius = caliber / 2f;
        float area = (float) (Math.PI * radius * radius);
        return new BallisticConfig(dragCoefficient, mass, area, gravity, 0.05f, 200);
    }
}
