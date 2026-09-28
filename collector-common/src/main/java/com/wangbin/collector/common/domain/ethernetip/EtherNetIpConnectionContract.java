package com.wangbin.collector.common.domain.ethernetip;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * EtherNet/IP 连接配置的统一边界；显式 PLC4X URI 不与自动路由字段混用。
 */
public final class EtherNetIpConnectionContract {

    private static final Set<String> BOOLEAN_KEYS = Set.of(
            "bigEndian", "forceUnconnectedOperation", "tcpKeepAlive", "tcpNoDelay");

    private EtherNetIpConnectionContract() {
    }

    public static void validate(DeviceInfo device, DeviceConnection connection) {
        String override = connection.getString("plc4xConnectionString", null);
        if (override != null && !override.isBlank()) {
            validateOverride(override);
        } else {
            String host = connection.getHost() != null && !connection.getHost().isBlank()
                    ? connection.getHost() : device == null ? null : device.getIpAddress();
            if (host == null || !host.matches("[a-zA-Z0-9._-]+")) {
                throw new IllegalArgumentException("ETHERNET_IP host 无效");
            }
            Integer port = connection.getPort() != null ? connection.getPort()
                    : device == null ? null : device.getPort();
            if (port != null && (port < 1 || port > 65535)) {
                throw new IllegalArgumentException("ETHERNET_IP port 必须在 1 到 65535 之间");
            }
            String route = connection.getString("communicationPath", null);
            if (route == null || route.isBlank()) {
                route = connection.getString("communication-path", null);
            }
            if (route != null) {
                validateRoute(route);
            } else {
                integer(connection, "backplane", 1, 1, 2);
                integer(connection, "slot", 0, 0, 255);
            }
            if (connection.getProperty("controllerType") != null) {
                throw new IllegalArgumentException("ETHERNET_IP controllerType 不受 PLC4X Logix 0.13.0 支持");
            }
            for (String key : BOOLEAN_KEYS) {
                Object value = connection.getProperty(key);
                if (value != null && !Set.of("true", "false")
                        .contains(value.toString().trim().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("ETHERNET_IP " + key + " 必须为 true 或 false");
                }
            }
        }
        integer(connection, "maxFieldsPerRequest", 64, 1, Integer.MAX_VALUE);
        if (connection.getConnectTimeout() != null && connection.getConnectTimeout() <= 0) {
            throw new IllegalArgumentException("ETHERNET_IP connectTimeout 必须大于 0");
        }
        if (connection.getReadTimeout() != null && connection.getReadTimeout() <= 0
                || connection.getTimeout() != null && connection.getTimeout() <= 0) {
            throw new IllegalArgumentException("ETHERNET_IP timeout 必须大于 0");
        }
    }

    public static int integer(DeviceConnection connection, String key, int fallback, int minimum, int maximum) {
        Object value = connection.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.toString().trim());
            if (parsed >= minimum && parsed <= maximum) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 显式非法配置不得由 DeviceConnection.getInt 静默替换为缺省值。
        }
        throw new IllegalArgumentException("ETHERNET_IP " + key + " 必须在 " + minimum + " 到 " + maximum + " 之间");
    }

    public static void validateOverride(String value) {
        if (!value.equals(value.trim()) || !value.startsWith("logix:tcp://")) {
            throw new IllegalArgumentException("ETHERNET_IP plc4xConnectionString 必须为 logix:tcp:// URI");
        }
        try {
            URI uri = URI.create(value.substring("logix:".length()));
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || (uri.getPort() == -1 && !uri.getRawAuthority().equals(uri.getHost()))
                    || uri.getRawFragment() != null || uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
                throw new IllegalArgumentException("invalid authority");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("ETHERNET_IP plc4xConnectionString 格式无效");
        }
    }

    public static void validateRoute(String value) {
        // PLC4X Logix 0.13.0 直接按逗号解析；界面旧式方括号只在这里去除。
        String route = value.trim();
        if (route.startsWith("[") && route.endsWith("]")) {
            route = route.substring(1, route.length() - 1);
        }
        if (route.contains("[") || route.contains("]")) {
            throw new IllegalArgumentException("ETHERNET_IP communicationPath 格式无效");
        }
        String[] parts = route.split(",", -1);
        if (parts.length < 2 || parts.length % 2 != 0) {
            throw new IllegalArgumentException("ETHERNET_IP communicationPath 必须由端口和地址成对组成");
        }
        for (int i = 0; i < parts.length; i += 2) {
            String port = parts[i].trim();
            String address = parts[i + 1].trim();
            if ("1".equals(port)) {
                try {
                    int slot = Integer.parseInt(address);
                    if (slot >= 0 && slot <= 255) {
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                    // 按下方统一拒绝无效路由。
                }
            } else if ("2".equals(port) && !address.isEmpty()) {
                continue;
            }
            throw new IllegalArgumentException("ETHERNET_IP communicationPath 路由段无效");
        }
    }

    public static String normalizeRoute(String route) {
        validateRoute(route);
        String normalized = route.trim();
        if (normalized.startsWith("[")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        String[] parts = normalized.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return String.join(",", parts);
    }
}
