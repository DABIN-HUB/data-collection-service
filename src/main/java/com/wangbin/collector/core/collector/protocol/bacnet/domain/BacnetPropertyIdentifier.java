package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public enum BacnetPropertyIdentifier {

    OBJECT_IDENTIFIER(75, "objectIdentifier"),
    OBJECT_NAME(77, "objectName"),
    OBJECT_TYPE(79, "objectType"),
    PRESENT_VALUE(85, "presentValue"),
    STATUS_FLAGS(111, "statusFlags"),
    UNITS(117, "units"),
    VENDOR_IDENTIFIER(120, "vendorIdentifier"),
    SEGMENTATION_SUPPORTED(107, "segmentationSupported"),
    MAX_APDU_LENGTH_ACCEPTED(62, "maxApduLengthAccepted"),
    PROTOCOL_VERSION(98, "protocolVersion"),
    PROTOCOL_REVISION(139, "protocolRevision"),
    MODEL_NAME(70, "modelName"),
    DESCRIPTION(28, "description"),
    RELIABILITY(103, "reliability"),
    OUT_OF_SERVICE(81, "outOfService"),
    ACTIVE_TEXT(4, "activeText"),
    INACTIVE_TEXT(46, "inactiveText"),
    STATE_TEXT(110, "stateText"),
    NUMBER_OF_STATES(74, "numberOfStates"),
    PRIORITY_ARRAY(87, "priorityArray"),
    OBJECT_LIST(76, "objectList");

    private static final Map<String, BacnetPropertyIdentifier> BY_NAME = new HashMap<>();
    private static final Map<Integer, BacnetPropertyIdentifier> BY_ID = new HashMap<>();

    static {
        for (BacnetPropertyIdentifier value : values()) {
            BY_ID.put(value.id, value);
            BY_NAME.put(normalize(value.name), value);
        }
    }

    private final int id;
    private final String name;

    BacnetPropertyIdentifier(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public static BacnetPropertyIdentifier fromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("BACnet property identifier cannot be empty");
        }
        String normalized = normalize(token);
        BacnetPropertyIdentifier byName = BY_NAME.get(normalized);
        if (byName != null) {
            return byName;
        }
        try {
            int id = Integer.parseInt(token.trim());
            return fromId(id);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Unsupported BACnet property identifier: " + token, ex);
        }
    }

    public static BacnetPropertyIdentifier fromId(int id) {
        BacnetPropertyIdentifier value = BY_ID.get(id);
        if (value == null) {
            throw new IllegalArgumentException("Unsupported BACnet property identifier id: " + id);
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
