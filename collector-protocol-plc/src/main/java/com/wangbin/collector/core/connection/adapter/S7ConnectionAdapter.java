package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class S7ConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private static final Set<String> CONTROLLER_TYPES = Set.of("S7_300", "S7_400", "S7_1200", "S7_1500", "LOGO");
    private static final Set<String> DEVICE_GROUPS = Set.of("PG_OR_PC", "OS", "OTHERS");

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
        try {
            connection = new DefaultPlcDriverManager().getConnection(connectionString);
            if (connection == null) {
                throw new IllegalStateException("PLC4X 未返回 S7 连接");
            }
            if (!connection.isConnected()) {
                connection.connect();
            }
        } catch (Exception exception) {
            // 驱动异常可能带有完整 URI；不能将其传递到公共连接状态和日志。
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // 保留建连失败作为主要错误。
                } finally {
                    connection = null;
                }
            }
            connectionString = null;
            throw new IllegalStateException("PLC4X S7 建连失败，请检查连接配置和设备状态");
        }
        setConnectionParam("connectionSource", hasText(config.getString("plc4xConnectionString", null))
                ? "EXPLICIT_PLC4X_STRING" : "GENERATED");
        log.info("PLC4X S7 连接已创建，设备:{}", getDeviceId());
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
            connectionString = null;
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
        return connectionString == null ? null : "s7://[已隐藏]";
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildConnectionString() {
        String configured = config.getString("plc4xConnectionString", null);
        if (hasText(configured)) {
            return validateExplicitUri(configured);
        }

        String host = hasText(config.getHost()) ? config.getHost()
                : deviceInfo != null ? deviceInfo.getIpAddress() : null;
        if (!hasText(host) || !host.equals(host.trim()) || host.contains(" ")
                || host.contains(":") || host.contains("/") || host.contains("?")
                || host.contains("#") || host.contains("@")) {
            throw new IllegalArgumentException("SIEMENS_S7 host 无效");
        }

        Integer configuredPort = config.getPort() != null
                ? config.getPort() : deviceInfo != null ? deviceInfo.getPort() : null;
        int port = configuredPort == null ? 102 : configuredPort;
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("SIEMENS_S7 port 必须在 1 到 65535 之间");
        }
        List<String> options = new ArrayList<>();
        options.add("remote-rack=" + intOption("rack", "remoteRack", 0, 0, 7));
        options.add("remote-slot=" + intOption("slot", "remoteSlot", 1, 0, 31));
        options.add("pdu-size=" + intOption("pduSize", null, 1024, 1, 65535));
        intOption("maxFieldsPerRequest", null, 64, 1, Integer.MAX_VALUE);

        String controllerType = normalizeControllerType(config.getString("controllerType", "S7_1200"));
        if (controllerType == null || !CONTROLLER_TYPES.contains(controllerType)) {
            throw new IllegalArgumentException("SIEMENS_S7 controllerType 无效");
        }
        options.add("controller-type=" + controllerType);

        long readTimeout = resolveRequestTimeout();

        Integer localTsap = optionalInt("localTsap", 1, 65535);
        if (localTsap != null) {
            options.add("local-tsap=" + localTsap);
        }
        Integer remoteTsap = optionalInt("remoteTsap", 1, 65535);
        if (remoteTsap != null) {
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
        Integer remoteRack2 = optionalInt("remoteRack2", 0, 7);
        if (remoteRack2 != null) {
            options.add("remote-rack2=" + remoteRack2);
        }
        Integer remoteSlot2 = optionalInt("remoteSlot2", 0, 31);
        if (remoteSlot2 != null) {
            options.add("remote-slot2=" + remoteSlot2);
        }
        String remoteDeviceGroup2 = normalizeDeviceGroup(config.getString("remoteDeviceGroup2", null));
        if (hasText(remoteDeviceGroup2)) {
            options.add("remote-device-group2=" + remoteDeviceGroup2);
        }
        Integer maxAmqCaller = optionalInt("maxAmqCaller", 1, 65535);
        if (maxAmqCaller != null) {
            options.add("max-amq-caller=" + maxAmqCaller);
        }
        Integer maxAmqCallee = optionalInt("maxAmqCallee", 1, 65535);
        if (maxAmqCallee != null) {
            options.add("max-amq-callee=" + maxAmqCallee);
        }

        Object pingValue = config.getProperty("ping");
        if (pingValue != null && !Set.of("true", "false")
                .contains(pingValue.toString().trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("SIEMENS_S7 ping 必须为 true 或 false");
        }
        Integer pingTime = optionalInt("pingTime", 1, Integer.MAX_VALUE);
        if (pingValue != null && Boolean.parseBoolean(pingValue.toString().trim())) {
            options.add("ping=true");
            if (pingTime != null) {
                options.add("ping-time=" + pingTime);
            }
            // DeviceConnection 为毫秒；PLC4X 的 read-timeout 为秒，且仅在 ping=true 时启用。
            options.add("read-timeout=" + (readTimeout + 999L) / 1000L);
        }

        Integer retryTime = optionalInt("retryTime", 0, Integer.MAX_VALUE);
        if (retryTime != null && retryTime > 0) {
            throw new IllegalArgumentException("SIEMENS_S7 retryTime 必须为 0，重连由采集框架负责");
        }
        options.add("retry-time=0");

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
        if (readTimeout != null && readTimeout <= 0) {
            throw new IllegalArgumentException("SIEMENS_S7 readTimeout 必须大于 0");
        }
        if (readTimeout != null) {
            return readTimeout;
        }
        Integer timeout = config.getTimeout();
        if (timeout != null && timeout <= 0) {
            throw new IllegalArgumentException("SIEMENS_S7 timeout 必须大于 0");
        }
        if (timeout != null) {
            return timeout;
        }
        return 5000L;
    }

    private String validateExplicitUri(String value) {
        try {
            URI uri = URI.create(value.trim());
            // PLC4X S7 TCP 在省略端口时使用 102；显式空端口不等同于省略端口。
            if (!"s7".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || (uri.getPort() == -1 && !uri.getHost().equals(uri.getRawAuthority()))
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("SIEMENS_S7 plc4xConnectionString 必须是 s7://host[:port] 格式");
            }
            String query = uri.getRawQuery();
            if (query != null) {
                for (String option : query.split("&")) {
                    String decoded = URLDecoder.decode(option, StandardCharsets.UTF_8);
                    String key = decoded.split("=", 2)[0].trim();
                    if ("retry-time".equalsIgnoreCase(key) && !"retry-time=0".equals(decoded)) {
                        throw new IllegalArgumentException("SIEMENS_S7 retry-time 必须为 0，重连由采集框架负责");
                    }
                }
            }
            return value.trim();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("SIEMENS_S7 plc4xConnectionString 无效或启用了驱动重连");
        }
    }

    private int intOption(String key, String alias, int defaultValue, int minimum, int maximum) {
        Integer value = optionalInt(key, minimum, maximum);
        if (alias != null) {
            Integer aliasValue = optionalInt(alias, minimum, maximum);
            if (value == null) {
                value = aliasValue;
            }
        }
        return value == null ? defaultValue : value;
    }

    private Integer optionalInt(String key, int minimum, int maximum) {
        Object raw = config.getProperty(key);
        if (raw == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.toString().trim());
            if (value >= minimum && value <= maximum) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 显式非法值不允许退化为默认值。
        }
        throw new IllegalArgumentException("SIEMENS_S7 " + key + " 必须在 " + minimum + " 到 " + maximum + " 之间");
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
        String normalized = deviceGroup.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        if (!DEVICE_GROUPS.contains(normalized)) {
            throw new IllegalArgumentException("SIEMENS_S7 设备组无效");
        }
        return normalized;
    }
}