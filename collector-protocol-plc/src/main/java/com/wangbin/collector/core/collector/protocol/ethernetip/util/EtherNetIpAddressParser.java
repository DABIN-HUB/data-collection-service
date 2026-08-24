package com.wangbin.collector.core.collector.protocol.ethernetip.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpPlcType;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpTagAddress;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定义当前模块的业务组件。
 */
public final class EtherNetIpAddressParser {

    private static final Pattern LOGIX_TYPED_PATTERN = Pattern.compile("^(.+):([A-Z][A-Z0-9_]*)(?:\\[(\\d+)])?$");
    private static final Pattern EIP_SEGMENT_PATTERN = Pattern.compile("^%(.+?)(?::(\\d+))?(?::([A-Z][A-Z0-9_]*))?$");

    /**
     * 创建当前组件实例。
     */
    private EtherNetIpAddressParser() {
    }

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 解析或转换业务数据。
     */
    public static EtherNetIpTagAddress parse(String address) {
        return parse(address, null, Collections.emptyMap());
    }

    /**
     * 解析或转换业务数据。
     */
    private static EtherNetIpTagAddress parse(String address, String dataType, Map<String, Object> config) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("EtherNet/IP tag address cannot be empty");
        }
        Map<String, Object> effectiveConfig = config != null ? config : Collections.emptyMap();
        String rawAddress = address.trim();
        EtherNetIpPlcType explicitType = resolveExplicitType(effectiveConfig);
        EtherNetIpPlcType inferredType = explicitType != null ? explicitType : inferType(dataType);

        if (rawAddress.startsWith("%")) {
            return parseEipAddress(rawAddress, inferredType);
        }
        return parseLogixAddress(rawAddress, inferredType);
    }

    /**
     * 解析或转换业务数据。
     */
    private static EtherNetIpTagAddress parseEipAddress(String rawAddress, EtherNetIpPlcType inferredType) {
        Matcher matcher = EIP_SEGMENT_PATTERN.matcher(rawAddress.toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported EtherNet/IP symbolic address: " + rawAddress);
        }

        String tagPart = rawAddress.substring(1);
        String working = tagPart;
        EtherNetIpPlcType explicitType = null;
        Integer elementCount = null;

        int lastColon = working.lastIndexOf(':');
        if (lastColon >= 0) {
            String tail = working.substring(lastColon + 1).trim();
            explicitType = tryParseDriverType(tail);
            if (explicitType != null) {
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

        EtherNetIpPlcType finalType = explicitType != null ? explicitType : inferredType;
        int arraySize = elementCount != null ? Math.max(1, elementCount) : 1;
        StringBuilder plc4xAddress = new StringBuilder("%").append(working);
        if (finalType != null) {
            plc4xAddress.append(':').append(arraySize).append(':').append(finalType.toTypeExpression());
        } else if (elementCount != null) {
            plc4xAddress.append(':').append(arraySize);
        }

        return new EtherNetIpTagAddress(rawAddress, plc4xAddress.toString(), working,
                finalType != null ? finalType.toTypeExpression() : null, arraySize);
    }

    /**
     * 解析或转换业务数据。
     */
    private static EtherNetIpTagAddress parseLogixAddress(String rawAddress, EtherNetIpPlcType inferredType) {
        Matcher matcher = LOGIX_TYPED_PATTERN.matcher(rawAddress.toUpperCase(Locale.ROOT));
        String tagName = rawAddress;
        EtherNetIpPlcType explicitType = null;
        int arraySize = 1;

        if (matcher.matches()) {
            explicitType = tryParseDriverType(matcher.group(2));
            if (explicitType != null) {
                int typeSeparator = rawAddress.lastIndexOf(':');
                tagName = rawAddress.substring(0, typeSeparator);
                if (matcher.group(3) != null) {
                    arraySize = Math.max(1, Integer.parseInt(matcher.group(3)));
                }
            }
        }

        EtherNetIpPlcType finalType = explicitType != null ? explicitType : inferredType;
        String plc4xAddress = rawAddress;
        if (explicitType == null && finalType != null) {
            plc4xAddress = tagName + ":" + finalType.toTypeExpression() + (arraySize > 1 ? "[" + arraySize + "]" : "");
        }

        return new EtherNetIpTagAddress(rawAddress, plc4xAddress, tagName,
                finalType != null ? finalType.toTypeExpression() : null, arraySize);
    }

    /**
     * 执行当前业务逻辑。
     */
    private static EtherNetIpPlcType inferType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return null;
        }
        return EtherNetIpPlcType.fromPlatformDataType(dataType);
    }

    /**
     * 解析或转换业务数据。
     */
    private static EtherNetIpPlcType resolveExplicitType(Map<String, Object> config) {
        String type = firstNonBlank(
                asString(config.get("driverDataType")),
                asString(config.get("eipType")),
                asString(config.get("logixType")),
                asString(config.get("plc4xType")),
                asString(config.get("plcType"))
        );
        return type != null ? EtherNetIpPlcType.fromDriverText(type) : null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private static EtherNetIpPlcType tryParseDriverType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return EtherNetIpPlcType.fromDriverText(text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
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
