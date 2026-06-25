package com.wangbin.collector.core.collector.protocol.s7.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7PlcType;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class S7AddressParser {

    private static final Pattern DB_TIA_PATTERN = Pattern.compile("^DB(\\d+)\\.DB([XBWD])(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern DB_SHORT_PATTERN = Pattern.compile("^DB(\\d+):(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern AREA_TIA_PATTERN = Pattern.compile("^([IQM])([BWD]?)(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern TYPED_PATTERN = Pattern.compile(
            "^(%?DB\\d+(?::(?:DB[XBWD])?\\d+(?:\\.\\d+)?|\\.DB[XBWD]\\d+(?:\\.\\d+)?)?|%?[IQM](?:\\d+(?:\\.\\d+)?|[BWD]\\d+)|%?[CDTL]\\d+(?:\\.\\d+)?)"
                    + ":(BOOL|BYTE|WORD|DWORD|LWORD|SINT|USINT|INT|UINT|DINT|UDINT|LINT|ULINT|REAL|LREAL|CHAR|WCHAR|STRING(?:\\(\\d+\\))?|WSTRING(?:\\(\\d+\\))?|TIME|LTIME|DATE|TIME_OF_DAY|DATE_AND_TIME|S5TIME)"
                    + "(?:\\[(\\d+)])?$",
            Pattern.CASE_INSENSITIVE);

    private S7AddressParser() {
    }

    public static S7Address parse(String address) {
        return parse(address, null, Collections.emptyMap());
    }

    public static S7Address parse(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }
        String address = firstNonBlank(
                point.getAddress(),
                asString(point.getAdditionalConfig("plc4xAddress")),
                asString(point.getAdditionalConfig("s7Address"))
        );
        return parse(address, point.getDataType(), point.getAdditionalConfig());
    }

    private static S7Address parse(String address, String dataType, Map<String, Object> config) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("S7 address cannot be empty");
        }

        String rawAddress = address.trim();
        String normalized = rawAddress.toUpperCase(Locale.ROOT);
        Map<String, Object> effectiveConfig = config != null ? config : Collections.emptyMap();

        Matcher typedMatcher = TYPED_PATTERN.matcher(normalized);
        if (typedMatcher.matches()) {
            int arraySize = resolveArraySize(typedMatcher.group(3), effectiveConfig);
            String canonicalAddress = canonicalizeTypedAddress(typedMatcher.group(1), typedMatcher.group(2), arraySize);
            return new S7Address(
                    rawAddress,
                    canonicalAddress,
                    detectArea(canonicalAddress),
                    typedMatcher.group(2).toUpperCase(Locale.ROOT),
                    arraySize
            );
        }

        Matcher dbTiaMatcher = DB_TIA_PATTERN.matcher(normalized);
        if (dbTiaMatcher.matches()) {
            String typeExpression = inferTypeExpression(dataType, dbTiaMatcher.group(2), effectiveConfig);
            int arraySize = resolveArraySize(null, effectiveConfig);
            String canonicalAddress = buildDbCanonicalAddress(dbTiaMatcher.group(1), dbTiaMatcher.group(3), dbTiaMatcher.group(4), typeExpression, arraySize);
            return new S7Address(rawAddress, canonicalAddress, "DB", typeExpression, arraySize);
        }

        Matcher dbShortMatcher = DB_SHORT_PATTERN.matcher(normalized);
        if (dbShortMatcher.matches()) {
            String typeExpression = dbShortMatcher.group(3) != null
                    ? "BOOL"
                    : inferTypeExpression(dataType, null, effectiveConfig);
            int arraySize = resolveArraySize(null, effectiveConfig);
            String canonicalAddress = buildDbCanonicalAddress(dbShortMatcher.group(1), dbShortMatcher.group(2), dbShortMatcher.group(3), typeExpression, arraySize);
            return new S7Address(rawAddress, canonicalAddress, "DB", typeExpression, arraySize);
        }

        Matcher areaMatcher = AREA_TIA_PATTERN.matcher(normalized);
        if (areaMatcher.matches()) {
            String typeExpression = areaMatcher.group(4) != null
                    ? "BOOL"
                    : inferTypeExpression(dataType, areaMatcher.group(2), effectiveConfig);
            int arraySize = resolveArraySize(null, effectiveConfig);
            String canonicalAddress = buildAreaCanonicalAddress(areaMatcher.group(1), areaMatcher.group(3), areaMatcher.group(4), typeExpression, arraySize);
            return new S7Address(rawAddress, canonicalAddress, detectArea(canonicalAddress), typeExpression, arraySize);
        }

        throw new IllegalArgumentException("Unsupported S7 address format: " + rawAddress);
    }

    private static String canonicalizeTypedAddress(String addressPart, String typePart, int arraySize) {
        boolean explicitPercent = addressPart.startsWith("%");
        String normalizedAddress = addressPart.toUpperCase(Locale.ROOT);
        if (!explicitPercent && !normalizedAddress.startsWith("DB")) {
            normalizedAddress = "%" + normalizedAddress;
        }
        StringBuilder builder = new StringBuilder(normalizedAddress)
                .append(':')
                .append(typePart.toUpperCase(Locale.ROOT));
        appendArraySuffix(builder, arraySize);
        return builder.toString();
    }

    private static String buildDbCanonicalAddress(String dbNumber, String byteOffset, String bitOffset, String typeExpression, int arraySize) {
        StringBuilder builder;
        if ("BOOL".equalsIgnoreCase(typeExpression)) {
            if (bitOffset == null) {
                throw new IllegalArgumentException("S7 boolean DB address requires a bit offset");
            }
            builder = new StringBuilder("DB")
                    .append(dbNumber)
                    .append(':')
                    .append(byteOffset)
                    .append('.')
                    .append(bitOffset)
                    .append(":BOOL");
        } else {
            builder = new StringBuilder("DB")
                    .append(dbNumber)
                    .append(':')
                    .append(byteOffset)
                    .append(':')
                    .append(typeExpression);
        }
        appendArraySuffix(builder, arraySize);
        return builder.toString();
    }

    private static String buildAreaCanonicalAddress(String area, String byteOffset, String bitOffset, String typeExpression, int arraySize) {
        StringBuilder builder;
        if ("BOOL".equalsIgnoreCase(typeExpression)) {
            if (bitOffset == null) {
                throw new IllegalArgumentException("S7 boolean address requires a bit offset");
            }
            builder = new StringBuilder("%")
                    .append(area)
                    .append(byteOffset)
                    .append('.')
                    .append(bitOffset)
                    .append(":BOOL");
        } else {
            builder = new StringBuilder("%")
                    .append(area)
                    .append(byteOffset)
                    .append(':')
                    .append(typeExpression);
        }
        appendArraySuffix(builder, arraySize);
        return builder.toString();
    }

    private static String detectArea(String normalized) {
        String candidate = normalized.startsWith("%") ? normalized.substring(1) : normalized;
        if (candidate.startsWith("DB")) {
            return "DB";
        }
        if (candidate.startsWith("I")) {
            return "INPUT";
        }
        if (candidate.startsWith("Q")) {
            return "OUTPUT";
        }
        if (candidate.startsWith("M")) {
            return "MERKER";
        }
        return "TAG";
    }

    private static String inferTypeExpression(String dataType, String shortCode, Map<String, Object> config) {
        String overrideType = firstNonBlank(
                asString(config.get("driverDataType")),
                asString(config.get("s7Type")),
                asString(config.get("plc4xType")),
                asString(config.get("plcType"))
        );
        if (overrideType != null) {
            return normalizeTypeExpression(overrideType, config);
        }

        String normalizedDataType = dataType != null ? dataType.trim().toUpperCase(Locale.ROOT) : null;
        if (normalizedDataType == null || normalizedDataType.isBlank()) {
            normalizedDataType = switch (Objects.toString(shortCode, "").toUpperCase(Locale.ROOT)) {
                case "B" -> "BYTE";
                case "W" -> "INT";
                case "D" -> "DINT";
                default -> "BOOL";
            };
        }

        return normalizeTypeExpression(normalizedDataType, config);
    }

    private static String normalizeTypeExpression(String typeExpression, Map<String, Object> config) {
        S7PlcType plcType = S7PlcType.fromText(typeExpression);
        if (plcType == S7PlcType.STRING) {
            return "STRING(" + resolveStringLength(config, 254) + ")";
        }
        if (plcType == S7PlcType.WSTRING) {
            return "WSTRING(" + resolveStringLength(config, 254) + ")";
        }
        return plcType.toTypeExpression();
    }

    private static int resolveStringLength(Map<String, Object> config, int defaultValue) {
        Object value = firstPresent(config, "stringLength", "s7StringLength");
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

    private static Object firstPresent(Map<String, Object> config, String... keys) {
        for (String key : keys) {
            if (config.containsKey(key)) {
                return config.get(key);
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
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

    private static int resolveArraySize(String explicitArrayPart, Map<String, Object> config) {
        if (explicitArrayPart != null && !explicitArrayPart.isBlank()) {
            return parseArraySize(explicitArrayPart);
        }
        Object configured = firstPresent(config, "arraySize", "s7ArraySize");
        if (configured == null) {
            return 1;
        }
        return parseArraySize(String.valueOf(configured));
    }

    private static int parseArraySize(String arrayPart) {
        if (arrayPart == null || arrayPart.isBlank()) {
            return 1;
        }
        int arraySize = Integer.parseInt(arrayPart.trim());
        if (arraySize <= 0) {
            throw new IllegalArgumentException("S7 array size must be greater than 0");
        }
        return arraySize;
    }

    private static void appendArraySuffix(StringBuilder builder, int arraySize) {
        if (arraySize > 1) {
            builder.append('[').append(arraySize).append(']');
        }
    }
}
