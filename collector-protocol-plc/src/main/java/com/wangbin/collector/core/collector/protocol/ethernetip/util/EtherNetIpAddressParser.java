package com.wangbin.collector.core.collector.protocol.ethernetip.util;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpPlcType;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpTagAddress;
import org.apache.plc4x.java.eip.base.tag.EipTag;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 Logix 符号地址及原生 PLC4X 地址转换为驱动实际接受的 TYPE:COUNT 格式。
 */
public final class EtherNetIpAddressParser {
    private static final Pattern ADDRESS = Pattern.compile("^([%a-zA-Z_.0-9]+(?:\\[([0-9]+)])?)(?::([A-Z]+))?(?::([0-9]+))?$");
    private static final Pattern PROGRAM_ADDRESS = Pattern.compile(
            "^(Program:[A-Za-z_][A-Za-z_0-9]*(?:\\.[A-Za-z_][A-Za-z_0-9]*)+)(?::([A-Z]+))?(?::([0-9]+))?$");

    private EtherNetIpAddressParser() {
    }

    public static EtherNetIpTagAddress parse(DataPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("DataPoint cannot be null");
        }
        String address = firstNonBlank(point.getAddress(),
                asString(point.getAdditionalConfig("plc4xAddress")),
                asString(point.getAdditionalConfig("etherNetIpAddress")),
                asString(point.getAdditionalConfig("logixAddress")),
                asString(point.getAdditionalConfig("tagName")));
        return parse(address, point.getDataType(), point.getAdditionalConfig());
    }

    public static EtherNetIpTagAddress parse(String address) {
        return parse(address, null, Collections.emptyMap());
    }

    private static EtherNetIpTagAddress parse(String address, String platformType, Map<String, Object> config) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("EtherNet/IP tag address cannot be empty");
        }
        String raw = address.trim();
        // Program:Main 必须作为单个 CIP 符号段编码；PLC4X 的 toAnsi 会丢失冒号。
        boolean program = raw.startsWith("Program:");
        Matcher matcher = (program ? PROGRAM_ADDRESS : ADDRESS).matcher(raw);
        if (!matcher.matches() || (!program && !EipTag.matches(raw))) {
            throw new IllegalArgumentException("Unsupported EtherNet/IP address: " + raw);
        }
        Map<String, Object> options = config != null ? config : Collections.emptyMap();
        String tag = matcher.group(1);
        Integer index = !program && matcher.group(2) != null ? positiveOrZero(matcher.group(2), "array index") : null;
        Integer configuredIndex = integerOption(options, "arrayIndex");
        if (configuredIndex != null) {
            if (configuredIndex < 0 || (index != null && !index.equals(configuredIndex))) {
                throw new IllegalArgumentException("EtherNet/IP array index conflict: " + raw);
            }
            if (index == null) {
                tag += "[" + configuredIndex + "]";
            }
        }
        Integer count = (program ? matcher.group(3) : matcher.group(4)) != null
                ? positive(program ? matcher.group(3) : matcher.group(4), "array count") : null;
        Integer configuredCount = integerOption(options, "arraySize");
        Integer elementCount = integerOption(options, "elementCount");
        if (configuredCount != null && elementCount != null && !configuredCount.equals(elementCount)) {
            throw new IllegalArgumentException("EtherNet/IP array count conflict: " + raw);
        }
        Integer desiredCount = configuredCount != null ? configuredCount : elementCount;
        if (desiredCount != null && (desiredCount <= 0 || (count != null && !count.equals(desiredCount)))) {
            throw new IllegalArgumentException("EtherNet/IP array count conflict: " + raw);
        }
        if (count == null) {
            count = desiredCount;
        }
        // PLC4X 0.13.0 的 EipTag 序列化将 elementNb 写入 16 位无符号字段。
        if (count != null && count > 65535) {
            throw new IllegalArgumentException("EtherNet/IP array count out of range: " + count);
        }
        if (program && (configuredIndex != null || (count != null && count != 1))) {
            throw new IllegalArgumentException("EtherNet/IP Program tag currently supports only a scalar: " + raw);
        }
        String explicit = matcher.group(program ? 2 : 3);
        EtherNetIpPlcType type = explicit != null ? EtherNetIpPlcType.fromDriverText(explicit)
                : resolveType(options, platformType);
        String normalized = tag + (type != null ? ":" + type.toTypeExpression() : "")
                + (count != null ? ":" + count : "");
        if (program) {
            return new EtherNetIpTagAddress(raw, normalized, tag,
                    type != null ? type.toTypeExpression() : "DINT", count != null ? count : 1);
        }
        // EipTag.of 对语法不符返回 null；同时核对解析结果，防止驱动悄悄改写计数或类型。
        EipTag driverTag = EipTag.of(normalized);
        if (driverTag == null || !tag.equals(driverTag.getTag())
                || (type != null && !type.toTypeExpression().equals(driverTag.getType().name()))
                || (count != null && count != driverTag.getElementNb())) {
            throw new IllegalArgumentException("Unsupported EtherNet/IP PLC4X address: " + normalized);
        }
        return new EtherNetIpTagAddress(raw, normalized, tag,
                type != null ? type.toTypeExpression() : driverTag.getType().name(),
                count != null ? count : driverTag.getElementNb());
    }

    private static EtherNetIpPlcType resolveType(Map<String, Object> options, String platformType) {
        String explicit = firstNonBlank(asString(options.get("driverDataType")), asString(options.get("eipType")),
                asString(options.get("logixType")), asString(options.get("plc4xType")), asString(options.get("plcType")));
        if (explicit != null) {
            return EtherNetIpPlcType.fromDriverText(explicit);
        }
        return platformType != null && !platformType.isBlank()
                ? EtherNetIpPlcType.fromPlatformDataType(platformType) : null;
    }

    private static Integer integerOption(Map<String, Object> options, String key) {
        Object value = options.get(key);
        return value == null || value.toString().isBlank() ? null : positiveOrZero(value.toString(), key);
    }

    private static int positive(String text, String field) {
        int value = positiveOrZero(text, field);
        if (value == 0) {
            throw new IllegalArgumentException("EtherNet/IP " + field + " must be positive: " + text);
        }
        return value;
    }

    private static int positiveOrZero(String text, String field) {
        if (!text.matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid EtherNet/IP " + field + ": " + text);
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("EtherNet/IP " + field + " out of range: " + text, exception);
        }
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
}
