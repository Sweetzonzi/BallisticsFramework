package io.github.sweetzonzi.ballistics_framework.example;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * 示例内容配置。
 * <p>
 * 使用 Forge 标准 ForgeConfigSpec 系统，生成 {@code ballistics_framework-common.toml}。
 * 示例代码常驻 src/main 随 JAR 发布，但仅在开发环境且 config 开启时才注册。
 * 生产环境无论如何都不注册示例内容。
 */
public final class ExampleConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLE_EXAMPLE_CONTENT;

    /** 是否为生产环境（非 dev）。生产环境强制关闭示例注册 */
    private static final boolean PRODUCTION = FMLLoader.isProduction();

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ENABLE_EXAMPLE_CONTENT = builder
                .comment("是否注册示例物品、实体和生物（仅在开发环境生效）。用于功能测试与开发参考。")
                .define("enableExampleContent", true);
        SPEC = builder.build();
    }

    private ExampleConfig() {}

    /**
     * 检查是否应注册示例内容。
     * <p>
     * 仅在开发环境（非生产）下启用。config 在注册时序上可能尚未加载，
     * 因此仅依据 {@link FMLLoader#isProduction()} 判断，不依赖 config 值。
     *
     * @return true 表示当前环境允许注册示例内容
     */
    public static boolean shouldEnable() {
        return !PRODUCTION;
    }

    /**
     * 获取生产环境标志（用于日志输出）。
     */
    public static boolean isProduction() {
        return PRODUCTION;
    }

    /**
     * 在 Mod 构造函数中注册此配置。
     * <p>
     * Forge 1.20.1 使用 {@link ModLoadingContext#registerConfig} 注册配置。
     */
    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
    }
}
