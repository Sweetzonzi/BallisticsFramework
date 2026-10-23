package io.github.sweetzonzi.ballistics_framework.api.trajectory;

/**
 * 发动机推力剖面——沿速度方向、大小恒定、只在给定时间窗内生效。
 * <p>
 * 时间用<b>求解器时间</b>表达：窗口是 {@code [startSeconds, endSeconds)}，从本次求解的
 * 起点（{@code t = 0}）算起。中途续算时调用方无需告诉求解器“已经燃了多久”，只需传
 * {@code (force, 0, 剩余燃烧时长)}；剩余时长为 0 时直接传 {@link #NONE}。
 * <p>
 * 推力的施加方式是 {@code a_T = force / BallisticConfig#mass}，方向恒为 {@code v / |v|}。
 * 求解器把它并入正解、反解与动目标提前量（见 {@code docs/弹道求解器-推力项设计.md}）。
 * <p>
 * 本类型不可变，线程安全。所有值使用国际单位制：力 N，时间 s。
 *
 * @param force        发动机推力（N），必须为正；非正或非有限值视为无效
 * @param startSeconds 点火时刻（s，自本次求解起点算起），可为负（表示全程）
 * @param endSeconds   熄火时刻（s，自本次求解起点算起），必须严格大于 {@code startSeconds}
 */
public record ThrustProfile(float force, float startSeconds, float endSeconds) {

    /** 无推力——行为与不带推力参数的旧签名完全一致 */
    public static final ThrustProfile NONE = new ThrustProfile(0f, 0f, 0f);

    /**
     * 窗口非空（{@code endSeconds > startSeconds}）、推力为正、三个值均有限时为 true。
     * <p>
     * 这是所有推力分支的唯一门禁：为 false 时求解器走与旧签名逐位一致的路径。
     *
     * @return 该剖面是否会实际施加推力
     */
    public boolean isActive() {
        return force > 0f
                && endSeconds > startSeconds
                && Float.isFinite(force)
                && Float.isFinite(startSeconds)
                && Float.isFinite(endSeconds);
    }
}
