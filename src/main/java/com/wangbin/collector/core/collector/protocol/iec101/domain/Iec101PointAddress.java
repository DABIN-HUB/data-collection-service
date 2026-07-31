package com.wangbin.collector.core.collector.protocol.iec101.domain;

/**
 * IEC101 点位类型和信息对象地址。
 */
public record Iec101PointAddress(Integer typeId, int informationObjectAddress) {

    /**
     * 解析或转换业务数据。
     */
    public static Iec101PointAddress parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IEC101 点位地址不能为空");
        }
        String[] parts = value.trim().split(":", 2);
        if (parts.length == 1) {
            return new Iec101PointAddress(null, parseNumber(parts[0]));
        }
        return new Iec101PointAddress(Iec101Type.parse(parts[0]).typeId(), parseNumber(parts[1]));
    }

    /**
     * 解析或转换业务数据。
     */
    private static int parseNumber(String value) {
        String normalized = value.trim();
        int result = normalized.startsWith("0x") || normalized.startsWith("0X")
                ? Integer.parseInt(normalized.substring(2), 16)
                : Integer.parseInt(normalized);
        if (result < 0) {
            throw new IllegalArgumentException("IEC101 信息对象地址不能小于零");
        }
        return result;
    }
}
