package io.github.sweetzonzi.terminal_ballistics.internal;

import io.github.sweetzonzi.terminal_ballistics.api.TBDamageExtensionKey;

import java.util.HashMap;
import java.util.Map;

/**
 * 扩展 key 的全局注册表（内部实现）。
 * <p>
 * 包可见，不构成公开 API。通过 {@code TBDamageExtensions.register()} 间接调用。
 * 注册时检查 ID 重名，防止不同模组意外覆盖。
 */
public final class TBExtensionKeyRegistry {

    private final Map<String, TBDamageExtensionKey<?>> keys = new HashMap<>();

    /**
     * 注册一个扩展 key。
     *
     * @param key 待注册的 key
     * @throws IllegalArgumentException 如果 ID 已注册
     */
    public void register(TBDamageExtensionKey<?> key) {
        String id = key.getId().toString();
        if (keys.containsKey(id)) {
            throw new IllegalArgumentException("扩展 key 重复注册: " + id);
        }
        keys.put(id, key);
    }
}
