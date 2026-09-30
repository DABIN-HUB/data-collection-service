package com.wangbin.collector.common.domain.ads;

/**
 * 统一校验并规范化 ADS 源端与目标端的六段 AMS Net ID。
 */
public final class AmsNetIdParser {
    private AmsNetIdParser() {
    }

    public static String parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AMS Net ID 不能为空");
        }
        String[] segments = value.trim().split("\\.", -1);
        if (segments.length != 6) {
            throw new IllegalArgumentException("AMS Net ID 必须包含六段数字");
        }
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("AMS Net ID 每段必须为 0 到 255 的数字");
            }
            int number = Integer.parseInt(segments[i]);
            if (number > 255) {
                throw new IllegalArgumentException("AMS Net ID 每段必须在 0 到 255 之间");
            }
            if (i > 0) {
                normalized.append('.');
            }
            normalized.append(number);
        }
        return normalized.toString();
    }

    public static boolean isValid(String value) {
        try {
            parse(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
