package com.wangbin.collector.core.collector.protocol.mc.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDriverType;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定义当前模块的业务组件。
 */
public final class McAddressParser {

    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "^(ZR|[MXYBDRW])([0-9A-Fa-f]+)(?:\\.(\\d+))?(?:\\[(\\d+)])?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 创建当前组件实例。
     */
    private McAddressParser() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static McAddress parse(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }
        String address = firstNonBlank(
                point.getAddress(),
                asString(point.getAdditionalConfig("mcAddress")),
                asString(point.getAdditionalConfig("deviceAddress"))
        );
        return parse(address, point.getDataType(), point.getAdditionalConfig());
    }

    /**
     * 解析或转换业务数据。
     */
    public static McAddress parse(String address) {
        return parse(address, null, Collections.emptyMap());
    }

    /**
     * 解析或转换业务数据。
     */
    private static McAddress parse(String address, String platformDataType, Map<String, Object> config) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("MC address cannot be empty");
        }

        Map<String, Object> effectiveConfig = config != null ? config : Collections.emptyMap();
        String rawAddress = address.trim();
        Matcher matcher = ADDRESS_PATTERN.matcher(rawAddress);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported MC address format: " + rawAddress);
        }

        McDeviceCode deviceCode = McDeviceCode.fromPrefix(matcher.group(1));
        int deviceNumber = Integer.parseInt(matcher.group(2), deviceCode.getRadix());
        Integer bitIndex = resolveBitIndex(matcher.group(3), effectiveConfig);
        int arraySize = resolveArraySize(matcher.group(4), effectiveConfig);
        McDriverType driverType = McTypeResolver.resolveRequired(platformDataType, effectiveConfig, deviceCode, bitIndex != null);
        Integer stringLength = driverType == McDriverType.STRING
                ? McTypeResolver.resolveStringLength(effectiveConfig)
                : null;

        if (driverType == McDriverType.STRING && arraySize > 1) {
            throw new IllegalArgumentException("MC STRING does not support array syntax in P0");
        }
        if (bitIndex != null && arraySize > 1) {
            throw new IllegalArgumentException("MC bit offset address does not support array syntax");
        }

        return new McAddress(
                rawAddress,
                canonicalize(deviceCode, deviceNumber, bitIndex, arraySize),
                deviceCode,
                deviceNumber,
                driverType,
                arraySize,
                stringLength,
                bitIndex
        );
    }

    /**
     * 执行当前业务逻辑。
     */
    private static String canonicalize(McDeviceCode deviceCode, int deviceNumber, Integer bitIndex, int arraySize) {
        String addressNumber = Integer.toString(deviceNumber, deviceCode.getRadix()).toUpperCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(deviceCode.getSymbol()).append(addressNumber);
        if (bitIndex != null) {
            builder.append('.').append(bitIndex);
        }
        if (arraySize > 1) {
            builder.append('[').append(arraySize).append(']');
        }
        return builder.toString();
    }

    /**
     * 解析或转换业务数据。
     */
    private static Integer resolveBitIndex(String explicitBitIndex, Map<String, Object> config) {
        String value = explicitBitIndex;
        if ((value == null || value.isBlank()) && config != null && !config.isEmpty()) {
            Object configured = firstPresent(config, "bitIndex", "mcBitIndex");
            if (configured != null) {
                value = String.valueOf(configured);
            }
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        int parsed = Integer.parseInt(value.trim());
        if (parsed < 0 || parsed > 15) {
            throw new IllegalArgumentException("MC bit offset must be between 0 and 15");
        }
        return parsed;
    }

    /**
     * 解析或转换业务数据。
     */
    private static int resolveArraySize(String explicitArraySize, Map<String, Object> config) {
        if (explicitArraySize != null && !explicitArraySize.isBlank()) {
            return parseArraySize(explicitArraySize);
        }
        Object configured = firstPresent(config, "arraySize", "mcArraySize");
        if (configured == null) {
            return 1;
        }
        return parseArraySize(String.valueOf(configured));
    }

    /**
     * 解析或转换业务数据。
     */
    private static int parseArraySize(String value) {
        int parsed = Integer.parseInt(value.trim());
        if (parsed <= 0) {
            throw new IllegalArgumentException("MC array size must be greater than 0");
        }
        return parsed;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static Object firstPresent(Map<String, Object> config, String... keys) {
        for (String key : keys) {
            if (config.containsKey(key)) {
                return config.get(key);
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }
}
