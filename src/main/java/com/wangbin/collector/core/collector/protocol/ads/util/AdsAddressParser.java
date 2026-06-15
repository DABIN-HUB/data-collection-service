package com.wangbin.collector.core.collector.protocol.ads.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ads.domain.AdsAddress;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdsAddressParser {

    private static final Pattern DIRECT_TYPED_PATTERN = Pattern.compile(
            "^((0[xX][0-9a-fA-F]+)|\\d+)/((0[xX][0-9a-fA-F]+)|\\d+):([A-Z][A-Z0-9_]*)(?:\\[(\\d+)])?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_STRING_PATTERN = Pattern.compile(
            "^((0[xX][0-9a-fA-F]+)|\\d+)/((0[xX][0-9a-fA-F]+)|\\d+):(STRING|WSTRING)\\((\\d{1,3})\\)(?:\\[(\\d+)])?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT_BASE_PATTERN = Pattern.compile(
            "^((0[xX][0-9a-fA-F]+)|\\d+)/((0[xX][0-9a-fA-F]+)|\\d+)$",
            Pattern.CASE_INSENSITIVE);

    private AdsAddressParser() {
    }

    public static AdsAddress parse(String address) {
        return parse(address, null, Collections.emptyMap());
    }

    public static AdsAddress parse(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }
        String address = firstNonBlank(
                point.getAddress(),
                asString(point.getAdditionalConfig("plc4xAddress")),
                asString(point.getAdditionalConfig("adsAddress")),
                asString(point.getAdditionalConfig("amsAddress"))
        );
        return parse(address, point.getDataType(), point.getAdditionalConfig());
    }

    private static AdsAddress parse(String address, String dataType, Map<String, Object> config) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("ADS address cannot be empty");
        }

        String rawAddress = address.trim();
        Map<String, Object> effectiveConfig = config != null ? config : Collections.emptyMap();

        Matcher directStringMatcher = DIRECT_STRING_PATTERN.matcher(rawAddress);
        if (directStringMatcher.matches()) {
            String plcType = directStringMatcher.group(5).toUpperCase(Locale.ROOT)
                    + "(" + Integer.parseInt(directStringMatcher.group(6)) + ")";
            int arraySize = parseArraySize(directStringMatcher.group(7), effectiveConfig);
            return new AdsAddress(rawAddress, normalizeDirectStringAddress(directStringMatcher, arraySize),
                    "DIRECT", plcType, arraySize, Integer.parseInt(directStringMatcher.group(6)));
        }

        Matcher directTypedMatcher = DIRECT_TYPED_PATTERN.matcher(rawAddress);
        if (directTypedMatcher.matches()) {
            String plcType = normalizeTypeExpression(directTypedMatcher.group(5), effectiveConfig);
            Integer stringLength = resolveStringLengthIfNeeded(plcType);
            int arraySize = parseArraySize(directTypedMatcher.group(6), effectiveConfig);
            return new AdsAddress(rawAddress, normalizeDirectTypedAddress(directTypedMatcher, plcType, arraySize),
                    "DIRECT", plcType, arraySize, stringLength);
        }

        Matcher directBaseMatcher = DIRECT_BASE_PATTERN.matcher(rawAddress);
        if (directBaseMatcher.matches()) {
            String inferredType = inferTypeExpression(dataType, effectiveConfig);
            Integer stringLength = resolveStringLengthIfNeeded(inferredType);
            int arraySize = resolveArraySize(effectiveConfig, 1);
            return new AdsAddress(rawAddress, buildInferredDirectAddress(directBaseMatcher, inferredType, arraySize),
                    "DIRECT", inferredType, arraySize, stringLength);
        }

        String symbolicType = inferTypeExpressionOrNull(dataType, effectiveConfig);
        int arraySize = resolveArraySize(effectiveConfig, 1);
        return new AdsAddress(rawAddress, rawAddress, "SYMBOLIC", symbolicType, arraySize,
                resolveStringLengthIfNeeded(symbolicType));
    }

    private static String normalizeDirectStringAddress(Matcher matcher, int arraySize) {
        StringBuilder builder = new StringBuilder()
                .append(matcher.group(1))
                .append('/')
                .append(matcher.group(3))
                .append(':')
                .append(matcher.group(5).toUpperCase(Locale.ROOT))
                .append('(')
                .append(Integer.parseInt(matcher.group(6)))
                .append(')');
        if (arraySize > 1) {
            builder.append('[').append(arraySize).append(']');
        }
        return builder.toString();
    }

    private static String normalizeDirectTypedAddress(Matcher matcher, String plcType, int arraySize) {
        StringBuilder builder = new StringBuilder()
                .append(matcher.group(1))
                .append('/')
                .append(matcher.group(3))
                .append(':')
                .append(plcType);
        if (arraySize > 1) {
            builder.append('[').append(arraySize).append(']');
        }
        return builder.toString();
    }

    private static String buildInferredDirectAddress(Matcher matcher, String plcType, int arraySize) {
        StringBuilder builder = new StringBuilder()
                .append(matcher.group(1))
                .append('/')
                .append(matcher.group(3))
                .append(':')
                .append(plcType);
        if (arraySize > 1) {
            builder.append('[').append(arraySize).append(']');
        }
        return builder.toString();
    }

    private static String inferTypeExpression(String dataType, Map<String, Object> config) {
        String inferred = inferTypeExpressionOrNull(dataType, config);
        if (inferred == null) {
            throw new IllegalArgumentException("ADS direct address requires explicit or inferable data type");
        }
        return inferred;
    }

    private static String inferTypeExpressionOrNull(String dataType, Map<String, Object> config) {
        String overrideType = firstNonBlank(
                asString(config.get("adsType")),
                asString(config.get("plc4xType")),
                asString(config.get("plcType"))
        );
        if (overrideType != null) {
            return normalizeTypeExpression(overrideType, config);
        }
        if (dataType == null || dataType.isBlank()) {
            return null;
        }
        return switch (dataType.trim().toUpperCase(Locale.ROOT)) {
            case "BOOLEAN", "BOOL" -> "BOOL";
            case "BYTE", "INT8", "SINT" -> "SINT";
            case "UINT8", "USINT" -> "USINT";
            case "CHAR" -> "BYTE";
            case "SHORT", "INT", "INT16" -> "INT";
            case "UINT16", "UINT", "WORD" -> "UINT";
            case "LONG", "INT32", "DINT" -> "DINT";
            case "UINT32", "UDINT", "DWORD" -> "UDINT";
            case "INT64", "LINT" -> "LINT";
            case "UINT64", "ULINT", "LWORD" -> "ULINT";
            case "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE", "REAL" -> "REAL";
            case "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP", "LREAL" -> "LREAL";
            case "STRING" -> "STRING(" + resolveStringLength(config, 80) + ")";
            case "WSTRING" -> "WSTRING(" + resolveStringLength(config, 80) + ")";
            default -> throw new IllegalArgumentException("Unsupported ADS data type mapping: " + dataType);
        };
    }

    private static String normalizeTypeExpression(String typeExpression, Map<String, Object> config) {
        String normalized = typeExpression.trim().toUpperCase(Locale.ROOT);
        if ("STRING".equals(normalized)) {
            return "STRING(" + resolveStringLength(config, 80) + ")";
        }
        if ("WSTRING".equals(normalized)) {
            return "WSTRING(" + resolveStringLength(config, 80) + ")";
        }
        return normalized;
    }

    private static Integer resolveStringLengthIfNeeded(String plcType) {
        if (plcType == null) {
            return null;
        }
        int start = plcType.indexOf('(');
        int end = plcType.indexOf(')');
        if (start < 0 || end <= start + 1) {
            return null;
        }
        return Integer.parseInt(plcType.substring(start + 1, end));
    }

    private static int resolveStringLength(Map<String, Object> config, int defaultValue) {
        Object value = firstPresent(config, "stringLength", "adsStringLength");
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(value.toString().trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static int resolveArraySize(Map<String, Object> config, int defaultValue) {
        Object value = firstPresent(config, "arraySize", "numberOfElements");
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(value.toString().trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static int parseArraySize(String arrayPart, Map<String, Object> config) {
        if (arrayPart != null && !arrayPart.isBlank()) {
            return Math.max(1, Integer.parseInt(arrayPart.trim()));
        }
        return resolveArraySize(config, 1);
    }

    private static Object firstPresent(Map<String, Object> config, String... keys) {
        for (String key : keys) {
            if (config.containsKey(key)) {
                return config.get(key);
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
}
