package io.github.sweetzonzi.ballistics_framework.mixin;

import io.github.sweetzonzi.ballistics_framework.api.BFDamageApi;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolver;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 {@link Projectile#onHit(HitResult)} 的 HEAD 阶段。
 * <p>
 * 若命中的实体实现了 {@link BFHitResolver}，在执行原版命中处理之前
 * 先做精确验证：调用 {@link BFDamageApi#resolveHitTarget(HitResult, Vec3)}
 * 检查是否实际命中。返回 null 时取消原版流程，投射物继续飞行（AABB 假阳性不销毁）。
 * <p>
 * 与 {@code EntityHurtMixin} / {@code LivingEntityHurtMixin} 的互补关系：
 * <ul>
 *   <li>本 Mixin 在管线之外——在 onHit 执行前拦截，解决投射物生命周期问题</li>
 *   <li>EntityHurtMixin 在管线入口——在 hurt 执行前拦截，解决协议外伤害接管问题</li>
 * </ul>
 */
@Mixin(Projectile.class)
public class ProjectileHitResolverMixin {

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void bf$resolveHitBeforeProcess(HitResult result, CallbackInfo ci) {
        if (!(result instanceof EntityHitResult ehr)) return;
        if (!(ehr.getEntity() instanceof BFHitResolver)) return;

        Projectile self = (Projectile) (Object) this;
        Vec3 velocity = self.getDeltaMovement();
        double speed = velocity.length();
        if (speed < 0.001) return;

        // 搜索距离：取两 tick 飞行距离，使搜索方向与距离合并在单一矢量中
        // 钳制在 [1.0, 8.0] 米。Java 17 兼容：Math.max/Math.min 替代 Math.clamp
        double clampedSpeed = Math.max(0.5, Math.min(4.0, speed));
        Vec3 delta = velocity.scale(clampedSpeed * 2.0 / speed);

        var resolved = BFDamageApi.resolveHitTarget(result, delta);
        if (resolved == null) {
            ci.cancel();
        }
    }
}
