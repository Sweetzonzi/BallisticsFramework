package io.github.sweetzonzi.ballistics_framework;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageExtensions;
import io.github.sweetzonzi.ballistics_framework.example.ExampleConfig;
import io.github.sweetzonzi.ballistics_framework.example.ExampleContent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(BallisticsFramework.MOD_ID)
public class BallisticsFramework {

    public static final String MOD_ID = "ballistics_framework";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Mod 构造函数。
     * <p>
     * 注册配置系统、初始化侧信道扩展、注册示例内容（仅在开发环境生效）。
     *
     * @param modEventBus  Mod 事件总线（用于注册 DeferredRegister）
     * @param modContainer 当前 Mod 容器（用于注册 Config）
     */
    public BallisticsFramework(IEventBus modEventBus, ModContainer modContainer) {
        // 注册 config（无论开关状态，config 始终存在）
        ExampleConfig.register(modContainer);
        LOGGER.info("[BF-Example] Config 已注册。shouldEnable={} (生产环境={})",
                ExampleConfig.shouldEnable(),
                ExampleConfig.isProduction());

        // 初始化类型安全扩展容器（必须在任何 API 调用前执行）
        BFDamageExtensions.init();

        // 示例内容注册（仅在开发环境 + config 开启时生效）
        ExampleContent.init(modEventBus);
    }
}
