package io.github.sweetzonzi.terminal_ballistics.internal;

import io.github.sweetzonzi.terminal_ballistics.api.TBDamageApi;
import io.github.sweetzonzi.terminal_ballistics.api.TBDamageContext;
import io.github.sweetzonzi.terminal_ballistics.api.TBHurtTarget;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin 共享拦截逻辑（内部实现）。
 * <p>
 * 两个 Mixin 类（{@code EntityHurtMixin}、{@code LivingEntityHurtMixin}）均委托此类完成
 * 协议外伤害拦截判断。抽离为独立类避免在 {@code @Mixin} 类中声明非 private 静态方法。
 */
public final class TBHurtInterceptor {

    private TBHurtInterceptor() {}

    /**
     * 在 {@link Entity#hurt(DamageSource, float)} 的 HEAD 阶段执行拦截判断。
     * <p>
     * 三种情况：
     * <ol>
     *   <li>已在此目标的协议管线内（hasContextFor）→ 放行，不拦截</li>
     *   <li>目标非 TBHurtTarget → 放行</li>
     *   <li>协议外伤害命中 TBHurtTarget → 接管，走协议管线</li>
     * </ol>
     *
     * @param self   调用 hurt 的实体
     * @param source 伤害来源
     * @param amount 伤害量
     * @param cir    Mixin 回调
     */
    public static void intercept(Entity self, DamageSource source, float amount,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (TBDamageApi.hasContextFor(self)) return;
        if (!(self instanceof TBHurtTarget tb)) return;

        TBDamageContext ctx = tb.createContextFromVanilla(source, amount);
        if (ctx == null) return;

        float result = TBDamageApi.hurt(self, ctx);
        if (result > 0f) {
            cir.setReturnValue(true);
        }
    }
}
