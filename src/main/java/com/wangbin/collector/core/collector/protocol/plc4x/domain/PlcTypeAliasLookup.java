package com.wangbin.collector.core.collector.protocol.plc4x.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定义当前模块的业务组件。
 */
public final class PlcTypeAliasLookup<T extends Enum<T> & PlcTypeDescriptor> {

    private final Map<String, T> aliases;

    /**
     * 创建当前组件实例。
     */
    private PlcTypeAliasLookup(Map<String, T> aliases) {
        this.aliases = Map.copyOf(aliases);
    }

    /**
     * 解析或转换业务数据。
     */
    public T resolveOrNull(String normalizedText) {
        return normalizedText != null ? aliases.get(normalizedText) : null;
    }

    /**
     * 校验业务条件和参数边界。
     */
    public T require(String normalizedText, String unsupportedMessage) {
        T resolved = resolveOrNull(normalizedText);
        if (resolved != null) {
            return resolved;
        }
        throw new IllegalArgumentException(unsupportedMessage);
    }

    public static <T extends Enum<T> & PlcTypeDescriptor> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * 定义当前模块的业务组件。
     */
    public static final class Builder<T extends Enum<T> & PlcTypeDescriptor> {

        private final Map<String, T> aliases = new LinkedHashMap<>();

        /**
         * 维护注册或订阅关系。
         */
        public Builder<T> register(T type, String... keys) {
            aliases.put(type.name(), type);
            return alias(type, keys);
        }

        /**
         * 执行当前业务逻辑。
         */
        public Builder<T> alias(T type, String... keys) {
            if (keys != null) {
                for (String key : keys) {
                    aliases.put(key, type);
                }
            }
            return this;
        }

        /**
         * 创建并返回业务对象。
         */
        public PlcTypeAliasLookup<T> build() {
            return new PlcTypeAliasLookup<>(aliases);
        }
    }
}