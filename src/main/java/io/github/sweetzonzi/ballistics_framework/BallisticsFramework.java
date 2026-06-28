package io.github.sweetzonzi.ballistics_framework;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageExtensions;
import io.github.sweetzonzi.ballistics_framework.example.ExampleConfig;
import io.github.sweetzonzi.ballistics_framework.example.ExampleContent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;

@Mod(BallisticsFramework.MOD_ID)
public class BallisticsFramework {

    public static final String MOD_ID = "ballistics_framework";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Mod 构造函数。
     * <p>
     * 注册配置系统、初始化侧信道扩展、注册示例内容（仅在开发环境生效）。
     * Forge 1.20.1 通过 {@code IEventBus} 参数注入获取事件总线，
     * 通过 {@link ModLoadingContext} 注册配置。
     */
    public BallisticsFramework(IEventBus modEventBus) {
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
