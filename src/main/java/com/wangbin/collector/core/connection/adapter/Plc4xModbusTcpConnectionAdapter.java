package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * PLC4X-backed Modbus TCP adapter.
 */
@Slf4j
public class Plc4xModbusTcpConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private PlcConnection connection;
    private String connectionString;

    public Plc4xModbusTcpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
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
        log.info("PLC4X Modbus TCP connection created: {}", connectionString);
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
            throw new IllegalStateException("PLC4X Modbus TCP connection is not active");
        }
    }

    @Override
    protected void doAuthenticate() {
        // Modbus TCP does not require a separate authentication phase here.
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
        Integer port = resolvePort();
        if (!hasText(host) || port == null || port <= 0) {
            throw new IllegalStateException("Invalid Modbus TCP connection host/port");
        }

        List<String> options = new ArrayList<>();
        options.add("request-timeout=" + resolveRequestTimeout());
        options.add("default-unit-identifier=" + config.getInt("slaveId", 1));
        options.add("max-registers-per-request=" + config.getInt("maxRegistersPerRequest", 125));
        options.add("max-coils-per-request=" + config.getInt("maxCoilsPerRequest", 2000));

        String pingAddress = config.getString("pingAddress", null);
        if (hasText(pingAddress)) {
            options.add("ping-address=" + pingAddress);
        }

        StringBuilder builder = new StringBuilder("modbus-tcp:tcp://")
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
