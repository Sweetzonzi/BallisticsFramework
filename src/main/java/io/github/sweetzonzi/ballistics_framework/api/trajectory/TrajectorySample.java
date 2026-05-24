package io.github.sweetzonzi.ballistics_framework.api.trajectory;

import net.minecraft.world.phys.Vec3;

/**
 * 弹道单个采样点——某一时刻的完整运动状态。
 * <p>
 * 采样点按时间升序排列，相邻两点的 time 差即积分步长。
 * 第一个采样点 time=0（发射时刻），最后一个 time=总飞行时间。
 *
 * @param time     自发射起经过的时间（s），MC 模型下 1 tick = 0.05s
 * @param position 世界坐标（m）
 * @param velocity 瞬时速度矢量（m/s，统一输出单位）
 */
public record TrajectorySample(
        float time,
        Vec3 position,
        Vec3 velocity
) {}
