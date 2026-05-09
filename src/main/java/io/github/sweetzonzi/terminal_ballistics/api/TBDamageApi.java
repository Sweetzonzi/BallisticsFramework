package io.github.sweetzonzi.terminal_ballistics.api;

import io.github.sweetzonzi.terminal_ballistics.internal.TBContextStack;
import net.minecraft.world.entity.LivingEntity;

/**
 * 协议层对外入口。
 * <p>
 * 武器模组调用 {@link #hurt(Object, TBDamageContext)} 发起协议伤害；
 * mixin 实现者调用 {@link #hasContextFor(Object)} 判断重入。
 */
public final class TBDamageApi {

    private TBDamageApi() {}

    /**
     * 发起一次协议伤害。
     * <p>
     * 自动完成穿甲判定并执行最终伤害。调用方只需构造上下文后传入即可。
     *
     * @param target 伤害目标（{@link TBHurtTarget} 或普通 {@link LivingEntity}）
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
            if (target instanceof LivingEntity living) {
                return living.hurt(ctx.source(), ctx.baseDamage()) ? ctx.baseDamage() : 0f;
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
}
