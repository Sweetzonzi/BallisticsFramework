package io.github.sweetzonzi.terminal_ballistics.api;

/**
 * 穿甲判定的一次性结果。
 * <p>
 * PENETRATED / BLOCKED / RICOCHET 三者互斥，是命中的主结果。
 * 由 {@link TBHurtTarget#resolvePenetration(TBDamageContext)} 返回，
 * 作为 {@code calculateFinalDamage} 的入参和回调触发的唯一依据。
 * <p>
 * 超匹配(碾压)与破片不在此枚举中表达——它们由
 * {@link TBDamageHandler#isOvermatch(TBHurtTarget, TBDamageContext, PenetrationResult)}
 * 和 {@link TBDamageHandler#isSpall(TBHurtTarget, TBDamageContext, PenetrationResult)}
 * 在回调阶段动态判定。
 */
public enum PenetrationResult {
    /** 击穿（穿深足以穿透装甲） */
    PENETRATED,
    /** 未击穿（含钝伤等，伤害量由护甲侧自由决定） */
    BLOCKED,
    /** 跳弹（穿深不足 + 入射角过大） */
    RICOCHET
}
