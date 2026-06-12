package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
public class EtherNetIpConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private PlcConnection connection;
    private String connectionString;

    public EtherNetIpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    @Override
    protected void doConnect() throws Exception {
        connectionString = buildConnectionString();
        connection = new DefaultPlcDriverManager().getConnection(connectionString);
        if (connection != null && !connection.isConnected()) {
            connection.connect();
        }
        setConnectionParam("connectionString", connectionString);
        log.info("PLC4X EtherNet/IP connection created: {}", connectionString);
    }

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

    @Override
    protected void doHeartbeat() {
        if (connection == null || !connection.isConnected()) {
            throw new IllegalStateException("PLC4X EtherNet/IP connection is not active");
        }
    }

    @Override
    protected void doAuthenticate() {
        // No additional authentication phase for PLC4X EtherNet/IP connections.
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

    private String buildConnectionString() {
        String configured = config.getString("plc4xConnectionString", null);
        if (hasText(configured)) {
            return configured;
        }

        String host = resolveHost();
        if (!hasText(host)) {
            throw new IllegalStateException("Invalid EtherNet/IP connection host");
        }

        int port = resolvePort() != null && resolvePort() > 0 ? resolvePort() : 44818;
        List<String> options = new ArrayList<>();

        String communicationPath = firstNonBlank(
                config.getString("communicationPath", null),
                config.getString("communication-path", null)
        );
        if (!hasText(communicationPath)) {
            int backplane = config.getInt("backplane", 1);
            int slot = config.getInt("slot", 0);
            communicationPath = "[" + backplane + "," + slot + "]";
        }
        options.add("communicationPath=" + communicationPath);

        if (hasConfig("bigEndian")) {
            options.add("big-endian=" + config.getBool("bigEndian", Boolean.TRUE));
        }
        if (hasConfig("forceUnconnectedOperation")) {
            options.add("force-unconnected-operation="
                    + config.getBool("forceUnconnectedOperation", Boolean.FALSE));
        }
        if (hasConfig("tcpKeepAlive")) {
            options.add("tcp.keep-alive=" + config.getBool("tcpKeepAlive", Boolean.TRUE));
        }
        if (hasConfig("tcpNoDelay")) {
            options.add("tcp.no-delay=" + config.getBool("tcpNoDelay", Boolean.TRUE));
        }

        long requestTimeout = resolveRequestTimeout();
        if (requestTimeout > 0) {
            options.add("tcp.default-timeout=" + requestTimeout);
        }

        String controllerType = normalizeControllerType(config.getString("controllerType", null));
        if (hasText(controllerType)) {
            options.add("controller-type=" + controllerType);
        }

        StringBuilder builder = new StringBuilder("logix:tcp://")
                .append(host)
                .append(':')
                .append(port);
        if (!options.isEmpty()) {
            builder.append('?').append(String.join("&", options));
        }
        return builder.toString();
    }

    private long resolveRequestTimeout() {
        Integer readTimeout = config.getReadTimeout();
        if (readTimeout != null && readTimeout > 0) {
            return readTimeout;
        }
        Integer timeout = config.getTimeout();
        if (timeout != null && timeout > 0) {
            return timeout;
        }
        return 5000L;
    }

    private String normalizeControllerType(String controllerType) {
        if (!hasText(controllerType)) {
            return null;
        }
        return controllerType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

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

    private boolean hasConfig(String key) {
        return (config.getExtJson() != null && config.getExtJson().containsKey(key))
                || (config.getAuthParams() != null && config.getAuthParams().containsKey(key));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
