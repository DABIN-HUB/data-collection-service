package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class BacnetObjectType {

    private static final Map<String, BacnetObjectType> BY_NAME = new HashMap<>();
    private static final Map<Integer, BacnetObjectType> BY_ID = new HashMap<>();
    private static final Map<Integer, BacnetObjectType> KNOWN_VALUES = new LinkedHashMap<>();

    public static final BacnetObjectType ANALOG_INPUT = registerKnown(0, "analogInput", "ai");
    public static final BacnetObjectType ANALOG_OUTPUT = registerKnown(1, "analogOutput", "ao");
    public static final BacnetObjectType ANALOG_VALUE = registerKnown(2, "analogValue", "av");
    public static final BacnetObjectType BINARY_INPUT = registerKnown(3, "binaryInput", "bi");
    public static final BacnetObjectType BINARY_OUTPUT = registerKnown(4, "binaryOutput", "bo");
    public static final BacnetObjectType BINARY_VALUE = registerKnown(5, "binaryValue", "bv");
    public static final BacnetObjectType DEVICE = registerKnown(8, "device");
    public static final BacnetObjectType MULTI_STATE_INPUT = registerKnown(13, "multiStateInput", "msi");
    public static final BacnetObjectType MULTI_STATE_OUTPUT = registerKnown(14, "multiStateOutput", "mso");
    public static final BacnetObjectType MULTI_STATE_VALUE = registerKnown(19, "multiStateValue", "msv");
    public static final BacnetObjectType NETWORK_PORT = registerKnown(56, "networkPort");

    private final int id;
    private final String name;
    private final boolean known;
    private final String[] aliases;

    private BacnetObjectType(int id, String name, boolean known, String... aliases) {
        this.id = id;
        this.name = name;
        this.known = known;
        this.aliases = aliases != null ? aliases : new String[0];
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isKnown() {
        return known;
    }

    public Collection<String> getAliases() {
        return Collections.unmodifiableList(Arrays.asList(aliases));
    }

    public static Collection<BacnetObjectType> knownValues() {
        return Collections.unmodifiableCollection(KNOWN_VALUES.values());
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
        Integer dynamicId = parseDynamicToken(normalized, "objecttype#");
        if (dynamicId != null) {
            return fromId(dynamicId);
        }
        try {
            return fromId(Integer.parseInt(token.trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Unsupported BACnet object type: " + token, ex);
        }
    }

    public static BacnetObjectType fromId(int id) {
        BacnetObjectType value = BY_ID.get(id);
        if (value != null) {
            return value;
        }
        return new BacnetObjectType(id, "objectType#" + id, false);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BacnetObjectType that)) {
            return false;
        }
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name;
    }

    private static BacnetObjectType registerKnown(int id, String name, String... aliases) {
        BacnetObjectType value = new BacnetObjectType(id, name, true, aliases);
        BY_ID.put(id, value);
        KNOWN_VALUES.put(id, value);
        BY_NAME.put(normalize(name), value);
        if (aliases != null) {
            for (String alias : aliases) {
                BY_NAME.put(normalize(alias), value);
            }
        }
        return value;
    }

    private static Integer parseDynamicToken(String normalized, String prefix) {
        if (normalized.startsWith(prefix) && normalized.length() > prefix.length()) {
            return Integer.parseInt(normalized.substring(prefix.length()));
        }
        return null;
    }

    private static String normalize(String token) {
        return token.trim()
                .replace("-", "")
                .replace("_", "")
                .replace("/", "")
                .toLowerCase(Locale.ROOT);
    }
}