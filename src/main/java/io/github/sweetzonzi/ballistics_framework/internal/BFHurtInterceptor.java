package io.github.sweetzonzi.ballistics_framework.internal;

import io.github.sweetzonzi.ballistics_framework.api.BFDamageApi;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageContext;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolveResult;
import io.github.sweetzonzi.ballistics_framework.api.BFHitResolver;
import io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
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
     * 五种情况（顺序即优先级）：
     * <ol>
     *   <li>已在此目标的协议管线内（hasContextFor）→ 放行，不拦截（重入守卫）</li>
     *   <li>实体自身是 BFHurtTarget → 通过 createContextFromVanilla 接管；
     *       返回 null 则继续走到情况 3（若其实也是 BFHitResolver）</li>
     *   <li>自身是 BFHitResolver 但不走情况 2 → 解析出实际目标并转发伤害；
     *       投射物来源直接取用 onHit 阶段已完成的解析结果，其余来源按伤害来源构造几何后解析</li>
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
            if (ctx != null) {
                float dealt = BFDamageApi.hurt(self, ctx);
                cir.setReturnValue(dealt > 0f);
                return;
            }
            // ctx == null：目标对这一类原版伤害弃权。仅实现 BFHurtTarget 时放行原版流程。
            // 若它同时实现 BFHitResolver（"能回答打中了谁"），则改由情况 3 的解析结果
            // 决定归属，不会落到情况 4 的护甲语义。
            if (!(self instanceof BFHitResolver)) return;
        }

        // 情况3：代理对象——自身是 BFHitResolver，解析出实际目标后转发协议外伤害
        if (self instanceof BFHitResolver) {
            BFHitResolveResult resolved = null;

            // 投射物来源：直接取用 onHit 阶段已完成的判定结果
            if (source.getDirectEntity() instanceof Projectile projectile
                    && projectile instanceof BFHitResolveCache cache) {
                BFHitResolveCache.CachedResolve cached = cache.bf$takeResolve(self);
                if (cached != null) resolved = cached.result();
            }
            // 其余来源（近战 / 爆炸等）：按伤害来源构造几何后判定
            if (resolved == null) {
                Vec3[] ray = BFHitResolver.searchRay(self, source);
                // 无源位置伤害（虚空、饥饿、/kill 等）没有可复检的几何，交回原版流程
                if (ray == null) return;
                resolved = BFDamageApi.resolveHitTarget((Object) self, ray[0], ray[1]);
            }

            // 假阳性：AABB 相交但几何未命中——不造成伤害
            if (resolved == null) {
                cir.setReturnValue(false);
                return;
            }

            BFHurtTarget actual = resolved.actualTarget();
            // 解析回自身：交回原版流程，避免自我递归
            if (actual == self) return;

            BFDamageContext ctx = actual.createContextFromVanilla(source, amount);
            // 目标不接受协议外伤害：交回原版流程
            if (ctx == null) return;

            // ctx 非 null 即"协议已接管这次伤害"：必须调用 setReturnValue 取消原版流程，
            // 否则原版 hurt 会继续执行，伤害将落到代理自身。
            // 返回值只表示是否实际造成了伤害（与情况 2/4 的约定一致）。
            float dealt = BFDamageApi.hurt(actual, ctx);
            cir.setReturnValue(dealt > 0f);
            return;
        }

        // 情况4：实体穿戴了 BFArmorMaterial 护甲 → 通过适配器接管
        if (self instanceof LivingEntity living && BFArmorAdapter.hasBFArmor(living)) {
            BFArmorAdapter adapter = new BFArmorAdapter(living);
            BFDamageContext ctx = adapter.createContextFromVanilla(source, amount);
            if (ctx == null) return;

            float dealt = BFDamageApi.hurt(self, ctx);
            cir.setReturnValue(dealt > 0f);
        }

        // 情况5：其他 → 放行原版流程
    }
}
