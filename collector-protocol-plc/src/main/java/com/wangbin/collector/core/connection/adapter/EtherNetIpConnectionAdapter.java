package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.ethernetip.EtherNetIpConnectionContract;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class EtherNetIpConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {

    private PlcConnection connection;
    private String connectionString;

    /**
     * 创建当前组件实例。
     */
    public EtherNetIpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
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
                throw new IllegalStateException("PLC4X 未返回 EtherNet/IP 连接");
            }
            if (!connection.isConnected()) {
                connection.connect();
            }
        } catch (Exception exception) {
            // 驱动错误可能包含显式连接串和查询参数，不传递到公共日志或状态。
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // 保留原始建连失败语义。
                } finally {
                    connection = null;
                }
            }
            connectionString = null;
            throw new IllegalStateException("PLC4X EtherNet/IP 建连失败，请检查连接配置和设备状态");
        }
        setConnectionParam("connectionSource", hasText(config.getString("plc4xConnectionString", null))
                ? "EXPLICIT" : "AUTO");
        setConnectionParam("driver", "PLC4X");
        log.info("PLC4X EtherNet/IP 连接已创建，设备:{}", getDeviceId());
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
            throw new IllegalStateException("PLC4X EtherNet/IP connection is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // No additional 认证 phase for PLC4X EtherNet/IP connections.
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
        return connectionString == null ? null : "logix:tcp://[已隐藏]";
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildConnectionString() {
        EtherNetIpConnectionContract.validate(deviceInfo, config);
        String configured = config.getString("plc4xConnectionString", null);
        if (hasText(configured)) {
            return configured;
        }

        String host = resolveHost();
        Integer configuredPort = resolvePort();
        int port = configuredPort == null ? 44818 : configuredPort;
        List<String> options = new ArrayList<>();

        String communicationPath = firstNonBlank(
                config.getString("communicationPath", null),
                config.getString("communication-path", null)
        );
        if (!hasText(communicationPath)) {
            int backplane = EtherNetIpConnectionContract.integer(config, "backplane", 1, 1, 2);
            int slot = EtherNetIpConnectionContract.integer(config, "slot", 0, 0, 255);
            communicationPath = backplane + "," + slot;
        } else {
            // PLC4X 0.13.0 按逗号直接切分路由，不识别配置界面的方括号。
            communicationPath = EtherNetIpConnectionContract.normalizeRoute(communicationPath);
        }
        options.add("communication-path=" + communicationPath);

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

        // PLC4X 0.13.0 将 tcp.default-timeout 用于 TCP 建连，不能用读取超时填充。
        Integer connectTimeout = config.getConnectTimeout();
        if (connectTimeout != null) {
            options.add("tcp.default-timeout=" + connectTimeout);
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
    private boolean hasConfig(String key) {
        return (config.getExtJson() != null && config.getExtJson().containsKey(key))
                || (config.getAuthParams() != null && config.getAuthParams().containsKey(key));
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
