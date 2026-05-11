package io.github.sweetzonzi.ballistics_framework.example.entity;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.ArmorLevel;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageContext;
import io.github.sweetzonzi.ballistics_framework.api.BFHurtTarget;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 示例靶子生物。
 * <p>
 * 实现 {@link BFHurtTarget} 接口，裸体无护甲时护甲等级为 UNARMORED_1（0mm RHA）。
 * 可穿戴示例护甲 {@code ExampleArmorItem} 来测试复合目标管线（分支0）。
 * 无自然生成，无 AI，仅用于开发测试。
 */
public class ExampleTargetEntity extends PathfinderMob implements BFHurtTarget {

    private static final Logger LOGGER = LogUtils.getLogger();

    public ExampleTargetEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        LOGGER.debug("[BF-Example] 靶子实体已创建: pos={}", position());
    }

    /**
     * 注册属性：60 HP，基础移动速度为 0（不动靶）。
     */
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 60.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0);
    }

    // ======================== BFHurtTarget 实现 ========================

    @Override
    public ArmorLevel getArmorLevel(BFDamageContext ctx) {
        LOGGER.debug("[BF-Example] 靶子 getArmorLevel 被调用: ctx.penetration={}", ctx.penetration());
        // 裸体靶子，无防护
        return ArmorLevel.UNARMORED_1;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return super.hurt(source, amount);
    }

    @Nullable
    @Override
    public BFDamageContext createContextFromVanilla(DamageSource source, float amount) {
        // 原版伤害不走协议管线，退回原版流程
        return null;
    }
}
