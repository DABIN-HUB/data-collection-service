package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DeviceConnection;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** HTTP request/response configuration shared by validation and runtime. */
public final class HttpConfigurationContract {
    private static final Set<String> REQUEST_MODES = Set.of("DIRECT", "ENVELOPE", "AUTO_COMPAT");
    private static final Set<String> RESPONSE_MODES = Set.of("RAW", "JSON_PATH", "POINT_ARRAY");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "HEAD");

    private HttpConfigurationContract() { }

    public static String requestMode(DeviceConnection config) {
        return mode(config.getString("requestMode", null), "AUTO_COMPAT", REQUEST_MODES, "requestMode");
    }

    public static String responseMode(DeviceConnection config) {
        return mode(config.getString("responseMode", null), "RAW", RESPONSE_MODES, "responseMode");
    }

    public static String method(String value, String fallback, String field) {
        return mode(value, fallback, METHODS, field);
    }

    private static String mode(String value, String fallback, Set<String> allowed, String field) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("HTTP invalid " + field + ": " + normalized);
        }
        return normalized;
    }

    /** path is canonical; apiPrefix is read only when path was not supplied by an older configuration. */
    public static String path(DeviceConnection config) {
        Object canonical = config.getProperty("path");
        String legacy = config.getString("apiPrefix", "");
        return canonical != null && (!canonical.toString().isBlank() || legacy.isBlank())
                ? canonical.toString() : legacy;
    }

    public static void validate(DeviceConnection config) {
        requestMode(config);
        String responseMode = responseMode(config);
        method(config.getString("method", null), "POST", "method");
        method(config.getString("receiveMethod", null), "GET", "receiveMethod");
        method(config.getString("authMethod", null), "POST", "authMethod");
        method(config.getString("writeMethod", null), "POST", "writeMethod");
        String writeAckMode = config.getString("writeAckMode", "RESPONSE");
        if (!"RESPONSE".equalsIgnoreCase(writeAckMode) && !"HTTP_2XX".equalsIgnoreCase(writeAckMode)) {
            throw new IllegalArgumentException("HTTP invalid writeAckMode");
        }
        if (config.getPort() != null && (config.getPort() < 1 || config.getPort() > 65535)) {
            throw new IllegalArgumentException("HTTP port must be between 1 and 65535");
        }
        if (config.getConnectTimeout() == null || config.getConnectTimeout() <= 0
                || config.getReadTimeout() == null || config.getReadTimeout() <= 0) {
            throw new IllegalArgumentException("HTTP connectTimeout and readTimeout must be positive");
        }
        String url = config.getUrl();
        if (url != null && !url.isBlank()) {
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("HTTP url must have an http(s) scheme and host");
            }
            if ("https".equalsIgnoreCase(uri.getScheme()) != Boolean.TRUE.equals(config.getSslEnabled())) {
                throw new IllegalArgumentException("HTTP url scheme and sslEnabled disagree");
            }
        }
        Object insecure = config.getProperty("insecureSkipVerify");
        if (insecure != null && !(insecure instanceof Boolean || "true".equalsIgnoreCase(insecure.toString())
                || "false".equalsIgnoreCase(insecure.toString()))) {
            throw new IllegalArgumentException("HTTP insecureSkipVerify must be boolean");
        }
        if (Boolean.TRUE.equals(config.getBool("insecureSkipVerify", false)) && !Boolean.TRUE.equals(config.getSslEnabled())) {
            throw new IllegalArgumentException("HTTP insecureSkipVerify requires HTTPS");
        }
        String proxyHost = config.getString("proxyHost", "");
        Integer proxyPort = config.getInt("proxyPort", null);
        if ((!proxyHost.isBlank() && (proxyPort == null || proxyPort < 1 || proxyPort > 65535))
                || (proxyHost.isBlank() && proxyPort != null)) {
            throw new IllegalArgumentException("HTTP proxyHost and proxyPort must be configured together");
        }
        boolean basic = hasText(config.getUsername()) || hasText(config.getPassword());
        if (basic && (!hasText(config.getUsername()) || !hasText(config.getPassword()))) {
            throw new IllegalArgumentException("HTTP Basic auth requires both username and password");
        }
        Map<String, Object> headers = config.getMap("headers");
        boolean customAuth = headers != null && headers.keySet().stream().anyMatch(k -> "Authorization".equalsIgnoreCase(k));
        if ((basic && hasText(config.getAuthToken())) || (customAuth && (basic || hasText(config.getAuthToken())
                || hasText(config.getString("authEndpoint", null))))) {
            throw new IllegalArgumentException("HTTP Authorization sources conflict");
        }
        Map<String, Object> query = config.getMap("queryParams");
        if (query != null && query.values().stream().anyMatch(v -> v == null)) {
            throw new IllegalArgumentException("HTTP queryParams cannot contain null values");
        }
        if ("POINT_ARRAY".equals(responseMode)) {
            requireField(config, "responseArrayPath", "$.points");
            requireField(config, "responseKeyField", "name");
            requireField(config, "responseValueField", "value");
        }
        if ("JSON_PATH".equals(responseMode)) {
            requireField(config, "responsePath", "$");
        }
    }

    private static void requireField(DeviceConnection config, String field, String fallback) {
        if (!hasText(config.getString(field, fallback))) {
            throw new IllegalArgumentException("HTTP " + field + " cannot be blank");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
