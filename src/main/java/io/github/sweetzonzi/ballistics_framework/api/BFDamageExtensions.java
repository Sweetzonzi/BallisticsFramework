package io.github.sweetzonzi.ballistics_framework.api;

import io.github.sweetzonzi.ballistics_framework.internal.BFExtensionKeyRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 扩展数据容器，以及协议预定义扩展 key 常量。
 * <p>
 * 每个 {@link BFDamageContext} 持有一个此容器实例，用于携带核心字段以外的任意结构化数据。
 * 读操作总返回非 null——未显式设置时退回注册的默认值。
 * <p>
 * 容器支持浅拷贝。若多个命中上下文共享一组基础扩展数据，可先构造基础容器，
 * 再通过 {@link #copy()} 或 {@link #BFDamageExtensions(BFDamageExtensions)}
 * 为每次命中创建独立副本，并写入命中特定的数据。
 * <p>
 * 扩展 key 通过 {@link #register(ResourceLocation, Class, Supplier)} 注册，建议存为
 * {@code public static final} 常量。协议自身预定义 key：
 * {@link #FUSE_DELAY}、{@link #CALIBER}、{@link #MASS}。
 */
public final class BFDamageExtensions {

    // ======================== 注册（必须在预定义 key 之前声明）=======================

    private static final BFExtensionKeyRegistry REGISTRY = new BFExtensionKeyRegistry();

    /**
     * 注册一个扩展 key。
     *
     * @param id                 唯一标识符
     * @param type               值类型
     * @param defaultValueFactory 默认值工厂
     * @param <T>                值类型
     * @return 注册完成的 key
     */
    public static <T> BFDamageExtensionKey<T> register(ResourceLocation id, Class<T> type, Supplier<T> defaultValueFactory) {
        var key = new BFDamageExtensionKey<>(id, type, defaultValueFactory);
        REGISTRY.register(key);
        return key;
    }

    /** 仅用于触发类加载，使预定义 key 完成注册 */
    public static void init() {}

    // ======================== 协议预定义扩展 key ========================

    /**
     * 引信延迟（s），0 表示瞬发。
     * <p>
     * 注：曾经的 RICOCHET / SPALL / OVERMATCH 常量已移除。
     * 跳弹由 {@link PenetrationResult#RICOCHET} 表达；
     * 超匹配(碾压)与破片由 {@link BFDamageHandler#isOvermatch} /
     * {@link BFDamageHandler#isSpall} 在回调阶段动态判定。
     */
    public static final BFDamageExtensionKey<Float> FUSE_DELAY =
            register(ResourceLocation.fromNamespaceAndPath("ballistics_framework", "fuse_delay"),
                    Float.class, () -> 0f);

    /** 弹体口径（m），默认 0.1（100mm，典型坦克炮口径） */
    public static final BFDamageExtensionKey<Float> CALIBER =
            register(ResourceLocation.fromNamespaceAndPath("ballistics_framework", "caliber"),
                    Float.class, () -> 0.1f);

    /** 弹体质量（kg），默认 10（典型坦克炮穿甲弹质量） */
    public static final BFDamageExtensionKey<Float> MASS =
            register(ResourceLocation.fromNamespaceAndPath("ballistics_framework", "mass"),
                    Float.class, () -> 10f);

    // ======================== 实例方法 ========================

    private final Map<BFDamageExtensionKey<?>, Object> data = new HashMap<>();

    /** 创建一个空扩展容器。 */
    public BFDamageExtensions() {}

    /**
     * 创建一个已有扩展容器的浅拷贝。
     * <p>
     * 拷贝后的容器拥有独立的内部映射，后续对两个容器执行 {@link #set}
     * 不会互相影响。但扩展值对象本身不会被深拷贝；如果某个扩展值是可变对象，
     * 两个容器仍会引用同一个值实例。建议扩展值优先使用 {@link Float}、
     * {@link Integer}、{@link Boolean}、{@link String}、枚举或不可变 record。
     *
     * @param other 要复制的扩展容器
     * @throws NullPointerException other 为 null 时抛出
     */
    public BFDamageExtensions(BFDamageExtensions other) {
        Objects.requireNonNull(other, "other");
        this.data.putAll(other.data);
    }

    /**
     * 返回此扩展容器的浅拷贝。
     * <p>
     * 常用于为同一种弹药预构造基础扩展数据，并在每次命中时复制后追加
     * 命中特定字段：
     * <pre>{@code
     * BFDamageExtensions perHit = baseExtensions.copy();
     * perHit.set(BFDamageExtensions.FUSE_DELAY, 0.05f);
     * }</pre>
     *
     * @return 拥有独立内部映射的新扩展容器
     */
    public BFDamageExtensions copy() {
        return new BFDamageExtensions(this);
    }

    /**
     * 读取扩展值。
     *
     * @param key  扩展 key
     * @param <T>  值类型
     * @return 扩展值，未设置时返回 key 注册时指定的默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(BFDamageExtensionKey<T> key) {
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
    public <T> void set(BFDamageExtensionKey<T> key, T value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        data.put(key, value);
    }
}
