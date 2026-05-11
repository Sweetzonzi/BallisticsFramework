package io.github.sweetzonzi.ballistics_framework.mixin;

import io.github.sweetzonzi.ballistics_framework.internal.BFHurtInterceptor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 注入 {@link Entity#hurt(DamageSource, float)} 的 HEAD 阶段。
 * <p>
 * 覆盖非 LivingEntity 的 BFHurtTarget 实体（如纯载具实体）的协议外伤害拦截。
 * 对于 {@link net.minecraft.world.entity.LivingEntity} 实例，由
 * {@link LivingEntityHurtMixin} 处理，不会重复触发。
 */
@Mixin(Entity.class)
public class EntityHurtMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void tb$onEntityHurt(DamageSource source, float amount,
                                  CallbackInfoReturnable<Boolean> cir) {
        BFHurtInterceptor.intercept((Entity) (Object) this, source, amount, cir);
    }
}
