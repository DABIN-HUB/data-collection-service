package com.wangbin.collector.core.collector.protocol.knx.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.knx.domain.KnxAddress;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 定义当前模块的业务组件。
 */
public final class KnxAddressParser {

    private static final Pattern DPT_PATTERN = Pattern.compile("^DPT[A-Z0-9._-]+$");

    /**
     * 创建当前组件实例。
     */
    private KnxAddressParser() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static KnxAddress parse(String address) {
        return parse(address, Collections.emptyMap());
    }

    /**
     * 解析或转换业务数据。
     */
    public static KnxAddress parse(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }
        Map<String, Object> config = point.getAdditionalConfig() != null
                ? point.getAdditionalConfig()
                : Collections.emptyMap();
        String address = firstNonBlank(
                point.getAddress(),
                asString(config.get("plc4xAddress")),
                asString(config.get("knxAddress")),
                asString(config.get("groupAddress")));
        return parse(address, config);
    }

    /**
     * 解析或转换业务数据。
     */
    private static KnxAddress parse(String address, Map<String, Object> config) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("KNX group address cannot be empty");
        }

        String rawAddress = address.trim();
        if (rawAddress.contains("*")) {
            throw new IllegalArgumentException("KNX wildcard group addresses are not supported: " + rawAddress);
        }

        Map<String, Object> effectiveConfig = config != null ? config : Collections.emptyMap();
        String[] addressParts = rawAddress.split(":", 2);
        String groupAddress = normalizeGroupAddress(addressParts[0]);
        String dptId = addressParts.length > 1
                ? normalizeDpt(addressParts[1])
                : normalizeDpt(firstNonBlank(
                asString(effectiveConfig.get("dpt")),
                asString(effectiveConfig.get("dptId")),
                asString(effectiveConfig.get("knxDpt"))));

        int levels = validateLevels(groupAddress);
        String plc4xAddress = dptId != null ? groupAddress + ":" + dptId : groupAddress;
        return new KnxAddress(rawAddress, groupAddress, plc4xAddress, levels, dptId);
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalizeGroupAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("KNX group address cannot be empty");
        }

        String normalized = address.trim();
        String[] segments = normalized.split("/");
        if (segments.length < 1 || segments.length > 3) {
            throw new IllegalArgumentException("Unsupported KNX group address format: " + address);
        }

        int[] values = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].matches("\\d+")) {
                throw new IllegalArgumentException("KNX group address must contain only digits: " + address);
            }
            values[i] = Integer.parseInt(segments[i]);
        }

        if (segments.length == 1) {
            validateRange(values[0], 0, 65535, address);
            return String.valueOf(values[0]);
        }
        if (segments.length == 2) {
            validateRange(values[0], 0, 31, address);
            validateRange(values[1], 0, 2047, address);
            return values[0] + "/" + values[1];
        }

        validateRange(values[0], 0, 31, address);
        validateRange(values[1], 0, 7, address);
        validateRange(values[2], 0, 255, address);
        return values[0] + "/" + values[1] + "/" + values[2];
    }

    /**
     * 校验业务条件和参数边界。
     */
    private static int validateLevels(String groupAddress) {
        return groupAddress.split("/").length;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private static void validateRange(int value, int min, int max, String address) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("KNX group address out of range: " + address);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private static String normalizeDpt(String dpt) {
        if (dpt == null || dpt.isBlank()) {
            return null;
        }
        String normalized = dpt.trim().toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("DPT")) {
            normalized = "DPT" + normalized;
        }
        if (!DPT_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Unsupported KNX DPT expression: " + dpt);
        }
        return normalized;
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
