package io.github.sweetzonzi.ballistics_framework.internal;

import io.github.sweetzonzi.ballistics_framework.api.BFDamageApi;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageContext;
import io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin 共享拦截逻辑（内部实现）。
 * <p>
 * 两个 Mixin 类（{@code EntityHurtMixin}、{@code LivingEntityHurtMixin}）均委托此类完成
 * 协议外伤害拦截判断。抽离为独立类避免在 {@code @Mixin} 类中声明非 private 静态方法。
 */
public final class BFHurtInterceptor {

    private BFHurtInterceptor() {}

    /**
     * 在 {@link Entity#hurt(DamageSource, float)} 的 HEAD 阶段执行拦截判断。
     * <p>
     * 四种情况：
     * <ol>
     *   <li>已在此目标的协议管线内（hasContextFor）→ 放行，不拦截</li>
     *   <li>实体自身是 BFHurtTarget → 通过 createContextFromVanilla 接管</li>
     *   <li>实体穿戴了 BFArmorMaterial 护甲 → 通过适配器接管</li>
     *   <li>其他 → 放行原版流程</li>
     * </ol>
     *
     * @param self   调用 hurt 的实体
     * @param source 伤害来源
     * @param amount 伤害量
     * @param cir    Mixin 回调
     */
    public static void intercept(Entity self, DamageSource source, float amount,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (BFDamageApi.hasContextFor(self)) return;

        // 情况2：实体自身是 BFHurtTarget → 接管
        if (self instanceof BFHurtTarget tb) {
            BFDamageContext ctx = tb.createContextFromVanilla(source, amount);
            if (ctx == null) return;

            float dealt = BFDamageApi.hurt(self, ctx);
            cir.setReturnValue(dealt > 0f);
            return;
        }

        // 情况3：实体穿戴了 BFArmorMaterial 护甲 → 通过适配器接管
        if (self instanceof LivingEntity living && BFArmorAdapter.hasBFArmor(living)) {
            BFArmorAdapter adapter = new BFArmorAdapter(living);
            BFDamageContext ctx = adapter.createContextFromVanilla(source, amount);
            if (ctx == null) return;

            float dealt = BFDamageApi.hurt(self, ctx);
            cir.setReturnValue(dealt > 0f);
        }

        // 情况4：其他 → 放行原版流程
    }
}
