package com.wangbin.collector.core.collector.protocol.fins.domain;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.core.collector.protocol.custom.codec.CustomFrameCodec;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 允许通过运维接口执行的只读 FINS 命令白名单。
 */
public enum FinsCommand {

    CONTROLLER_DATA_READ(0x05, 0x01),
    CPU_STATUS_READ(0x06, 0x01),
    CLOCK_READ(0x07, 0x01);

    private final int mainCommand;
    private final int subCommand;

    /**
     * 创建当前组件实例。
     */
    FinsCommand(int mainCommand, int subCommand) {
        this.mainCommand = mainCommand;
        this.subCommand = subCommand;
    }

    public int getMainCommand() {
        return mainCommand;
    }

    public int getSubCommand() {
        return subCommand;
    }

    /**
     * 解析或转换业务数据。
     */
    public Map<String, Object> decode(byte[] payload) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CommonMapKeys.COMMAND, name());
        result.put("payloadHex", CustomFrameCodec.encodeHex(safePayload));
        switch (this) {
            case CONTROLLER_DATA_READ -> decodeControllerData(safePayload, result);
            case CPU_STATUS_READ -> decodeCpuStatus(safePayload, result);
            case CLOCK_READ -> decodeClock(safePayload, result);
            default -> {
                // 枚举已覆盖全部命令，此分支用于防止后续扩展遗漏解析。
            }
        }
        return result;
    }

    /**
     * 创建并返回业务对象。
     */
    public static FinsCommand fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("FINS命令不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "CONTROLLER_DATA", "CONTROLLER_INFO", "CONTROLLER_DATA_READ" -> CONTROLLER_DATA_READ;
            case "CPU_STATUS", "CPU_UNIT_STATUS", "CPU_STATUS_READ" -> CPU_STATUS_READ;
            case "CLOCK", "CLOCK_READ" -> CLOCK_READ;
            default -> throw new IllegalArgumentException("不允许执行的FINS命令: " + value);
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private void decodeControllerData(byte[] payload, Map<String, Object> result) {
        if (payload.length >= 20) {
            result.put("controllerModel", ascii(payload, 0, 20));
        }
        if (payload.length >= 40) {
            result.put("controllerVersion", ascii(payload, 20, 20));
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private void decodeCpuStatus(byte[] payload, Map<String, Object> result) {
        if (payload.length > 0) {
            result.put(CommonMapKeys.STATUS, payload[0] & 0xFF);
        }
        if (payload.length > 1) {
            result.put(CommonMapKeys.MODE, payload[1] & 0xFF);
        }
        if (payload.length >= 4) {
            result.put("fatalErrorData", unsignedShort(payload, 2));
        }
        if (payload.length >= 6) {
            result.put("nonFatalErrorData", unsignedShort(payload, 4));
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private void decodeClock(byte[] payload, Map<String, Object> result) {
        if (payload.length < 6) {
            return;
        }
        int year = 2000 + fromBcd(payload[0]);
        int month = fromBcd(payload[1]);
        int day = fromBcd(payload[2]);
        int hour = fromBcd(payload[3]);
        int minute = fromBcd(payload[4]);
        int second = fromBcd(payload[5]);
        result.put("clock", LocalDateTime.of(year, month, day, hour, minute, second).toString());
        if (payload.length > 6) {
            result.put("dayOfWeek", payload[6] & 0xFF);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private int unsignedShort(byte[] payload, int offset) {
        return ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
    }

    /**
     * 创建并返回业务对象。
     */
    private int fromBcd(byte value) {
        int unsigned = value & 0xFF;
        int high = (unsigned >> 4) & 0x0F;
        int low = unsigned & 0x0F;
        if (high > 9 || low > 9) {
            throw new IllegalArgumentException("FINS时钟响应包含非法BCD值: " + unsigned);
        }
        return high * 10 + low;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String ascii(byte[] payload, int offset, int length) {
        byte[] section = Arrays.copyOfRange(payload, offset, Math.min(payload.length, offset + length));
        return new String(section, StandardCharsets.US_ASCII).replace("\0", "").trim();
    }
}
