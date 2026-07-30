package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.enums.Parity;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * PLC4X-backed Modbus RTU/ASCII serial adapter.
 */
@Slf4j
public class Plc4xModbusRtuConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private PlcConnection connection;
    private String connectionString;

    public Plc4xModbusRtuConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
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
        log.info("PLC4X Modbus serial connection created: {}", connectionString);
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
            throw new IllegalStateException("PLC4X Modbus serial connection is not active");
        }
    }

    @Override
    protected void doAuthenticate() {
        // Modbus serial does not require a separate authentication phase here.
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

        String protocolCode = resolveProtocolCode();
        String serialPort = resolveSerialPort();
        List<String> options = new ArrayList<>();
        options.add("request-timeout=" + resolveRequestTimeout());
        options.add("default-unit-identifier=" + config.getInt("slaveId", 1));
        options.add("serial.baud-rate=" + config.getInt("baudRate", 9600));
        options.add("serial.num-data-bits=" + config.getInt("dataBits", 8));
        options.add("serial.num-stop-bits=" + config.getInt("stopBits", 1));
        options.add("serial.parity=" + resolveParityName());
        options.add("max-registers-per-request=" + config.getInt("maxRegistersPerRequest", 125));
        options.add("max-coils-per-request=" + config.getInt("maxCoilsPerRequest", 2000));

        String byteOrder = config.getString("byteOrder", null);
        if (hasText(byteOrder)) {
            options.add("default-payload-byte-order=" + byteOrder);
        }

        StringBuilder builder = new StringBuilder(protocolCode)
                .append(":///")
                .append(normalizeSerialPort(serialPort));
        if (!options.isEmpty()) {
            builder.append('?').append(String.join("&", options));
        }
        return builder.toString();
    }

    private String resolveProtocolCode() {
        String configured = config.getString("plc4xProtocolCode", null);
        if (hasText(configured)) {
            return configured;
        }
        String protocolType = deviceInfo != null ? deviceInfo.getProtocolType() : null;
        if ("MODBUS_ASCII".equalsIgnoreCase(protocolType)) {
            return "modbus-ascii";
        }
        return "modbus-rtu";
    }

    private String resolveSerialPort() {
        String serialPort = config.getStringConfig("serialPort", null);
        if (hasText(serialPort)) {
            return serialPort;
        }
        String host = resolveHost();
        if (hasText(host)) {
            return host;
        }
        return "COM1";
    }

    private String normalizeSerialPort(String serialPort) {
        String normalized = serialPort.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
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

    private String resolveParityName() {
        String parityText = config.getStringConfig("parity", null);
        if (hasText(parityText)) {
            try {
                Parity parity = Parity.fromName(parityText.toLowerCase());
                return toPlc4xParityName(parity);
            } catch (IllegalArgumentException ignore) {
                log.warn("Unknown parity '{}', fallback to NO_PARITY", parityText);
            }
        }
        Integer parityNumber = config.getIntConfig("parity", null);
        if (parityNumber != null) {
            try {
                return toPlc4xParityName(Parity.fromValue(parityNumber));
            } catch (IllegalArgumentException ignore) {
                log.warn("Unknown parity value '{}', fallback to NO_PARITY", parityNumber);
            }
        }
        return "NO_PARITY";
    }

    private String toPlc4xParityName(Parity parity) {
        return switch (parity) {
            case even -> "EVEN_PARITY";
            case odd -> "ODD_PARITY";
            case none -> "NO_PARITY";
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
