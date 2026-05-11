package io.github.sweetzonzi.ballistics_framework.internal;

import io.github.sweetzonzi.ballistics_framework.api.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 护甲适配器（内部实现，非公开 API）。
 * <p>
 * 将穿戴了 {@link BFArmorMaterial} 护甲的 {@link LivingEntity} 包裹为
 * {@link BFHurtTarget} 实现，使协议管线能查询装备槽位中的护甲防护数据。
 * <p>
 * 外部模组不可直接引用此类，只能通过 {@link BFArmorMaterial} 接口接入。
 */
public final class BFArmorAdapter implements BFHurtTarget {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    private final LivingEntity entity;

    @Nullable
    private EquipmentSlot resolvedSlot;

    @Nullable
    private BFArmorMaterial resolvedMaterial;

    private boolean resolved;

    public BFArmorAdapter(LivingEntity entity) {
        this.entity = entity;
    }

    // ======================== 槽位映射 ========================

    /**
     * 遍历护甲槽位，调用每件护甲物品的 {@link BFArmorMaterial#mapHitToSlot}。
     * 第一件返回匹配非 null 者胜出。
     *
     * @param ctx 命中上下文
     * @return 是否解析成功
     */
    private boolean resolveTarget(BFDamageContext ctx) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armor = entity.getItemBySlot(slot);
            if (armor.getItem() instanceof BFArmorMaterial mat) {
                EquipmentSlot mapped = mat.mapHitToSlot(entity, ctx);
                if (mapped == slot) {
                    this.resolvedSlot = slot;
                    this.resolvedMaterial = mat;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 兜底：无命中点时取所有槽位中等阶最高的护甲等级。
     *
     * @param ctx 命中上下文
     */
    private void resolveBestSlot(BFDamageContext ctx) {
        ArmorLevel best = ArmorLevel.UNARMORED_1;
        EquipmentSlot bestSlot = null;
        BFArmorMaterial bestMat = null;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack armor = entity.getItemBySlot(slot);
            if (armor.getItem() instanceof BFArmorMaterial mat) {
                ArmorLevel level = mat.getArmorLevel(slot, ctx);
                if (level.ordinal() > best.ordinal()) {
                    best = level;
                    bestSlot = slot;
                    bestMat = mat;
                }
            }
        }
        this.resolvedSlot = bestSlot;
        this.resolvedMaterial = bestMat;
    }

    /**
     * 惰性初始化槽位解析，整个管线周期内缓存复用。
     * 由各管线方法首次调用时自动触发。
     */
    private void ensureResolved(BFDamageContext ctx) {
        if (!resolved) {
            resolved = true;
            if (!resolveTarget(ctx)) {
                resolveBestSlot(ctx);
            }
        }
    }

    // ======================== 护甲工具方法 ========================

    /**
     * 检查实体是否穿戴了任何实现 {@link BFArmorMaterial} 的护甲。
     *
     * @param entity 目标实体
     * @return true 表示至少有一个护甲槽位穿戴了协议护甲
     */
    public static boolean hasBFArmor(LivingEntity entity) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (entity.getItemBySlot(slot).getItem() instanceof BFArmorMaterial) {
                return true;
            }
        }
        return false;
    }

    // ======================== BFHurtTarget 管线方法（委托给护甲物品） ========================

    @Override
    public ArmorLevel getArmorLevel(BFDamageContext ctx) {
        ensureResolved(ctx);
        if (resolvedMaterial != null && resolvedSlot != null) {
            return resolvedMaterial.getArmorLevel(resolvedSlot, ctx);
        }
        return ArmorLevel.UNARMORED_1;
    }

    @Override
    public float getRHA(BFDamageContext ctx) {
        ensureResolved(ctx);
        if (resolvedMaterial != null && resolvedSlot != null) {
            return resolvedMaterial.getRHA(resolvedSlot, ctx);
        }
        return 0f;
    }

    @Override
    public float modifyPenetration(BFDamageContext ctx) {
        ensureResolved(ctx);
        if (resolvedMaterial != null && resolvedSlot != null) {
            return resolvedMaterial.modifyPenetration(resolvedSlot, ctx);
        }
        return ctx.penetration();
    }

    @Override
    public PenetrationResult resolvePenetration(BFDamageContext ctx) {
        ensureResolved(ctx);
        if (resolvedMaterial != null && resolvedSlot != null) {
            return resolvedMaterial.resolvePenetration(resolvedSlot, ctx);
        }
        return BFHurtTarget.super.resolvePenetration(ctx);
    }

    @Override
    public float calculateFinalDamage(BFDamageContext ctx, PenetrationResult result) {
        ensureResolved(ctx);
        if (resolvedMaterial != null && resolvedSlot != null) {
            return resolvedMaterial.calculateFinalDamage(resolvedSlot, ctx, result);
        }
        return BFHurtTarget.super.calculateFinalDamage(ctx, result);
    }

    /**
     * 委托原始实体执行伤害。
     * <p>
     * 注意两层防护模型：此方法委托 {@code entity.hurt(source, amount)} 走原版伤害管线，
     * 因此原版的护甲属性（ARMOR / ARMOR_TOUGHNESS）和保护附魔会在此之上进行二次减免。
     * 即：<code>calculateFinalDamage</code> 返回的值 ≠ 实体实际减少的 HP。
     * <p>
     * 例如：协议计算最终伤害为 20 HP，但实体身穿全套钻石甲（80% 减伤），
     * 原版管线进一步减免后实际仅扣 4 HP。这是设计意图——协议层处理穿甲判定，
     * 原版护甲处理伤害数值的二次减免，形成两层防护模型。
     *
     * @param source 伤害来源
     * @param amount 协议层计算后的最终伤害量
     * @return {@code entity.hurt()} 的返回值
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return entity.hurt(source, amount);
    }

    @Override
    @Nullable
    public Entity getBFEntity() {
        return entity;
    }

    @Override
    @Nullable
    public BFDamageContext createContextFromVanilla(DamageSource source, float amount) {
        return BFDamageContext.builder()
                .source(source)
                .baseDamage(amount)
                .penetration(estimatePenetration(amount))
                .build();
    }

    /**
     * 将原版伤害量估算为协议穿深值（mm RHA）。
     * <p>
     * 默认实现取伤害量的一半——即 20 HP 的原版伤害 ≈ 10mm 穿深。
     * 此值仅作为初始穿深输入管线，最终由护甲物品的
     * {@link BFArmorMaterial#modifyPenetration} 等方法进一步调整。
     */
    private static float estimatePenetration(float amount) {
        return amount / 2f;
    }
}
