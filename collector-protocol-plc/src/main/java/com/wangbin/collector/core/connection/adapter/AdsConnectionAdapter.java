package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.ads.AdsConnectionContract;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;

/**
 * PLC4X ADS 连接适配器；原始连接串只在驱动调用时使用，不进入状态或日志。
 */
@Slf4j
public class AdsConnectionAdapter extends AbstractConnectionAdapter<PlcConnection> {
    private PlcConnection connection;
    private String connectionString;

    public AdsConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    @Override
    protected void doConnect() throws Exception {
        connectionString = AdsConnectionContract.connectionString(deviceInfo, config);
        try {
            connection = new DefaultPlcDriverManager().getConnection(connectionString);
            if (connection != null && !connection.isConnected()) {
                connection.connect();
            }
            if (connection == null || !connection.isConnected()) {
                throw new IllegalStateException("ADS 连接未建立");
            }
            String override = config.getStringConfig("plc4xConnectionString", null);
            setConnectionParam("connectionSource", override != null && !override.isBlank() ? "EXPLICIT" : "AUTO");
            log.info("PLC4X ADS 连接已建立: deviceId={}", deviceInfo.getDeviceId());
        } catch (Exception failure) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // 保留建连失败作为主诊断，避免驱动异常打印原始连接串。
                }
            }
            connection = null;
            connectionString = null;
            throw new IllegalStateException("ADS 连接失败（连接参数已隐藏）");
        }
    }

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

    @Override
    protected void doHeartbeat() {
        if (connection == null || !connection.isConnected()) {
            throw new IllegalStateException("PLC4X ADS connection is not active");
        }
    }

    @Override
    protected void doAuthenticate() {
        // ADS 在当前驱动中不提供独立认证阶段。
    }

    @Override
    public PlcConnection getClient() {
        return connection;
    }

    @Override
    public boolean isConnected() {
        return super.isConnected() && connection != null && connection.isConnected();
    }

    /**
     * 仅供内部驱动诊断使用；不得加入 API、状态或日志。
     */
    public String getConnectionString() {
        return connectionString;
    }
}
