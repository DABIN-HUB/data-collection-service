package com.wangbin.collector.core.collector.protocol.mqtt;

import java.util.Locale;

/**
 * 定义当前模块的业务组件。
 */
final class MqttCollectorUtils {
    /**
     * 创建当前组件实例。
     */
    private MqttCollectorUtils() {
    }

    /**
     * 执行当前业务逻辑。
     */
    static int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    static boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return defaultValue;
        }
        return "true".equals(text) || "1".equals(text) || "yes".equals(text) || "on".equals(text);
    }
}
