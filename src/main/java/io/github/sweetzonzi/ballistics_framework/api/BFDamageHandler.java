package io.github.sweetzonzi.ballistics_framework.api;

/**
 * 协议伤害发起方的处理接口。
 * <p>
 * 由武器/弹头模组实现，在构造 {@link BFDamageContext} 时通过
 * {@code builder.handler(myHandler)} 或 {@link BFDamageContext#withHandler(BFDamageHandler)} 注入。
 * <p>
 * 职责：
 * <ul>
 *   <li>接收管线执行后的事件回调（击穿/未击穿/跳弹/超匹配(碾压)/破片）</li>
 *   <li>通过 {@link #isOvermatch} / {@link #isSpall} 的默认实现，
 *       向协议层声明是否应触发超匹配(碾压)与破片回调；实现者可覆写以完全控制条件</li>
 *   <li>向护甲侧提供伤害来源的元信息（护甲侧通过
 *       {@code ctx.getHandler() instanceof MyHandler} 做精细判定）</li>
 * </ul>
 * <p>
 * 所有回调方法的默认实现均为空操作，实现者按需覆写。
 * {@link #onPenetrated}、{@link #onBlocked}、{@link #onRicochet} 三个主结果回调
 * 由 {@link PenetrationResult} 决定；{@link #onOvermatch} 和 {@link #onSpall}
 * 仅由对应的 {@link #isOvermatch} / {@link #isSpall} 返回值决定。
 * <p>
 * 回调在 {@link BFDamageApi#hurt} 的管线末尾、伤害执行之后、上下文栈出栈之前触发。
 */
public interface BFDamageHandler {

    // ==================== 事件回调 ====================

    /** 击穿回调。伤害已执行后触发 */
    default void onPenetrated(BFHurtTarget target, BFDamageContext ctx) {}

    /** 未击穿回调（含钝伤等）。伤害已执行后触发 */
    default void onBlocked(BFHurtTarget target, BFDamageContext ctx) {}

    /** 跳弹回调。伤害已执行后触发 */
    default void onRicochet(BFHurtTarget target, BFDamageContext ctx) {}

    /**
     * 超匹配(碾压)回调。
     * <p>
     * 仅在 {@link #isOvermatch} 返回 true 时触发。
     * 穿深远超装甲厚度——弹体"碾压"装甲，不发生碎裂。
     */
    default void onOvermatch(BFHurtTarget target, BFDamageContext ctx) {}

    /**
     * 破片回调。
     * <p>
     * 仅在 {@link #isSpall} 返回 true 时触发。
     * 默认未击穿或击穿但非超匹配(碾压)时，弹体碎裂产生破片。
     */
    default void onSpall(BFHurtTarget target, BFDamageContext ctx) {}

    // ==================== 判定方法（默认实现，可覆写） ====================

    /**
     * 判断本次命中是否为超匹配(碾压)（穿深远超装甲厚度）。
     * <p>
     * 默认规则：击穿 且 穿深 &gt; 装甲厚度 × 1.5。
     * 协议不会额外限制此回调只能在某些穿甲结果下触发；覆写此方法即可
     * 自定义阈值、禁用判定，或让特殊弹药在其它结果下也触发超匹配(碾压)回调。
     *
     * @param target 伤害目标
     * @param ctx    命中上下文
     * @param result 穿甲判定结果
     * @return true 表示超匹配(碾压)——弹体碾压装甲，不发生碎裂
     */
    default boolean isOvermatch(BFHurtTarget target, BFDamageContext ctx, PenetrationResult result) {
        if (result != PenetrationResult.PENETRATED) return false;
        float rha = target.getRHA(ctx);
        float modifiedPen = target.modifyPenetration(ctx);
        return modifiedPen > rha * 1.5f;
    }

    /**
     * 判断本次命中是否产生破片（弹体碎裂）。
     * <p>
     * 默认规则：
     * <ul>
     *   <li>未击穿 → 弹体在装甲表面碎裂 → 产生破片</li>
     *   <li>击穿但非超匹配(碾压) → 弹体在穿透过程中碎裂 → 产生破片</li>
     *   <li>超匹配(碾压) → 弹体完整穿透 → 不产生破片</li>
     *   <li>跳弹 → 弹体偏转飞走 → 不产生破片</li>
     * </ul>
     * 协议不会额外限制此回调只能在某些穿甲结果下触发；覆写此方法即可
     * 自定义破片条件，例如让跳弹碎裂、HESH 命中或特殊弹药在任意结果下产生破片。
     *
     * @param target 伤害目标
     * @param ctx    命中上下文
     * @param result 穿甲判定结果
     * @return true 表示产生破片
     */
    default boolean isSpall(BFHurtTarget target, BFDamageContext ctx, PenetrationResult result) {
        if (result == PenetrationResult.RICOCHET) return false;
        if (result == PenetrationResult.BLOCKED) return true;
        return !isOvermatch(target, ctx, result);
    }

    // ==================== 便捷方法 ====================

    /**
     * 发起一次协议伤害，并将自身作为 handler 注入上下文以便接收回调。
     * <p>
     * 等价于 {@code BFDamageApi.hurt(target, ctx.withHandler(this))}。
     * 调用方无需知道 {@code withHandler} 的存在。
     *
     * @param target 伤害目标（{@link BFHurtTarget} 或普通 {@link net.minecraft.world.entity.Entity}）
     * @param ctx    命中上下文（handler 字段可留空，本方法自动注入）
     * @return 实际造成的伤害量
     */
    default float dealDamage(Object target, BFDamageContext ctx) {
        return BFDamageApi.hurt(target, ctx.withHandler(this));
    }
}
