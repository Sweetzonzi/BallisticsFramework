package io.github.sweetzonzi.ballistics_framework.api.trajectory;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 弹道正解计算结果。
 * <p>
 * 包含从发射时刻到模拟终止（到达 maxSteps/maxTicks）的全部采样点，按时间升序排列。
 * 不包含"是否命中目标"的判断——正解是纯物理模拟，命中判定由用户自行完成。
 * <p>
 * 解算器仅以 maxSteps/maxTicks 为终止条件，不做落地判定。
 * MC 世界 Y 坐标范围可达负值（深暗之域等），调用方应根据实际地形自行判断弹丸是否触地。
 *
 * @param samples 轨迹采样点列表（至少包含发射点一项）
 */
public record TrajectoryResult(
        List<TrajectorySample> samples
) {
    /** 紧凑构造函数——防御性拷贝，确保返回的 record 不可变 */
    public TrajectoryResult {
        samples = List.copyOf(samples);
    }

    /** @return 发射时刻采样点 */
    public TrajectorySample first() {
        return samples.get(0);
    }

    /** @return 终止时刻采样点（达 maxSteps/maxTicks 时） */
    public TrajectorySample last() {
        return samples.get(samples.size() - 1);
    }

    /** @return 总飞行时间（s） */
    public float totalTime() {
        return last().time();
    }

    /** @return 终点位置——可用于构造 BFDamageContext.hitPoint */
    public Vec3 terminalPoint() {
        return last().position();
    }

    /** @return 末速度——用于计算残余穿深 */
    public Vec3 terminalVelocity() {
        return last().velocity();
    }

    /** @return 命中面法线近似（末速度反方向归一化） */
    public Vec3 approximateHitNormal() {
        return last().velocity().normalize().scale(-1);
    }
}
