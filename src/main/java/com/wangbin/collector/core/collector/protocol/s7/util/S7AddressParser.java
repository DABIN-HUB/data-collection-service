package com.wangbin.collector.core.collector.protocol.s7.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;

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
            String canonicalAddress = canonicalizeTypedAddress(typedMatcher.group(1), typedMatcher.group(2), typedMatcher.group(3));
            return new S7Address(
                    rawAddress,
                    canonicalAddress,
                    detectArea(canonicalAddress),
                    typedMatcher.group(2).toUpperCase(Locale.ROOT),
                    parseArraySize(typedMatcher.group(3))
            );
        }

        Matcher dbTiaMatcher = DB_TIA_PATTERN.matcher(normalized);
        if (dbTiaMatcher.matches()) {
            String typeExpression = inferTypeExpression(dataType, dbTiaMatcher.group(2), effectiveConfig);
            String canonicalAddress = buildDbCanonicalAddress(dbTiaMatcher.group(1), dbTiaMatcher.group(3), dbTiaMatcher.group(4), typeExpression);
            return new S7Address(rawAddress, canonicalAddress, "DB", typeExpression, 1);
        }

        Matcher dbShortMatcher = DB_SHORT_PATTERN.matcher(normalized);
        if (dbShortMatcher.matches()) {
            String typeExpression = dbShortMatcher.group(3) != null
                    ? "BOOL"
                    : inferTypeExpression(dataType, null, effectiveConfig);
            String canonicalAddress = buildDbCanonicalAddress(dbShortMatcher.group(1), dbShortMatcher.group(2), dbShortMatcher.group(3), typeExpression);
            return new S7Address(rawAddress, canonicalAddress, "DB", typeExpression, 1);
        }

        Matcher areaMatcher = AREA_TIA_PATTERN.matcher(normalized);
        if (areaMatcher.matches()) {
            String typeExpression = areaMatcher.group(4) != null
                    ? "BOOL"
                    : inferTypeExpression(dataType, areaMatcher.group(2), effectiveConfig);
            String canonicalAddress = buildAreaCanonicalAddress(areaMatcher.group(1), areaMatcher.group(3), areaMatcher.group(4), typeExpression);
            return new S7Address(rawAddress, canonicalAddress, detectArea(canonicalAddress), typeExpression, 1);
        }

        throw new IllegalArgumentException("Unsupported S7 address format: " + rawAddress);
    }

    private static String canonicalizeTypedAddress(String addressPart, String typePart, String arrayPart) {
        String normalizedAddress = addressPart.startsWith("%")
                ? addressPart.substring(1)
                : addressPart;
        normalizedAddress = normalizedAddress.toUpperCase(Locale.ROOT);
        if (!normalizedAddress.startsWith("DB")) {
            normalizedAddress = "%" + normalizedAddress;
        }
        StringBuilder builder = new StringBuilder(normalizedAddress)
                .append(':')
                .append(typePart.toUpperCase(Locale.ROOT));
        if (arrayPart != null && !arrayPart.isBlank()) {
            builder.append('[').append(arrayPart.trim()).append(']');
        }
        return builder.toString();
    }

    private static String buildDbCanonicalAddress(String dbNumber, String byteOffset, String bitOffset, String typeExpression) {
        if ("BOOL".equalsIgnoreCase(typeExpression)) {
            if (bitOffset == null) {
                throw new IllegalArgumentException("S7 boolean DB address requires a bit offset");
            }
            return "DB" + dbNumber + ":" + byteOffset + "." + bitOffset + ":BOOL";
        }
        return "DB" + dbNumber + ":" + byteOffset + ":" + typeExpression;
    }

    private static String buildAreaCanonicalAddress(String area, String byteOffset, String bitOffset, String typeExpression) {
        if ("BOOL".equalsIgnoreCase(typeExpression)) {
            if (bitOffset == null) {
                throw new IllegalArgumentException("S7 boolean address requires a bit offset");
            }
            return "%" + area + byteOffset + "." + bitOffset + ":BOOL";
        }
        return "%" + area + byteOffset + ":" + typeExpression;
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

        return switch (normalizedDataType) {
            case "BOOLEAN", "BOOL" -> "BOOL";
            case "BYTE", "INT8", "SINT" -> "SINT";
            case "UINT8", "USINT" -> "USINT";
            case "CHAR" -> "CHAR";
            case "SHORT", "INT", "INT16" -> "INT";
            case "UINT16", "UINT", "WORD" -> "UINT";
            case "LONG", "INT32", "DINT" -> "DINT";
            case "UINT32", "UDINT", "DWORD" -> "UDINT";
            case "INT64", "LINT" -> "LINT";
            case "UINT64", "ULINT", "LWORD" -> "ULINT";
            case "FLOAT", "FLOAT32", "FLOAT32_SWAP", "FLOAT32_LITTLE", "REAL" -> "REAL";
            case "FLOAT64", "FLOAT64_SWAP", "FLOAT64_LITTLE", "DOUBLE", "DOUBLE_SWAP", "LREAL" -> "LREAL";
            case "STRING" -> "STRING(" + resolveStringLength(config, 254) + ")";
            case "WSTRING" -> "WSTRING(" + resolveStringLength(config, 254) + ")";
            case "TIME", "LTIME", "DATE", "TIME_OF_DAY", "DATE_AND_TIME", "S5TIME", "WCHAR" -> normalizedDataType;
            default -> throw new IllegalArgumentException("Unsupported S7 data type mapping: " + normalizedDataType);
        };
    }

    private static String normalizeTypeExpression(String typeExpression, Map<String, Object> config) {
        String normalized = typeExpression.trim().toUpperCase(Locale.ROOT);
        if ("STRING".equals(normalized)) {
            return "STRING(" + resolveStringLength(config, 254) + ")";
        }
        if ("WSTRING".equals(normalized)) {
            return "WSTRING(" + resolveStringLength(config, 254) + ")";
        }
        return normalized;
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

    private static int parseArraySize(String arrayPart) {
        if (arrayPart == null || arrayPart.isBlank()) {
            return 1;
        }
        return Integer.parseInt(arrayPart.trim());
    }
}
