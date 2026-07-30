package com.wangbin.collector.core.collector.protocol.ads.util;

public final class AmsNetIdParser {

    private AmsNetIdParser() {
    }

    public static String parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AMS Net ID cannot be empty");
        }

        String[] segments = value.trim().split("\\.");
        if (segments.length != 6) {
            throw new IllegalArgumentException("AMS Net ID must contain 6 numeric segments: " + value);
        }

        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            int number;
            try {
                number = Integer.parseInt(segments[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("AMS Net ID segment must be numeric: " + value, e);
            }
            if (number < 0 || number > 255) {
                throw new IllegalArgumentException("AMS Net ID segment out of range 0-255: " + value);
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
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
