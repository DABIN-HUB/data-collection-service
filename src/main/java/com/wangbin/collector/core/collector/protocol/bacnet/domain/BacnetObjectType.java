package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public enum BacnetObjectType {

    ANALOG_INPUT(0, "analogInput", "ai"),
    ANALOG_OUTPUT(1, "analogOutput", "ao"),
    ANALOG_VALUE(2, "analogValue", "av"),
    BINARY_INPUT(3, "binaryInput", "bi"),
    BINARY_OUTPUT(4, "binaryOutput", "bo"),
    BINARY_VALUE(5, "binaryValue", "bv"),
    DEVICE(8, "device"),
    MULTI_STATE_INPUT(13, "multiStateInput", "msi"),
    MULTI_STATE_OUTPUT(14, "multiStateOutput", "mso"),
    MULTI_STATE_VALUE(19, "multiStateValue", "msv"),
    NETWORK_PORT(56, "networkPort");

    private static final Map<String, BacnetObjectType> BY_NAME = new HashMap<>();
    private static final Map<Integer, BacnetObjectType> BY_ID = new HashMap<>();

    static {
        for (BacnetObjectType value : values()) {
            BY_ID.put(value.id, value);
            BY_NAME.put(normalize(value.name), value);
            for (String alias : value.aliases) {
                BY_NAME.put(normalize(alias), value);
            }
        }
    }

    private final int id;
    private final String name;
    private final String[] aliases;

    BacnetObjectType(int id, String name, String... aliases) {
        this.id = id;
        this.name = name;
        this.aliases = aliases != null ? aliases : new String[0];
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public static BacnetObjectType fromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("BACnet object type cannot be empty");
        }
        String normalized = normalize(token);
        BacnetObjectType byName = BY_NAME.get(normalized);
        if (byName != null) {
            return byName;
        }
        try {
            int id = Integer.parseInt(token.trim());
            return fromId(id);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Unsupported BACnet object type: " + token, ex);
        }
    }

    public static BacnetObjectType fromId(int id) {
        BacnetObjectType value = BY_ID.get(id);
        if (value == null) {
            throw new IllegalArgumentException("Unsupported BACnet object type id: " + id);
        }
        return value;
    }

    private static String normalize(String token) {
        return token.trim()
                .replace("-", "")
                .replace("_", "")
                .replace("/", "")
                .toLowerCase(Locale.ROOT);
    }
}
