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

    /** 各槽位在无命中点兜底时的加权系数（面积+重要性） */
    private static final float[] SLOT_WEIGHTS = {
            0.30f,  // HEAD：最重要但面积最小——30%
            0.35f,  // CHEST：面积最大——35%
            0.25f,  // LEGS：面积可观——25%
            0.10f,  // FEET：面积最小——10%
    };

    private final LivingEntity entity;

    @Nullable
    private EquipmentSlot resolvedSlot;

    @Nullable
    private BFArmorMaterial resolvedMaterial;

    private boolean resolved;

    /** 是否为精确匹配模式（mapHitToSlot 命中），false 表示兜底加权平均模式 */
    private boolean exactMatch;

    /** 兜底模式下的加权平均 RHA（mm），精确匹配模式下无效 */
    private float fallbackRha;

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
                    this.exactMatch = true;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 兜底：无命中点时取加权平均 RHA，同时记录最高等级槽位供管线其余步骤委托。
     * <p>
     * 加权系数：胸甲 35%（面积最大）、头盔 30%（最重要）、护腿 25%、靴子 10%。
     * 未穿戴协议护甲的槽位贡献 RHA=0。
     * 此模式下 {@link #getRHA} 和 {@link #getArmorLevel} 使用加权平均结果，
     * 而管线其余方法（{@code modifyPenetration}、{@code resolvePenetration}、
     * {@code calculateFinalDamage}）仍委托给最高等级槽位的护甲物品。
     *
     * @param ctx 命中上下文
     */
    private void resolveBestSlot(BFDamageContext ctx) {
        ArmorLevel best = ArmorLevel.UNARMORED_1;
        EquipmentSlot bestSlot = null;
        BFArmorMaterial bestMat = null;

        float weightedRha = 0f;
        float totalWeight = 0f;

        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            EquipmentSlot slot = ARMOR_SLOTS[i];
            ItemStack armor = entity.getItemBySlot(slot);
            if (armor.getItem() instanceof BFArmorMaterial mat) {
                ArmorLevel level = mat.getArmorLevel(slot, ctx);
                if (level.ordinal() > best.ordinal()) {
                    best = level;
                    bestSlot = slot;
                    bestMat = mat;
                }
                weightedRha += mat.getRHA(slot, ctx) * SLOT_WEIGHTS[i];
                totalWeight += SLOT_WEIGHTS[i];
            }
        }

        this.resolvedSlot = bestSlot;
        this.resolvedMaterial = bestMat;
        this.fallbackRha = totalWeight > 0f
                ? weightedRha / totalWeight
                : 0f;
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
        if (!exactMatch) {
            return ArmorLevel.fromRha(fallbackRha);
        }
        if (resolvedMaterial != null && resolvedSlot != null) {
            return resolvedMaterial.getArmorLevel(resolvedSlot, ctx);
        }
        return ArmorLevel.UNARMORED_1;
    }

    @Override
    public float getRHA(BFDamageContext ctx) {
        ensureResolved(ctx);
        if (!exactMatch) {
            return fallbackRha;
        }
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
     * 触发已解析槽位的护甲物品的 {@link BFArmorMaterial#afterHurt} 回调。
     * <p>
     * 由 {@code BFDamageApi} 在护甲层管线末尾、实体 {@code hurt()} 之前调用。
     * 兜底模式下同样委托给最高等级槽位的护甲物品。
     *
     * @param ctx         完整命中上下文
     * @param result      穿甲结果
     * @param finalDamage {@link #calculateFinalDamage} 计算出的最终伤害量
     */
    public void armorAfterHurt(BFDamageContext ctx, PenetrationResult result, float finalDamage) {
        if (resolvedMaterial != null && resolvedSlot != null) {
            resolvedMaterial.afterHurt(entity, resolvedSlot, ctx, result, finalDamage);
        }
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

    /**
     * 将原版伤害转换为协议上下文。
     * <p>
     * 遍历所有护甲槽位，调用每件 {@link BFArmorMaterial} 护甲物品的
     * {@link BFArmorMaterial#createContextFromVanilla}。
     * 第一件返回非 null 的物品胜出；若所有护甲物品均返回 null，
     * 则整次原版伤害不被接管，继续走原版流程。
     * <p>
     * 默认实现中，每件护甲物品的穿深估算为伤害量的一半
     * （即 20 HP 的原版伤害 ≈ 10mm 穿深）。
     * 护甲物品可覆写以自定义穿深估算逻辑，或对特定伤害类型返回 null 放行原版。
     *
     * @param source 原版 DamageSource
     * @param amount 原版伤害量
     * @return 协议上下文，或 null（退回原版流程）
     */
    @Override
    @Nullable
    public BFDamageContext createContextFromVanilla(DamageSource source, float amount) {
        // 遍历所有护甲槽位，任一护甲物品愿意接管即可
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (entity.getItemBySlot(slot).getItem() instanceof BFArmorMaterial mat) {
                BFDamageContext ctx = mat.createContextFromVanilla(source, amount);
                if (ctx != null) return ctx;
            }
        }
        return null;
    }
}
