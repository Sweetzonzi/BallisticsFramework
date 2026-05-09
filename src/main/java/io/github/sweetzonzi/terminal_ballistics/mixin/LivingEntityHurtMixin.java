package io.github.sweetzonzi.terminal_ballistics.mixin;

import io.github.sweetzonzi.terminal_ballistics.internal.TBHurtInterceptor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 注入 {@link LivingEntity#hurt(DamageSource, float)} 的 HEAD 阶段。
 * <p>
 * 覆盖 LivingEntity 子类的协议外伤害拦截。逻辑与 {@link EntityHurtMixin} 完全相同，
 * 但必须独立注入，因为 JVM 对 LivingEntity 实例的方法分派直接进入此覆写，
 * 不会经过 {@link net.minecraft.world.entity.Entity#hurt} 的注入点。
 * <p>
 * 对 LivingEntity 实例不会与 EntityHurtMixin 重复触发——方法分派只命中一个覆写版本。
 */
@Mixin(LivingEntity.class)
public class LivingEntityHurtMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void tb$onLivingHurt(DamageSource source, float amount,
                                  CallbackInfoReturnable<Boolean> cir) {
        TBHurtInterceptor.intercept((LivingEntity) (Object) this, source, amount, cir);
    }
}
