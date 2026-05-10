package io.github.sweetzonzi.terminal_ballistics.api;

import io.github.sweetzonzi.terminal_ballistics.internal.TBContextStack;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * 协议层对外入口。
 * <p>
 * 武器模组调用 {@link #hurt(Object, TBDamageContext)} 发起协议伤害；
 * mixin 实现者调用 {@link #hasContextFor(Object)} 判断重入；
 * TBHurtTarget 实现者可在 {@code hurt()} 或其它管线方法内调用
 * {@link #getContextFor(Object)} 获取当前攻击上下文。
 */
public final class TBDamageApi {

    private TBDamageApi() {}

    /**
     * 发起一次协议伤害。
     * <p>
     * 自动完成穿甲判定并执行最终伤害。调用方只需构造上下文后传入即可。
     *
     * @param target 伤害目标（{@link TBHurtTarget} 或普通 {@link Entity}）
     * @param ctx    完整命中上下文
     * @return 实际造成的伤害量
     */
    public static float hurt(Object target, TBDamageContext ctx) {
        // 压栈 → 执行穿甲判定与伤害 → finally 出栈
        TBContextStack.INSTANCE.push(target, ctx);
        try {
            if (target instanceof TBHurtTarget tb) {
                float finalDamage = tb.calculateFinalDamage(ctx);
                return tb.hurt(ctx.source(), finalDamage) ? finalDamage : 0f;
            }
            if (target instanceof Entity entity) {
                return entity.hurt(ctx.source(), ctx.baseDamage()) ? ctx.baseDamage() : 0f;
            }
            return 0f;
        } finally {
            TBContextStack.INSTANCE.pop();
        }
    }

    /**
     * 判断当前线程中是否已有针对指定目标的协议上下文。
     * <p>
     * 用于 mixin 重入守卫：若已在同一目标的协议管线内，应放行原版流程。
     *
     * @param target 要检查的目标
     * @return true 表示该目标正处于协议伤害管线中
     */
    public static boolean hasContextFor(Object target) {
        return TBContextStack.INSTANCE.hasContextFor(target);
    }

    /**
     * 获取当前线程中指定目标的协议上下文。
     * <p>
     * 在 {@link TBHurtTarget#hurt TBHurtTarget.hurt()} 或
     * {@link TBHurtTarget#calculateFinalDamage calculateFinalDamage()} 等
     * 管线方法内调用，以获取完整的命中上下文（命中位置、速度、穿透、侧信道扩展等），
     * 从而基于这些信息做额外后效处理，如播放不同位置的中弹音效、产生破片粒子等。
     * <p>
     * 调用示例：
     * <pre>{@code
     * public boolean hurt(DamageSource source, float amount) {
     *     TBDamageContext ctx = TBDamageApi.getContextFor(this);
     *     if (ctx != null) {
     *         // 根据命中位置播放音效
     *         playHitSound(ctx.hitPoint());
     *         // 读取侧信道跳弹标记
     *         if (Boolean.TRUE.equals(
     *                 ctx.extensions().get(TBDamageExtensions.RICOCHET))) {
     *             playRicochetEffect(ctx.hitPoint(), ctx.hitNormal());
     *         }
     *     }
     *     return super.hurt(source, amount);
     * }
     * }</pre>
     *
     * @param target 要获取上下文的目标（通常传入 {@code this}）
     * @return 当前协议上下文；如果不在协议管线内、或栈顶目标不匹配则返回 null
     */
    @Nullable
    public static TBDamageContext getContextFor(Object target) {
        return TBContextStack.INSTANCE.getContextFor(target);
    }
}
