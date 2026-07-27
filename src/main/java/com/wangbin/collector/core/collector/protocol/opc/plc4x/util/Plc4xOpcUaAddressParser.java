package com.wangbin.collector.core.collector.protocol.opc.plc4x.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.domain.Plc4xOpcUaAddress;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.domain.Plc4xOpcUaType;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Converts the current OPC UA point model into PLC4X OPC UA tag addresses.
 */
public final class Plc4xOpcUaAddressParser {

    private static final Pattern FULL_NODE_ID_PATTERN = Pattern.compile(
            "^ns=\\d+;[isgb]=[^;{}]+(?:;a=[^;{}]+)?(?:;[A-Za-z_]+)?(?:\\{.*})?$");
    private static final Pattern EXPLICIT_TYPE_PATTERN = Pattern.compile(
            "^ns=\\d+;[isgb]=[^;{}]+(?:;a=[^;{}]+)?;[A-Za-z_]+$");

    private Plc4xOpcUaAddressParser() {
    }

    public static Plc4xOpcUaAddress parse(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }

        Map<String, Object> config = point.getAdditionalConfig() != null
                ? point.getAdditionalConfig()
                : Collections.emptyMap();

        String rawAddress = resolveAddress(point, config);
        String dataType = resolveDriverDataType(
                firstPresent(config, "driverDataType", "opcUaType", "opcType", "nodeType", "dataType"),
                point.getDataType());
        double samplingInterval = parseDouble(
                firstPresent(config, "samplingInterval", "publishingInterval"),
                -1d);
        int queueSize = parseInt(config.get("queueSize"), 10);
        double deadband = parseDouble(config.get("deadband"), -1d);
        boolean subscribe = resolveSubscription(point, config);
        int arraySize = Math.max(1, parseInt(firstPresent(config, "arraySize", "numberOfElements"), 1));

        return new Plc4xOpcUaAddress(
                rawAddress,
                normalizeAddress(rawAddress, dataType),
                dataType,
                samplingInterval,
                queueSize,
                deadband,
                subscribe,
                arraySize);
    }

    public static Plc4xOpcUaAddress parse(String address) {
        return parse(address, null);
    }

    public static Plc4xOpcUaAddress parse(String address, String dataType) {
        String normalizedType = resolveDriverDataType(dataType, null);
        String rawAddress = normalizeNodeId(address);
        return new Plc4xOpcUaAddress(
                rawAddress,
                normalizeAddress(rawAddress, normalizedType),
                normalizedType,
                -1d,
                10,
                -1d,
                false,
                1);
    }

    private static String resolveAddress(DataPoint point, Map<String, Object> config) {
        String explicit = firstNonBlank(
                asString(firstPresent(config, "nodeId", "id")),
                point.getAddress()
        );
        if (explicit != null) {
            return normalizeNodeId(explicit);
        }

        int namespace = parseInt(firstPresent(config, "namespace", "ns"), 0);
        Object identifier = firstPresent(config, "identifier", "id");
        if (identifier == null) {
            throw new IllegalArgumentException("OPC UA point missing nodeId/identifier: " + point.getPointId());
        }
        String identifierType = firstNonBlank(
                asString(firstPresent(config, "identifierType", "idType")),
                "s");
        return buildNodeId(namespace, identifierType, identifier);
    }

    private static String normalizeNodeId(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) {
            throw new IllegalArgumentException("OPC UA address cannot be blank");
        }
        String trimmed = rawAddress.trim();
        if (trimmed.startsWith("ns=")) {
            return trimmed;
        }
        if (trimmed.matches("^[isgb]=.+")) {
            return "ns=0;" + trimmed;
        }
        if (trimmed.matches("^\\d+$")) {
            return "ns=0;i=" + trimmed;
        }
        throw new IllegalArgumentException("Invalid OPC UA NodeId: " + rawAddress);
    }

    private static String buildNodeId(int namespace, String identifierType, Object identifier) {
        String type = identifierType != null ? identifierType.trim().toLowerCase(Locale.ROOT) : "s";
        String value = Objects.toString(identifier, "");
        return switch (type) {
            case "i", "n", "numeric" -> "ns=" + namespace + ";i=" + Long.parseLong(value);
            case "g", "guid" -> "ns=" + namespace + ";g=" + value;
            case "b", "bytes", "binary" -> "ns=" + namespace + ";b=" + value;
            case "s", "string" -> "ns=" + namespace + ";s=" + value;
            default -> throw new IllegalArgumentException("Unsupported OPC UA identifierType: " + identifierType);
        };
    }

    private static String normalizeAddress(String rawAddress, String dataType) {
        String normalized = normalizeNodeId(rawAddress);
        if (!FULL_NODE_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid PLC4X OPC UA address: " + rawAddress);
        }
        if (dataType == null || dataType.isBlank()) {
            return normalized;
        }

        int configStart = normalized.indexOf('{');
        String base = configStart >= 0 ? normalized.substring(0, configStart) : normalized;
        String configSuffix = configStart >= 0 ? normalized.substring(configStart) : "";
        if (EXPLICIT_TYPE_PATTERN.matcher(base).matches()) {
            return base + configSuffix;
        }
        return base + ";" + dataType + configSuffix;
    }

    private static String resolveDriverDataType(Object preferredType, String fallbackType) {
        Plc4xOpcUaType type = Plc4xOpcUaType.fromDriverTextOrNull(firstNonBlank(asString(preferredType), fallbackType));
        return type != null ? type.toTypeExpression() : null;
    }

    private static boolean resolveSubscription(DataPoint point, Map<String, Object> config) {
        Object flag = firstPresent(config, "subscribe", "monitor");
        if (flag != null) {
            return parseBoolean(flag);
        }
        return point.getCollectionMode() != null
                && point.getCollectionMode().equalsIgnoreCase("SUBSCRIPTION");
    }

    private static Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
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

    private static int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static double parseDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static boolean parseBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
