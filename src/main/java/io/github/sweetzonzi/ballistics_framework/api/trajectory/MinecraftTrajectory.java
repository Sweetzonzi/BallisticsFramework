package io.github.sweetzonzi.ballistics_framework.api.trajectory;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * MC 原版物理解算器——使用与原版弹射物（箭、雪球、火球等）相同的每 tick 物理模型。
 * <p>
 * 物理模型（类型 A 计算顺序）：每 tick 执行 加速度 → 阻力 → 位置。
 * <pre>{@code
 * v.y += gravity          // 加速度（m/tick²）
 * v.x *= dragH            // 水平阻力（每 tick 速度乘数）
 * v.y *= dragV            // 垂直阻力
 * v.z *= dragH
 * p += v                  // 欧拉积分
 * }</pre>
 * <p>
 * <strong>入参速度单位为 m/tick</strong>（与 MC 原版 {@code Entity.getDeltaMovement()} 一致），
 * 输出 {@link TrajectoryResult} 和 {@link FiringSolution} 中速度自动换算为 m/s（×20）。
 * <p>
 * 解算器自动识别退化路径（匀速直线、纯抛物线、直线减速），在不损失精度的情况下跳过分步积分。
 * <p>
 * 所有方法均为 {@code static} 且无内部状态，线程安全。
 */
public final class MinecraftTrajectory {

    private MinecraftTrajectory() {}

    // ======================== 实体预设参数常量 ========================

    /** 箭 / 三叉戟 */
    public static final class Arrows {
        public static final float GRAVITY = -0.05f;
        public static final float DRAG_H  = 0.99f;
        public static final float DRAG_V  = 0.99f;
    }

    /** 雪球 / 鸡蛋 / 末影珍珠 */
    public static final class Snowballs {
        public static final float GRAVITY = -0.03f;
        public static final float DRAG_H  = 0.99f;
        public static final float DRAG_V  = 0.99f;
    }

    /** 喷溅药水 */
    public static final class Potions {
        public static final float GRAVITY = -0.05f;
        public static final float DRAG_H  = 0.99f;
        public static final float DRAG_V  = 0.99f;
    }

    /** 附魔之瓶 */
    public static final class BottlesOfEnchanting {
        public static final float GRAVITY = -0.07f;
        public static final float DRAG_H  = 0.99f;
        public static final float DRAG_V  = 0.99f;
    }

    /** 火球 / 凋灵之首（注意：正向加速度，向上漂） */
    public static final class Fireballs {
        public static final float GRAVITY = 0.10f;
        public static final float DRAG_H  = 0.95f;
        public static final float DRAG_V  = 0.95f;
    }

    /** 羊驼唾沫 */
    public static final class LlamaSpit {
        public static final float GRAVITY = -0.06f;
        public static final float DRAG_H  = 0.99f;
        public static final float DRAG_V  = 0.99f;
    }

    // ======================== 退化路径判定 ========================

    /** 判定是否无阻力（速度乘数等于 1） */
    private static boolean isNoDrag(float dragH, float dragV) {
        return dragH == 1.0f && dragV == 1.0f;
    }

    // ======================== 正解（前向模拟）- 主方法 ========================

    /**
     * 弹道前向模拟（通用参数）。
     * <p>
     * 每 tick 物理顺序：加速度 → 阻力 → 位置。
     * 入参速度单位为 m/tick（与 MC 原版 {@code Entity.getDeltaMovement()} 一致），
     * 输出 {@link TrajectoryResult} 中速度自动换算为 m/s（×20）。
     * <p>
     * 退化路径自动选择：
     * <ul>
     *   <li>g=0 且无阻力 → 匀速直线运动（O(1)）</li>
     *   <li>g=0 且有阻力 → 直线减速运动</li>
     *   <li>g≠0 且无阻力 → 纯抛物线（仍走分步积分以确保采样粒度一致）</li>
     *   <li>否则 → 完整数值积分</li>
     * </ul>
     *
     * @param start    发射位置（世界坐标）
     * @param velocity 初速度矢量（m/tick）
     * @param dragH    水平每 tick 速度乘数（0~1），如 0.99 表示每 tick 保留 99% 水平速度
     * @param dragV    垂直每 tick 速度乘数（0~1），如 0.98 表示每 tick 保留 98% 垂直速度
     * @param gravity  重力加速度（m/tick²），向下为负值，火球等正向加速度实体用正值
     * @param maxTicks 最大模拟 tick 数（超过则强制终止）
     * @return 轨迹采样点列表，速度单位为 m/s
     */
    public static TrajectoryResult forwardSolve(
            Vec3 start, Vec3 velocity,
            float dragH, float dragV, float gravity,
            int maxTicks
    ) {
        // 退化路径 1：匀速直线运动（g=0, 无阻力）
        if (gravity == 0f && isNoDrag(dragH, dragV)) {
            return solveLinearMotion(start, velocity, maxTicks);
        }
        // 退化路径 2：直线减速运动（g=0, 有阻力）
        if (gravity == 0f) {
            return solveStraightDeceleration(start, velocity, dragH, dragV, maxTicks);
        }
        // 完整数值积分（包含 g≠0 且无阻力的纯抛物线，仍走分步积分）
        return solveFullIntegration(start, velocity, dragH, dragV, gravity, maxTicks);
    }

    /** 匀速直线运动——正解 O(1)，直接计算终点位置 */
    private static TrajectoryResult solveLinearMotion(Vec3 start, Vec3 velocity, int maxTicks) {
        var samples = new ArrayList<TrajectorySample>(maxTicks + 1);
        float timeStep = 0.05f;
        // 发射时刻
        samples.add(new TrajectorySample(0f, start, new Vec3(velocity.x * 20, velocity.y * 20, velocity.z * 20)));
        Vec3 pos = start;
        for (int i = 1; i <= maxTicks; i++) {
            pos = pos.add(velocity);
            samples.add(new TrajectorySample(i * timeStep, pos,
                    new Vec3(velocity.x * 20, velocity.y * 20, velocity.z * 20)));
        }
        return new TrajectoryResult(samples);
    }

    /** 直线减速运动——g=0，仅沿速度方向减速 */
    private static TrajectoryResult solveStraightDeceleration(
            Vec3 start, Vec3 velocity, float dragH, float dragV, int maxTicks
    ) {
        var samples = new ArrayList<TrajectorySample>(maxTicks + 1);
        float timeStep = 0.05f;
        samples.add(new TrajectorySample(0f, start, new Vec3(velocity.x * 20, velocity.y * 20, velocity.z * 20)));
        Vec3 pos = start;
        Vec3 vel = velocity;
        for (int i = 1; i <= maxTicks; i++) {
            vel = new Vec3(vel.x * dragH, vel.y * dragV, vel.z * dragH);
            pos = pos.add(vel);
            float spd = (float) vel.length();
            if (spd < 1e-8f) {
                samples.add(new TrajectorySample(i * timeStep, pos,
                        new Vec3(vel.x * 20, vel.y * 20, vel.z * 20)));
                return new TrajectoryResult(samples);
            }
            samples.add(new TrajectorySample(i * timeStep, pos,
                    new Vec3(vel.x * 20, vel.y * 20, vel.z * 20)));
        }
        return new TrajectoryResult(samples);
    }

    /** 完整数值积分——标准 MC 类型 A 计算顺序 */
    private static TrajectoryResult solveFullIntegration(
            Vec3 start, Vec3 velocity, float dragH, float dragV, float gravity, int maxTicks
    ) {
        var samples = new ArrayList<TrajectorySample>(maxTicks + 1);
        float timeStep = 0.05f;
        samples.add(new TrajectorySample(0f, start, new Vec3(velocity.x * 20, velocity.y * 20, velocity.z * 20)));
        Vec3 pos = start;
        Vec3 vel = velocity;
        for (int i = 1; i <= maxTicks; i++) {
            // 步骤 1：加速度
            vel = vel.add(0, gravity, 0);
            // 步骤 2：阻力
            vel = new Vec3(vel.x * dragH, vel.y * dragV, vel.z * dragH);
            // 步骤 3：位置
            pos = pos.add(vel);
            samples.add(new TrajectorySample(i * timeStep, pos,
                    new Vec3(vel.x * 20, vel.y * 20, vel.z * 20)));
        }
        return new TrajectoryResult(samples);
    }

    // ======================== 正解便捷方法 ========================

    /** 箭/三叉戟弹道 */
    public static TrajectoryResult arrowTrajectory(Vec3 start, Vec3 velocity, int maxTicks) {
        return forwardSolve(start, velocity, Arrows.DRAG_H, Arrows.DRAG_V, Arrows.GRAVITY, maxTicks);
    }

    /** 雪球/鸡蛋弹道 */
    public static TrajectoryResult snowballTrajectory(Vec3 start, Vec3 velocity, int maxTicks) {
        return forwardSolve(start, velocity, Snowballs.DRAG_H, Snowballs.DRAG_V, Snowballs.GRAVITY, maxTicks);
    }

    /** 火球弹道 */
    public static TrajectoryResult fireballTrajectory(Vec3 start, Vec3 velocity, int maxTicks) {
        return forwardSolve(start, velocity, Fireballs.DRAG_H, Fireballs.DRAG_V, Fireballs.GRAVITY, maxTicks);
    }

    /** 喷溅药水弹道 */
    public static TrajectoryResult potionTrajectory(Vec3 start, Vec3 velocity, int maxTicks) {
        return forwardSolve(start, velocity, Potions.DRAG_H, Potions.DRAG_V, Potions.GRAVITY, maxTicks);
    }

    /** 附魔之瓶弹道 */
    public static TrajectoryResult bottleTrajectory(Vec3 start, Vec3 velocity, int maxTicks) {
        return forwardSolve(start, velocity, BottlesOfEnchanting.DRAG_H, BottlesOfEnchanting.DRAG_V,
                BottlesOfEnchanting.GRAVITY, maxTicks);
    }

    // ======================== 反解（出射仰角二分搜索）=======================

    /**
     * 基于初速标量反解出射方向。
     * <p>
     * 方位角由目标水平投影直接确定。仰角通过二分搜索求解。
     * 初速充足时返回两个解（平射弹道 + 高抛弹道），先平射后高抛。
     * 临界初速时两解收敛，仅返回平射等效解。
     * <p>
     * 入参速度单位为 m/tick，输出 {@link FiringSolution} 中速度自动换算为 m/s（×20）。
     * <p>
     * 退化路径自动选择（匀速直线、纯抛物线解析解、直线减速）：
     * <ul>
     *   <li>g=0 且无阻力 → 指向目标的单位向量，O(1)</li>
     *   <li>g≠0 且无阻力 → 抛物线解析仰角公式，不二分搜索</li>
     *   <li>g=0 且有阻力 → 沿目标方向一维标量二分</li>
     *   <li>否则 → 完整二维二分搜索（仰角）</li>
     * </ul>
     *
     * @param start           发射位置
     * @param target          目标位置
     * @param speed           初速标量（m/tick）
     * @param shooterVelocity 发射者自身速度（m/tick），与 {@code Entity.getDeltaMovement()} 一致，可为 Vec3.ZERO
     * @param dragH           水平每 tick 速度乘数
     * @param dragV           垂直每 tick 速度乘数
     * @param gravity         重力加速度（m/tick²）
     * @param maxTicks        最大模拟 tick 数
     * @param angleEpsilon    仰角收敛阈值（弧度），推荐 0.01（≈0.57°）
     * @return 0~2 个解（空列表表示初速不足，任何仰角都无法命中）
     */
    public static List<FiringSolution> solveFiringAngle(
            Vec3 start, Vec3 target, float speed,
            Vec3 shooterVelocity,
            float dragH, float dragV, float gravity,
            int maxTicks, float angleEpsilon
    ) {
        Vec3 diff = target.subtract(start);
        float horizDist = (float) Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float heightDiff = (float) diff.y;

        // 退化路径 1：匀速直线运动（g=0, 无阻力）
        if (gravity == 0f && isNoDrag(dragH, dragV)) {
            return solveLinearFiring(start, target, speed, shooterVelocity, diff, horizDist);
        }

        // 退化路径 2：纯抛物线（g≠0, 无阻力）——解析解
        if (isNoDrag(dragH, dragV)) {
            return solveParabolicFiring(start, target, speed, shooterVelocity,
                    horizDist, heightDiff, gravity);
        }

        // 退化路径 3：直线减速（g=0, 有阻力）——一维标量搜索
        if (gravity == 0f) {
            return solveDecelerationFiring(start, target, speed, shooterVelocity,
                    diff, horizDist, dragH, dragV, maxTicks, angleEpsilon);
        }

        // 完整路径：二维二分搜索仰角
        return solveFullFiringAngle(start, target, speed, shooterVelocity,
                dragH, dragV, gravity, maxTicks, angleEpsilon, horizDist, heightDiff);
    }

    /** 匀速直线反解：出射方向直接指向目标，单选 */
    private static List<FiringSolution> solveLinearFiring(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity,
            Vec3 diff, float horizDist
    ) {
        if (horizDist < 1e-6f && diff.y == 0) {
            return List.of();
        }
        // 速度方向指向目标
        Vec3 dir = diff.normalize();
        // 世界速度 = 方向 × 标量 + 发射者速度
        Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
        float flightTime = horizDist < 1e-6f
                ? (float) (Math.abs(diff.y) / Math.abs((float) worldVel.y))
                : (float) (diff.horizontalDistance() / new Vec3(worldVel.x, 0, worldVel.z).length());
        if (flightTime <= 0) return List.of();
        Vec3 terminalVel = worldVel;
        return List.of(new FiringSolution(dir, flightTime,
                new Vec3(terminalVel.x * 20, terminalVel.y * 20, terminalVel.z * 20),
                false, 0));
    }

    /** 纯抛物线反解：解析仰角公式，无二分搜索 */
    private static List<FiringSolution> solveParabolicFiring(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity,
            float horizDist, float heightDiff, float gravity
    ) {
        if (horizDist < 1e-6f) {
            // 垂直方向发射
            Vec3 dir = heightDiff >= 0 ? new Vec3(0, 1, 0) : new Vec3(0, -1, 0);
            Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
            float flightTime = computeParabolicVerticalFlightTime(heightDiff, (float) worldVel.y, gravity);
            if (flightTime <= 0) return List.of();
            Vec3 terminalVel = new Vec3(worldVel.x, (float) (worldVel.y + gravity * flightTime), worldVel.z);
            return List.of(new FiringSolution(dir, flightTime,
                    new Vec3(terminalVel.x * 20, terminalVel.y * 20, terminalVel.z * 20),
                    false, 0));
        }

        float gAbs = Math.abs(gravity);
        float s2 = speed * speed;
        float gd = gAbs * horizDist;
        float discriminant = s2 * s2 - gAbs * gAbs * horizDist * horizDist - 2 * gAbs * heightDiff * s2;

        if (discriminant < 0) {
            return List.of(); // 初速不足，无法命中
        }

        float sqrtD = (float) Math.sqrt(discriminant);
        List<FiringSolution> solutions = new ArrayList<>(2);
        Vec3 horizDir = new Vec3(target.x - start.x, 0, target.z - start.z).normalize();

        // 解析仰角公式：θ = atan((s² ± √Δ) / (g·d))
        // 注意：s²+√Δ 对应较大仰角（高抛），s²-√Δ 对应较小仰角（平射）
        float thetaLow, thetaHigh;
        boolean critical = false;

        // 平射解（较小仰角）
        float numeratorLow = s2 - sqrtD;
        if (numeratorLow > 0) {
            thetaLow = (float) Math.atan(numeratorLow / gd);
        } else if (Math.abs(numeratorLow) < 1e-6f) {
            thetaLow = 0f;
            critical = true;
        } else {
            thetaLow = 0f;
        }

        // 高抛解（较大仰角）
        float numeratorHigh = s2 + sqrtD;
        thetaHigh = (float) Math.atan(numeratorHigh / gd);

        if (critical || Math.abs(thetaLow - thetaHigh) < 1e-4f) {
            // 临界初速，两解收敛
            float theta = (thetaLow + thetaHigh) / 2f;
            Vec3 dir = buildDirection(horizDir, theta);
            Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
            float flightTime = computeParabolicFlightTime(horizDist, heightDiff, (float) worldVel.y, gravity);
            Vec3 terminalVel = new Vec3(worldVel.x,
                    (float) (worldVel.y + gravity * flightTime),
                    worldVel.z);
            solutions.add(new FiringSolution(dir, flightTime,
                    new Vec3(terminalVel.x * 20, terminalVel.y * 20, terminalVel.z * 20),
                    false, 0));
        } else {
            // 平射解
            Vec3 dirLow = buildDirection(horizDir, thetaLow);
            Vec3 worldVelLow = dirLow.scale(speed).add(shooterVelocity);
            float flightTimeLow = computeParabolicFlightTime(horizDist, heightDiff,
                    (float) worldVelLow.y, gravity);
            Vec3 terminalVelLow = new Vec3(worldVelLow.x,
                    (float) (worldVelLow.y + gravity * flightTimeLow),
                    worldVelLow.z);
            solutions.add(new FiringSolution(dirLow, flightTimeLow,
                    new Vec3(terminalVelLow.x * 20, terminalVelLow.y * 20, terminalVelLow.z * 20),
                    false, 0));

            // 高抛解
            Vec3 dirHigh = buildDirection(horizDir, thetaHigh);
            Vec3 worldVelHigh = dirHigh.scale(speed).add(shooterVelocity);
            float flightTimeHigh = computeParabolicFlightTime(horizDist, heightDiff,
                    (float) worldVelHigh.y, gravity);
            Vec3 terminalVelHigh = new Vec3(worldVelHigh.x,
                    (float) (worldVelHigh.y + gravity * flightTimeHigh),
                    worldVelHigh.z);
            solutions.add(new FiringSolution(dirHigh, flightTimeHigh,
                    new Vec3(terminalVelHigh.x * 20, terminalVelHigh.y * 20, terminalVelHigh.z * 20),
                    true, 0));
        }

        return solutions;
    }

    /** 抛物线飞行时间计算 */
    private static float computeParabolicFlightTime(float horizDist, float heightDiff,
                                                     float velY, float gravity) {
        if (Math.abs(gravity) < 1e-10f) {
            return horizDist / Math.max(Math.abs(velY), 1e-6f);
        }
        return horizDist / Math.max(Math.abs(velY), 1e-6f);
    }

    /** 垂直方向抛物线飞行时间计算 */
    private static float computeParabolicVerticalFlightTime(float heightDiff, float velY, float gravity) {
        if (Math.abs(gravity) < 1e-10f) {
            return Math.abs(heightDiff) / Math.max(Math.abs(velY), 1e-6f);
        }
        // h = v*t + 0.5*g*t² → 0.5*g*t² + v*t - h = 0
        float a = Math.abs(gravity) * 0.5f;
        float b = velY;
        float c = -heightDiff;
        float disc = b * b - 4 * a * c;
        if (disc < 0) return -1;
        float t1 = (float) ((-b + Math.sqrt(disc)) / (2 * a));
        float t2 = (float) ((-b - Math.sqrt(disc)) / (2 * a));
        return t1 > 0 ? t1 : t2;
    }

    /** 直线减速反解：沿目标方向一维标量搜索 */
    private static List<FiringSolution> solveDecelerationFiring(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity,
            Vec3 diff, float horizDist,
            float dragH, float dragV, int maxTicks, float angleEpsilon
    ) {
        if (horizDist < 1e-6f && diff.y == 0) {
            return List.of();
        }
        Vec3 dir = diff.normalize();
        Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
        // 沿目标方向模拟，找到最近接近点
        TrajectoryResult result = forwardSolve(start, worldVel, dragH, dragV, 0f, maxTicks);

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

        return List.of(new FiringSolution(dir, bestTime, bestSample.velocity(),
                false, 0));
    }

    /** 完整路径：仰角二分搜索 */
    private static List<FiringSolution> solveFullFiringAngle(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity,
            float dragH, float dragV, float gravity,
            int maxTicks, float angleEpsilon,
            float horizDist, float heightDiff
    ) {
        if (horizDist < 1e-6f) {
            // 垂直方向：直接向上或向下
            Vec3 dir = heightDiff >= 0 ? new Vec3(0, 1, 0) : new Vec3(0, -1, 0);
            Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
            TrajectoryResult result = forwardSolve(start, worldVel, dragH, dragV, gravity, maxTicks);
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

        Vec3 horizDir = new Vec3(target.x - start.x, 0, target.z - start.z).normalize();
        List<FiringSolution> solutions = new ArrayList<>(2);

        // 平射解搜索区间 [θ_min_low, θ_max_low]，从贴近水平到约 45°
        float thetaMin = 0.001f;
        float thetaMid = (float) (Math.PI / 4); // 45度
        float thetaMax = (float) (Math.PI / 2 - 0.001f);

        // 平射解搜索
        FiringSolution lowSol = binarySearchAngle(start, target, speed, shooterVelocity,
                dragH, dragV, gravity, maxTicks, horizDir,
                thetaMin, thetaMid, angleEpsilon, false);
        if (lowSol != null) {
            solutions.add(lowSol);
        }

        // 高抛解搜索
        FiringSolution highSol = binarySearchAngle(start, target, speed, shooterVelocity,
                dragH, dragV, gravity, maxTicks, horizDir,
                thetaMid, thetaMax, angleEpsilon, true);
        if (highSol != null) {
            // 如果平射解和高抛解几乎相同，去重
            if (solutions.isEmpty() || angleDiff(highSol.direction(), solutions.get(0).direction()) > angleEpsilon) {
                solutions.add(highSol);
            }
        }

        return solutions;
    }

    /** 仰角二分搜索核心 */
    private static FiringSolution binarySearchAngle(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity,
            float dragH, float dragV, float gravity, int maxTicks,
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
            Vec3 dir = buildDirection(horizDir, theta);
            Vec3 worldVel = dir.scale(speed).add(shooterVelocity);
            TrajectoryResult result = forwardSolve(start, worldVel, dragH, dragV, gravity, maxTicks);

            // 计算最近接近距离
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
                break; // 已足够接近
            }

            // 判断倾向：检查弹道在目标高度时是否超过目标水平位置
            float horizAtTargetHeight = estimateHorizontalProgress(result, target);
            if (horizAtTargetHeight > 1.0f) {
                // 超过目标 → 仰角过高（高抛倾向）或过低（实际是过高导致近端下坠也过了）
                // 安全策略：比较最近接近点前后位置与目标的水平距离
                boolean overshoot = checkHorizontalOvershoot(result, target, horizDir);
                if (overshoot) {
                    thetaHigh = theta;
                } else {
                    thetaLow = theta;
                }
            } else {
                // 未达到目标 → 仰角过低
                thetaLow = theta;
            }

            if (thetaHigh - thetaLow < epsilon) {
                break;
            }
            iterations++;
        }

        return bestSolution;
    }

    /** 构建带仰角的方向向量 */
    private static Vec3 buildDirection(Vec3 horizDir, float theta) {
        float cosT = (float) Math.cos(theta);
        float sinT = (float) Math.sin(theta);
        return new Vec3(horizDir.x * cosT, sinT, horizDir.z * cosT);
    }

    /** 估算弹道在目标高度处的水平进度（0~1 为未到达，>1 为超过） */
    private static float estimateHorizontalProgress(TrajectoryResult result, Vec3 target) {
        float targetY = (float) target.y;
        float targetHorizDist = (float) new Vec3(target.x - result.first().position().x, 0,
                target.z - result.first().position().z).length();

        if (targetHorizDist < 1e-6f) return 0f;

        TrajectorySample prev = result.first();
        for (int i = 1; i < result.samples().size(); i++) {
            var curr = result.samples().get(i);
            float prevY = (float) prev.position().y;
            float currY = (float) curr.position().y;

            if ((prevY >= targetY && currY <= targetY) || (prevY <= targetY && currY >= targetY)) {
                // 插值找到穿过目标高度时的水平位置
                float t = (targetY - prevY) / (currY - prevY);
                float interpX = (float) (prev.position().x + t * (curr.position().x - prev.position().x));
                float interpZ = (float) (prev.position().z + t * (curr.position().z - prev.position().z));
                float horizDone = (float) Math.sqrt(interpX * interpX + interpZ * interpZ);
                return horizDone / targetHorizDist;
            }
            prev = curr;
        }
        // 弹道未达到目标高度
        return -1f;
    }

    /** 检查弹道水平方向是否超过目标 */
    private static boolean checkHorizontalOvershoot(TrajectoryResult result, Vec3 target, Vec3 horizDir) {
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

    /** 两个方向向量之间的角度差（弧度） */
    private static float angleDiff(Vec3 a, Vec3 b) {
        double dot = Math.max(-1, Math.min(1, a.dot(b)));
        return (float) Math.acos(dot);
    }

    // ======================== 反解便捷方法 ========================

    /** 便捷反解：箭/三叉戟参数 */
    public static List<FiringSolution> arrowFiringAngle(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity, int maxTicks
    ) {
        return solveFiringAngle(start, target, speed, shooterVelocity,
                Arrows.DRAG_H, Arrows.DRAG_V, Arrows.GRAVITY, maxTicks, 0.01f);
    }

    /** 便捷反解：雪球/鸡蛋参数 */
    public static List<FiringSolution> snowballFiringAngle(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity, int maxTicks
    ) {
        return solveFiringAngle(start, target, speed, shooterVelocity,
                Snowballs.DRAG_H, Snowballs.DRAG_V, Snowballs.GRAVITY, maxTicks, 0.01f);
    }

    /** 便捷反解：火球参数 */
    public static List<FiringSolution> fireballFiringAngle(
            Vec3 start, Vec3 target, float speed, Vec3 shooterVelocity, int maxTicks
    ) {
        return solveFiringAngle(start, target, speed, shooterVelocity,
                Fireballs.DRAG_H, Fireballs.DRAG_V, Fireballs.GRAVITY, maxTicks, 0.01f);
    }

    // ======================== 动目标提前量反解 ========================

    /**
     * 动目标瞄准点迭代收敛求解。
     * <p>
     * 算法：
     * <ol>
     *   <li>以 distance/speed 估算粗略飞行时间，外推目标初始预测位置</li>
     *   <li>以预测位置反解弹道 → 获得飞行时间 t_flight</li>
     *   <li>预测：P_predicted = P_current + V_target · t_flight
     *       （若 targetAcceleration 非零：+ ½·a·t_flight²）</li>
     *   <li>以 P_predicted 为新目标位置，重复步骤 2</li>
     *   <li>当 |P_predicted 变化量| &lt; convergeEpsilon 时收敛</li>
     * </ol>
     * <p>
     * 典型场景下 2~4 次迭代即可收敛（首次粗略外推已靠近收敛点）。
     * 极端情况（目标速度接近弹丸速度）可能发散——设 maxIterations 保护。
     *
     * @param start              发射位置
     * @param targetPosition     目标当前位置
     * @param targetVelocity     目标速度矢量（m/tick）
     * @param targetAcceleration 目标加速度矢量（m/tick²），可为 Vec3.ZERO
     * @param speed              初速标量（m/tick）
     * @param shooterVelocity    发射者速度（m/tick）
     * @param dragH              水平每 tick 速度乘数
     * @param dragV              垂直每 tick 速度乘数
     * @param gravity            重力加速度（m/tick²）
     * @param maxTicks           最大模拟 tick 数
     * @param maxIterations      最大迭代次数（推荐 5~8）
     * @param convergeEpsilon    收敛阈值（m），推荐 0.1
     * @return 最优瞄准解（即使未收敛也返回最优猜测）
     */
    public static FiringSolution solveWithLead(
            Vec3 start,
            Vec3 targetPosition, Vec3 targetVelocity, Vec3 targetAcceleration,
            float speed, Vec3 shooterVelocity,
            float dragH, float dragV, float gravity,
            int maxTicks, int maxIterations, float convergeEpsilon
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
                    dragH, dragV, gravity, maxTicks, 0.01f);

            if (solutions.isEmpty()) {
                // 当前预测位置无法命中，返回上次的最优解
                return bestSolution;
            }

            // 取平射解（第一个）
            FiringSolution sol = solutions.get(0);
            float flightTime = sol.flightTime();

            // 预测新位置：P + V·t + ½·a·t²
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

    /**
     * 动目标提前量反解便捷方法：箭/三叉戟参数。
     *
     * @see #solveWithLead(Vec3, Vec3, Vec3, Vec3, float, Vec3, float, float, float, int, int, float)
     */
    public static FiringSolution arrowWithLead(
            Vec3 start, Vec3 targetPosition, Vec3 targetVelocity, Vec3 targetAcceleration,
            float speed, Vec3 shooterVelocity, int maxTicks
    ) {
        return solveWithLead(start, targetPosition, targetVelocity, targetAcceleration,
                speed, shooterVelocity,
                Arrows.DRAG_H, Arrows.DRAG_V, Arrows.GRAVITY,
                maxTicks, 8, 0.1f);
    }

    /**
     * 动目标提前量反解便捷方法：雪球/鸡蛋参数。
     *
     * @see #solveWithLead(Vec3, Vec3, Vec3, Vec3, float, Vec3, float, float, float, int, int, float)
     */
    public static FiringSolution snowballWithLead(
            Vec3 start, Vec3 targetPosition, Vec3 targetVelocity, Vec3 targetAcceleration,
            float speed, Vec3 shooterVelocity, int maxTicks
    ) {
        return solveWithLead(start, targetPosition, targetVelocity, targetAcceleration,
                speed, shooterVelocity,
                Snowballs.DRAG_H, Snowballs.DRAG_V, Snowballs.GRAVITY,
                maxTicks, 8, 0.1f);
    }

    /**
     * 动目标提前量反解便捷方法：火球参数。
     *
     * @see #solveWithLead(Vec3, Vec3, Vec3, Vec3, float, Vec3, float, float, float, int, int, float)
     */
    public static FiringSolution fireballWithLead(
            Vec3 start, Vec3 targetPosition, Vec3 targetVelocity, Vec3 targetAcceleration,
            float speed, Vec3 shooterVelocity, int maxTicks
    ) {
        return solveWithLead(start, targetPosition, targetVelocity, targetAcceleration,
                speed, shooterVelocity,
                Fireballs.DRAG_H, Fireballs.DRAG_V, Fireballs.GRAVITY,
                maxTicks, 8, 0.1f);
    }
}
