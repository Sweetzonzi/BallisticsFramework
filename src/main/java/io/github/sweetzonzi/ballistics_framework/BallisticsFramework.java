package io.github.sweetzonzi.ballistics_framework;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageExtensions;
import io.github.sweetzonzi.ballistics_framework.example.ExampleConfig;
import io.github.sweetzonzi.ballistics_framework.example.ExampleContent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(BallisticsFramework.MOD_ID)
public class BallisticsFramework {

    public static final String MOD_ID = "ballistics_framework";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Mod 构造函数。
     * <p>
     * 注册配置系统、初始化侧信道扩展、注册示例内容（仅在开发环境生效）。
     * Forge 1.20.1 的 {@code FMLModContainer#constructMod} 只识别
     * {@code (FMLJavaModLoadingContext)} 与无参两种构造函数，因此事件总线经
     * {@link FMLJavaModLoadingContext#getModEventBus()} 取得；配置仍由
     * {@code ModLoadingContext} 注册。
     *
     * @param context FML 注入的模组加载上下文
     */
    public BallisticsFramework(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // 注册 config（无论开关状态，config 始终存在）
        ExampleConfig.register();
        LOGGER.info("[BF-Example] Config 已注册。shouldEnable={} (生产环境={})",
                ExampleConfig.shouldEnable(),
                ExampleConfig.isProduction());

        // 初始化类型安全扩展容器（必须在任何 API 调用前执行）
        BFDamageExtensions.init();

        // 示例内容注册（仅在开发环境 + config 开启时生效）
        ExampleContent.init(modEventBus);
    }
}
