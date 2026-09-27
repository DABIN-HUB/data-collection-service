package com.wangbin.collector.core.collector.protocol.s7.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7PlcType;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定义当前模块的业务组件。
 */
public final class S7AddressParser {

    private static final Pattern DB_TIA_PATTERN = Pattern.compile("^DB(\\d+)\\.DB([XBWD])(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern DB_SHORT_PATTERN = Pattern.compile("^DB(\\d+):(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern DB_COLON_TIA_PATTERN = Pattern.compile("^DB(\\d+):DB([XBWD])(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern AREA_TIA_PATTERN = Pattern.compile("^([IQM])([BWD]?)(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern STRING_TYPE_PATTERN = Pattern.compile("^(W?STRING)(?:\\((\\d+)\\))?$");
    private static final Pattern TYPED_PATTERN = Pattern.compile(
            "^(%?DB\\d+(?::(?:DB[XBWD])?\\d+(?:\\.\\d+)?|\\.DB[XBWD]\\d+(?:\\.\\d+)?)?|%?[IQM](?:\\d+(?:\\.\\d+)?|[BWD]\\d+)|%?[CDTL]\\d+(?:\\.\\d+)?)"
                    + ":(BOOL|BYTE|WORD|DWORD|LWORD|SINT|USINT|INT|UINT|DINT|UDINT|LINT|ULINT|REAL|LREAL|CHAR|WCHAR|STRING(?:\\(\\d+\\))?|WSTRING(?:\\(\\d+\\))?|TIME|LTIME|DATE|TIME_OF_DAY|DATE_AND_TIME|S5TIME)"
                    + "(?:\\[(\\d+)])?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 创建当前组件实例。
     */
    private S7AddressParser() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static S7Address parse(String address) {
        return parse(address, null, Collections.emptyMap());
    }

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 解析或转换业务数据。
     */
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
            String typeExpression = normalizeTypeExpression(typedMatcher.group(2), effectiveConfig);
            String configuredType = configuredDriverType(effectiveConfig);
            if (configuredType != null && !typeExpression.equals(normalizeTypeExpression(configuredType, effectiveConfig))) {
                throw new IllegalArgumentException("S7 address type conflicts with driverDataType: " + rawAddress);
            }
            return parseLocation(rawAddress, typedMatcher.group(1), typeExpression, arraySize);
        }

        int arraySize = resolveArraySize(null, effectiveConfig);
        return parseLocation(rawAddress, normalized, inferLocationType(normalized, dataType, effectiveConfig), arraySize);
    }

    /**
     * 将不同写法的地址转换为同一驱动地址，并检查位偏移及地址宽度。
     */
    private static S7Address parseLocation(String rawAddress, String addressPart, String typeExpression, int arraySize) {
        String normalized = addressPart.startsWith("%") ? addressPart.substring(1) : addressPart;
        Matcher dbTiaMatcher = DB_TIA_PATTERN.matcher(normalized);
        if (dbTiaMatcher.matches()) {
            validateLocation(dbTiaMatcher.group(2), dbTiaMatcher.group(4), typeExpression);
            String canonicalAddress = buildDbCanonicalAddress(dbTiaMatcher.group(1), dbTiaMatcher.group(3), dbTiaMatcher.group(4), typeExpression, arraySize);
            return new S7Address(rawAddress, canonicalAddress, "DB", typeExpression, arraySize);
        }
        Matcher dbColonTiaMatcher = DB_COLON_TIA_PATTERN.matcher(normalized);
        if (dbColonTiaMatcher.matches()) {
            validateLocation(dbColonTiaMatcher.group(2), dbColonTiaMatcher.group(4), typeExpression);
            String canonicalAddress = buildDbCanonicalAddress(dbColonTiaMatcher.group(1), dbColonTiaMatcher.group(3),
                    dbColonTiaMatcher.group(4), typeExpression, arraySize);
            return new S7Address(rawAddress, canonicalAddress, "DB", typeExpression, arraySize);
        }

        Matcher dbShortMatcher = DB_SHORT_PATTERN.matcher(normalized);
        if (dbShortMatcher.matches()) {
            validateLocation(null, dbShortMatcher.group(3), typeExpression);
            String canonicalAddress = buildDbCanonicalAddress(dbShortMatcher.group(1), dbShortMatcher.group(2), dbShortMatcher.group(3), typeExpression, arraySize);
            return new S7Address(rawAddress, canonicalAddress, "DB", typeExpression, arraySize);
        }

        Matcher areaMatcher = AREA_TIA_PATTERN.matcher(normalized);
        if (areaMatcher.matches()) {
            validateLocation(areaMatcher.group(2), areaMatcher.group(4), typeExpression);
            String canonicalAddress = buildAreaCanonicalAddress(areaMatcher.group(1), areaMatcher.group(3), areaMatcher.group(4), typeExpression, arraySize);
            return new S7Address(rawAddress, canonicalAddress, detectArea(canonicalAddress), typeExpression, arraySize);
        }

        if (normalized.matches("^[CDTL]\\d+(?:\\.\\d+)?$")) {
            StringBuilder canonicalAddress = new StringBuilder("%").append(normalized).append(':').append(typeExpression);
            appendArraySuffix(canonicalAddress, arraySize);
            return new S7Address(rawAddress, canonicalAddress.toString(), "TAG", typeExpression, arraySize);
        }
        throw new IllegalArgumentException("Unsupported S7 address format: " + rawAddress);
    }

    /**
     * 按类型显式声明优先于平台类型的规则解析非类型化地址。
     */
    private static String inferLocationType(String addressPart, String dataType, Map<String, Object> config) {
        String normalized = addressPart.startsWith("%") ? addressPart.substring(1) : addressPart;
        Matcher dbTia = DB_TIA_PATTERN.matcher(normalized);
        if (dbTia.matches()) {
            return inferTypeExpression(dataType, dbTia.group(2), config);
        }
        Matcher dbShort = DB_SHORT_PATTERN.matcher(normalized);
        if (dbShort.matches()) {
            return inferTypeExpression(dataType, dbShort.group(3) != null ? "X" : null, config);
        }
        Matcher area = AREA_TIA_PATTERN.matcher(normalized);
        if (area.matches()) {
            return inferTypeExpression(dataType, area.group(4) != null ? "X" : area.group(2), config);
        }
        throw new IllegalArgumentException("Unsupported S7 address format: " + addressPart);
    }

    /**
     * 位地址只能读写布尔量，显式 B/W/D 前缀不得与数据宽度冲突。
     */
    private static void validateLocation(String widthCode, String bitOffset, String typeExpression) {
        String baseType = S7PlcType.fromText(typeExpression).name();
        boolean bool = "BOOL".equals(baseType);
        if (bitOffset != null) {
            int bit = parsePositiveInt(bitOffset, "S7 bit offset", false);
            if (bit > 7 || !bool) {
                throw new IllegalArgumentException("S7 bit offset requires BOOL and a value from 0 to 7");
            }
        } else if (bool || "X".equals(widthCode)) {
            throw new IllegalArgumentException("S7 boolean address requires a bit offset");
        }
        if (widthCode != null && !widthCode.isEmpty() && !"X".equals(widthCode)) {
            int width = switch (widthCode) {
                case "B" -> 1;
                case "W" -> 2;
                case "D" -> 4;
                default -> throw new IllegalArgumentException("Unsupported S7 address width: " + widthCode);
            };
            int typeWidth = switch (baseType) {
                case "SINT", "USINT", "CHAR", "STRING", "WSTRING" -> 1;
                case "INT", "UINT", "WCHAR", "DATE", "S5TIME" -> 2;
                case "DINT", "UDINT", "REAL", "TIME", "TIME_OF_DAY" -> 4;
                case "LINT", "ULINT", "LREAL", "LTIME", "DATE_AND_TIME" -> 8;
                default -> 0;
            };
            if (typeWidth != width) {
                throw new IllegalArgumentException("S7 address width " + widthCode + " conflicts with type " + typeExpression);
            }
        }
    }

    /**
     * 创建并返回业务对象。
     */
    private static String buildDbCanonicalAddress(String dbNumber, String byteOffset, String bitOffset, String typeExpression, int arraySize) {
        StringBuilder builder;
        if ("BOOL".equalsIgnoreCase(typeExpression)) {
            if (bitOffset == null) {
                throw new IllegalArgumentException("S7 boolean DB address requires a bit offset");
            }
            builder = new StringBuilder("%DB")
                    .append(parsePositiveInt(dbNumber, "S7 DB number", false))
                    .append(':')
                    .append(parsePositiveInt(byteOffset, "S7 byte offset", false))
                    .append('.')
                    .append(parsePositiveInt(bitOffset, "S7 bit offset", false))
                    .append(":BOOL");
        } else {
            builder = new StringBuilder("%DB")
                    .append(parsePositiveInt(dbNumber, "S7 DB number", false))
                    .append(':')
                    .append(parsePositiveInt(byteOffset, "S7 byte offset", false))
                    .append(':')
                    .append(typeExpression);
        }
        appendArraySuffix(builder, arraySize);
        return builder.toString();
    }

    /**
     * 创建并返回业务对象。
     */
    private static String buildAreaCanonicalAddress(String area, String byteOffset, String bitOffset, String typeExpression, int arraySize) {
        StringBuilder builder;
        if ("BOOL".equalsIgnoreCase(typeExpression)) {
            if (bitOffset == null) {
                throw new IllegalArgumentException("S7 boolean address requires a bit offset");
            }
            builder = new StringBuilder("%")
                    .append(area)
                    .append(parsePositiveInt(byteOffset, "S7 byte offset", false))
                    .append('.')
                    .append(parsePositiveInt(bitOffset, "S7 bit offset", false))
                    .append(":BOOL");
        } else {
            builder = new StringBuilder("%")
                    .append(area)
                    .append(parsePositiveInt(byteOffset, "S7 byte offset", false))
                    .append(':')
                    .append(typeExpression);
        }
        appendArraySuffix(builder, arraySize);
        return builder.toString();
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    private static String inferTypeExpression(String dataType, String shortCode, Map<String, Object> config) {
        String overrideType = configuredDriverType(config);
        if (overrideType != null) {
            return normalizeTypeExpression(overrideType, config);
        }

        String normalizedDataType = dataType != null ? dataType.trim().toUpperCase(Locale.ROOT) : null;
        if (normalizedDataType == null || normalizedDataType.isBlank()) {
            normalizedDataType = switch (shortCode == null ? "" : shortCode.toUpperCase(Locale.ROOT)) {
                case "B" -> "BYTE";
                case "W" -> "INT";
                case "D" -> "DINT";
                default -> "BOOL";
            };
        }

        return normalizeTypeExpression(normalizedDataType, config);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalizeTypeExpression(String typeExpression, Map<String, Object> config) {
        String normalized = typeExpression.trim().toUpperCase(Locale.ROOT);
        Matcher stringType = STRING_TYPE_PATTERN.matcher(normalized);
        if (stringType.matches()) {
            int length = stringType.group(2) == null ? resolveStringLength(config, 254)
                    : parsePositiveInt(stringType.group(2), "S7 string length", true);
            Integer configured = configuredStringLength(config);
            if (configured != null && configured != length) {
                throw new IllegalArgumentException("S7 encoded string length conflicts with stringLength");
            }
            return stringType.group(1) + "(" + length + ")";
        }
        if (normalized.startsWith("STRING(") || normalized.startsWith("WSTRING(")) {
            throw new IllegalArgumentException("Invalid S7 string length: " + typeExpression);
        }
        S7PlcType plcType = S7PlcType.fromText(typeExpression);
        return plcType.toTypeExpression();
    }

    /**
     * 解析或转换业务数据。
     */
    private static int resolveStringLength(Map<String, Object> config, int defaultValue) {
        Integer configured = configuredStringLength(config);
        return configured == null ? defaultValue : configured;
    }

    private static Integer configuredStringLength(Map<String, Object> config) {
        Integer length = null;
        for (String key : new String[]{"stringLength", "s7StringLength"}) {
            if (config.containsKey(key)) {
                int candidate = parsePositiveInt(asString(config.get(key)), "S7 string length", true);
                if (length != null && length != candidate) {
                    throw new IllegalArgumentException("Conflicting S7 string length configuration");
                }
                length = candidate;
            }
        }
        return length;
    }

    private static String configuredDriverType(Map<String, Object> config) {
        return firstNonBlank(asString(config.get("driverDataType")), asString(config.get("s7Type")),
                asString(config.get("plc4xType")), asString(config.get("plcType")));
    }

    private static int parsePositiveInt(String value, String field, boolean strictlyPositive) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0 || (strictlyPositive && parsed == 0)) {
                throw new IllegalArgumentException(field + " must be greater than " + (strictlyPositive ? "0" : "or equal to 0"));
            }
            return parsed;
        } catch (NullPointerException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + field + ": " + value, e);
        }
    }


    /**
     * 执行当前业务逻辑。
     */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * 解析或转换业务数据。
     */
    private static int resolveArraySize(String explicitArrayPart, Map<String, Object> config) {
        Integer size = explicitArrayPart == null ? null : parseArraySize(explicitArrayPart);
        for (String key : new String[]{"arraySize", "s7ArraySize"}) {
            if (config.containsKey(key)) {
                int configured = parseArraySize(asString(config.get(key)));
                if (size != null && size != configured) {
                    throw new IllegalArgumentException("S7 array size conflicts with " + key);
                }
                size = configured;
            }
        }
        return size == null ? 1 : size;
    }

    /**
     * 解析或转换业务数据。
     */
    private static int parseArraySize(String arrayPart) {
        int arraySize = parsePositiveInt(arrayPart, "S7 array size", false);
        if (arraySize == 0) {
            throw new IllegalArgumentException("S7 array size must be greater than 0");
        }
        return arraySize;
    }

    /**
     * 写入或持久化业务数据。
     */
    private static void appendArraySuffix(StringBuilder builder, int arraySize) {
        if (arraySize > 1) {
            builder.append('[').append(arraySize).append(']');
        }
    }
}
