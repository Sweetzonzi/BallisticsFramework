package io.github.sweetzonzi.ballistics_framework.example;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

/**
 * 示例创造模式标签页。
 * <p>
 * 两个标签页：
 * <ul>
 *   <li>{@code example_weapons} — 武器（近战武器 + 投射物发射器）</li>
 *   <li>{@code example_equipment} — 护甲套装（头盔、胸甲、护腿、靴子）</li>
 * </ul>
 */
public final class ExampleCreativeTab {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String MOD_ID = "ballistics_framework";

    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    /** 武器标签页：放置近战武器和投射物发射器 */
    public static final RegistryObject<CreativeModeTab> EXAMPLE_WEAPONS = TABS.register(
            "example_weapons",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("BF Example - Weapons"))
                    .icon(() -> new ItemStack(ExampleContent.EXAMPLE_MELEE_WEAPON.get()))
                    .displayItems((params, output) -> {
                        output.accept(ExampleContent.EXAMPLE_MELEE_WEAPON.get());
                        output.accept(ExampleContent.EXAMPLE_PROJECTILE.get());
                    })
                    .build()
    );

    /** 护甲标签页：放置示例护甲四件套 */
    public static final RegistryObject<CreativeModeTab> EXAMPLE_EQUIPMENT = TABS.register(
            "example_equipment",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("BF Example - Equipment"))
                    .icon(() -> new ItemStack(ExampleContent.EXAMPLE_CHESTPLATE.get()))
                    .displayItems((params, output) -> {
                        output.accept(ExampleContent.EXAMPLE_HELMET.get());
                        output.accept(ExampleContent.EXAMPLE_CHESTPLATE.get());
                        output.accept(ExampleContent.EXAMPLE_LEGGINGS.get());
                        output.accept(ExampleContent.EXAMPLE_BOOTS.get());
                    })
                    .build()
    );

    private ExampleCreativeTab() {}

    /**
     * 注册创造标签页到事件总线。
     * 由 {@link ExampleContent#init} 在 shouldEnable 时调用。
     */
    static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
        LOGGER.info("[BF-Example] 创造标签页已创建: example_weapons, example_equipment");
    }
}
