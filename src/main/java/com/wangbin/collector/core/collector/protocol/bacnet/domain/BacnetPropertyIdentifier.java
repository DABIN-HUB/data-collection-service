package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetPropertyIdentifier {

    private static final Map<String, BacnetPropertyIdentifier> BY_NAME = new HashMap<>();
    private static final Map<Integer, BacnetPropertyIdentifier> BY_ID = new HashMap<>();
    private static final Map<Integer, BacnetPropertyIdentifier> KNOWN_VALUES = new LinkedHashMap<>();

    public static final BacnetPropertyIdentifier ACTIVE_TEXT = registerKnown(4, "activeText");
    public static final BacnetPropertyIdentifier DESCRIPTION = registerKnown(28, "description");
    public static final BacnetPropertyIdentifier INACTIVE_TEXT = registerKnown(46, "inactiveText");
    public static final BacnetPropertyIdentifier MAX_APDU_LENGTH_ACCEPTED = registerKnown(62, "maxApduLengthAccepted");
    public static final BacnetPropertyIdentifier MODEL_NAME = registerKnown(70, "modelName");
    public static final BacnetPropertyIdentifier NUMBER_OF_STATES = registerKnown(74, "numberOfStates");
    public static final BacnetPropertyIdentifier OBJECT_IDENTIFIER = registerKnown(75, "objectIdentifier");
    public static final BacnetPropertyIdentifier OBJECT_LIST = registerKnown(76, "objectList");
    public static final BacnetPropertyIdentifier OBJECT_NAME = registerKnown(77, "objectName");
    public static final BacnetPropertyIdentifier OBJECT_TYPE = registerKnown(79, "objectType");
    public static final BacnetPropertyIdentifier OUT_OF_SERVICE = registerKnown(81, "outOfService");
    public static final BacnetPropertyIdentifier PRESENT_VALUE = registerKnown(85, "presentValue");
    public static final BacnetPropertyIdentifier PRIORITY_ARRAY = registerKnown(87, "priorityArray");
    public static final BacnetPropertyIdentifier PROTOCOL_VERSION = registerKnown(98, "protocolVersion");
    public static final BacnetPropertyIdentifier RELIABILITY = registerKnown(103, "reliability");
    public static final BacnetPropertyIdentifier SEGMENTATION_SUPPORTED = registerKnown(107, "segmentationSupported");
    public static final BacnetPropertyIdentifier STATE_TEXT = registerKnown(110, "stateText");
    public static final BacnetPropertyIdentifier STATUS_FLAGS = registerKnown(111, "statusFlags");
    public static final BacnetPropertyIdentifier UNITS = registerKnown(117, "units");
    public static final BacnetPropertyIdentifier VENDOR_IDENTIFIER = registerKnown(120, "vendorIdentifier");
    public static final BacnetPropertyIdentifier PROTOCOL_REVISION = registerKnown(139, "protocolRevision");

    private final int id;
    private final String name;
    private final boolean known;

    /**
     * 创建当前组件实例。
     */
    private BacnetPropertyIdentifier(int id, String name, boolean known) {
        this.id = id;
        this.name = name;
        this.known = known;
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

    /**
     * 执行当前业务逻辑。
     */
    public static Collection<BacnetPropertyIdentifier> knownValues() {
        return Collections.unmodifiableCollection(KNOWN_VALUES.values());
    }

    /**
     * 创建并返回业务对象。
     */
    public static BacnetPropertyIdentifier fromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("BACnet property identifier cannot be empty");
        }
        String normalized = normalize(token);
        BacnetPropertyIdentifier byName = BY_NAME.get(normalized);
        if (byName != null) {
            return byName;
        }
        Integer dynamicId = parseDynamicToken(normalized, "property#");
        if (dynamicId != null) {
            return fromId(dynamicId);
        }
        try {
            return fromId(Integer.parseInt(token.trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Unsupported BACnet property identifier: " + token, ex);
        }
    }

    /**
     * 创建并返回业务对象。
     */
    public static BacnetPropertyIdentifier fromId(int id) {
        BacnetPropertyIdentifier value = BY_ID.get(id);
        if (value != null) {
            return value;
        }
        return new BacnetPropertyIdentifier(id, "property#" + id, false);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BacnetPropertyIdentifier that)) {
            return false;
        }
        return id == that.id;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    public String toString() {
        return name;
    }

    /**
     * 维护注册或订阅关系。
     */
    private static BacnetPropertyIdentifier registerKnown(int id, String name) {
        BacnetPropertyIdentifier value = new BacnetPropertyIdentifier(id, name, true);
        BY_ID.put(id, value);
        KNOWN_VALUES.put(id, value);
        BY_NAME.put(normalize(name), value);
        return value;
    }

    /**
     * 解析或转换业务数据。
     */
    private static Integer parseDynamicToken(String normalized, String prefix) {
        if (normalized.startsWith(prefix) && normalized.length() > prefix.length()) {
            return Integer.parseInt(normalized.substring(prefix.length()));
        }
        return null;
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalize(String token) {
        return token.trim()
                .replace("-", "")
                .replace("_", "")
                .replace("/", "")
                .toLowerCase(Locale.ROOT);
    }
}