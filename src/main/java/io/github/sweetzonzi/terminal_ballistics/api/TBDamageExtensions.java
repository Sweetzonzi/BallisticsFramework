package io.github.sweetzonzi.terminal_ballistics.api;

import io.github.sweetzonzi.terminal_ballistics.internal.TBExtensionKeyRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 扩展数据容器，以及协议预定义扩展 key 常量。
 * <p>
 * 每个 {@link TBDamageContext} 持有一个此容器实例，用于携带核心字段以外的任意结构化数据。
 * 读操作总返回非 null——未显式设置时退回注册的默认值。
 * <p>
 * 扩展 key 通过 {@link #register(ResourceLocation, Class, Supplier)} 注册，建议存为
 * {@code public static final} 常量。协议库自身预定义的 key 定义在本类上：
 * {@link #RICOCHET}、{@link #SPALL}、{@link #OVERMATCH}、{@link #FUSE_DELAY}。
 */
public final class TBDamageExtensions {

    // ======================== 注册（必须在预定义 key 之前声明）=======================

    private static final TBExtensionKeyRegistry REGISTRY = new TBExtensionKeyRegistry();

    /**
     * 注册一个扩展 key。
     *
     * @param id                 唯一标识符
     * @param type               值类型
     * @param defaultValueFactory 默认值工厂
     * @param <T>                值类型
     * @return 注册完成的 key
     */
    public static <T> TBDamageExtensionKey<T> register(ResourceLocation id, Class<T> type, Supplier<T> defaultValueFactory) {
        var key = new TBDamageExtensionKey<>(id, type, defaultValueFactory);
        REGISTRY.register(key);
        return key;
    }

    /** 仅用于触发类加载，使预定义 key 完成注册 */
    public static void init() {}

    // ======================== 协议预定义扩展 key ========================

    /** 跳弹标记 */
    public static final TBDamageExtensionKey<Boolean> RICOCHET =
            register(ResourceLocation.fromNamespaceAndPath("terminal_ballistics", "ricochet"),
                    Boolean.class, () -> false);

    /** 破片标记 */
    public static final TBDamageExtensionKey<Boolean> SPALL =
            register(ResourceLocation.fromNamespaceAndPath("terminal_ballistics", "spall"),
                    Boolean.class, () -> false);

    /** 超匹配标记（穿深远超装甲厚度的情况） */
    public static final TBDamageExtensionKey<Boolean> OVERMATCH =
            register(ResourceLocation.fromNamespaceAndPath("terminal_ballistics", "overmatch"),
                    Boolean.class, () -> false);

    /** 引信延迟（ms），0 表示瞬发 */
    public static final TBDamageExtensionKey<Integer> FUSE_DELAY =
            register(ResourceLocation.fromNamespaceAndPath("terminal_ballistics", "fuse_delay"),
                    Integer.class, () -> 0);

    // ======================== 实例方法 ========================

    private final Map<TBDamageExtensionKey<?>, Object> data = new HashMap<>();

    public TBDamageExtensions() {}

    /**
     * 读取扩展值。
     *
     * @param key  扩展 key
     * @param <T>  值类型
     * @return 扩展值，未设置时返回 key 注册时指定的默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(TBDamageExtensionKey<T> key) {
        Objects.requireNonNull(key);
        if (data.containsKey(key)) {
            return (T) data.get(key);
        }
        return key.getDefaultValue();
    }

    /**
     * 写入扩展值。
     *
     * @param key   扩展 key
     * @param value 扩展值
     * @param <T>   值类型
     */
    public <T> void set(TBDamageExtensionKey<T> key, T value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        data.put(key, value);
    }
}
