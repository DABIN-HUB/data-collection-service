package com.wangbin.collector.core.connection.serial;

import java.util.Locale;

/**
 * 串口物理端点配置。
 */
public record SerialEndpoint(String portName,
                             int baudRate,
                             int dataBits,
                             int stopBits,
                             String parity,
                             int readTimeoutMs,
                             int writeTimeoutMs) {

    public SerialEndpoint {
        if (portName == null || portName.isBlank()) {
            throw new IllegalArgumentException("串口名称不能为空");
        }
        if (baudRate <= 0) {
            throw new IllegalArgumentException("串口波特率必须大于零");
        }
        if (dataBits < 5 || dataBits > 8) {
            throw new IllegalArgumentException("串口数据位必须在 5 到 8 之间");
        }
        if (stopBits != 1 && stopBits != 2) {
            throw new IllegalArgumentException("串口停止位只能是 1 或 2");
        }
        portName = portName.trim();
        parity = normalizeParity(parity);
        readTimeoutMs = Math.max(1, readTimeoutMs);
        writeTimeoutMs = Math.max(1, writeTimeoutMs);
    }

    public String physicalPortKey() {
        return portName.toUpperCase(Locale.ROOT);
    }

    private static String normalizeParity(String value) {
        if (value == null || value.isBlank()) {
            return "NONE";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "NONE", "EVEN", "ODD" -> normalized;
            default -> throw new IllegalArgumentException("不支持的串口校验方式: " + value);
        };
    }
}
