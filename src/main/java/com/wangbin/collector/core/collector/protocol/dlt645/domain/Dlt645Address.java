package com.wangbin.collector.core.collector.protocol.dlt645.domain;

import java.util.Locale;

/**
 * DL/T 645 电表通信地址。
 */
public record Dlt645Address(String value) {

    public static final Dlt645Address BROADCAST = new Dlt645Address("AAAAAAAAAAAA");

    public Dlt645Address {
        if (value == null) {
            throw new IllegalArgumentException("电表地址不能为空");
        }
        value = value.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (!value.matches("[0-9A-F]{12}")) {
            throw new IllegalArgumentException("电表地址必须是 12 位十六进制字符");
        }
    }

    public byte[] toWireBytes() {
        byte[] result = new byte[6];
        for (int index = 0; index < result.length; index++) {
            int source = value.length() - (index + 1) * 2;
            result[index] = (byte) Integer.parseInt(value.substring(source, source + 2), 16);
        }
        return result;
    }

    public static Dlt645Address fromWireBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 6) {
            throw new IllegalArgumentException("电表地址域必须是 6 字节");
        }
        StringBuilder builder = new StringBuilder(12);
        for (int index = bytes.length - 1; index >= 0; index--) {
            builder.append(String.format("%02X", bytes[index] & 0xFF));
        }
        return new Dlt645Address(builder.toString());
    }
}
