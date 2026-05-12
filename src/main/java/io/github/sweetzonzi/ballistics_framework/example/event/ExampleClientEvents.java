package io.github.sweetzonzi.ballistics_framework.example.event;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.BallisticsFramework;
import io.github.sweetzonzi.ballistics_framework.example.ExampleConfig;
import io.github.sweetzonzi.ballistics_framework.example.ExampleContent;
import io.github.sweetzonzi.ballistics_framework.example.entity.ExampleTargetEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * 示例内容的客户端事件处理器。
 * <p>
 * 注册实体渲染器。仅在客户端生效。
 * 所有渲染器复用原版材质——投射物用雪球渲染器，靶子用玩家模型。
 */
@Mod.EventBusSubscriber(modid = BallisticsFramework.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ExampleClientEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 靶子实体纹理：复用原版僵尸纹理 */
    private static final ResourceLocation TARGET_TEXTURE =
            new ResourceLocation("textures/entity/zombie/zombie.png");

    private ExampleClientEvents() {}

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        if (!ExampleConfig.shouldEnable()) return;

        // 投射物渲染器：复用雪球的 ThrownItemRenderer
        event.registerEntityRenderer(
                ExampleContent.EXAMPLE_PROJECTILE_ENTITY.get(),
                ThrownItemRenderer::new
        );

        // 靶子实体渲染器：复用玩家模型 + 僵尸纹理
        event.registerEntityRenderer(
                ExampleContent.EXAMPLE_TARGET_ENTITY.get(),
                ctx -> new HumanoidMobRenderer<ExampleTargetEntity, HumanoidModel<ExampleTargetEntity>>(
                        ctx,
                        new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)),
                        0.5f
                ) {
                    @Override
                    public ResourceLocation getTextureLocation(ExampleTargetEntity entity) {
                        return TARGET_TEXTURE;
                    }
                }
        );

        LOGGER.info("[BF-Example] 已注册实体渲染器: ExampleProjectileEntity, ExampleTargetEntity");
    }
}
