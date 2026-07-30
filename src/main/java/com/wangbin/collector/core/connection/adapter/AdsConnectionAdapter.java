package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.ads.util.AmsNetIdParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.ads.readwrite.DefaultAmsPorts;
import org.apache.plc4x.java.api.PlcConnection;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class AdsConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private PlcConnection connection;
    private String connectionString;

    public AdsConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
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
        log.info("PLC4X ADS connection created: {}", connectionString);
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
            throw new IllegalStateException("PLC4X ADS connection is not active");
        }
    }

    @Override
    protected void doAuthenticate() {
        // ADS access has no separate authentication phase here.
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
            throw new IllegalStateException("Invalid ADS connection host");
        }

        int port = resolvePort() != null && resolvePort() > 0 ? resolvePort() : 48898;
        List<String> options = new ArrayList<>();
        options.add("target-ams-net-id=" + resolveRequiredAmsNetId("targetAmsNetId", "target-ams-net-id"));
        options.add("target-ams-port=" + resolveTargetAmsPort());
        options.add("source-ams-net-id=" + resolveRequiredAmsNetId("sourceAmsNetId", "source-ams-net-id"));
        options.add("source-ams-port=" + resolveRequiredPort("sourceAmsPort", "source-ams-port"));
        options.add("timeout-request=" + resolveRequestTimeout());
        options.add("load-symbol-and-data-type-tables=" + resolveLoadSymbolAndDataTypeTables());

        return new StringBuilder("ads:tcp://")
                .append(host)
                .append(':')
                .append(port)
                .append('?')
                .append(String.join("&", options))
                .toString();
    }

    private String resolveRequiredAmsNetId(String primaryKey, String aliasKey) {
        String value = firstNonBlank(
                config.getString(primaryKey, null),
                config.getString(aliasKey, null)
        );
        if (!hasText(value)) {
            throw new IllegalStateException("Missing required ADS config: " + primaryKey);
        }
        return AmsNetIdParser.parse(value);
    }

    private int resolveTargetAmsPort() {
        Integer configured = firstPositive(
                config.getInt("targetAmsPort", null),
                config.getInt("target-ams-port", null)
        );
        return configured != null ? configured : DefaultAmsPorts.RUNTIME_SYSTEM_01.getValue();
    }

    private int resolveRequiredPort(String primaryKey, String aliasKey) {
        Integer value = firstPositive(config.getInt(primaryKey, null), config.getInt(aliasKey, null));
        if (value == null) {
            throw new IllegalStateException("Missing required ADS config: " + primaryKey);
        }
        return value;
    }

    private int resolveRequestTimeout() {
        Integer timeoutRequest = firstPositive(
                config.getInt("timeoutRequest", null),
                config.getInt("timeout-request", null)
        );
        if (timeoutRequest != null) {
            return timeoutRequest;
        }
        Integer readTimeout = config.getReadTimeout();
        if (readTimeout != null && readTimeout > 0) {
            return readTimeout;
        }
        Integer timeout = config.getTimeout();
        if (timeout != null && timeout > 0) {
            return timeout;
        }
        return 4000;
    }

    private boolean resolveLoadSymbolAndDataTypeTables() {
        return firstBoolean(
                config.getBool("loadSymbolAndDataTypeTables", null),
                config.getBool("load-symbol-and-data-type-tables", null),
                Boolean.TRUE
        );
    }

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

    private boolean firstBoolean(Boolean... values) {
        if (values == null) {
            return false;
        }
        for (Boolean value : values) {
            if (value != null) {
                return value;
            }
        }
        return false;
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
