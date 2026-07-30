package com.wangbin.collector.core.collector.protocol.fins.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsByteOrder;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsMemoryArea;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsWordOrder;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FinsAddressParser {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "^([A-Za-z0-9]+):(\\d+)(?:\\.(\\d+))?(?:#(\\d+))?$",
            Pattern.CASE_INSENSITIVE);

    private FinsAddressParser() {
    }

    public static FinsAddress parse(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }
        return parse(point.getAddress(), point.getDataType(), point.getAdditionalConfig());
    }

    public static FinsAddress parse(String address, String dataType, Map<String, Object> config) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("FINS address cannot be empty");
        }
        Map<String, Object> effectiveConfig = config != null ? config : Collections.emptyMap();
        Matcher matcher = ADDRESS_PATTERN.matcher(address.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported FINS address format: " + address);
        }

        FinsMemoryArea memoryArea = FinsMemoryArea.fromToken(matcher.group(1));
        int wordAddress = Integer.parseInt(matcher.group(2));
        if (wordAddress < 0 || wordAddress > 65535) {
            throw new IllegalArgumentException("FINS word address must be between 0 and 65535");
        }

        Integer bitOffset = resolveBitOffset(matcher.group(3), effectiveConfig);
        Integer explicitLength = resolvePositiveInt(matcher.group(4), null, "address length", true);
        String normalizedType = normalizeType(resolveType(dataType, effectiveConfig));
        Integer stringLength = "STRING".equals(normalizedType)
                ? resolveStringLength(explicitLength, effectiveConfig)
                : null;
        int elementCount = "STRING".equals(normalizedType)
                ? 1
                : resolveArraySize(explicitLength, effectiveConfig);

        if (bitOffset != null && !"BOOLEAN".equals(normalizedType)) {
            throw new IllegalArgumentException("FINS bit address only supports BOOLEAN/BOOL type");
        }
        if (bitOffset != null && elementCount > 1) {
            throw new IllegalArgumentException("FINS bit address does not support array length");
        }
        if ("STRING".equals(normalizedType) && bitOffset != null) {
            throw new IllegalArgumentException("FINS STRING does not support bit offset");
        }

        FinsByteOrder byteOrder = FinsByteOrder.from(effectiveConfig.get("byteOrder"), FinsByteOrder.BIG_ENDIAN);
        FinsWordOrder wordOrder = FinsWordOrder.from(effectiveConfig.get("wordOrder"), FinsWordOrder.BIG_ENDIAN);
        String canonical = memoryArea.name() + ":" + wordAddress
                + (bitOffset != null ? "." + bitOffset : "")
                + ("STRING".equals(normalizedType) && stringLength != null ? "#" + stringLength : "")
                + (!"STRING".equals(normalizedType) && elementCount > 1 ? "#" + elementCount : "");

        return new FinsAddress(
                address.trim(),
                canonical,
                memoryArea,
                wordAddress,
                bitOffset,
                normalizedType,
                elementCount,
                stringLength,
                byteOrder,
                wordOrder
        );
    }

    public static FinsAddress parse(String address) {
        return parse(address, "UINT16", Collections.emptyMap());
    }

    public static String normalizeType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return "UINT16";
        }
        String normalized = dataType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "BOOL", "BOOLEAN" -> "BOOLEAN";
            case "BYTE", "UINT8", "USINT" -> "UINT8";
            case "INT8", "SINT" -> "INT8";
            case "SHORT", "INT", "INT16" -> "INT16";
            case "UINT", "WORD", "UINT16" -> "UINT16";
            case "LONG", "DINT", "INT32" -> "INT32";
            case "DWORD", "UDINT", "UINT32" -> "UINT32";
            case "LINT", "INT64" -> "INT64";
            case "ULINT", "UINT64" -> "UINT64";
            case "FLOAT", "REAL", "FLOAT32" -> "FLOAT";
            case "DOUBLE", "FLOAT64", "LREAL" -> "DOUBLE";
            case "STRING" -> "STRING";
            default -> throw new IllegalArgumentException("Unsupported FINS dataType: " + dataType);
        };
    }

    private static String resolveType(String dataType, Map<String, Object> config) {
        if (dataType != null && !dataType.isBlank()) {
            return dataType;
        }
        Object driverType = config.get("driverDataType");
        return driverType != null ? driverType.toString() : "UINT16";
    }

    private static Integer resolveBitOffset(String explicitBit, Map<String, Object> config) {
        Integer bitOffset = resolvePositiveInt(explicitBit, null, "bit offset", false);
        if (bitOffset == null && config.containsKey("bitIndex")) {
            bitOffset = resolvePositiveInt(String.valueOf(config.get("bitIndex")), null, "bitIndex", false);
        }
        if (bitOffset != null && bitOffset > 15) {
            throw new IllegalArgumentException("FINS bit offset must be between 0 and 15");
        }
        return bitOffset;
    }

    private static Integer resolveStringLength(Integer explicitLength, Map<String, Object> config) {
        if (explicitLength != null && explicitLength > 0) {
            return explicitLength;
        }
        if (config.containsKey("stringLength")) {
            return resolvePositiveInt(String.valueOf(config.get("stringLength")), null, "stringLength", true);
        }
        throw new IllegalArgumentException("FINS STRING requires address #length or additionalConfig.stringLength");
    }

    private static int resolveArraySize(Integer explicitLength, Map<String, Object> config) {
        if (explicitLength != null && explicitLength > 0) {
            return explicitLength;
        }
        if (config.containsKey("arraySize")) {
            Integer size = resolvePositiveInt(String.valueOf(config.get("arraySize")), null, "arraySize", true);
            return size != null ? size : 1;
        }
        return 1;
    }

    private static Integer resolvePositiveInt(String value, Integer defaultValue, String field, boolean strictPositive) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value.trim());
        if (strictPositive && parsed <= 0) {
            throw new IllegalArgumentException("FINS " + field + " must be greater than 0");
        }
        if (!strictPositive && parsed < 0) {
            throw new IllegalArgumentException("FINS " + field + " must be 0 or greater");
        }
        return parsed;
    }
}