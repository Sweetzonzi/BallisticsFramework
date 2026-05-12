package io.github.sweetzonzi.ballistics_framework.example.entity;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageContext;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageHandler;
import io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;

/**
 * 示例投射物实体。
 * <p>
 * 继承 {@link ThrowableProjectile}，实现 {@link BFDamageHandler} 作为自身回调。
 * 实现 {@link ItemSupplier} 以便复用 {@code ThrownItemRenderer}（雪球渲染器）。
 * 命中实体时构造 {@link BFDamageContext} 并发起协议伤害。
 */
public class ExampleProjectileEntity extends ThrowableProjectile implements BFDamageHandler, ItemSupplier {

    private static final Logger LOGGER = LogUtils.getLogger();

    public ExampleProjectileEntity(EntityType<? extends ThrowableProjectile> type, Level level) {
        super(type, level);
    }

    public ExampleProjectileEntity(EntityType<? extends ThrowableProjectile> type, Level level, LivingEntity shooter) {
        super(type, shooter, level);
    }

    @Override
    protected void defineSynchedData() {
        // 投射物无需额外同步数据
    }

    /**
     * 返回用于渲染的物品图标（雪球）。
     * 使 ThrownItemRenderer 能显示雪球纹理。
     */
    @Override
    public ItemStack getItem() {
        return new ItemStack(Items.SNOWBALL);
    }

    @Override
    public void tick() {
        super.tick();
        // 超出一定时间或距离后自动移除，避免永久飞行
        if (tickCount > 200) {
            discard();
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        // 无论命中什么，投射物都消失
        if (!level().isClientSide) {
            discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity target = result.getEntity();
        if (level().isClientSide) return;

        LOGGER.info("[BF-Example] 投射物命中实体: target={}, pos={}",
                target.getName().getString(), result.getLocation());

        // 构造 BFDamageContext：
        //   穿透力 200mm（SUPER_HEAVY_3 等级，一定能击穿示例护甲 40mm）
        //   基础伤害 25 HP
        //   hitNormal 简化为从投射物指向目标的方向
        BFDamageContext ctx = BFDamageContext.builder()
                .source(damageSources().thrown(this, getOwner()))
                .baseDamage(25f)
                .hitVelocity(getDeltaMovement())
                .hitPoint(result.getLocation())
                .hitNormal(result.getLocation().subtract(target.position()).normalize())
                .penetration(200f)
                .build();

        // 使用 BFDamageHandler.dealDamage 快捷方法自动注入自身为 handler
        float dealt = dealDamage(target, ctx);
        LOGGER.info("[BF-Example] 投射物伤害结果: dealt={}", dealt);
    }

    // ======================== BFDamageHandler 回调实现 ========================

    @Override
    public void onPenetrated(BFHurtTarget target, BFDamageContext ctx) {
        LOGGER.info("[BF-Example] 回调 PENETRATED: target={}", target.getBFEntity().getName().getString());
    }

    @Override
    public void onBlocked(BFHurtTarget target, BFDamageContext ctx) {
        LOGGER.info("[BF-Example] 回调 BLOCKED: target={}", target.getBFEntity().getName().getString());
    }

    @Override
    public void onRicochet(BFHurtTarget target, BFDamageContext ctx) {
        LOGGER.info("[BF-Example] 回调 RICOCHET: target={}", target.getBFEntity().getName().getString());
    }

    @Override
    public void onOvermatch(BFHurtTarget target, BFDamageContext ctx) {
        LOGGER.info("[BF-Example] 回调 OVERMATCH: target={}", target.getBFEntity().getName().getString());
    }

    @Override
    public void onSpall(BFHurtTarget target, BFDamageContext ctx) {
        LOGGER.info("[BF-Example] 回调 SPALL: target={}", target.getBFEntity().getName().getString());
    }
}
