package io.github.sweetzonzi.ballistics_framework.example.item;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.ArmorLevel;
import io.github.sweetzonzi.ballistics_framework.api.BFArmorMaterial;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageContext;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 示例协议护甲物品。
 * <p>
 * 使用原版铁甲材质（{@link net.minecraft.world.item.ArmorMaterials#IRON}）。
 * 实现 {@link BFArmorMaterial} 接口，护甲等级为 {@link ArmorLevel#HEAVY}（40mm RHA），
 * 与原版铁甲防护力相当。
 * <p>
 * 穿戴此护甲的实体——无论是否实现 {@link io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget}——
 * 在受到协议伤害或原版伤害时都会被纳入穿甲判定管线。
 */
public class ExampleArmorItem extends ArmorItem implements BFArmorMaterial {

    private static final Logger LOGGER = LogUtils.getLogger();

    public ExampleArmorItem(ArmorMaterial material, Type type, Properties properties) {
        super(material, type, properties);
    }

    /**
     * 返回此护甲在指定槽位的防护等级。
     * 全身统一为 {@link ArmorLevel#HEAVY}（40mm RHA）。
     */
    @Override
    public ArmorLevel getArmorLevel(EquipmentSlot slot, @Nullable BFDamageContext ctx) {
        LOGGER.debug("[BF-Example] 示例护甲 getArmorLevel: slot={}, level=HEAVY", slot);
        return ArmorLevel.HEAVY;
    }

    /**
     * 返回此护甲的 RHA 等效厚度（精确数值模式）。
     * 此处直接返回 40mm，对应 ArmorLevel.HEAVY 的中位值。
     */
    @Override
    public float getRHA(EquipmentSlot slot, @Nullable BFDamageContext ctx) {
        return 40f;
    }
}
