package io.github.sweetzonzi.ballistics_framework.example;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.example.entity.ExampleProjectileEntity;
import io.github.sweetzonzi.ballistics_framework.example.entity.ExampleTargetEntity;
import io.github.sweetzonzi.ballistics_framework.example.item.ExampleArmorItem;
import io.github.sweetzonzi.ballistics_framework.example.item.ExampleMeleeWeapon;
import io.github.sweetzonzi.ballistics_framework.example.item.ExampleProjectileItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * 示例内容注册入口。
 * <p>
 * 统一管理所有 DeferredRegister 定义和注册。
 * 内部调用 {@link ExampleConfig#shouldEnable()} 判断是否实际注册到总线。
 * 无论在开发还是生产环境，DeferredRegister 对象始终初始化（声明不产生副作用），
 * 但 {@link #init} 方法中的 {@code register(bus)} 调用仅在 shouldEnable 为 true 时执行。
 */
public final class ExampleContent {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ======================== 物品注册表 ========================

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ExampleCreativeTab.MOD_ID);

    /** 示例近战武器（60mm 穿深） */
    public static final DeferredItem<ExampleMeleeWeapon> EXAMPLE_MELEE_WEAPON = ITEMS.register(
            "example_melee_weapon",
            () -> new ExampleMeleeWeapon(new Item.Properties())
    );

    /** 示例投射物发射物品 */
    public static final DeferredItem<ExampleProjectileItem> EXAMPLE_PROJECTILE = ITEMS.register(
            "example_projectile",
            () -> new ExampleProjectileItem(new Item.Properties())
    );

    /** 示例头盔 */
    public static final DeferredItem<ExampleArmorItem> EXAMPLE_HELMET = ITEMS.register(
            "example_helmet",
            () -> new ExampleArmorItem(ArmorMaterials.IRON, ArmorItem.Type.HELMET, new Item.Properties())
    );

    /** 示例胸甲 */
    public static final DeferredItem<ExampleArmorItem> EXAMPLE_CHESTPLATE = ITEMS.register(
            "example_chestplate",
            () -> new ExampleArmorItem(ArmorMaterials.IRON, ArmorItem.Type.CHESTPLATE, new Item.Properties())
    );

    /** 示例护腿 */
    public static final DeferredItem<ExampleArmorItem> EXAMPLE_LEGGINGS = ITEMS.register(
            "example_leggings",
            () -> new ExampleArmorItem(ArmorMaterials.IRON, ArmorItem.Type.LEGGINGS, new Item.Properties())
    );

    /** 示例靴子 */
    public static final DeferredItem<ExampleArmorItem> EXAMPLE_BOOTS = ITEMS.register(
            "example_boots",
            () -> new ExampleArmorItem(ArmorMaterials.IRON, ArmorItem.Type.BOOTS, new Item.Properties())
    );

    // ======================== 实体注册表 ========================

    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ExampleCreativeTab.MOD_ID);

    /** 示例投射物实体 */
    public static final DeferredHolder<EntityType<?>, EntityType<ExampleProjectileEntity>> EXAMPLE_PROJECTILE_ENTITY =
            ENTITIES.register("example_projectile", () ->
                    EntityType.Builder.<ExampleProjectileEntity>of(
                                    ExampleProjectileEntity::new, MobCategory.MISC)
                            .sized(0.25f, 0.25f)
                            .clientTrackingRange(64)
                            .updateInterval(1)
                            .build("example_projectile")
            );

    /** 示例靶子实体 */
    public static final DeferredHolder<EntityType<?>, EntityType<ExampleTargetEntity>> EXAMPLE_TARGET_ENTITY =
            ENTITIES.register("example_target", () ->
                    EntityType.Builder.<ExampleTargetEntity>of(
                                    ExampleTargetEntity::new, MobCategory.MISC)
                            .sized(0.6f, 1.8f)
                            .clientTrackingRange(80)
                            .updateInterval(3)
                            .build("example_target")
            );

    private ExampleContent() {}

    /**
     * 初始化入口。
     * <p>
     * 在 Mod 构造函数中调用。内部检查 {@link ExampleConfig#shouldEnable()}，
     * 仅在开发环境且 config 开启时注册到事件总线。
     *
     * @param modEventBus MOD 事件总线
     */
    public static void init(IEventBus modEventBus) {
        boolean shouldEnable = ExampleConfig.shouldEnable();
        LOGGER.info("[BF-Example] 示例内容注册 = {} (开发环境 = {}, 生产环境 = {})",
                shouldEnable, !ExampleConfig.isProduction(), ExampleConfig.isProduction());

        if (!shouldEnable) return;

        ITEMS.register(modEventBus);
        ENTITIES.register(modEventBus);
        ExampleCreativeTab.register(modEventBus);
        modEventBus.addListener(ExampleContent::onEntityAttributeCreation);

        LOGGER.info("[BF-Example] 已注册 {} 个物品", ITEMS.getEntries().size());
        LOGGER.info("[BF-Example] 已注册 {} 个实体", ENTITIES.getEntries().size());
    }

    /**
     * 注册示例实体的属性（血量、移速等）。
     * <p>
     * 通过 {@link EntityAttributeCreationEvent} 在实体注册时关联属性表，
     * GameTestServer 环境下此步骤必须显式执行，否则实体生成时 AttributeSupplier 为 null。
     */
    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(EXAMPLE_TARGET_ENTITY.get(),
                ExampleTargetEntity.createAttributes().build());
    }
}
