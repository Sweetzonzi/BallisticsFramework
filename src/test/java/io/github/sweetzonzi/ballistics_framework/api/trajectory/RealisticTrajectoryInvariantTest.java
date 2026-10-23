package io.github.sweetzonzi.ballistics_framework.api.trajectory;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RealisticTrajectory} 的性质测试（纯 JUnit，不启动游戏）。
 * <p>
 * 断言只针对性质，不锁数值快照：数值快照在模型演进时会变成维护负担，而性质断言
 * 恰好是“推力项”这类扩展必须保持的不变量。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>退化路径的精确性（无重力无阻力 → 匀速直线；无阻力 → 离散抛物线闭式解）；</li>
 *   <li>轨迹的平面性——外力只有重力时，运动恒在初始速度所在的竖直平面内；</li>
 *   <li>半隐式欧拉积分与高阶参考解的一致性（模型方程正确性的交叉验证）；</li>
 *   <li>反解闭环：反解给出的仰角回代正解后确实经过目标；</li>
 *   <li>射程对仰角单峰——反解二分搜索的收敛前提。</li>
 * </ul>
 *
 * @see RealisticTrajectory#forwardSolve
 * @see RealisticTrajectory#solveFiringAngle
 */
class RealisticTrajectoryInvariantTest {

    /** 常密度，避开随高度变化带来的额外变量 */
    private static final DensityFunction CONSTANT_DENSITY = pos -> 1.2f;

    /** 无阻力、无重力：匀速直线 */
    @Test
    void linearMotionWithoutGravityOrDragIsExact() {
        var config = new BallisticConfig(0f, 1f, 0f, 0f, 0.02f, 50);
        var result = RealisticTrajectory.forwardSolve(
                new Vec3(0, 64, 0), new Vec3(10, -3, 5), config, CONSTANT_DENSITY);

        assertEquals(51, result.samples().size(), "maxSteps + 1 个采样点");
        for (TrajectorySample sample : result.samples()) {
            double t = sample.time();
            assertEquals(10.0 * t, sample.position().x, 1e-6, "匀速直线：x 与时间成正比");
            assertEquals(64.0 - 3.0 * t, sample.position().y, 1e-6, "匀速直线：y 与时间成正比");
            assertEquals(5.0 * t, sample.position().z, 1e-6, "匀速直线：z 与时间成正比");
            assertEquals(-3.0, sample.velocity().y, 1e-9, "匀速直线：速度恒定");
        }
    }

    /** 无阻力：与半隐式欧拉的离散闭式解逐点一致 */
    @Test
    void parabolicPathMatchesDiscreteSemiImplicitEuler() {
        float dt = 0.05f;
        int steps = 100;
        float gravity = 9.81f;
        var config = new BallisticConfig(0f, 1f, 0f, gravity, dt, steps);
        var result = RealisticTrajectory.forwardSolve(
                new Vec3(0, 64, 0), new Vec3(300, 120, 0), config, CONSTANT_DENSITY);

        // 半隐式欧拉：v_n = v_0 − g·n·dt，p_n = p_0 + Σ_{i=1..n} v_i·dt
        double expectedX = 300.0 * steps * dt;
        double expectedY = 64.0 + 120.0 * steps * dt
                - gravity * (double) dt * dt * steps * (steps + 1) / 2.0;
        double expectedVy = 120.0 - gravity * steps * dt;

        TrajectorySample last = result.samples().get(result.samples().size() - 1);
        assertEquals(steps * dt, last.time(), 1e-6);
        assertEquals(expectedX, last.position().x, 1e-3);
        assertEquals(expectedY, last.position().y, 1e-3);
        assertEquals(expectedVy, last.velocity().y, 1e-3);
    }

    /** 外力只有重力与阻力时，水平方向不会产生侧向分量：轨迹保持在初始速度的竖直平面内 */
    @Test
    void trajectoryStaysInLaunchVerticalPlane() {
        var config = new BallisticConfig(0.3f, 1f, 0.001f, 9.81f, 0.01f, 300);
        var result = RealisticTrajectory.forwardSolve(
                new Vec3(0, 64, 0), new Vec3(200, 80, -100), config, CONSTANT_DENSITY);

        // 初始水平方向为 (2, 0, -1)，故平面约束为 z = −x/2
        for (TrajectorySample sample : result.samples()) {
            assertEquals(-sample.position().x / 2.0, sample.position().z, 0.05,
                    "位置偏离初始竖直平面");
            assertEquals(-sample.velocity().x / 2.0, sample.velocity().z, 0.01,
                    "速度偏离初始竖直平面");
        }
    }

    /**
     * 半隐式欧拉与高阶参考解的一致性。
     * <p>
     * 参考解在测试内用 RK4 独立实现（步长 dt/10），与 {@code forwardSolve} 的
     * 半隐式欧拉共享同一组方程。两者之差应当与步长同阶；若方程项写错（例如阻力少一个
     * ½、重力符号反了），差值会是米级而不是厘米级。
     */
    @Test
    void eulerIntegrationAgreesWithHigherOrderReference() {
        var config = new BallisticConfig(0.3f, 1f, 0.001f, 9.81f, 0.002f, 1000);
        Vec3 start = new Vec3(0, 64, 0);
        Vec3 velocity = new Vec3(300, 120, 0);
        var result = RealisticTrajectory.forwardSolve(start, velocity, config, CONSTANT_DENSITY);

        Vec3 eulerEnd = result.samples().get(result.samples().size() - 1).position();
        Vec3 referenceEnd = referenceRk4(start, velocity, config, 2.0);
        assertTrue(eulerEnd.distanceTo(referenceEnd) < 0.2,
                "半隐式欧拉与 RK4 参考解偏差过大：" + eulerEnd.distanceTo(referenceEnd));
    }

    /** 反解闭环：返回的仰角回代正解后经过目标（无阻力时解析解应当精确） */
    @Test
    void firingAngleSolutionPassesThroughTarget() {
        var config = new BallisticConfig(0f, 1f, 0f, 9.81f, 0.01f, 2000);
        float speed = 600f;
        Vec3 start = new Vec3(0, 64, 0);

        for (double distance : new double[]{500, 1000, 1500}) {
            Vec3 target = new Vec3(distance, 64, 0);
            List<FiringSolution> solutions = RealisticTrajectory.solveFiringAngle(
                    start, target, speed, Vec3.ZERO, config, CONSTANT_DENSITY, 0.01f);
            assertFalse(solutions.isEmpty(), "距离 " + distance + " 应当有解");

            FiringSolution solution = solutions.get(0);
            var flight = RealisticTrajectory.forwardSolve(
                    start, solution.direction().scale(speed), config, CONSTANT_DENSITY);
            // 采样间隔为 speed·dt，直接取最近采样点会引入数米误差；在目标水平距离处线性插值
            double heightAtTarget = interpolatedHeight(flight.samples(), distance);
            assertTrue(Math.abs(heightAtTarget - 64) < 0.3,
                    "距 " + distance + " 的解回代后在目标处高度为 " + heightAtTarget);
        }
    }

    /**
     * 射程对仰角单峰。
     * <p>
     * {@code solveFiringAngle} 在两个半区间上各做一次二分，前提是射程随仰角“先升后降”。
     * 该性质必须由测试守住：带推力的二维推进若破坏单峰性，反解会收敛到错误的极值。
     */
    @Test
    void rangeIsUnimodalInElevation() {
        var config = new BallisticConfig(0.3f, 0.1f, 3.14e-4f, 9.81f, 0.01f, 5000);
        float speed = 300f;

        double[] ranges = new double[45];
        for (int i = 0; i < ranges.length; i++) {
            ranges[i] = horizontalRange(config, speed, 1 + 2 * i);
        }

        int peak = 0;
        for (int i = 1; i < ranges.length; i++) {
            if (ranges[i] > ranges[peak]) peak = i;
        }
        assertTrue(peak > 0 && peak < ranges.length - 1, "峰值不应落在扫描区间端点上");
        for (int i = 0; i < peak; i++) {
            assertTrue(ranges[i] <= ranges[i + 1] + 1e-6,
                    "峰值前射程应单调不减：index " + i);
        }
        for (int i = peak; i < ranges.length - 1; i++) {
            assertTrue(ranges[i] >= ranges[i + 1] - 1e-6,
                    "峰值后射程应单调不增：index " + i);
        }
    }

    // ==================== 辅助 ====================

    /** 以给定仰角发射，返回到达发射高度的水平距离；未在步数内落地返回 0 */
    private static double horizontalRange(BallisticConfig config, float speed, double elevationDeg) {
        double elevation = Math.toRadians(elevationDeg);
        Vec3 direction = new Vec3(Math.cos(elevation), Math.sin(elevation), 0);
        var result = RealisticTrajectory.forwardSolve(
                new Vec3(0, 64, 0), direction.scale(speed), config, CONSTANT_DENSITY);

        for (TrajectorySample sample : result.samples()) {
            if (sample.time() > 0.5 && sample.position().y <= 64) {
                return sample.position().x;
            }
        }
        return 0;
    }

    /** 在给定水平距离处对轨迹高度线性插值；轨迹未达到该距离时返回 NaN */
    private static double interpolatedHeight(List<TrajectorySample> samples, double x) {
        for (int i = 1; i < samples.size(); i++) {
            double x0 = samples.get(i - 1).position().x;
            double x1 = samples.get(i).position().x;
            if (x1 >= x) {
                if (x1 - x0 < 1e-9) return samples.get(i).position().y;
                double f = (x - x0) / (x1 - x0);
                double y0 = samples.get(i - 1).position().y;
                double y1 = samples.get(i).position().y;
                return y0 + (y1 - y0) * f;
            }
        }
        return Double.NaN;
    }

    /** 测试内独立实现的 RK4 参考积分，用于交叉验证模型方程 */
    private static Vec3 referenceRk4(Vec3 start, Vec3 velocity, BallisticConfig config, double totalTime) {
        double h = 0.0002;
        double halfRhoRef = 0.5 * config.dragCoefficient() * config.crossSectionArea() / config.mass();
        Vec3 p = start;
        Vec3 v = velocity;
        for (double t = 0; t < totalTime - 1e-12; t += h) {
            Vec3 k1p = v;
            Vec3 k1v = acceleration(v, config.gravity(), halfRhoRef);
            Vec3 k2p = v.add(k1v.scale(0.5 * h));
            Vec3 k2v = acceleration(k2p, config.gravity(), halfRhoRef);
            Vec3 k3p = v.add(k2v.scale(0.5 * h));
            Vec3 k3v = acceleration(k3p, config.gravity(), halfRhoRef);
            Vec3 k4p = v.add(k3v.scale(h));
            Vec3 k4v = acceleration(k4p, config.gravity(), halfRhoRef);
            p = p.add(k1p.add(k2p.scale(2)).add(k3p.scale(2)).add(k4p).scale(h / 6.0));
            v = v.add(k1v.add(k2v.scale(2)).add(k3v.scale(2)).add(k4v).scale(h / 6.0));
        }
        return p;
    }

    private static Vec3 acceleration(Vec3 velocity, float gravity, double halfRhoRef) {
        double speed = velocity.length();
        Vec3 drag = speed > 1e-10
                ? velocity.scale(-halfRhoRef * 1.2 * speed)
                : Vec3.ZERO;
        return new Vec3(drag.x, drag.y - gravity, drag.z);
    }
}
