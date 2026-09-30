package com.wangbin.collector.core.collector.protocol.ads.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ads.domain.AdsAddress;
import com.wangbin.collector.core.collector.protocol.ads.domain.AdsPlcType;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 校验 ADS 地址、类型及点位配置，并生成 PLC4X 0.13 可解析的地址。
 */
public final class AdsAddressParser {
    private static final String NUMBER = "(?:0[xX][0-9a-fA-F]+|\\d+)";
    private static final Pattern DIRECT = Pattern.compile("^(" + NUMBER + ")/(" + NUMBER
            + ")(?:\u003a([A-Za-z][A-Za-z0-9_]*)(?:\\(([^()]*)\\))?(?:\\[([^\\[\\]]*)])?)?$");
    private static final Pattern SYMBOLIC = Pattern.compile("^[A-Za-z_][A-Za-z_0-9]*(?:\\[\\d+])?(?:\\.[A-Za-z_0-9]+(?:\\[\\d+])?)*$");
    private static final Pattern STRING_TYPE = Pattern.compile("^(STRING|WSTRING)(?:\\(([^()]*)\\))?$", Pattern.CASE_INSENSITIVE);
    private static final String[] DRIVER_KEYS = {"driverDataType", "adsType", "plc4xType", "plcType"};

    private AdsAddressParser() {
    }

    public static AdsAddress parse(String address) {
        return parse(address, null, Collections.emptyMap());
    }

    public static AdsAddress parse(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }
        String address = firstNonBlank(point.getAddress(), asString(point.getAdditionalConfig("plc4xAddress")),
                asString(point.getAdditionalConfig("adsAddress")), asString(point.getAdditionalConfig("amsAddress")));
        return parse(address, point.getDataType(), point.getAdditionalConfig());
    }

    private static AdsAddress parse(String address, String dataType, Map<String, Object> config) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("ADS address cannot be empty");
        }
        String raw = address.trim();
        Map<String, Object> options = config == null ? Collections.emptyMap() : config;
        Matcher direct = DIRECT.matcher(raw);
        if (direct.matches()) {
            // 驱动把两段数值作为 long 解析，但 ADS 索引组和偏移在线路上均为无符号 32 位。
            checkIndex(direct.group(1), "indexGroup");
            checkIndex(direct.group(2), "indexOffset");
            String driverType = configuredDriverType(options);
            String type;
            Integer length = null;
            if (direct.group(3) != null) {
                AdsPlcType explicit = AdsPlcType.fromDriverText(direct.group(3));
                if (driverType != null && AdsPlcType.fromDriverText(driverType) != explicit) {
                    throw new IllegalArgumentException("ADS address type conflicts with driverDataType");
                }
                if (explicit == AdsPlcType.STRING || explicit == AdsPlcType.WSTRING) {
                    length = stringLength(direct.group(4), driverType, options);
                    type = explicit.name() + "(" + length + ")";
                } else {
                    if (direct.group(4) != null) {
                        throw new IllegalArgumentException("ADS string length requires STRING or WSTRING");
                    }
                    type = explicit.toTypeExpression();
                }
            } else {
                type = inferredType(dataType, driverType, options);
                length = typeLength(type);
            }
            int count = arraySize(direct.group(5), options);
            String normalized = direct.group(1) + "/" + direct.group(2) + ":" + type
                    + (direct.group(5) != null || count > 1 ? "[" + count + "]" : "");
            return new AdsAddress(raw, normalized, "DIRECT", type, count, length);
        }
        if (!SYMBOLIC.matcher(raw).matches()) {
            throw new IllegalArgumentException("Invalid ADS address format");
        }
        String type = inferredTypeOrNull(dataType, configuredDriverType(options), options);
        int count = arraySize(null, options);
        if (count != 1) {
            // 0.13 符号标签只接受符号路径，点位计数不能转为驱动数组请求。
            throw new IllegalArgumentException("ADS symbolic address does not support numberOfElements");
        }
        return new AdsAddress(raw, raw, "SYMBOLIC", type, count, typeLength(type));
    }

    private static void checkIndex(String value, String field) {
        try {
            long parsed = value.startsWith("0x") || value.startsWith("0X")
                    ? Long.parseLong(value.substring(2), 16) : Long.parseLong(value);
            if (parsed > 0xffff_ffffL) {
                throw new IllegalArgumentException("ADS " + field + " exceeds unsigned 32-bit range");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid ADS " + field, e);
        }
    }

    private static String configuredDriverType(Map<String, Object> config) {
        String selected = null;
        for (String key : DRIVER_KEYS) {
            String candidate = asString(config.get(key));
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (selected != null && !selected.equalsIgnoreCase(candidate.trim())) {
                throw new IllegalArgumentException("Conflicting ADS driver type settings");
            }
            selected = candidate.trim();
        }
        return selected;
    }

    private static String inferredType(String dataType, String driverType, Map<String, Object> config) {
        String inferred = inferredTypeOrNull(dataType, driverType, config);
        if (inferred == null) {
            throw new IllegalArgumentException("ADS direct address requires explicit or inferable data type");
        }
        return inferred;
    }

    private static String inferredTypeOrNull(String dataType, String driverType, Map<String, Object> config) {
        if (driverType != null) {
            AdsPlcType plcType = AdsPlcType.fromDriverText(driverType);
            if (plcType == AdsPlcType.STRING || plcType == AdsPlcType.WSTRING) {
                Matcher matcher = STRING_TYPE.matcher(driverType);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("Invalid ADS string type expression");
                }
                return plcType.name() + "(" + stringLength(null, driverType, config) + ")";
            }
            return plcType.toTypeExpression();
        }
        if (dataType == null || dataType.isBlank()) {
            return null;
        }
        AdsPlcType plcType = AdsPlcType.fromPlatformDataType(dataType);
        if (plcType == AdsPlcType.STRING || plcType == AdsPlcType.WSTRING) {
            return plcType.name() + "(" + stringLength(null, null, config) + ")";
        }
        return plcType.toTypeExpression();
    }

    private static int stringLength(String rawLength, String driverType, Map<String, Object> config) {
        Integer result = rawLength == null ? null : positive(rawLength, "stringLength", 999);
        if (driverType != null) {
            Matcher matcher = STRING_TYPE.matcher(driverType);
            if (matcher.matches() && matcher.group(2) != null) {
                result = merge(result, positive(matcher.group(2), "driverDataType stringLength", 999), "stringLength");
            }
        }
        result = merge(result, configuredNumber(config, "stringLength", 999), "stringLength");
        result = merge(result, configuredNumber(config, "adsStringLength", 999), "stringLength");
        if (result == null) {
            throw new IllegalArgumentException("ADS STRING/WSTRING requires explicit stringLength");
        }
        return result;
    }

    private static Integer typeLength(String type) {
        if (type == null) {
            return null;
        }
        int open = type.indexOf('(');
        return open < 0 ? null : Integer.parseInt(type.substring(open + 1, type.length() - 1));
    }

    private static int arraySize(String rawCount, Map<String, Object> config) {
        Integer result = rawCount == null ? null : positive(rawCount, "arraySize", Integer.MAX_VALUE);
        result = merge(result, configuredNumber(config, "arraySize", Integer.MAX_VALUE), "arraySize");
        result = merge(result, configuredNumber(config, "numberOfElements", Integer.MAX_VALUE), "arraySize");
        return result == null ? 1 : result;
    }

    private static Integer configuredNumber(Map<String, Object> config, String key, int max) {
        if (!config.containsKey(key)) {
            return null;
        }
        Object value = config.get(key);
        if (value == null || value instanceof Float || value instanceof Double) {
            throw new IllegalArgumentException("Invalid ADS " + key);
        }
        return positive(value.toString().trim(), key, max);
    }

    private static int positive(String text, String field, int max) {
        if (!text.matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid ADS " + field);
        }
        try {
            int value = Integer.parseInt(text);
            if (value <= 0 || value > max) {
                throw new IllegalArgumentException("ADS " + field + " out of range");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid ADS " + field, e);
        }
    }

    private static Integer merge(Integer first, Integer second, String field) {
        if (first != null && second != null && !first.equals(second)) {
            throw new IllegalArgumentException("Conflicting ADS " + field);
        }
        return first != null ? first : second;
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
        return value == null ? null : value.toString();
    }
}
