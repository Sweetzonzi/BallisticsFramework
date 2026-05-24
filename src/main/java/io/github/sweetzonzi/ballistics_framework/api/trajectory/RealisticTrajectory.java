package io.github.sweetzonzi.ballistics_framework.api.trajectory;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 二次阻力拟真解算器——基于牛顿力学 + 标准空气阻力方程，半隐式欧拉法积分。
 * <p>
 * 物理模型：
 * <pre>{@code
 * 每步 Δt:
 *   v_mag = |v|
 *   a_drag = -0.5 · ρ(pos) · Cd · A · v_mag · v / m
 *   v_new = v + (g + a_drag) · Δt          // 半隐式欧拉
 *   p_new = p + v_new · Δt
 * }</pre>
 * <p>
 * <strong>所有单位均为国际单位制（SI）</strong>：
 * 速度 m/s，位置 m，质量 kg，加速度 m/s²，时间 s。
 * 与 {@link BFDamageContext#hitVelocity} 单位一致，无需换算。
 * <p>
 * 解算器自动识别退化路径（匀速直线、纯抛物线、直线减速），
 * 在不损失精度的情况下跳过分步积分。
 * <p>
 * 所有方法均为 {@code static} 且无内部状态，线程安全。
 */
public final class RealisticTrajectory {

    private RealisticTrajectory() {}

    /** 判定是否无阻力（Cd=0 或 A=0） */
    private static boolean isNoDrag(BallisticConfig config) {
        return config.dragCoefficient() == 0f || config.crossSectionArea() == 0f;
    }

    // ======================== 正解（前向模拟）=======================

    /**
     * 半隐式欧拉法弹道前向模拟。
     * <p>
     * 阻力项：F_drag = ½ · ρ(pos) · Cd · A · |v| · v / m。
     * 每步调用 density.getDensity(pos) 以获取当前高度的空气密度。
     * <p>
     * 退化路径自动选择：
     * <ul>
     *   <li>g=0 且无阻力 → 匀速直线运动（O(1)）</li>
     *   <li>g=0 且有阻力 → 直线减速运动</li>
     *   <li>g≠0 且无阻力 → 纯抛物线</li>
     *   <li>否则 → 完整半隐式欧拉积分</li>
     * </ul>
     *
     * @param start    发射位置（世界坐标，m）
     * @param velocity 初速度矢量（m/s）
     * @param config   弹丸参数（Cd, m, A, g, Δt, maxSteps）
     * @param density  空气密度函数（逐位置查询）
     * @return 轨迹采样点列表
     */
    public static TrajectoryResult forwardSolve(
            Vec3 start, Vec3 velocity,
            BallisticConfig config, DensityFunction density
    ) {
        // 退化路径 1：匀速直线运动（g=0, 无阻力）
        if (config.gravity() == 0f && isNoDrag(config)) {
            return solveLinearMotionSI(start, velocity, config);
        }
        // 退化路径 2：直线减速运动（g=0, 有阻力）
        if (config.gravity() == 0f) {
            return solveStraightDecelerationSI(start, velocity, config, density);
        }
        // 退化路径 3：纯抛物线（有重力，无阻力）
        if (isNoDrag(config)) {
            return solveParabolicMotionSI(start, velocity, config);
        }
        // 完整积分
        return solveFullIntegrationSI(start, velocity, config, density);
    }

    /** 匀速直线运动——SI 版本 */
    private static TrajectoryResult solveLinearMotionSI(Vec3 start, Vec3 velocity, BallisticConfig config) {
        float dt = config.timeStep();
        int maxSteps = config.maxSteps();
        var samples = new ArrayList<TrajectorySample>(maxSteps + 1);
        samples.add(new TrajectorySample(0f, start, velocity));
        Vec3 pos = start;
        for (int i = 1; i <= maxSteps; i++) {
            pos = pos.add(velocity.scale(dt));
            samples.add(new TrajectorySample(i * dt, pos, velocity));
        }
        return new TrajectoryResult(samples);
    }

    /** 直线减速运动——SI 版本（仅阻力，无重力） */
    private static TrajectoryResult solveStraightDecelerationSI(
            Vec3 start, Vec3 velocity, BallisticConfig config, DensityFunction density
    ) {
        float dt = config.timeStep();
        int maxSteps = config.maxSteps();
        var samples = new ArrayList<TrajectorySample>(maxSteps + 1);
        samples.add(new TrajectorySample(0f, start, velocity));
        Vec3 pos = start;
        Vec3 vel = velocity;
        for (int i = 1; i <= maxSteps; i++) {
            float vMag = (float) vel.length();
            if (vMag < 1e-10f) {
                samples.add(new TrajectorySample(i * dt, pos, vel));
                return new TrajectoryResult(samples);
            }
            // 二次阻力项（使用起始位置密度）
            float rho = density.getDensity(pos);
            float dragMag = 0.5f * rho * config.dragCoefficient() * config.crossSectionArea() * vMag / config.mass();
            Vec3 dragAccel = vel.scale(-dragMag);
            vel = vel.add(dragAccel.scale(dt));
            pos = pos.add(vel.scale(dt));
            samples.add(new TrajectorySample(i * dt, pos, vel));
        }
        return new TrajectoryResult(samples);
    }

    /** 纯抛物线运动——SI 版本（有重力，无阻力） */
    private static TrajectoryResult solveParabolicMotionSI(
            Vec3 start, Vec3 velocity, BallisticConfig config
    ) {
        float dt = config.timeStep();
        int maxSteps = config.maxSteps();
        float gravity = config.gravity();
        var samples = new ArrayList<TrajectorySample>(maxSteps + 1);
        samples.add(new TrajectorySample(0f, start, velocity));
        Vec3 pos = start;
        Vec3 vel = velocity;
        for (int i = 1; i <= maxSteps; i++) {
            vel = vel.add(0, -gravity * dt, 0);
            pos = pos.add(vel.scale(dt));
            samples.add(new TrajectorySample(i * dt, pos, vel));
        }
        return new TrajectoryResult(samples);
    }

    /** 完整半隐式欧拉积分——SI 版本 */
    private static TrajectoryResult solveFullIntegrationSI(
            Vec3 start, Vec3 velocity, BallisticConfig config, DensityFunction density
    ) {
        float dt = config.timeStep();
        int maxSteps = config.maxSteps();
        float gravity = config.gravity();
        float cd = config.dragCoefficient();
        float aRef = config.crossSectionArea();
        float mass = config.mass();
        float halfRhoRef = 0.5f * cd * aRef / mass;

        var samples = new ArrayList<TrajectorySample>(maxSteps + 1);
        samples.add(new TrajectorySample(0f, start, velocity));
        Vec3 pos = start;
        Vec3 vel = velocity;
        for (int i = 1; i <= maxSteps; i++) {
            float vMag = (float) vel.length();
            // 二次阻力
            if (vMag > 1e-10f) {
                float rho = density.getDensity(pos);
                float dragFactor = halfRhoRef * rho * vMag;
                vel = vel.add(vel.scale(-dragFactor * dt));
            }
            // 重力
            vel = vel.add(0, -gravity * dt, 0);
            // 位置更新（半隐式欧拉）
            pos = pos.add(vel.scale(dt));
            samples.add(new TrajectorySample(i * dt, pos, vel));
        }
        return new TrajectoryResult(samples);
    }

    // ======================== 反解（出射仰角二分搜索）=======================

    /**
     * 基于初速标量反解出射方向。
     * <p>
     * 方位角由目标水平投影直接确定。仰角通过二分搜索求解。
     * 初速充足时返回两个解（平射弹道 + 高抛弹道），先平射后高抛。
     * 临界初速时两解收敛，仅返回平射等效解。
     * <p>
     * 退化路径自动选择（匀速直线、纯抛物线解析解、直线减速）：
     * <ul>
     *   <li>g=0 且无阻力 → 指向目标的单位向量，O(1)</li>
     *   <li>g≠0 且无阻力 → 抛物线解析仰角公式，不二分搜索</li>
     *   <li>g=0 且有阻力 → 沿目标方向一维搜索</li>
     *   <li>否则 → 完整二维二分搜索（仰角）</li>
     * </ul>
     *
     * @param start           发射位置（世界坐标，m）
     * @param target          目标位置（世界坐标，m）
     * @param speed           初速标量（m/s）
     * @param shooterVelocity 发射者自身速度（m/s），可为 Vec3.ZERO
     * @param config          弹丸参数
     * @param density         空气密度函数
     * @param angleEpsilon    仰角收敛阈值（弧度），推荐 0.01（≈0.57°）
     * @return 0~2 个解（空列表表示初速不足，任何仰角都无法命中）
     */
    public static List<FiringSolution> solveFiringAngle(
            Vec3 start, Vec3 target, float speed,
            Vec3 shooterVelocity,
            BallisticConfig config, DensityFunction density,
            float angleEpsilon
    ) {
        Vec3 diff = target.subtract(start);
        float horizDist = (float) Math.sqrt(diff.x * diff.x + diff.z * diff.z);

        // 退化路径 1：匀速直线运动（g=0, 无阻力）
        if (config.gravity() == 0f && isNoDrag(config)) {
            return solveLinearFiringSI(start, target, speed, shooterVelocity, diff, horizDist);
        }

        // 退化路径 2：纯抛物线（g≠0, 无阻力）
        if (isNoDrag(config)) {
            return solveParabolicFiringSI(start, target, speed, shooterVelocity,
                    horizDist, (float) diff.y, config);
        }

        // 退化路径 3：直线减速（g=0, 有阻力）
        if (config.gravity() == 0f) {
            return solveDecelerationFiringSI(start, target, speed, shooterVelocity,
                    diff, horizDist, config, density);
        }

        // 完整路径：二分搜索仰角
        return solveFullFiringAngleSI(start, target, speed, shooterVelocity,
                config, density, angleEpsilon, horizDist, (float) diff.y);
    }

    /** 匀速直线反解——SI 版本 */
    private static List<FiringSolution> solveLinearFiringSI(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity,
            Vec3 diff, float horizDist
    ) {
        if (horizDist < 1e-6f && diff.y == 0) {
            return List.of();
        }
        Vec3 dir = diff.normalize();
        Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
        float flightTime = horizDist < 1e-6f
                ? Math.abs((float) diff.y) / Math.max(Math.abs((float) worldVel.y), 1e-6f)
                : (float) diff.horizontalDistance() / Math.max(
                (float) new Vec3(worldVel.x, 0, worldVel.z).length(), 1e-6f);
        if (flightTime <= 0) return List.of();
        return List.of(new FiringSolution(dir, flightTime, worldVel, false, 0));
    }

    /** 纯抛物线反解——SI 版本 */
    private static List<FiringSolution> solveParabolicFiringSI(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity,
            float horizDist, float heightDiff, BallisticConfig config
    ) {
        float gAbs = config.gravity();
        if (gAbs < 1e-6f) {
            return solveLinearFiringSI(start, target, speed, shooterVelocity,
                    target.subtract(start), horizDist);
        }

        if (horizDist < 1e-6f) {
            Vec3 dir = heightDiff >= 0 ? new Vec3(0, 1, 0) : new Vec3(0, -1, 0);
            Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
            float flightTime = computeParabolicVerticalFlightTimeSI(heightDiff, (float) worldVel.y, gAbs);
            if (flightTime <= 0) return List.of();
            Vec3 terminalVel = new Vec3(worldVel.x, (float) (worldVel.y - gAbs * flightTime), worldVel.z);
            return List.of(new FiringSolution(dir, flightTime, terminalVel, false, 0));
        }

        float s2 = speed * speed;
        float gd = gAbs * horizDist;
        float discriminant = s2 * s2 - gAbs * gAbs * horizDist * horizDist - 2 * gAbs * heightDiff * s2;

        if (discriminant < 0) {
            return List.of();
        }

        float sqrtD = (float) Math.sqrt(discriminant);
        List<FiringSolution> solutions = new ArrayList<>(2);
        Vec3 horizDir = new Vec3(start.x, 0, start.z).reverse().add(
                new Vec3(target.x, 0, target.z)).normalize();

        float numeratorLow = s2 - sqrtD;
        float thetaLow, thetaHigh;
        boolean critical = false;

        if (numeratorLow > 0) {
            thetaLow = (float) Math.atan(numeratorLow / gd);
        } else if (Math.abs(numeratorLow) < 1e-6f) {
            thetaLow = 0f;
            critical = true;
        } else {
            thetaLow = 0f;
        }

        float numeratorHigh = s2 + sqrtD;
        thetaHigh = (float) Math.atan(numeratorHigh / gd);

        if (critical || Math.abs(thetaLow - thetaHigh) < 1e-4f) {
            float theta = (thetaLow + thetaHigh) / 2f;
            Vec3 dir = buildDirectionSI(horizDir, theta);
            Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
            float flightTime = horizDist / Math.max(
                    (float) new Vec3(worldVel.x, 0, worldVel.z).length(), 1e-6f);
            Vec3 terminalVel = new Vec3(worldVel.x, (float) (worldVel.y - gAbs * flightTime), worldVel.z);
            solutions.add(new FiringSolution(dir, flightTime, terminalVel, false, 0));
        } else {
            // 平射解
            float theta1 = thetaLow;
            Vec3 dir1 = buildDirectionSI(horizDir, theta1);
            Vec3 worldVel1 = dir1.scale(speed).add(shooterVelocity);
            float flightTime1 = horizDist / Math.max(
                    (float) new Vec3(worldVel1.x, 0, worldVel1.z).length(), 1e-6f);
            Vec3 terminalVel1 = new Vec3(worldVel1.x,
                    (float) (worldVel1.y - gAbs * flightTime1), worldVel1.z);
            solutions.add(new FiringSolution(dir1, flightTime1, terminalVel1, false, 0));

            // 高抛解
            float theta2 = thetaHigh;
            Vec3 dir2 = buildDirectionSI(horizDir, theta2);
            Vec3 worldVel2 = dir2.scale(speed).add(shooterVelocity);
            float flightTime2 = horizDist / Math.max(
                    (float) new Vec3(worldVel2.x, 0, worldVel2.z).length(), 1e-6f);
            Vec3 terminalVel2 = new Vec3(worldVel2.x,
                    (float) (worldVel2.y - gAbs * flightTime2), worldVel2.z);
            solutions.add(new FiringSolution(dir2, flightTime2, terminalVel2, true, 0));
        }

        return solutions;
    }

    /** 垂直方向抛物线飞行时间计算——SI 版本 */
    private static float computeParabolicVerticalFlightTimeSI(float heightDiff, float velY, float gravity) {
        // h = v*t - 0.5*g*t² → -0.5*g*t² + v*t - h = 0
        float a = -0.5f * gravity;
        float b = velY;
        float c = -heightDiff;
        float disc = b * b - 4 * a * c;
        if (disc < 0) return -1;
        float t1 = (float) ((-b + Math.sqrt(disc)) / (2 * a));
        float t2 = (float) ((-b - Math.sqrt(disc)) / (2 * a));
        return t1 > 0 ? t1 : (t2 > 0 ? t2 : -1);
    }

    /** 直线减速反解——SI 版本 */
    private static List<FiringSolution> solveDecelerationFiringSI(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity,
            Vec3 diff, float horizDist,
            BallisticConfig config, DensityFunction density
    ) {
        if (horizDist < 1e-6f && diff.y == 0) {
            return List.of();
        }
        Vec3 dir = diff.normalize();
        Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
        TrajectoryResult result = forwardSolve(start, worldVel, config, density);

        float minDist = Float.MAX_VALUE;
        float bestTime = 0;
        TrajectorySample bestSample = null;
        for (var sample : result.samples()) {
            float dist = (float) sample.position().distanceTo(target);
            if (dist < minDist) {
                minDist = dist;
                bestTime = sample.time();
                bestSample = sample;
            }
        }

        if (bestSample == null) return List.of();
        return List.of(new FiringSolution(dir, bestTime, bestSample.velocity(), false, 0));
    }

    /** 完整路径：仰角二分搜索——SI 版本 */
    private static List<FiringSolution> solveFullFiringAngleSI(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity,
            BallisticConfig config, DensityFunction density,
            float angleEpsilon, float horizDist, float heightDiff
    ) {
        if (horizDist < 1e-6f) {
            Vec3 dir = heightDiff >= 0 ? new Vec3(0, 1, 0) : new Vec3(0, -1, 0);
            Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
            TrajectoryResult result = forwardSolve(start, worldVel, config, density);
            float minDist = Float.MAX_VALUE;
            TrajectorySample bestSample = null;
            for (var sample : result.samples()) {
                float dist = (float) sample.position().distanceTo(target);
                if (dist < minDist) {
                    minDist = dist;
                    bestSample = sample;
                }
            }
            if (bestSample == null) return List.of();
            return List.of(new FiringSolution(dir, bestSample.time(), bestSample.velocity(), false, 0));
        }

        Vec3 horizDir = new Vec3(target.x - start.x, 0, target.z - start.z).normalize();
        List<FiringSolution> solutions = new ArrayList<>(2);

        float thetaMin = 0.001f;
        float thetaMid = (float) (Math.PI / 4);
        float thetaMax = (float) (Math.PI / 2 - 0.001f);

        // 平射解搜索
        FiringSolution lowSol = binarySearchAngleSI(start, target, speed, shooterVelocity,
                config, density, horizDir, thetaMin, thetaMid, angleEpsilon, false);
        if (lowSol != null) {
            solutions.add(lowSol);
        }

        // 高抛解搜索
        FiringSolution highSol = binarySearchAngleSI(start, target, speed, shooterVelocity,
                config, density, horizDir, thetaMid, thetaMax, angleEpsilon, true);
        if (highSol != null) {
            if (solutions.isEmpty() || angleDiffSI(highSol.direction(), solutions.get(0).direction()) > angleEpsilon) {
                solutions.add(highSol);
            }
        }

        return solutions;
    }

    /** 仰角二分搜索核心——SI 版本 */
    private static FiringSolution binarySearchAngleSI(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity,
            BallisticConfig config, DensityFunction density,
            Vec3 horizDir, float thetaLow, float thetaHigh, float epsilon,
            boolean isHighArc
    ) {
        float bestTheta = (thetaLow + thetaHigh) / 2f;
        float bestDist = Float.MAX_VALUE;
        FiringSolution bestSolution = null;
        int iterations = 0;
        int maxIter = 50;

        while (iterations < maxIter) {
            float theta = (thetaLow + thetaHigh) / 2f;
            Vec3 dir = buildDirectionSI(horizDir, theta);
            Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
            TrajectoryResult result = forwardSolve(start, worldVel, config, density);

            float minDist = Float.MAX_VALUE;
            TrajectorySample bestSample = null;
            for (var sample : result.samples()) {
                float dist = (float) sample.position().distanceTo(target);
                if (dist < minDist) {
                    minDist = dist;
                    bestSample = sample;
                }
            }

            if (minDist < bestDist) {
                bestDist = minDist;
                bestTheta = theta;
                if (bestSample != null) {
                    bestSolution = new FiringSolution(dir, bestSample.time(), bestSample.velocity(),
                            isHighArc, iterations);
                }
            }

            if (minDist < 0.5f) {
                break;
            }

            boolean overshoot = checkHorizontalOvershootSI(result, target, horizDir);
            if (overshoot) {
                thetaHigh = theta;
            } else {
                thetaLow = theta;
            }

            if (thetaHigh - thetaLow < epsilon) {
                break;
            }
            iterations++;
        }

        return bestSolution;
    }

    /** 构建带仰角的方向向量——SI 版本 */
    private static Vec3 buildDirectionSI(Vec3 horizDir, float theta) {
        float cosT = (float) Math.cos(theta);
        float sinT = (float) Math.sin(theta);
        return new Vec3(horizDir.x * cosT, sinT, horizDir.z * cosT);
    }

    /** 检查弹道水平方向是否超过目标——SI 版本 */
    private static boolean checkHorizontalOvershootSI(TrajectoryResult result, Vec3 target, Vec3 horizDir) {
        Vec3 dir2d = new Vec3(horizDir.x, 0, horizDir.z);
        double targetDot = target.subtract(result.first().position()).dot(dir2d);
        for (var sample : result.samples()) {
            double sampleDot = sample.position().subtract(result.first().position()).dot(dir2d);
            if (sampleDot > targetDot) {
                return true;
            }
        }
        return false;
    }

    /** 两个方向向量之间的角度差（弧度）——SI 版本 */
    private static float angleDiffSI(Vec3 a, Vec3 b) {
        double dot = Math.max(-1, Math.min(1, a.dot(b)));
        return (float) Math.acos(dot);
    }

    // ======================== 动目标提前量反解 ========================

    /**
     * 动目标瞄准点迭代收敛求解。
     * <p>
     * 算法：先以 distance/speed 粗略外推目标初始预测位置 → 反解弹道 → 重预测 → 迭代收敛。
     * 首次粗略外推可稳定省去一轮迭代。
     *
     * @param start              发射位置（m）
     * @param targetPosition     目标当前位置（m）
     * @param targetVelocity     目标速度矢量（m/s）
     * @param targetAcceleration 目标加速度矢量（m/s²），可为 Vec3.ZERO
     * @param speed              初速标量（m/s）
     * @param shooterVelocity    发射者速度（m/s）
     * @param config             弹丸参数
     * @param density            空气密度函数
     * @param maxIterations      最大迭代次数（推荐 4~7）
     * @param convergeEpsilon    收敛阈值（m），推荐 0.1
     * @return 最优瞄准解（即使未收敛也返回最优猜测）
     */
    public static FiringSolution solveWithLead(
            Vec3 start,
            Vec3 targetPosition, Vec3 targetVelocity, Vec3 targetAcceleration,
            float speed, Vec3 shooterVelocity,
            BallisticConfig config, DensityFunction density,
            int maxIterations, float convergeEpsilon
    ) {
        // 首次粗略外推：distance/speed 估算飞行时间，让迭代起点靠近收敛点
        float roughDist = (float) targetPosition.distanceTo(start);
        float roughTime = roughDist > 1e-6f ? roughDist / Math.max(speed, 1e-6f) : 0f;
        Vec3 predictedPos = roughTime > 0
                ? targetPosition.add(targetVelocity.scale(roughTime))
                        .add(targetAcceleration.scale(0.5f * roughTime * roughTime))
                : targetPosition;
        FiringSolution bestSolution = null;

        for (int iter = 0; iter < maxIterations; iter++) {
            var solutions = solveFiringAngle(start, predictedPos, speed, shooterVelocity,
                    config, density, 0.01f);

            if (solutions.isEmpty()) {
                return bestSolution;
            }

            FiringSolution sol = solutions.get(0);
            float flightTime = sol.flightTime();

            Vec3 newPredicted = targetPosition
                    .add(targetVelocity.scale(flightTime))
                    .add(targetAcceleration.scale(0.5f * flightTime * flightTime));

            double delta = newPredicted.distanceTo(predictedPos);
            predictedPos = newPredicted;
            bestSolution = new FiringSolution(sol.direction(), flightTime, sol.terminalVelocity(),
                    sol.isHighArc(), iter + 1);

            if (delta < convergeEpsilon) {
                break;
            }
        }

        return bestSolution;
    }
}
