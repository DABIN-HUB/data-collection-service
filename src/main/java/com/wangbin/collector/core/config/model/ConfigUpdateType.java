package com.wangbin.collector.core.config.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 配置变更类型。
 */
public enum ConfigUpdateType {

    DEVICE("device"),
    POINTS("points"),
    CONNECTION("connection"),
    COLLECTION("collection"),
    ALL("all"),
    LOCAL("local"),
    LOCAL_DELETE("local-delete");

    private final String value;

    ConfigUpdateType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Optional<ConfigUpdateType> fromValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.value.equals(normalized))
                .findFirst();
    }
}
