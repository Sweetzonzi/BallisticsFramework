package io.github.sweetzonzi.terminal_ballistics.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * 穿甲/护甲等级枚举。
 * <p>
 * 将连续 RHA 值映射为离散等级，提供双向映射工具和等级间判定。
 * 等级按防护强度升序排列，{@link #canDefeat} 使用 {@code >=} 比较（等于算击穿）。
 * <p>
 * 本枚举不改变 {@link TBHurtTarget} 的现有契约——{@link #getRHA} 仍返回精确 float，
 * 本枚举仅作为可选的语义辅助层。
 * <p>
 * 等级划分参考 Helldivers 2 的离散护甲理念，大约 ×2 非线性增长，
 * 覆盖从血肉到现代主战坦克正面防护的完整范围。
 * <p>
 * 枚举末尾有一个特殊的元等级 {@link #UNPENETRABLE}，表示绝对不可击穿，
 * 专供需要"免疫弹道伤害"的简易模式场景使用（魔法护盾、管理员方块等）。
 * 它只能通过显式引用获取，不会由 {@link #fromRha} 映射返回。
 */
public enum ArmorLevel {

    /** 0~1mm，血肉、无保护裸露表面 */
    UNARMORED_1(1f),
    /** 1~3mm，几丁质甲壳、木板、薄塑料壳 */
    UNARMORED_2(3f),
    /** 3~5mm，轻型防弹衣、铁皮、铝合金薄板 */
    LIGHT_1(5f),
    /** 5~10mm，重型防弹衣、装甲车门 */
    LIGHT_2(10f),
    /** 10~20mm，一般车辆车架、步战车侧后方 */
    MEDIUM(20f),
    /** 20~40mm，步战车正面、坦克侧后/顶部 */
    HEAVY(40f),
    /** 40~80mm，二战早期中型坦克正面（T-34、谢尔曼） */
    SUPER_HEAVY_1(80f),
    /** 80~150mm，二战晚期重型坦克正面（虎王、IS-2） */
    SUPER_HEAVY_2(150f),
    /** 150~300mm，冷战早期主战坦克（T-55、M48） */
    SUPER_HEAVY_3(300f),
    /** 300~600mm，冷战中期+爆反（T-72、M60A3 ERA） */
    SUPER_HEAVY_4(600f),
    /** 600~1200mm，冷战晚期现代MBT（豹2A4、M1A1 HA） */
    SUPER_HEAVY_5(1200f),
    /** 1200~2000mm */
    SUPER_HEAVY_6(2000f),
    /**
     * 绝对不可击穿等级（元等级）。
     * <p>
     * 用于简易模式下表达"免疫弹道伤害"的语义：魔法护盾、创造模式装备、
     * 管理员方块、剧情保护目标等。
     * <ul>
     *   <li>只能通过显式引用（{@code ArmorLevel.UNPENETRABLE}）获取，
     *       {@link #fromRha} 永远不会返回此等级。</li>
     *   <li>{@link #canDefeat} 判定：任何等级都无法击穿此等级。</li>
     *   <li>中位值 RHA 为 {@link Float#MAX_VALUE}，精密模式下
     *       返回此值也会导致 {@code modifyPenetration > getRHA} 始终为 false。</li>
     * </ul>
     */
    UNPENETRABLE(Float.POSITIVE_INFINITY);

    private final float upperRha;
    private final float lowerRha;
    private final ResourceLocation armorDisplayName;
    private final ResourceLocation penetrationDisplayName;

    ArmorLevel(float upperRha) {
        this.upperRha = upperRha;
        // 第一个等级下限为 0，其余为上一级上限
        this.lowerRha = ordinal() == 0 ? 0f : values()[ordinal() - 1].upperRha;
        String name = name().toLowerCase();
        this.armorDisplayName = ResourceLocation.fromNamespaceAndPath("terminal_ballistics", "armor_level/" + name + "/armor");
        this.penetrationDisplayName = ResourceLocation.fromNamespaceAndPath("terminal_ballistics", "armor_level/" + name + "/penetration");
    }

    // ======================== 等级映射 ========================

    /**
     * 根据 RHA 值查找对应的护甲/穿甲等级。
     * <p>
     * 取第一个 {@code upperRha >= rha} 的等级，即向上映射。
     * 元等级 {@link #UNPENETRABLE} 不会被此方法返回——它只能通过显式引用获取。
     *
     * @param rha RHA 等效厚度（mm），单位与 {@link TBDamageContext#penetration} 一致
     * @return 对应的护甲/穿甲等级。大于 2000mm 的值映射到 {@link #SUPER_HEAVY_6}
     */
    public static ArmorLevel fromRha(float rha) {
        for (var level : values()) {
            if (level == UNPENETRABLE) continue;
            if (rha <= level.upperRha) {
                return level;
            }
        }
        return SUPER_HEAVY_6;
    }

    // ======================== 边界值 ========================

    /** @return 本等级的 RHA 上限（mm），包含该值 */
    public float upperRha() {
        return upperRha;
    }

    /** @return 本等级的 RHA 下限（mm），不包含该值。第一级为 0 */
    public float lowerRha() {
        return lowerRha;
    }

    /** @return 本等级的 RHA 中位值（mm），供需要单一数值的场景使用 */
    public float medianRha() {
        return (lowerRha + upperRha) / 2f;
    }

    // ======================== 等级判定 ========================

    /**
     * 判断此穿甲等级能否击穿给定的护甲等级。
     * <p>
     * 使用 {@code >=} 比较（等于算击穿）。
     * 等级按防护强度升序排列，因此比较 ordinal 等价于比较 RHA 上限。
     * <p>
     * 特殊规则：任何等级都无法击穿 {@link #UNPENETRABLE}（绝对防御）。
     *
     * @param armorLevel 目标护甲等级
     * @return true 表示此等级的穿深能击穿该护甲等级
     */
    public boolean canDefeat(ArmorLevel armorLevel) {
        Objects.requireNonNull(armorLevel);
        if (armorLevel == UNPENETRABLE) return false;
        return ordinal() >= armorLevel.ordinal();
    }

    // ======================== 本地化显示名 ========================

    /**
     * 获取护甲显示名称（如 "重型防护"、"无防护1级"）。
     * <p>
     * 用于 HUD 上显示目标的防护等级。
     *
     * @return 可本地化的护甲显示名
     */
    public Component getArmorDisplayName() {
        return Component.translatable(armorDisplayName.toLanguageKey());
    }

    /**
     * 获取穿甲显示名称（如 "重型穿深"、"无防护1级穿深"）。
     * <p>
     * 用于 HUD 上显示武器的穿甲等级。
     *
     * @return 可本地化的穿甲显示名
     */
    public Component getPenetrationDisplayName() {
        return Component.translatable(penetrationDisplayName.toLanguageKey());
    }
}
