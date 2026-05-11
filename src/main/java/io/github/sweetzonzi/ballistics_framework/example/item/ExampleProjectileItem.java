package io.github.sweetzonzi.ballistics_framework.example.item;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.example.ExampleContent;
import io.github.sweetzonzi.ballistics_framework.example.entity.ExampleProjectileEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * 示例投射物发射物品。
 * <p>
 * 右键发射 {@link ExampleProjectileEntity}。
 * 发射后物品不消耗（创造模式测试用）。
 */
public class ExampleProjectileItem extends Item {

    private static final Logger LOGGER = LogUtils.getLogger();

    public ExampleProjectileItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            // 使用注册的 EntityType 创建投射物实体
            ExampleProjectileEntity projectile = new ExampleProjectileEntity(
                    ExampleContent.EXAMPLE_PROJECTILE_ENTITY.get(),
                    level,
                    player
            );

            // 设置初始位置（玩家眼睛位置）和速度（朝向玩家视线方向，速度 2.0）
            projectile.setPos(player.getX(), player.getEyeY() - 0.1, player.getZ());
            projectile.shoot(player.getLookAngle().x, player.getLookAngle().y, player.getLookAngle().z, 2.0f, 0.5f);

            level.addFreshEntity(projectile);

            LOGGER.info("[BF-Example] 投射物已发射: shooter={}, velocity=2.0",
                    player.getName().getString());
        }

        // 不消耗物品（创造模式测试用）
        return InteractionResultHolder.success(stack);
    }
}
