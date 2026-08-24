package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class S7ConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private PlcConnection connection;
    private String connectionString;

    /**
     * 创建当前组件实例。
     */
    public S7ConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
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
        log.info("PLC4X S7 连接 已创建:{}", connectionString);
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
            throw new IllegalStateException("PLC4X S7 connection is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // S7 access has no separate 认证 phase here.
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
            return configured;
        }

        String host = resolveHost();
        if (!hasText(host)) {
            throw new IllegalStateException("Invalid Siemens S7 connection host");
        }

        int port = resolvePort() != null && resolvePort() > 0 ? resolvePort() : 102;
        List<String> options = new ArrayList<>();
        options.add("remote-rack=" + config.getInt("rack", config.getInt("remoteRack", 0)));
        options.add("remote-slot=" + config.getInt("slot", config.getInt("remoteSlot", 1)));
        options.add("pdu-size=" + config.getInt("pduSize", 1024));

        String controllerType = normalizeControllerType(config.getString("controllerType", "S7_1200"));
        if (hasText(controllerType)) {
            options.add("controller-type=" + controllerType);
        }

        long readTimeout = resolveRequestTimeout();
        if (readTimeout > 0) {
            options.add("read-timeout=" + readTimeout);
        }

        Integer localTsap = config.getInt("localTsap", null);
        if (localTsap != null && localTsap > 0) {
            options.add("local-tsap=" + localTsap);
        }
        Integer remoteTsap = config.getInt("remoteTsap", null);
        if (remoteTsap != null && remoteTsap > 0) {
            options.add("remote-tsap=" + remoteTsap);
        }

        String localDeviceGroup = normalizeDeviceGroup(config.getString("localDeviceGroup", null));
        if (hasText(localDeviceGroup)) {
            options.add("local-device-group=" + localDeviceGroup);
        }
        String remoteDeviceGroup = normalizeDeviceGroup(config.getString("remoteDeviceGroup", null));
        if (hasText(remoteDeviceGroup)) {
            options.add("remote-device-group=" + remoteDeviceGroup);
        }
        Integer remoteRack2 = config.getInt("remoteRack2", null);
        if (remoteRack2 != null && remoteRack2 >= 0) {
            options.add("remote-rack2=" + remoteRack2);
        }
        Integer remoteSlot2 = config.getInt("remoteSlot2", null);
        if (remoteSlot2 != null && remoteSlot2 >= 0) {
            options.add("remote-slot2=" + remoteSlot2);
        }
        String remoteDeviceGroup2 = normalizeDeviceGroup(config.getString("remoteDeviceGroup2", null));
        if (hasText(remoteDeviceGroup2)) {
            options.add("remote-device-group2=" + remoteDeviceGroup2);
        }
        Integer maxAmqCaller = config.getInt("maxAmqCaller", null);
        if (maxAmqCaller != null && maxAmqCaller > 0) {
            options.add("max-amq-caller=" + maxAmqCaller);
        }
        Integer maxAmqCallee = config.getInt("maxAmqCallee", null);
        if (maxAmqCallee != null && maxAmqCallee > 0) {
            options.add("max-amq-callee=" + maxAmqCallee);
        }

        if (Boolean.TRUE.equals(config.getBool("ping", Boolean.FALSE))) {
            options.add("ping=true");
            Integer pingTime = config.getInt("pingTime", null);
            if (pingTime != null && pingTime > 0) {
                options.add("ping-time=" + pingTime);
            }
        }

        Integer retryTime = config.getInt("retryTime", null);
        if (retryTime != null && retryTime > 0) {
            options.add("retry-time=" + retryTime);
        }

        StringBuilder builder = new StringBuilder("s7://")
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

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeControllerType(String controllerType) {
        if (!hasText(controllerType)) {
            return null;
        }
        return controllerType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeDeviceGroup(String deviceGroup) {
        if (!hasText(deviceGroup)) {
            return null;
        }
        return deviceGroup.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}