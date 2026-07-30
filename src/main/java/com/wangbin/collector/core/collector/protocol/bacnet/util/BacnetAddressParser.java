package com.wangbin.collector.core.collector.protocol.bacnet.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetAddress;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BacnetAddressParser {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9_#-]*|\\d+)\\s*:\\s*(\\d+)\\s*\\.\\s*([A-Za-z][A-Za-z0-9_#-]*|\\d+)(?:\\[(\\d+)])?$"
    );

    private BacnetAddressParser() {
    }

    public static BacnetAddress parse(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }
        String address = firstNonBlank(
                point.getAddress(),
                asString(point.getAdditionalConfig("bacnetAddress")),
                asString(point.getAdditionalConfig("objectAddress"))
        );
        return parse(address, point.getAdditionalConfig());
    }

    public static BacnetAddress parse(String address) {
        return parse(address, Collections.emptyMap());
    }

    private static BacnetAddress parse(String address, Map<String, Object> config) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("BACnet address cannot be empty");
        }

        Map<String, Object> effectiveConfig = config != null ? config : Collections.emptyMap();
        String rawAddress = address.trim();
        Matcher matcher = ADDRESS_PATTERN.matcher(rawAddress);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported BACnet address format: " + rawAddress);
        }

        String objectType = normalizeToken(matcher.group(1));
        BacnetObjectType resolvedObjectType = BacnetObjectType.fromToken(objectType);
        int instanceNumber = Integer.parseInt(matcher.group(2));
        if (instanceNumber < 0) {
            throw new IllegalArgumentException("BACnet instance number must be greater than or equal to 0");
        }

        String propertyIdentifier = normalizeToken(matcher.group(3));
        BacnetPropertyIdentifier resolvedPropertyIdentifier = BacnetPropertyIdentifier.fromToken(propertyIdentifier);
        Integer arrayIndex = resolveArrayIndex(matcher.group(4), effectiveConfig);
        String driverDataType = resolveDriverDataType(effectiveConfig);

        return new BacnetAddress(
                rawAddress,
                canonicalize(resolvedObjectType.getName(), instanceNumber, resolvedPropertyIdentifier.getName(), arrayIndex),
                resolvedObjectType.getName(),
                resolvedObjectType.getId(),
                instanceNumber,
                resolvedPropertyIdentifier.getName(),
                resolvedPropertyIdentifier.getId(),
                arrayIndex,
                driverDataType
        );
    }

    private static String canonicalize(String objectType,
                                       int instanceNumber,
                                       String propertyIdentifier,
                                       Integer arrayIndex) {
        StringBuilder builder = new StringBuilder()
                .append(objectType)
                .append(':')
                .append(instanceNumber)
                .append('.')
                .append(propertyIdentifier);
        if (arrayIndex != null) {
            builder.append('[').append(arrayIndex).append(']');
        }
        return builder.toString();
    }

    private static Integer resolveArrayIndex(String explicitArrayIndex, Map<String, Object> config) {
        String value = explicitArrayIndex;
        if ((value == null || value.isBlank()) && config != null && !config.isEmpty()) {
            Object configured = firstPresent(config, "arrayIndex", "bacnetArrayIndex");
            if (configured != null) {
                value = String.valueOf(configured);
            }
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        int parsed = Integer.parseInt(value.trim());
        if (parsed < 0) {
            throw new IllegalArgumentException("BACnet array index must be greater than or equal to 0");
        }
        return parsed;
    }

    private static String resolveDriverDataType(Map<String, Object> config) {
        Object value = firstPresent(config,
                "driverDataType",
                "bacnetType",
                "propertyType");
        if (value == null) {
            return "AUTO";
        }
        String normalized = value.toString().trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? "AUTO" : normalized;
    }

    private static Object firstPresent(Map<String, Object> config, String... keys) {
        if (config == null) {
            return null;
        }
        for (String key : keys) {
            if (config.containsKey(key)) {
                return config.get(key);
            }
        }
        return null;
    }

    private static String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        return token.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }
}
