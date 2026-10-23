package io.github.sweetzonzi.ballistics_framework.api.trajectory;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 *   <li>射程对仰角单峰——反解二分搜索的收敛前提；</li>
 *   <li>推力项（T1~T9）：旧签名逐位兼容、离散加速闭式解、燃烧窗口的尾部外推、
 *       分派门禁、带推力的单峰性与提前量闭环。</li>
 * </ul>
 *
 * @see RealisticTrajectory#forwardSolve
 * @see RealisticTrajectory#solveFiringAngle
 */
class RealisticTrajectoryInvariantTest {

    /** 常密度，避开随高度变化带来的额外变量 */
    private static final DensityFunction CONSTANT_DENSITY = pos -> 1.2f;

    /** 推力测试用的弹丸：26 kg、152 mm、Cd 0.3——2600 N 对应 a_T = 100 m/s²，与文档的 Kornet 一致 */
    private static final ThrustProfile KORNET = new ThrustProfile(2600f, 0f, 2.5f);

    /** 带推力测试的推荐初速（Kornet 出膛 50 m/s） */
    private static final float LAUNCH_SPEED = 50f;

    private static BallisticConfig thrustConfig(float dt, int maxSteps) {
        return new BallisticConfig(0.3f, 26f, 0.018f, 9.81f, dt, maxSteps);
    }

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

    // ==================== 推力项 ====================

    /** T1：三个旧签名与显式 {@code NONE} 重载逐位相等 */
    @Test
    void legacySignaturesMatchNoneOverloads() {
        var config = thrustConfig(0.01f, 300);
        Vec3 start = new Vec3(0, 64, 0);
        Vec3 velocity = new Vec3(50, 20, -10);

        var legacy = RealisticTrajectory.forwardSolve(start, velocity, config, CONSTANT_DENSITY);
        var explicitNone = RealisticTrajectory.forwardSolve(
                start, velocity, config, CONSTANT_DENSITY, ThrustProfile.NONE);
        assertEquals(legacy.samples().size(), explicitNone.samples().size());
        for (int i = 0; i < legacy.samples().size(); i++) {
            assertEquals(legacy.samples().get(i).time(), explicitNone.samples().get(i).time());
            assertEquals(legacy.samples().get(i).position(), explicitNone.samples().get(i).position());
            assertEquals(legacy.samples().get(i).velocity(), explicitNone.samples().get(i).velocity());
        }

        Vec3 target = new Vec3(500, 64, 0);
        assertEquals(
                RealisticTrajectory.solveFiringAngle(start, target, 300f, Vec3.ZERO,
                        config, CONSTANT_DENSITY, 0.01f),
                RealisticTrajectory.solveFiringAngle(start, target, 300f, Vec3.ZERO,
                        config, CONSTANT_DENSITY, 0.01f, ThrustProfile.NONE));

        Vec3 targetVel = new Vec3(0, 0, 10);
        assertEquals(
                RealisticTrajectory.solveWithLead(start, target, targetVel, Vec3.ZERO, 300f, Vec3.ZERO,
                        config, CONSTANT_DENSITY, 5, 0.1f),
                RealisticTrajectory.solveWithLead(start, target, targetVel, Vec3.ZERO, 300f, Vec3.ZERO,
                        config, CONSTANT_DENSITY, 5, 0.1f, ThrustProfile.NONE));
    }

    /** T2：推力 + 无重力 + 无阻力时，采样点等于半隐式欧拉的离散闭式解 */
    @Test
    void thrustAccelerationMatchesDiscreteClosedForm() {
        float dt = 0.01f;
        int steps = 250;
        var config = new BallisticConfig(0f, 26f, 0f, 0f, dt, steps);
        Vec3 start = new Vec3(0, 64, 0);
        Vec3 velocity = new Vec3(50, 0, 0);
        var result = RealisticTrajectory.forwardSolve(
                start, velocity, config, CONSTANT_DENSITY, new ThrustProfile(2600f, 0f, 10f));

        double a = 100.0;
        assertEquals(steps + 1, result.samples().size());
        for (int n = 1; n <= steps; n++) {
            TrajectorySample sample = result.samples().get(n);
            double t = n * (double) dt;
            double expectedV = 50 + a * t;
            double expectedX = 50 * t + a * dt * t * (n + 1) / 2.0;
            assertEquals(expectedV, sample.velocity().x, 1e-6 * expectedV, "n=" + n + " 速度");
            assertEquals(expectedX, sample.position().x, 1e-6 * expectedX, "n=" + n + " 位置");
            // 与连续解的差恰好是 ½·a_T·Δt·t——离散语义的证据
            assertEquals(0.5 * a * dt * t,
                    expectedX - (50 * t + 0.5 * a * t * t), 1e-9, "n=" + n + " 与连续解之差");
        }
        assertEquals(64.0, result.last().position().y, 1e-9, "无重力时高度不变");
    }

    /** T4：推力二维推进与 RK4 参考解一致（同时验证轨迹的平面性） */
    @Test
    void thrustTwoDimensionalIntegrationAgreesWithHigherOrderReference() {
        float dt = 1e-4f;
        int steps = 100_000;
        var config = thrustConfig(dt, steps);
        Vec3 start = new Vec3(0, 64, 0);
        Vec3 velocity = new Vec3(50, 20, 0);
        var result = RealisticTrajectory.forwardSolve(
                start, velocity, config, CONSTANT_DENSITY, new ThrustProfile(2600f, 0f, 10f));

        Vec3 eulerEnd = result.last().position();
        Vec3 referenceEnd = referenceRk4(start, velocity, config, 10.0, new ThrustProfile(2600f, 0f, 10f));
        assertTrue(eulerEnd.distanceTo(referenceEnd) < 0.1,
                "半隐式欧拉与 RK4 参考解偏差过大：" + eulerEnd.distanceTo(referenceEnd));

        for (TrajectorySample sample : result.samples()) {
            assertEquals(0.0, sample.position().z, 1e-9, "推力不产生侧向分量");
            assertEquals(0.0, sample.velocity().z, 1e-9, "推力不产生侧向分量");
        }
    }

    /** T5：点火前与无推力解重合；燃尽瞬间另起无推力外推，尾部重合；且与整段无推力解显著不同 */
    @Test
    void burnWindowTailMatchesNoThrustExtrapolation() {
        float dt = 0.01f;
        int steps = 2000;
        var config = thrustConfig(dt, steps);
        var thrust = new ThrustProfile(2600f, 0.5f, 3.0f);
        Vec3 start = new Vec3(0, 64, 0);
        Vec3 velocity = new Vec3(50, 20, 0);

        var boosted = RealisticTrajectory.forwardSolve(start, velocity, config, CONSTANT_DENSITY, thrust);
        var plain = RealisticTrajectory.forwardSolve(start, velocity, config, CONSTANT_DENSITY);

        int firstThrust = firstThrustStep(thrust, dt, steps);
        int lastThrust = lastThrustStep(thrust, dt, steps);
        assertTrue(firstThrust < lastThrust, "窗口内应有推力步");

        // 点火前：与无推力解逐点重合（漏施加一个推力步会在点火前积累约 1 m）
        for (int i = 1; i < firstThrust; i++) {
            double gap = boosted.samples().get(i).position()
                    .distanceTo(plain.samples().get(i).position());
            assertTrue(gap < 1e-3, "点火前第 " + i + " 点与无推力解相差 " + gap + " m");
        }

        // 燃尽后：从最后一个推力步的状态另起无推力外推，尾部逐点重合
        TrajectorySample boundary = boosted.samples().get(lastThrust);
        var tail = RealisticTrajectory.forwardSolve(
                boundary.position(), boundary.velocity(), config, CONSTANT_DENSITY);
        int horizon = Math.min(1000, steps - lastThrust);
        for (int j = 0; j <= horizon; j++) {
            double gap = tail.samples().get(j).position()
                    .distanceTo(boosted.samples().get(lastThrust + j).position());
            assertTrue(gap < 1e-3, "燃尽后第 " + j + " 点与无推力外推相差 " + gap + " m");
        }

        // 区分力：若推力完全没生效，从发射点按无推力重算的终点会与带推力终点重合
        double divergence = boosted.last().position().distanceTo(plain.last().position());
        assertTrue(divergence > 100, "带推力与无推力终点只差 " + divergence + " m，断言没有区分力");
    }

    /** T6：推力激活时不得走解析抛物线捷径 */
    @Test
    void thrustSkipsParabolicShortcut() {
        float dt = 0.01f;
        int steps = 2000;
        var config = new BallisticConfig(0f, 26f, 0f, 9.81f, dt, steps);
        Vec3 start = new Vec3(0, 64, 0);
        Vec3 velocity = new Vec3(LAUNCH_SPEED, 0, 0);

        var boosted = RealisticTrajectory.forwardSolve(start, velocity, config, CONSTANT_DENSITY, KORNET);
        var plain = RealisticTrajectory.forwardSolve(start, velocity, config, CONSTANT_DENSITY);

        double boostedRange = boosted.last().position().x;
        double plainRange = plain.last().position().x;
        assertTrue(boostedRange > plainRange + 200,
                "带推力射程 " + boostedRange + " m 与抛物线射程 " + plainRange + " m 过于接近");

        // 燃尽时刻的沿轨迹速度应超过初速：50 + 100×2.5 = 300 m/s，再叠加重力在下倾航迹上的
        // 顺向分量（约 +2.7 m/s）
        double speedAtBurnEnd = boosted.samples().get(250).velocity().length();
        assertTrue(speedAtBurnEnd > 300 && speedAtBurnEnd < 310,
                "燃尽速度应约为 300 m/s，实际 " + speedAtBurnEnd);
    }

    /** T7：带推力时射程对仰角单峰——反解二分的收敛前提 */
    @Test
    void rangeIsUnimodalInElevationWithThrust() {
        var config = thrustConfig(0.01f, 4000);
        double[] ranges = new double[89];
        for (int i = 0; i < ranges.length; i++) {
            ranges[i] = thrustRange(config, KORNET, LAUNCH_SPEED, 1 + i);
        }

        int peak = 0;
        for (int i = 1; i < ranges.length; i++) {
            if (ranges[i] > ranges[peak]) peak = i;
        }
        assertTrue(peak > 0 && peak < ranges.length - 1, "峰值不应落在扫描区间端点上");

        int localMaxima = 0;
        for (int i = 1; i < ranges.length - 1; i++) {
            if (ranges[i] > ranges[i - 1] && ranges[i] >= ranges[i + 1]) {
                localMaxima++;
            }
        }
        assertEquals(1, localMaxima, "射程应恰有一个局部极大");
    }

    /** T8：带推力的反解闭环——回代正解后在目标水平距离处的高度与目标高度一致 */
    @Test
    void firingAngleSolutionPassesThroughTargetWithThrust() {
        var config = thrustConfig(0.01f, 2000);
        Vec3 start = new Vec3(0, 64, 0);

        for (double distance : new double[]{1000, 1400, 1800}) {
            Vec3 target = new Vec3(distance, 64, 0);
            List<FiringSolution> solutions = RealisticTrajectory.solveFiringAngle(
                    start, target, LAUNCH_SPEED, Vec3.ZERO, config, CONSTANT_DENSITY, 1e-4f, KORNET);
            assertFalse(solutions.isEmpty(), "距离 " + distance + " 应当有解");

            FiringSolution solution = solutions.get(0);
            var flight = RealisticTrajectory.forwardSolve(
                    start, solution.direction().scale(LAUNCH_SPEED), config, CONSTANT_DENSITY, KORNET);
            double heightAtTarget = interpolatedHeight(flight.samples(), distance);
            assertTrue(Math.abs(heightAtTarget - 64) < 1.0,
                    "距 " + distance + " 的解回代后在目标处高度为 " + heightAtTarget);
        }
    }

    /** T9：带推力的动目标提前量闭环 */
    @Test
    void leadSolutionWithThrustInterceptsMovingTarget() {
        var config = thrustConfig(0.01f, 2000);
        Vec3 start = new Vec3(0, 64, 0);
        Vec3 targetPos = new Vec3(1200, 64, 0);
        Vec3 targetVel = new Vec3(0, 0, 25);

        FiringSolution solution = RealisticTrajectory.solveWithLead(
                start, targetPos, targetVel, Vec3.ZERO, LAUNCH_SPEED, Vec3.ZERO,
                config, CONSTANT_DENSITY, 7, 0.1f, KORNET);
        assertNotNull(solution, "带推力的提前量应给出解");

        var flight = RealisticTrajectory.forwardSolve(
                start, solution.direction().scale(LAUNCH_SPEED), config, CONSTANT_DENSITY, KORNET);
        Vec3 atFlightTime = sampleAt(flight, config.timeStep(), solution.flightTime());
        Vec3 predicted = targetPos.add(targetVel.scale(solution.flightTime()));
        assertTrue(atFlightTime.distanceTo(predicted) < 5.0,
                "提前量解在飞行时刻偏离目标预测点 " + atFlightTime.distanceTo(predicted) + " m");
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

    /** 文档 §三 的步判定：第 i 步的左端点落在窗口内才施加推力 */
    private static boolean thrustAppliedAt(int step, ThrustProfile thrust, float dt) {
        double stepStart = (step - 1) * (double) dt;
        return stepStart >= thrust.startSeconds() && stepStart < thrust.endSeconds();
    }

    private static int firstThrustStep(ThrustProfile thrust, float dt, int maxSteps) {
        for (int i = 1; i <= maxSteps; i++) {
            if (thrustAppliedAt(i, thrust, dt)) return i;
        }
        return maxSteps + 1;
    }

    private static int lastThrustStep(ThrustProfile thrust, float dt, int maxSteps) {
        int last = 0;
        for (int i = 1; i <= maxSteps; i++) {
            if (thrustAppliedAt(i, thrust, dt)) last = i;
        }
        return last;
    }

    /** 带推力发射后回到发射高度的水平距离；未在步数内落地返回 0 */
    private static double thrustRange(
            BallisticConfig config, ThrustProfile thrust, float speed, double elevationDeg) {
        double elevation = Math.toRadians(elevationDeg);
        Vec3 direction = new Vec3(Math.cos(elevation), Math.sin(elevation), 0);
        var result = RealisticTrajectory.forwardSolve(
                new Vec3(0, 64, 0), direction.scale(speed), config, CONSTANT_DENSITY, thrust);
        for (TrajectorySample sample : result.samples()) {
            if (sample.time() > 0.5 && sample.position().y <= 64) {
                return sample.position().x;
            }
        }
        return 0;
    }

    /** 按均匀采样步长取给定时刻的位置（四舍五入到最近采样点） */
    private static Vec3 sampleAt(TrajectoryResult result, float dt, float time) {
        int index = Math.round(time / dt);
        index = Math.max(0, Math.min(index, result.samples().size() - 1));
        return result.samples().get(index).position();
    }

    /** 带推力的 RK4 参考积分（步长 0.0002 s），用于交叉验证二维推进 */
    private static Vec3 referenceRk4(
            Vec3 start, Vec3 velocity, BallisticConfig config, double totalTime, ThrustProfile thrust) {
        double h = 0.0002;
        double halfRhoRef = 0.5 * config.dragCoefficient() * config.crossSectionArea() / config.mass();
        double aT = thrust.force() / (double) config.mass();
        Vec3 p = start;
        Vec3 v = velocity;
        for (double t = 0; t < totalTime - 1e-12; t += h) {
            double thrustNow = t >= thrust.startSeconds() && t < thrust.endSeconds() ? aT : 0.0;
            Vec3 k1p = v;
            Vec3 k1v = acceleration(v, config.gravity(), halfRhoRef, thrustNow);
            Vec3 k2p = v.add(k1v.scale(0.5 * h));
            Vec3 k2v = acceleration(k2p, config.gravity(), halfRhoRef, thrustNow);
            Vec3 k3p = v.add(k2v.scale(0.5 * h));
            Vec3 k3v = acceleration(k3p, config.gravity(), halfRhoRef, thrustNow);
            Vec3 k4p = v.add(k3v.scale(h));
            Vec3 k4v = acceleration(k4p, config.gravity(), halfRhoRef, thrustNow);
            p = p.add(k1p.add(k2p.scale(2)).add(k3p.scale(2)).add(k4p).scale(h / 6.0));
            v = v.add(k1v.add(k2v.scale(2)).add(k3v.scale(2)).add(k4v).scale(h / 6.0));
        }
        return p;
    }

    private static Vec3 acceleration(
            Vec3 velocity, float gravity, double halfRhoRef, double thrustAccel) {
        double speed = velocity.length();
        Vec3 drag = speed > 1e-10 ? velocity.scale(-halfRhoRef * 1.2 * speed) : Vec3.ZERO;
        Vec3 accel = new Vec3(drag.x, drag.y - gravity, drag.z);
        return thrustAccel != 0 && speed > 1e-10
                ? accel.add(velocity.scale(thrustAccel / speed))
                : accel;
    }
}
