package io.github.sweetzonzi.ballistics_framework.api.trajectory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 发动机推力剖面——由若干推力段组成的段表，决定“何时推多大、烧掉多少药”。
 * <p>
 * 每个 {@link Segment} 是一个左闭右开的时间窗
 * {@code [delaySeconds, delaySeconds + durationSeconds)}：窗内推力沿速度方向、大小恒定，
 * 窗外与段与段之间的空隙都不施加推力（弹道滑行）。段表按 {@code delaySeconds} 升序存放，
 * 允许空隙、不允许重叠。
 * <p>
 * <b>时间的原点是发射时刻。</b>求解器把传入的剖面一律当作“已经平移到本次求解起点”，
 * 即 {@code t = 0} 就是入参位置与速度对应的那一瞬。中途重解（初速被改变、燃尽校核）时
 * 先用 {@link #remainingSince(double)} 把剖面平移，否则整条燃烧窗口会整体后移。
 * <p>
 * <b>质量只有两个来源。</b>{@link BallisticConfig#mass} 是弹体基础质量，
 * {@link Segment#massKg} 是该段推进剂在段内匀速烧掉的质量；于是
 * {@code m(t) = 基础质量 + Σ 未燃尽的段质量}，由 {@link #massAt(double, float)} 按段序在
 * {@code float} 里累加。段质量为 0 的剖面（{@link #of(float, float, float)}、{@link #NONE}）
 * 不改变质量，推力加速度因此恒为 {@code forceN / BallisticConfig#mass}。
 * <p>
 * 本类型不可变、线程安全。所有值使用国际单位制：力 N，时间 s，质量 kg。
 * 契约与验收断言见 {@code docs/推力分段与燃烧质量流失-实施计划.md}。
 */
public record ThrustProfile(List<Segment> segments) {

    /**
     * 单个推力段。
     *
     * @param forceN          推力（N），必须为正
     * @param delaySeconds    点火延迟（s，自本次求解起点算起），可为负（表示起点处已在燃烧）
     * @param durationSeconds 持续时长（s），必须为正；段末质量恰好归零
     * @param massKg          该段推进剂质量（kg），段内匀速消耗；0 表示该段不改变质量
     */
    public record Segment(float forceN, float delaySeconds, float durationSeconds, float massKg) {
    }

    /** 质量下限，只用于避免除零；远小于任何真实弹丸 */
    private static final float MASS_FLOOR = 1e-6f;

    /**
     * 段间重叠的判定容差（s）。{@code delaySeconds} 与 {@code durationSeconds} 都是 {@code float}，
     * “上一段结束”与“下一段开始”两个十进制写法在二进制里可能相差几个 ulp，因此小于该值的
     * 重叠按接触处理——真正的笔误（毫秒级及以上）仍会被拒绝。
     */
    private static final double OVERLAP_TOLERANCE = 1e-6;

    /** 无推力——空段表，行为与不带推力参数的重载完全一致 */
    public static final ThrustProfile NONE = new ThrustProfile(List.of());

    /**
     * 规范化段表：丢弃无效段、按点火时刻排序、拒绝重叠、冻结列表。
     *
     * <p>无效段指 {@code forceN} 或 {@code durationSeconds} 非正、{@code massKg} 为负、
     * 任一值非有限，或派生量 {@code massKg / durationSeconds} 非有限。丢弃一段的效果是这一段
     * 从此不存在：它既不产生推力也不贡献质量。
     *
     * @throws IllegalArgumentException 两段的窗口重叠超过 {@link #OVERLAP_TOLERANCE}
     * @throws NullPointerException     段表或其元素为 {@code null}
     */
    public ThrustProfile {
        Objects.requireNonNull(segments, "segments");
        List<Segment> valid = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            if (isValid(Objects.requireNonNull(segment, "segments 不允许包含 null"))) {
                valid.add(segment);
            }
        }
        valid.sort(Comparator.comparingDouble(Segment::delaySeconds));
        for (int i = 1; i < valid.size(); i++) {
            Segment previous = valid.get(i - 1);
            Segment current = valid.get(i);
            if ((double) current.delaySeconds() < endSecondsOf(previous) - OVERLAP_TOLERANCE) {
                throw new IllegalArgumentException(
                        "推力段窗口重叠：段 " + previous + " 与段 " + current);
            }
        }
        segments = List.copyOf(valid);
    }

    /**
     * 单段、恒质量的剖面——“一次点火助推”的规范写法，等价于
     * {@code new ThrustProfile(List.of(new Segment(forceN, delaySeconds, durationSeconds, 0f)))}。
     * <p>
     * 第三个参数是<b>持续时长</b>而不是熄火时刻：单段的冲量就是
     * {@code forceN × durationSeconds}，与内容包的 {@code delay + duration} 一一对应。
     *
     * @param forceN          推力（N）
     * @param delaySeconds    点火延迟（s）
     * @param durationSeconds 持续时长（s）
     * @return 只含一个段的剖面
     */
    public static ThrustProfile of(float forceN, float delaySeconds, float durationSeconds) {
        return new ThrustProfile(List.of(new Segment(forceN, delaySeconds, durationSeconds, 0f)));
    }

    /**
     * 段表非空即为 true——规范化保证剩下的每一段都有效。
     * <p>
     * 这是所有推力分支的唯一门禁：为 false 时求解器走与不带剖面参数的重载逐位一致的路径。
     *
     * @return 该剖面是否会实际施加推力
     */
    public boolean isActive() {
        return !segments.isEmpty();
    }

    /**
     * 给定时刻的推力。
     *
     * @param seconds 自本次求解起点算起的时刻（s）
     * @return 落在某个段窗口内时是该段的 {@code forceN}，否则为 {@code 0}
     */
    public float forceAt(double seconds) {
        for (Segment segment : segments) {
            double start = segment.delaySeconds();
            if (seconds < start) {
                break; // 段表按点火时刻升序
            }
            if (seconds < endSecondsOf(segment)) {
                return segment.forceN();
            }
        }
        return 0f;
    }

    /**
     * 给定时刻的弹丸质量：基础质量加上各段尚未燃尽的质量。
     * <p>
     * 逐段在 {@code float} 里累加（{@code total += massKg − burned}），不做“先升到 double 求和
     * 再窄化”——那样每段多一次舍入，{@link #remainingSince(double)} 裁剪后的剖面与原剖面会在
     * 末位分家。
     *
     * @param seconds  自本次求解起点算起的时刻（s）
     * @param baseMass 弹体基础质量（kg），即 {@link BallisticConfig#mass}
     * @return {@code max(基础质量 + Σ 未燃尽的段质量, 1e-6)}；段质量全为 0 时不改变质量
     *         （{@code baseMass} 本身高于 1e-6 时逐位返回 {@code baseMass}）
     */
    public float massAt(double seconds, float baseMass) {
        float total = baseMass;
        for (Segment segment : segments) {
            total += segment.massKg() - burnedAt(segment, seconds);
        }
        return Math.max(total, MASS_FLOOR);
    }

    /** 首段点火时刻；{@link #NONE} 为 0 */
    public float startSeconds() {
        return segments.isEmpty() ? 0f : segments.get(0).delaySeconds();
    }

    /**
     * 末段熄火时刻 = 末段 {@code delaySeconds + durationSeconds}，窄化到 {@code float}；
     * {@link #NONE} 为 0。它是只读派生量，窗口判定以 {@link #forceAt(double)} 为准——
     * 两者相差最多 1 ulp。
     */
    public float endSeconds() {
        return segments.isEmpty() ? 0f : (float) endSecondsOf(segments.get(segments.size() - 1));
    }

    /** 总燃烧时长 = Σ {@code durationSeconds}，不等于 {@link #endSeconds()} 减 {@link #startSeconds()} */
    public float burnDuration() {
        float total = 0f;
        for (Segment segment : segments) {
            total += segment.durationSeconds();
        }
        return total;
    }

    /**
     * 把剖面平移到新的时间原点，用于中途重解。
     * <p>
     * 已燃尽的段丢弃，正在燃烧的段改为“从 0 起、剩余时长、剩余质量”，尚未点火的段整体后移。
     * 质量用与 {@link #massAt(double, float)} 同一个折算函数，因此
     * {@code remainingSince(elapsed).massAt(0, m)} 与原剖面 {@code massAt(elapsed, m)} 逐位相同；
     * 基础质量不在裁剪范围内，续算不需要任何质量参数。
     *
     * @param elapsedSeconds 距原时间原点已经过去的秒数
     * @return 平移后的新剖面；没有剩余段时返回等价的空剖面
     */
    public ThrustProfile remainingSince(double elapsedSeconds) {
        List<Segment> remaining = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            double end = endSecondsOf(segment);
            if (end <= elapsedSeconds) {
                continue; // 已燃尽
            }
            if (segment.delaySeconds() < elapsedSeconds) {
                remaining.add(new Segment(
                        segment.forceN(),
                        0f,
                        (float) (end - elapsedSeconds),
                        segment.massKg() - burnedAt(segment, elapsedSeconds)));
            } else {
                remaining.add(new Segment(
                        segment.forceN(),
                        (float) (segment.delaySeconds() - elapsedSeconds),
                        segment.durationSeconds(),
                        segment.massKg()));
            }
        }
        return new ThrustProfile(remaining);
    }

    /** 窗口右端；在 {@code double} 里算，避免两个 float 相加后再窄化的二次舍入 */
    private static double endSecondsOf(Segment segment) {
        return (double) segment.delaySeconds() + (double) segment.durationSeconds();
    }

    /**
     * 段内已燃尽的质量。
     * <p>
     * 先算比值再乘 {@code massKg}：比值被钳到 1 时结果<b>恰好</b>是 {@code massKg}，
     * 段末质量精确归零；写成“钳位到秒再乘 {@code massKg / duration}”会留下 1e-6 kg 量级的残渣。
     */
    private static float burnedAt(Segment segment, double seconds) {
        double ratio = (seconds - segment.delaySeconds()) / segment.durationSeconds();
        if (ratio <= 0.0) {
            return 0f;
        }
        if (ratio >= 1.0) {
            return segment.massKg();
        }
        return segment.massKg() * (float) ratio;
    }

    private static boolean isValid(Segment segment) {
        return segment.forceN() > 0f
                && segment.durationSeconds() > 0f
                && segment.massKg() >= 0f
                && Float.isFinite(segment.forceN())
                && Float.isFinite(segment.delaySeconds())
                && Float.isFinite(segment.durationSeconds())
                && Float.isFinite(segment.massKg())
                && Float.isFinite(segment.massKg() / segment.durationSeconds());
    }
}
