package com.wangbin.collector.common.domain.ads;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.net.URI;

/**
 * ADS 连接参数的唯一校验与 AUTO/EXPLICIT 构造契约。
 */
public final class AdsConnectionContract {
    public static final int DEFAULT_TCP_PORT = 48898;
    public static final int DEFAULT_TARGET_AMS_PORT = 851;
    public static final int DEFAULT_REQUEST_TIMEOUT_MS = 4000;
    public static final int DEFAULT_COLLECTOR_TIMEOUT_MS = 5000;

    private AdsConnectionContract() {
    }

    public static void validate(DeviceInfo device, DeviceConnection connection) {
        if (device == null || connection == null) {
            throw new IllegalArgumentException("ADS 设备与连接配置不能为空");
        }
        String explicit = connection.getStringConfig("plc4xConnectionString", null);
        if (hasText(explicit)) {
            validateExplicit(explicit);
        } else {
            autoConnectionString(device, connection);
        }
        positiveInteger(connection, "maxFieldsPerRequest", 64);
        positiveInteger(connection, "subscriptionInterval", 2000);
        Object enabled = connection.getProperty("subscriptionEnabled");
        if (enabled != null && !(enabled instanceof Boolean)
                && !"true".equalsIgnoreCase(enabled.toString())
                && !"false".equalsIgnoreCase(enabled.toString())) {
            throw new IllegalArgumentException("ADS subscriptionEnabled 必须为布尔值");
        }
        int request = hasText(explicit) ? DEFAULT_REQUEST_TIMEOUT_MS
                : positiveInteger(connection, "timeoutRequest", DEFAULT_REQUEST_TIMEOUT_MS);
        if (connection.getReadTimeout() != null && connection.getReadTimeout() <= 0) {
            throw new IllegalArgumentException("ADS readTimeout 必须大于 0");
        }
        if (connection.getTimeout() != null && connection.getTimeout() <= 0) {
            throw new IllegalArgumentException("ADS timeout 必须大于 0");
        }
        if (request >= collectorTimeout(connection) && !hasText(explicit)) {
            throw new IllegalArgumentException("ADS timeoutRequest 必须小于 Collector Future timeout");
        }
    }

    public static String connectionString(DeviceInfo device, DeviceConnection connection) {
        validate(device, connection);
        String explicit = connection.getStringConfig("plc4xConnectionString", null);
        return hasText(explicit) ? explicit.trim() : autoConnectionString(device, connection);
    }

    public static int collectorTimeout(DeviceConnection connection) {
        Integer read = connection.getReadTimeout();
        Integer timeout = connection.getTimeout();
        return read != null ? read : timeout != null ? timeout : DEFAULT_COLLECTOR_TIMEOUT_MS;
    }

    public static int requestTimeout(DeviceConnection connection) {
        return positiveInteger(connection, "timeoutRequest", DEFAULT_REQUEST_TIMEOUT_MS);
    }

    private static String autoConnectionString(DeviceInfo device, DeviceConnection connection) {
        String host = hasText(connection.getHost()) ? connection.getHost() : device.getIpAddress();
        if (!hasText(host) || !host.equals(host.trim()) || !host.matches("[a-zA-Z0-9._-]+")) {
            throw new IllegalArgumentException("ADS AUTO host 必须是主机名或 IPv4 地址");
        }
        int tcpPort = port(connection.getPort() != null ? connection.getPort() : device.getPort(),
                DEFAULT_TCP_PORT, "TCP port");
        String target = AmsNetIdParser.parse(stringProperty(connection, "targetAmsNetId", "target-ams-net-id"));
        int targetPort = port(integerProperty(connection, "targetAmsPort", "target-ams-port"),
                DEFAULT_TARGET_AMS_PORT, "targetAmsPort");
        String source = AmsNetIdParser.parse(stringProperty(connection, "sourceAmsNetId", "source-ams-net-id"));
        Integer sourceConfigured = integerProperty(connection, "sourceAmsPort", "source-ams-port");
        if (sourceConfigured == null) {
            throw new IllegalArgumentException("ADS AUTO sourceAmsPort 不能为空");
        }
        int sourcePort = port(sourceConfigured, null, "sourceAmsPort");
        int request = positiveInteger(connection, "timeoutRequest", DEFAULT_REQUEST_TIMEOUT_MS);
        boolean load = booleanProperty(connection, "loadSymbolAndDataTypeTables", true);
        return "ads:tcp://" + host + ":" + tcpPort
                + "?target-ams-net-id=" + target + "&target-ams-port=" + targetPort
                + "&source-ams-net-id=" + source + "&source-ams-port=" + sourcePort
                + "&timeout-request=" + request + "&load-symbol-and-data-type-tables=" + load;
    }

    private static void validateExplicit(String text) {
        try {
            URI uri = URI.create(text.trim());
            if (!"ads".equals(uri.getScheme()) || !text.trim().startsWith("ads:tcp://")) {
                throw new IllegalArgumentException("ADS plc4xConnectionString 必须使用 ads:tcp:// scheme");
            }
            URI transport = URI.create(text.trim().substring("ads:".length()));
            if (!"tcp".equals(transport.getScheme()) || !hasText(transport.getHost())
                    || transport.getRawUserInfo() != null || transport.getRawFragment() != null
                    || transport.getPort() == 0 || transport.getPort() > 65535
                    || (transport.getPort() == -1 && !transport.getHost().equals(transport.getRawAuthority()))
                    || (transport.getRawPath() != null && !transport.getRawPath().isEmpty())) {
                throw new IllegalArgumentException("ADS plc4xConnectionString 需要有效 TCP host/port");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("ADS plc4xConnectionString 格式无效");
        }
    }

    private static int port(Integer value, Integer fallback, String field) {
        if (value == null) {
            if (fallback == null) {
                throw new IllegalArgumentException("ADS " + field + " 不能为空");
            }
            return fallback;
        }
        if (value <= 0 || value > 65535) {
            throw new IllegalArgumentException("ADS " + field + " 必须在 1..65535 范围内");
        }
        return value;
    }

    private static Object property(DeviceConnection connection, String camel, String kebab) {
        Object primary = connection.getProperty(camel);
        return primary != null ? primary : connection.getProperty(kebab);
    }

    private static String stringProperty(DeviceConnection connection, String camel, String kebab) {
        Object raw = property(connection, camel, kebab);
        return raw == null ? null : raw.toString();
    }

    private static Integer integerProperty(DeviceConnection connection, String camel, String kebab) {
        Object raw = property(connection, camel, kebab);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean || !raw.toString().matches("[0-9]+")) {
            throw new IllegalArgumentException("ADS " + camel + " 必须为正整数");
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("ADS " + camel + " 超出整数范围");
        }
    }

    private static int positiveInteger(DeviceConnection connection, String key, int fallback) {
        Integer value = integerProperty(connection, key, key.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase());
        if (value != null && value <= 0) {
            throw new IllegalArgumentException("ADS " + key + " 必须大于 0");
        }
        return value == null ? fallback : value;
    }

    private static boolean booleanProperty(DeviceConnection connection, String key, boolean fallback) {
        Object raw = property(connection, key, key.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase());
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean flag) {
            return flag;
        }
        if ("true".equalsIgnoreCase(raw.toString()) || "false".equalsIgnoreCase(raw.toString())) {
            return Boolean.parseBoolean(raw.toString());
        }
        throw new IllegalArgumentException("ADS " + key + " 必须为布尔值");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
