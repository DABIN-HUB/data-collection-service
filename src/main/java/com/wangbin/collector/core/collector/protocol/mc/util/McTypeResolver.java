package com.wangbin.collector.core.collector.protocol.mc.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDriverType;

import java.util.Collections;
import java.util.Map;

public final class McTypeResolver {

    private McTypeResolver() {
    }

    public static McDriverType resolveRequired(DataPoint point, McDeviceCode deviceCode) {
        String platformDataType = point != null ? point.getDataType() : null;
        Map<String, Object> config = point != null && point.getAdditionalConfig() != null
                ? point.getAdditionalConfig()
                : Collections.emptyMap();
        return resolveRequired(platformDataType, config, deviceCode, false);
    }

    public static McDriverType resolveRequired(String platformDataType,
                                               Map<String, Object> config,
                                               McDeviceCode deviceCode) {
        return resolveRequired(platformDataType, config, deviceCode, false);
    }

    public static McDriverType resolveRequired(String platformDataType,
                                               Map<String, Object> config,
                                               McDeviceCode deviceCode,
                                               boolean bitOffset) {
        Map<String, Object> effectiveConfig = config != null ? config : Collections.emptyMap();
        String configuredType = firstNonBlank(
                asString(effectiveConfig.get("driverDataType")),
                asString(effectiveConfig.get("mcType")),
                asString(effectiveConfig.get("plcType"))
        );
        McDriverType resolved;
        if (configuredType != null) {
            resolved = McDriverType.fromDriverText(configuredType);
        } else if (platformDataType != null && !platformDataType.isBlank()) {
            resolved = McDriverType.fromPlatformDataType(platformDataType);
        } else if (deviceCode != null && deviceCode.isBitDevice()) {
            resolved = McDriverType.BOOL;
        } else {
            throw new IllegalArgumentException("MC address requires driverDataType or platform dataType");
        }

        validateCompatibility(deviceCode, resolved, effectiveConfig, bitOffset);
        return resolved;
    }

    public static void validateCompatibility(McDeviceCode deviceCode,
                                             McDriverType driverType,
                                             Map<String, Object> config) {
        validateCompatibility(deviceCode, driverType, config, false);
    }

    public static void validateCompatibility(McDeviceCode deviceCode,
                                             McDriverType driverType,
                                             Map<String, Object> config,
                                             boolean bitOffset) {
        if (deviceCode == null || driverType == null) {
            return;
        }
        if (bitOffset) {
            if (deviceCode.isBitDevice()) {
                throw new IllegalArgumentException("MC bit offset syntax only supports word devices");
            }
            if (driverType != McDriverType.BOOL) {
                throw new IllegalArgumentException("MC bit offset address only supports BOOL");
            }
            return;
        }
        if (deviceCode.isBitDevice() && driverType != McDriverType.BOOL) {
            throw new IllegalArgumentException("MC bit devices only support BOOL in P0");
        }
        if (deviceCode.isWordDevice() && driverType == McDriverType.BOOL) {
            throw new IllegalArgumentException("MC word devices do not support BOOL in P0; use M/X/Y/B");
        }
        if (driverType == McDriverType.STRING) {
            Integer stringLength = resolveStringLength(config);
            if (stringLength == null || stringLength <= 0) {
                throw new IllegalArgumentException("MC STRING requires additionalConfig.stringLength");
            }
        }
    }

    public static Integer resolveStringLength(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        Object value = firstPresent(config, "stringLength", "mcStringLength");
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(value.toString().trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
