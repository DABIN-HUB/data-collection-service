package com.wangbin.collector.core.collector.protocol.ethernetip.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpTagAddress;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EtherNetIpAddressParser {

    private static final Pattern LOGIX_TYPED_PATTERN = Pattern.compile("^(.+):([A-Z][A-Z0-9_]*)(?:\\[(\\d+)])?$");
    private static final Pattern EIP_SEGMENT_PATTERN = Pattern.compile("^%(.+?)(?::(\\d+))?(?::([A-Z][A-Z0-9_]*))?$");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "BOOL", "BYTE", "SINT", "USINT", "INT", "UINT", "WORD",
            "DINT", "UDINT", "DWORD", "LINT", "ULINT", "LWORD",
            "REAL", "LREAL", "STRING");

    private EtherNetIpAddressParser() {
    }

    public static EtherNetIpTagAddress parse(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }
        String address = firstNonBlank(
                point.getAddress(),
                asString(point.getAdditionalConfig("plc4xAddress")),
                asString(point.getAdditionalConfig("etherNetIpAddress")),
                asString(point.getAdditionalConfig("logixAddress")),
                asString(point.getAdditionalConfig("tagName"))
        );
        return parse(address, point.getDataType(), point.getAdditionalConfig());
    }

    public static EtherNetIpTagAddress parse(String address) {
        return parse(address, null, Collections.emptyMap());
    }

    private static EtherNetIpTagAddress parse(String address, String dataType, Map<String, Object> config) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("EtherNet/IP tag address cannot be empty");
        }
        Map<String, Object> effectiveConfig = config != null ? config : Collections.emptyMap();
        String rawAddress = address.trim();
        String explicitType = normalizeType(firstNonBlank(
                asString(effectiveConfig.get("eipType")),
                asString(effectiveConfig.get("logixType")),
                asString(effectiveConfig.get("plc4xType")),
                asString(effectiveConfig.get("plcType"))
        ));
        String inferredType = explicitType != null ? explicitType : inferType(dataType);

        if (rawAddress.startsWith("%")) {
            return parseEipAddress(rawAddress, inferredType);
        }
        return parseLogixAddress(rawAddress, inferredType);
    }

    private static EtherNetIpTagAddress parseEipAddress(String rawAddress, String inferredType) {
        Matcher matcher = EIP_SEGMENT_PATTERN.matcher(rawAddress.toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported EtherNet/IP symbolic address: " + rawAddress);
        }

        String tagPart = rawAddress.substring(1);
        String working = tagPart;
        String explicitType = null;
        Integer elementCount = null;

        int lastColon = working.lastIndexOf(':');
        if (lastColon >= 0) {
            String tail = working.substring(lastColon + 1).trim().toUpperCase(Locale.ROOT);
            if (SUPPORTED_TYPES.contains(tail)) {
                explicitType = tail;
                working = working.substring(0, lastColon);
            }
        }

        lastColon = working.lastIndexOf(':');
        if (lastColon >= 0) {
            String tail = working.substring(lastColon + 1).trim();
            if (tail.matches("\\d+")) {
                elementCount = Integer.parseInt(tail);
                working = working.substring(0, lastColon);
            }
        }

        String finalType = explicitType != null ? explicitType : inferredType;
        int arraySize = elementCount != null ? Math.max(1, elementCount) : 1;
        StringBuilder plc4xAddress = new StringBuilder("%").append(working);
        if (finalType != null) {
            plc4xAddress.append(':').append(arraySize).append(':').append(finalType);
        } else if (elementCount != null) {
            plc4xAddress.append(':').append(arraySize);
        }

        return new EtherNetIpTagAddress(rawAddress, plc4xAddress.toString(), working, finalType, arraySize);
    }

    private static EtherNetIpTagAddress parseLogixAddress(String rawAddress, String inferredType) {
        Matcher matcher = LOGIX_TYPED_PATTERN.matcher(rawAddress.toUpperCase(Locale.ROOT));
        String tagName = rawAddress;
        String explicitType = null;
        int arraySize = 1;

        if (matcher.matches()) {
            String candidateType = matcher.group(2).toUpperCase(Locale.ROOT);
            if (SUPPORTED_TYPES.contains(candidateType)) {
                int typeSeparator = rawAddress.lastIndexOf(':');
                tagName = rawAddress.substring(0, typeSeparator);
                explicitType = candidateType;
                if (matcher.group(3) != null) {
                    arraySize = Math.max(1, Integer.parseInt(matcher.group(3)));
                }
            }
        }

        String finalType = explicitType != null ? explicitType : inferredType;
        String plc4xAddress = rawAddress;
        if (explicitType == null && finalType != null) {
            plc4xAddress = tagName + ":" + finalType + (arraySize > 1 ? "[" + arraySize + "]" : "");
        }

        return new EtherNetIpTagAddress(rawAddress, plc4xAddress, tagName, finalType, arraySize);
    }

    private static String inferType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return null;
        }
        return switch (dataType.trim().toUpperCase(Locale.ROOT)) {
            case "BOOLEAN", "BOOL" -> "BOOL";
            case "BYTE" -> "BYTE";
            case "INT8", "SINT" -> "SINT";
            case "UINT8", "USINT" -> "USINT";
            case "SHORT", "INT", "INT16" -> "INT";
            case "UINT16", "UINT", "WORD" -> "UINT";
            case "LONG", "INT32", "DINT" -> "DINT";
            case "UINT32", "UDINT", "DWORD" -> "UDINT";
            case "INT64", "LINT" -> "LINT";
            case "UINT64", "ULINT", "LWORD" -> "ULINT";
            case "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE", "REAL" -> "REAL";
            case "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP", "LREAL" -> "LREAL";
            case "STRING" -> "STRING";
            default -> throw new IllegalArgumentException("Unsupported EtherNet/IP data type mapping: " + dataType);
        };
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported EtherNet/IP PLC4X type: " + type);
        }
        return normalized;
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
