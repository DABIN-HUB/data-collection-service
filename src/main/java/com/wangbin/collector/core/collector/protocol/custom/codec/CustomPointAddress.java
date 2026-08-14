package com.wangbin.collector.core.collector.protocol.custom.codec;

import java.util.Locale;

/**
 * 自定义协议点位解析地址。
 */
public record CustomPointAddress(AddressMode mode,
                                 int byteOffset,
                                 int bitOffset,
                                 Integer length,
                                 String jsonPath) {

    /**
     * 解析或转换业务数据。
     */
    public static CustomPointAddress parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("自定义协议点位地址不能为空");
        }
        String trimmed = value.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("JSON:")) {
            String path = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("JSON点位地址必须配置解析路径");
            }
            return new CustomPointAddress(AddressMode.JSON, 0, 0, null, path);
        }
        if (upper.startsWith("BIT:")) {
            String[] parts = trimmed.split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("BIT地址格式必须为BIT:字节偏移:位偏移");
            }
            int byteOffset = parseNonNegative(parts[1], "字节偏移");
            int bitOffset = parseNonNegative(parts[2], "位偏移");
            if (bitOffset > 7) {
                throw new IllegalArgumentException("位偏移必须在0到7之间");
            }
            return new CustomPointAddress(AddressMode.BIT, byteOffset, bitOffset, 1, null);
        }
        if (upper.startsWith("BYTE:")) {
            String[] parts = trimmed.split(":");
            if (parts.length < 2 || parts.length > 3) {
                throw new IllegalArgumentException("BYTE地址格式必须为BYTE:字节偏移或BYTE:字节偏移:长度");
            }
            int byteOffset = parseNonNegative(parts[1], "字节偏移");
            Integer length = parts.length == 3 ? parsePositive(parts[2], "长度") : null;
            return new CustomPointAddress(AddressMode.BYTE, byteOffset, 0, length, null);
        }
        return new CustomPointAddress(AddressMode.BYTE, parseNonNegative(trimmed, "字节偏移"), 0, null, null);
    }

    /**
     * 解析或转换业务数据。
     */
    private static int parseNonNegative(String value, String name) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                throw new IllegalArgumentException(name + "不能为负数");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + "必须是整数", exception);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private static int parsePositive(String value, String name) {
        int parsed = parseNonNegative(value, name);
        if (parsed == 0) {
            throw new IllegalArgumentException(name + "必须大于0");
        }
        return parsed;
    }

    /**
     * 定义当前模块的枚举值。
     */
    public enum AddressMode {
        BYTE,
        BIT,
        JSON
    }
}
