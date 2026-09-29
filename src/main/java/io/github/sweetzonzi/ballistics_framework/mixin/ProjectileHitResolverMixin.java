package io.github.sweetzonzi.ballistics_framework.mixin;

import io.github.sweetzonzi.ballistics_framework.api.BFDamageApi;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolveResult;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolver;
import io.github.sweetzonzi.ballistics_framework.internal.BFHitResolveCache;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 {@link Projectile#onHit(HitResult)} 的 HEAD 阶段。
 * <p>
 * 若命中的实体实现了 {@link BFHitResolver}，在执行原版命中处理之前
 * 先做精确验证：调用 {@link BFDamageApi#resolveHitTarget(HitResult, Vec3)}
 * 检查是否实际命中。返回 null 时取消原版流程，投射物继续飞行（AABB 假阳性不销毁）；
 * 判定为真命中时把该次命中的解析结果写入 {@link BFHitResolveCache}，
 * 供 {@code BFHurtInterceptor} 在随后的 {@code Entity#hurt} 阶段直接取用
 * （{@code hurt} 阶段的位置可能落后一个 tick 的位移，无法还原本阶段的精确几何）。
 * <p>
 * 与 {@code EntityHurtMixin} / {@code LivingEntityHurtMixin} 的互补关系：
 * <ul>
 *   <li>本 Mixin 在管线之外——在 onHit 执行前拦截，解决投射物生命周期问题并缓存判定结果</li>
 *   <li>EntityHurtMixin 在管线入口——在 hurt 执行前拦截，解决协议外伤害接管问题</li>
 * </ul>
 */
@Mixin(Projectile.class)
public class ProjectileHitResolverMixin implements BFHitResolveCache {

    /** 本次命中的解析结果；仅在一次 onHit → hurt 之间有效，取用即清空 */
    @Unique
    @Nullable
    private BFHitResolveCache.CachedResolve bf$cachedResolve;

    @Override
    public void bf$cacheResolve(Entity hitEntity, BFHitResolveResult result) {
        this.bf$cachedResolve = new BFHitResolveCache.CachedResolve(hitEntity, result);
    }

    @Override
    @Nullable
    public BFHitResolveCache.CachedResolve bf$takeResolve(Entity hitEntity) {
        BFHitResolveCache.CachedResolve cached = this.bf$cachedResolve;
        this.bf$cachedResolve = null;
        return cached != null && cached.hitEntity() == hitEntity ? cached : null;
    }

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void bf$resolveHitBeforeProcess(HitResult result, CallbackInfo ci) {
        if (!(result instanceof EntityHitResult ehr)) return;
        if (!(ehr.getEntity() instanceof BFHitResolver)) return;

        Projectile self = (Projectile) (Object) this;
        Vec3 delta = BFHitResolver.searchDelta(self.getDeltaMovement());
        // 速率过低：本阶段无从判断假阳性，保持原版 onHit 行为
        if (delta.equals(Vec3.ZERO)) return;

        var resolved = BFDamageApi.resolveHitTarget(result, delta);
        if (resolved == null) {
            ci.cancel();
            return;
        }
        // 真命中：把本次判定结果留给 hurt 阶段复用；本阶段不施加伤害
        bf$cacheResolve(ehr.getEntity(), resolved);
    }
}
