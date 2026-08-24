package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class Plc4xOpcUaConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private PlcConnection connection;
    private String connectionString;

    /**
     * 创建当前组件实例。
     */
    public Plc4xOpcUaConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        connectionString = buildConnectionString();
        connection = new DefaultPlcDriverManager().getConnection(connectionString);
        if (connection != null && !connection.isConnected()) {
            connection.connect();
        }
        setConnectionParam("connectionString", connectionString);
        log.info("PLC4X OPC UA 连接 已创建:{}", connectionString);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        try {
            if (connection != null) {
                connection.close();
            }
        } finally {
            connection = null;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (connection == null || !connection.isConnected()) {
            throw new IllegalStateException("PLC4X OPC UA connection is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // OPC UA 认证 is handled inside the PLC4X 连接 handshake.
    }

    @Override
    public PlcConnection getClient() {
        return connection;
    }

    @Override
    public boolean isConnected() {
        return super.isConnected() && connection != null && connection.isConnected();
    }

    public String getConnectionString() {
        return connectionString;
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildConnectionString() {
        String configured = config.getString("plc4xConnectionString", null);
        if (hasText(configured)) {
            return configured.trim();
        }

        String endpoint = resolveEndpoint();
        List<String> options = new ArrayList<>();

        appendBooleanOption(options, "discovery", config.getBool("discovery", null));
        appendTextOption(options, "username", resolveUsername());
        appendTextOption(options, "password", resolvePassword());
        appendTextOption(options, "security-policy", normalizeSecurityPolicy(
                config.getString("securityPolicy", null)));
        appendTextOption(options, "message-security", normalizeMessageSecurity(
                firstNonBlank(config.getString("messageSecurity", null), config.getString("securityMode", null))));
        appendTextOption(options, "key-store-file", resolveKeyStoreFile());
        appendTextOption(options, "key-store-type", config.getString("keyStoreType", null));
        appendTextOption(options, "key-store-password", resolveKeyStorePassword());
        appendTextOption(options, "server-certificate-file", config.getString("serverCertificateFile", null));
        appendTextOption(options, "trust-store-file", config.getString("trustStoreFile", null));
        appendTextOption(options, "trust-store-type", config.getString("trustStoreType", null));
        appendTextOption(options, "trust-store-password", config.getString("trustStorePassword", null));
        appendLongOption(options, "channel-lifetime", config.getLong("channelLifetime", null));
        appendLongOption(options, "session-timeout", config.getLong("sessionTimeout", null));
        appendLongOption(options, "negotiation-timeout", resolveNegotiationTimeout());
        appendLongOption(options, "request-timeout", resolveRequestTimeout());
        appendTextOption(options, "endpoint-host", config.getString("endpointHost", null));
        appendIntOption(options, "endpoint-port", config.getInt("endpointPort", null));

        if (options.isEmpty()) {
            return endpoint;
        }
        return endpoint + "?" + String.join("&", options);
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveEndpoint() {
        String endpoint = firstNonBlank(
                config.getUrl(),
                config.getString("endpointUrl", null),
                config.getString("endpoint", null));
        if (!hasText(endpoint)) {
            String host = resolveHost();
            if (!hasText(host)) {
                throw new IllegalStateException("Invalid PLC4X OPC UA connection host");
            }
            int port = resolvePort() != null && resolvePort() > 0 ? resolvePort() : 4840;
            endpoint = "opc.tcp://" + host + ":" + port;
        }
        return normalizeEndpoint(endpoint);
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeEndpoint(String endpoint) {
        String trimmed = endpoint.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("opcua:")) {
            return trimmed;
        }
        if (lower.startsWith("opc.tcp://")) {
            return "opcua:tcp://" + trimmed.substring("opc.tcp://".length());
        }
        if (lower.startsWith("tcp://")) {
            return "opcua:" + trimmed;
        }
        return "opcua:tcp://" + trimmed;
    }

    /**
     * 解析或转换业务数据。
     */
    private Long resolveRequestTimeout() {
        Long requestTimeout = config.getLong("requestTimeout", null);
        if (requestTimeout != null && requestTimeout > 0) {
            return requestTimeout;
        }
        requestTimeout = config.getLong("requestTimeoutMs", null);
        if (requestTimeout != null && requestTimeout > 0) {
            return requestTimeout;
        }
        Integer readTimeout = config.getReadTimeout();
        if (readTimeout != null && readTimeout > 0) {
            return readTimeout.longValue();
        }
        Integer timeout = config.getTimeout();
        if (timeout != null && timeout > 0) {
            return timeout.longValue();
        }
        return null;
    }

    /**
     * 解析或转换业务数据。
     */
    private Long resolveNegotiationTimeout() {
        Long negotiationTimeout = config.getLong("negotiationTimeout", null);
        if (negotiationTimeout != null && negotiationTimeout > 0) {
            return negotiationTimeout;
        }
        Integer connectTimeoutMs = config.getInt("connectTimeoutMs", null);
        if (connectTimeoutMs != null && connectTimeoutMs > 0) {
            return connectTimeoutMs.longValue();
        }
        Integer connectTimeout = config.getInt("connectTimeout", null);
        if (connectTimeout != null && connectTimeout > 0) {
            return connectTimeout.longValue();
        }
        Integer configuredConnectTimeout = config.getConnectTimeout();
        if (configuredConnectTimeout != null && configuredConnectTimeout > 0) {
            return configuredConnectTimeout.longValue();
        }
        return null;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveUsername() {
        if ("ANONYMOUS".equals(resolveAuthType())) {
            return null;
        }
        return firstNonBlank(
                config.getString("username", null),
                config.getUsername(),
                authParam("username"));
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolvePassword() {
        if ("ANONYMOUS".equals(resolveAuthType())) {
            return null;
        }
        return firstNonBlank(
                config.getString("password", null),
                config.getPassword(),
                authParam("password"));
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveKeyStoreFile() {
        return firstNonBlank(
                config.getString("keyStoreFile", null),
                config.getString("clientCertPath", null));
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveKeyStorePassword() {
        return firstNonBlank(
                config.getString("keyStorePassword", null),
                config.getString("clientCertPassword", null));
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveAuthType() {
        String authType = firstNonBlank(config.getString("authType", null), "ANONYMOUS");
        return authType != null ? authType.trim().toUpperCase(Locale.ROOT) : "ANONYMOUS";
    }

    /**
     * 写入或持久化业务数据。
     */
    private void appendTextOption(List<String> options, String key, String value) {
        if (hasText(value)) {
            options.add(key + "=" + encode(value.trim()));
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    private void appendBooleanOption(List<String> options, String key, Boolean value) {
        if (value != null) {
            options.add(key + "=" + value);
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    private void appendIntOption(List<String> options, String key, Integer value) {
        if (value != null && value > 0) {
            options.add(key + "=" + value);
        }
    }

    /**
     * 写入或持久化业务数据。
     */
    private void appendLongOption(List<String> options, String key, Long value) {
        if (value != null && value > 0) {
            options.add(key + "=" + value);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeSecurityPolicy(String value) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        int fragmentIndex = trimmed.lastIndexOf('#');
        if (fragmentIndex >= 0 && fragmentIndex < trimmed.length() - 1) {
            trimmed = trimmed.substring(fragmentIndex + 1);
        }
        if (trimmed.endsWith("#None")) {
            return "NONE";
        }
        if ("NONE".equalsIgnoreCase(trimmed) || "NONE".equalsIgnoreCase(trimmed.replace(" ", "_"))) {
            return "NONE";
        }
        return trimmed;
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeMessageSecurity(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "NONE" -> "NONE";
            case "SIGN" -> "SIGN";
            case "SIGNANDENCRYPT", "SIGN_AND_ENCRYPT", "SIGN_ENCRYPT" -> "SIGN_ENCRYPT";
            default -> value.trim();
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 执行当前业务逻辑。
     */
    private String authParam(String key) {
        Map<String, String> authParams = config.getAuthParams();
        if (authParams == null || key == null) {
            return null;
        }
        return authParams.get(key);
    }

    /**
     * 执行当前业务逻辑。
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
