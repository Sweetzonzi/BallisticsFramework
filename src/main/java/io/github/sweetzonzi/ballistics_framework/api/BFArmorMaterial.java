package io.github.sweetzonzi.ballistics_framework.api;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 协议护甲物品接口。
 * <p>
 * 护甲模组让物品实现此接口后，穿戴该物品的实体——无论是否实现 {@link BFHurtTarget}——
 * 在受到协议伤害或原版伤害时，协议层都会自动将其纳入穿甲判定管线。
 * <p>
 * 简易模式下只需实现 {@link #getArmorLevel}，其余方法均有默认实现。
 * 精密模式下可覆写全部管线方法，实现爆反拦截、间隙衰减、跳弹判定等高级逻辑。
 * <p>
 * 默认会拦截所有原版伤害并转换为协议伤害。如果你想让某些伤害类型绕过护甲
 * （如魔法伤害不被钢板阻挡），覆写 {@link #createContextFromVanilla}
 * 对特定 {@code DamageSource} 返回 {@code null} 即可。
 * <p>
 * 管线委托链：适配器 {@code BFArmorAdapter} 逐方法委托到此接口的对应方法，
 * 确保护甲模组拥有与 {@link BFHurtTarget} 实现者同等的定制权限。
 */
public interface BFArmorMaterial {

    // ======================== 简易模式：只需实现此方法 ========================

    /**
     * 返回此护甲物品在指定槽位提供的护甲等级。
     * <p>
     * 简易模式入口——只关心"我是什么等级的护甲"的模组只需实现此方法。
     * 默认 {@link #getRHA} 实现和穿甲判定均委托到此方法。
     *
     * @param slot 装备槽位（HEAD / CHEST / LEGS / FEET）
     * @param ctx  命中上下文（可用于读取武器元数据做精细判定）；可为 null（非命中场景查询时）
     * @return 护甲等级，默认 UNARMORED_1（无防护）
     */
    default ArmorLevel getArmorLevel(EquipmentSlot slot, @Nullable BFDamageContext ctx) {
        return ArmorLevel.UNARMORED_1;
    }

    /**
     * 返回此护甲物品在指定槽位的 RHA 等效厚度（mm）。
     * <p>
     * 默认委托给 {@link #getArmorLevel} 的中位值。精密模组可覆写为精确数值。
     * 即使覆写了此方法，也应保持 {@link #getArmorLevel} 的正确实现——
     * 因为默认的 {@link #calculateFinalDamage} 仍依赖等级做同级/越级判定区分，
     * 且等级也用于 HUD 显示。
     *
     * @param slot 装备槽位
     * @param ctx  命中上下文；可为 null
     * @return RHA 等效厚度（mm）
     */
    default float getRHA(EquipmentSlot slot, @Nullable BFDamageContext ctx) {
        return getArmorLevel(slot, ctx).medianRha();
    }

    // ======================== 穿甲判定管线（精密模式覆写） ========================

    /**
     * 修正穿深（此槽位护甲的减效逻辑）。
     * <p>
     * 默认直接返回 {@code ctx.penetration()}——不做减效。
     * 高级护甲可覆写以实现：
     * <ul>
     *   <li>爆反拦截（ERA）：仅对化学能弹头生效，减去固定的等效厚度</li>
     *   <li>间隙衰减：穿深随间隙距离递减</li>
     *   <li>跳弹角度：入射角过大时大幅缩减有效穿深</li>
     * </ul>
     *
     * @param slot 装备槽位
     * @param ctx  命中上下文
     * @return 修正后的有效穿深
     */
    default float modifyPenetration(EquipmentSlot slot, BFDamageContext ctx) {
        return ctx.penetration();
    }

    /**
     * 判断此槽位护甲是否被击穿（纯击穿判定，不含跳弹）。
     * <p>
     * 默认基于离散等级比较：先调用 {@link #modifyPenetration} 获取修正后的有效穿深，
     * 再映射到穿甲等级并与目标护甲等级比较（等于算击穿）。
     * 精密模组可覆写为精确 float 比较。
     *
     * @param slot 装备槽位
     * @param ctx  命中上下文
     * @return true 表示穿深足以击穿此槽位护甲
     */
    default boolean isArmorPenetrated(EquipmentSlot slot, BFDamageContext ctx) {
        float effectivePen = modifyPenetration(slot, ctx);
        return ArmorLevel.fromRha(effectivePen).canDefeat(getArmorLevel(slot, ctx));
    }

    /**
     * 解析此槽位护甲的最终穿甲结果（含跳弹判定）。
     * <p>
     * 默认委托给 {@link #isArmorPenetrated}，返回 PENETRATED 或 BLOCKED。
     * 需要跳弹判定的护甲应覆写此方法——在入射角过大时返回 RICOCHET。
     *
     * @param slot 装备槽位
     * @param ctx  命中上下文
     * @return 穿甲结果（PENETRATED / BLOCKED / RICOCHET，三者互斥）
     */
    default PenetrationResult resolvePenetration(EquipmentSlot slot, BFDamageContext ctx) {
        return isArmorPenetrated(slot, ctx)
                ? PenetrationResult.PENETRATED
                : PenetrationResult.BLOCKED;
    }

    /**
     * 根据穿甲结果计算此槽位护甲的最终伤害量。
     * <p>
     * 默认分三级（基于离散等级）：
     * <ul>
     *   <li>PENETRATED + 同级击穿（刚好击穿）：标称伤害 × 65%</li>
     *   <li>PENETRATED + 越级击穿：标称伤害 × 100%</li>
     *   <li>BLOCKED / RICOCHET：0</li>
     * </ul>
     * <p>
     * 需要非零未击穿伤害（如钝伤、破片）、跳弹转贯穿加成的模组应覆写此方法。
     *
     * @param slot   装备槽位
     * @param ctx    命中上下文
     * @param result 由 {@link #resolvePenetration} 返回的穿甲结果
     * @return 最终伤害量
     */
    default float calculateFinalDamage(EquipmentSlot slot, BFDamageContext ctx,
                                        PenetrationResult result) {
        if (result != PenetrationResult.PENETRATED) return 0f;
        ArmorLevel penLevel = ctx.getPenetrationLevel();
        ArmorLevel armorLevel = getArmorLevel(slot, ctx);
        return penLevel == armorLevel ? ctx.baseDamage() * 0.65f : ctx.baseDamage();
    }

    // ======================== 命中槽位映射（可选覆写） ========================

    /**
     * 根据命中上下文确定此护甲物品对应的装备槽位。
     * <p>
     * 默认按命中点高度占比划分：
     * <ul>
     *   <li>头部（HEAD）：&gt;85% 高度</li>
     *   <li>躯干（CHEST）：55%~85% 高度</li>
     *   <li>腿部（LEGS）：35%~55% 高度</li>
     *   <li>脚部（FEET）：&lt;35% 高度</li>
     * </ul>
     * 命中点无效时返回 null。
     * <p>
     * 需要自定义映射的护甲可覆写——例如全覆盖头盔、盾牌物品、坐骑装甲等。
     *
     * @param wearer 穿戴此护甲的实体
     * @param ctx    命中上下文
     * @return 对应装备槽位；命中点无效或此物品不应处理时返回 null
     */
    @Nullable
    default EquipmentSlot mapHitToSlot(LivingEntity wearer, BFDamageContext ctx) {
        Vec3 hitPoint = ctx.hitPoint();
        if (hitPoint == null || hitPoint.equals(Vec3.ZERO)) return null;
        Vec3 localHit = hitPoint.subtract(wearer.position());
        double heightFrac = localHit.y / wearer.getBbHeight();
        if (heightFrac > 0.85) return EquipmentSlot.HEAD;
        if (heightFrac > 0.55) return EquipmentSlot.CHEST;
        if (heightFrac > 0.35) return EquipmentSlot.LEGS;
        return EquipmentSlot.FEET;
    }

    // ======================== 协议外伤害兼容（可选覆写） ========================

    /**
     * 护甲层的穿甲判定和伤害削减完成后调用，在实体实际受到伤害之前触发。
     * <p>
     * 调用时序：{@link #calculateFinalDamage} → <b>{@code afterHurt}</b> → 实体 {@code hurt()}。
     * 此时护甲职责已结束（已修正穿深、判定击穿、计算残余伤害），
     * 但整体管线尚未走完，伤害尚未施加到实体。
     * <p>
     * 与 {@link #calculateFinalDamage} 的区别：{@code calculateFinalDamage} 是纯计算，
     * 返回伤害数值；{@code afterHurt} 是副作用通知，在此处执行护甲被命中后的后效处理。
     * <p>
     * 默认空实现。护甲物品可覆写以执行：
     * <ul>
     *   <li>耐久损耗：通过 {@code wearer.getItemBySlot(slot)} 获取 ItemStack 后调用 {@code hurtAndBreak}</li>
     *   <li>爆反消耗：标记 ItemStack 上的自定义组件表示爆反已触发</li>
     *   <li>碎裂降级：替换 ItemStack 为降级后的护甲</li>
     *   <li>统计追踪：在 ItemStack 的组件中记录命中次数/吸收伤害</li>
     * </ul>
     *
     * @param wearer      穿戴此护甲的实体
     * @param slot        命中槽位
     * @param ctx         完整命中上下文
     * @param result      穿甲结果（PENETRATED / BLOCKED / RICOCHET）
     * @param finalDamage {@link #calculateFinalDamage} 计算出的最终伤害量
     */
    default void afterHurt(LivingEntity wearer, EquipmentSlot slot, BFDamageContext ctx,
                           PenetrationResult result, float finalDamage) {
        // 默认空实现
    }

    // ======================== 协议外伤害兼容（可选覆写） ========================

    /**
     * 将原版伤害转换为协议上下文，供 Mixin 拦截使用。
     * <p>
     * 默认始终返回有效上下文（穿深 = 原版伤害量 / 2），意味着所有原版伤害
     * 都会被拦截并进入穿甲管线。如果需要让某些伤害类型绕过护甲——
     * 例如魔法伤害（药水）、虚空、溺水等不应被钢板阻挡的伤害——
     * 覆写此方法，对特定 {@code source} 返回 {@code null}。
     * <p>
     * 适配器遍历所有护甲槽位，调用每件护甲物品的此方法。
     * <b>任一</b>护甲物品返回非 null 时整次伤害被接管；
     * <b>所有</b>护甲物品均返回 null 时才退回原版流程。
     *
     * @param source 原版 {@link net.minecraft.world.damagesource.DamageSource}
     * @param amount 原版伤害量
     * @return 协议上下文；返回 null 表示此伤害类型不纳入穿甲管线，走原版流程
     */
    @Nullable
    default BFDamageContext createContextFromVanilla(DamageSource source, float amount) {
        return BFDamageContext.builder()
                .source(source)
                .baseDamage(amount)
                .penetration(amount / 2f)
                .build();
    }
}
