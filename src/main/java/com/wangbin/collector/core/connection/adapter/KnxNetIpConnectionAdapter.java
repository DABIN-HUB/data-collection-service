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

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class KnxNetIpConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private PlcConnection connection;
    private String connectionString;

    /**
     * 创建当前组件实例。
     */
    public KnxNetIpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
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
        log.info("PLC4X KNXnet/IP 连接 已创建:{}", connectionString);
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
            throw new IllegalStateException("PLC4X KNXnet/IP connection is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // KNXnet/IP access has no separate 认证 phase here.
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
        String configured = firstNonBlank(
                config.getString("plc4xConnectionString", null),
                config.getString("plc4x-connection-string", null));
        if (hasText(configured)) {
            return configured;
        }

        String host = resolveHost();
        if (!hasText(host)) {
            throw new IllegalStateException("Invalid KNXnet/IP connection host");
        }

        int port = resolvePort() != null && resolvePort() > 0 ? resolvePort() : 3671;
        List<String> options = new ArrayList<>();
        options.add("group-address-num-levels=" + resolveGroupAddressNumLevels());

        String connectionType = normalizeKnxConnectionType(firstNonBlank(
                config.getString("knxConnectionType", null),
                config.getString("connection-type", null),
                config.getString("knxnetIpConnectionType", null)));
        if (hasText(connectionType)) {
            options.add("connection-type=" + encode(connectionType));
        }

        long requestTimeout = resolveRequestTimeout();
        if (requestTimeout > 0) {
            options.add("request-timeout=" + requestTimeout);
        }

        String knxprojFilePath = firstNonBlank(
                config.getString("knxprojFilePath", null),
                config.getString("knxproj-file-path", null));
        if (hasText(knxprojFilePath)) {
            options.add("knxproj-file-path=" + encode(knxprojFilePath));
        }

        String knxprojPassword = firstNonBlank(
                config.getString("knxprojPassword", null),
                config.getString("knxproj-password", null));
        if (hasText(knxprojPassword)) {
            options.add("knxproj-password=" + encode(knxprojPassword));
        }

        StringBuilder builder = new StringBuilder("knxnet-ip://")
                .append(host)
                .append(':')
                .append(port);
        if (!options.isEmpty()) {
            builder.append('?').append(String.join("&", options));
        }
        return builder.toString();
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveGroupAddressNumLevels() {
        Integer configured = firstNonNull(
                config.getInt("groupAddressNumLevels", null),
                config.getInt("group-address-num-levels", null));
        if (configured == null) {
            return 3;
        }
        if (configured < 1 || configured > 3) {
            throw new IllegalStateException("KNX groupAddressNumLevels must be 1, 2, or 3");
        }
        return configured;
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveRequestTimeout() {
        Integer configured = firstPositive(
                config.getInt("requestTimeout", null),
                config.getInt("request-timeout", null),
                config.getReadTimeout(),
                config.getTimeout());
        return configured != null ? configured : 10000L;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Integer firstPositive(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private Integer firstNonNull(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeKnxConnectionType(String value) {
        if (!hasText(value)) {
            return "LINK_LAYER";
        }
        return value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
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
     * 解析或转换业务数据。
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
