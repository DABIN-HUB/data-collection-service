package com.wangbin.collector.core.collector.protocol.plc4x.domain;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PlcTypeAliasLookup<T extends Enum<T> & PlcTypeDescriptor> {

    private final Map<String, T> aliases;

    private PlcTypeAliasLookup(Map<String, T> aliases) {
        this.aliases = Map.copyOf(aliases);
    }

    public T resolveOrNull(String normalizedText) {
        return normalizedText != null ? aliases.get(normalizedText) : null;
    }

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

    public static final class Builder<T extends Enum<T> & PlcTypeDescriptor> {

        private final Map<String, T> aliases = new LinkedHashMap<>();

        public Builder<T> register(T type, String... keys) {
            aliases.put(type.name(), type);
            return alias(type, keys);
        }

        public Builder<T> alias(T type, String... keys) {
            if (keys != null) {
                for (String key : keys) {
                    aliases.put(key, type);
                }
            }
            return this;
        }

        public PlcTypeAliasLookup<T> build() {
            return new PlcTypeAliasLookup<>(aliases);
        }
    }
}