package io.github.sweetzonzi.terminal_ballistics.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 类型安全的扩展字段键。
 * <p>
 * 每个 key 绑定一个唯一标识符、一个 Java 类型、一个默认值工厂。
 * 通过 {@link TBDamageExtensions#register(ResourceLocation, Class, Supplier)} 创建。
 * key 本身是全局单例，建议在 mod 侧存为 {@code public static final} 常量。
 *
 * @param <T> 扩展值的类型
 */
public final class TBDamageExtensionKey<T> {

    private final ResourceLocation id;
    private final Class<T> type;
    private final Supplier<T> defaultValueFactory;

    TBDamageExtensionKey(ResourceLocation id, Class<T> type, Supplier<T> defaultValueFactory) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.defaultValueFactory = Objects.requireNonNull(defaultValueFactory, "defaultValueFactory");
    }

    public ResourceLocation getId() {
        return id;
    }

    public Class<T> getType() {
        return type;
    }

    public T getDefaultValue() {
        return defaultValueFactory.get();
    }
}
