package io.github.sweetzonzi.ballistics_framework.example.item;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.ArmorLevel;
import io.github.sweetzonzi.ballistics_framework.api.BFArmorMaterial;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageContext;
import io.github.sweetzonzi.ballistics_framework.api.PenetrationResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 示例投递期护甲（胸甲）。
 * <p>
 * 装饰上与 {@link ExampleArmorItem} 同类（原版铁甲材质、实现 {@link BFArmorMaterial}），
 * 差别有三处，都是为了把"投递期的护甲层"做成可判定的观测对象：
 * <ul>
 *   <li><b>RHA 取 15mm</b>（{@link ArmorLevel#MEDIUM}，10~20mm 等级带）。{@link ExampleArmorItem}
 *       的 40mm 恰在 HEAVY 带上界，任何大于它的穿深都会跳到下一个等级，因此"穿深大于 RHA"
 *       与"同级击穿（伤害 ×0.65）"无法同时成立；15mm 则使 18mm 穿深同时满足两者，
 *       5mm 穿深则被挡下。</li>
 *   <li><b>精密模式的判定</b>：{@link #resolvePenetration} 与 {@link #isArmorPenetrated}
 *       覆写为精确 float 比较，不经过默认实现内部的 {@link #modifyPenetration} 调用。
 *       本护甲不覆写减效逻辑（{@link #modifyPenetration} 只计数并委托默认实现），
 *       因此"修正后穿深"与 {@code ctx.penetration()} 等价，判定直接读后者。</li>
 *   <li><b>调用计数</b>：三件套与 {@link #afterHurt} 各带一个静态计数器，供 GameTest 断言
 *       "这一层跑了 / 没跑"与"各跑了几次"。计数器全类共享，使用前调用
 *       {@link #resetCounters()}。</li>
 * </ul>
 */
public class ExampleDeliveryArmorItem extends ArmorItem implements BFArmorMaterial {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 本护甲的 RHA 等效厚度（mm），落在 {@link ArmorLevel#MEDIUM}（10~20mm）等级带内。 */
    public static final float RHA = 15f;

    private static int modifyPenetrationCalls;
    private static int resolvePenetrationCalls;
    private static int calculateFinalDamageCalls;
    private static int afterHurtCalls;

    public ExampleDeliveryArmorItem(ArmorMaterial material, Type type, Properties properties) {
        super(material, type, properties);
    }

    // ======================== BFArmorMaterial 实现 ========================

    @Override
    public ArmorLevel getArmorLevel(EquipmentSlot slot, @Nullable BFDamageContext ctx) {
        return ArmorLevel.fromRha(RHA);
    }

    @Override
    public float getRHA(EquipmentSlot slot, @Nullable BFDamageContext ctx) {
        return RHA;
    }

    @Override
    public float modifyPenetration(EquipmentSlot slot, BFDamageContext ctx) {
        modifyPenetrationCalls++;
        return BFArmorMaterial.super.modifyPenetration(slot, ctx);
    }

    /**
     * 精确 float 穿深比较（精密模式）。
     * <p>
     * 本护甲只计数、不修正穿深，故 {@code ctx.penetration()} 即修正后穿深；直接读它可避免
     * 在判定内部二次调用 {@link #modifyPenetration}，使投递期的三件套调用计数各为 1。
     */
    @Override
    public boolean isArmorPenetrated(EquipmentSlot slot, BFDamageContext ctx) {
        return ctx.penetration() >= RHA;
    }

    @Override
    public PenetrationResult resolvePenetration(EquipmentSlot slot, BFDamageContext ctx) {
        resolvePenetrationCalls++;
        return isArmorPenetrated(slot, ctx)
                ? PenetrationResult.PENETRATED
                : PenetrationResult.BLOCKED;
    }

    @Override
    public float calculateFinalDamage(EquipmentSlot slot, BFDamageContext ctx,
                                      PenetrationResult result) {
        calculateFinalDamageCalls++;
        return BFArmorMaterial.super.calculateFinalDamage(slot, ctx, result);
    }

    @Override
    public void afterHurt(LivingEntity wearer, EquipmentSlot slot, BFDamageContext ctx,
                          PenetrationResult result, float finalDamage) {
        afterHurtCalls++;
        LOGGER.debug("[BF-Example] 投递期护甲 afterHurt: slot={}, result={}, finalDamage={}",
                slot, result, finalDamage);
        BFArmorMaterial.super.afterHurt(wearer, slot, ctx, result, finalDamage);
    }

    // ======================== 调用计数 ========================

    /** 清零三件套与 {@code afterHurt} 的调用计数。 */
    public static void resetCounters() {
        modifyPenetrationCalls = 0;
        resolvePenetrationCalls = 0;
        calculateFinalDamageCalls = 0;
        afterHurtCalls = 0;
    }

    /** @return {@link #modifyPenetration} 被调用的次数 */
    public static int getModifyPenetrationCalls() {
        return modifyPenetrationCalls;
    }

    /** @return {@link #resolvePenetration} 被调用的次数 */
    public static int getResolvePenetrationCalls() {
        return resolvePenetrationCalls;
    }

    /** @return {@link #calculateFinalDamage} 被调用的次数 */
    public static int getCalculateFinalDamageCalls() {
        return calculateFinalDamageCalls;
    }

    /** @return {@link #afterHurt} 被调用的次数 */
    public static int getAfterHurtCalls() {
        return afterHurtCalls;
    }
}
