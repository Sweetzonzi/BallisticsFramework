package io.github.sweetzonzi.ballistics_framework.api.trajectory;

import net.minecraft.world.phys.Vec3;

/**
 * 瞄准解算结果——通过初速反解获得的出射方向与弹道信息。
 * <p>
 * 当初速充足时，{@link MinecraftTrajectory#solveFiringAngle} 返回两个解（先平射后高抛）。
 * 临界初速时两解收敛为一点，仅返回平射解。初速不足时返回空列表。
 *
 * @param direction        出射方向（单位向量，世界坐标），含方位角和仰角
 * @param flightTime       预计飞行时间（s）
 * @param terminalVelocity 命中目标时的弹丸末速度（m/s）
 * @param isHighArc        高抛弹道（true）还是平射弹道（false）
 * @param iterations       二分搜索收敛迭代次数
 */
public record FiringSolution(
        Vec3 direction,
        float flightTime,
        Vec3 terminalVelocity,
        boolean isHighArc,
        int iterations
) {}
