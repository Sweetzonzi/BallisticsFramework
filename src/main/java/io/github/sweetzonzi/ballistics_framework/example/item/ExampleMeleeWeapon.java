package io.github.sweetzonzi.ballistics_framework.example.item;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageApi;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * 示例近战武器。
 * <p>
 * 使用原版铁剑材质（{@link Tiers#IRON}）。
 * 覆写 {@link #hurtEnemy}，在攻击时构造 {@link BFDamageContext} 并发起协议伤害，
 * 替换原版的伤害流程。
 * <p>
 * 穿深 60mm（{@link io.github.sweetzonzi.ballistics_framework.api.ArmorLevel#HEAVY} 等级），
 * 基础伤害 15 HP，无回调 handler。
 */
public class ExampleMeleeWeapon extends SwordItem {

    private static final Logger LOGGER = LogUtils.getLogger();

    public ExampleMeleeWeapon(Properties properties) {
        super(Tiers.IRON, 3, -2.4F, properties);
    }

    /**
     * 攻击实体时拦截，用协议伤害替换原版伤害流程。
     * <p>
     * 不调用 {@code super.hurtEnemy()}，完全交由 {@link BFDamageApi#hurt} 处理。
     */
    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        LOGGER.info("[BF-Example] 近战武器命中: target={}, baseDamage=15, penetration=60",
                target.getName().getString());

        // 构造 BFDamageContext：
        //   穿透力 60mm（HEAVY 等级，同级击穿示例护甲 40mm）
        //   基础伤害 15 HP（原版铁剑基础伤害 + 1 点修正）
        //   handler 传 null——此武器不关心回调
        BFDamageContext ctx = BFDamageContext.builder()
                .source(attacker.damageSources().mobAttack(attacker))
                .baseDamage(15f)
                .hitVelocity(attacker.getDeltaMovement())
                .hitPoint(target.position())
                .hitNormal(new Vec3(0, 0, 0))
                .penetration(60f)
                .build();

        float dealt = BFDamageApi.hurt(target, ctx);
        LOGGER.info("[BF-Example] 近战武器伤害结果: dealt={}", dealt);

        // 返回 true 表示成功造成伤害（触发附魔效果如火焰附加等）
        return dealt > 0f;
    }
}
