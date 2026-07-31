package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.Setter;
import org.openmuc.j60870.ClientConnectionBuilder;
import org.openmuc.j60870.Connection;
import org.openmuc.j60870.ConnectionEventListener;

import java.net.InetAddress;

/**
 * IEC104 连接 适配器.
 */
public class Iec104ConnectionAdapter extends AbstractConnectionAdapter<Connection> {

    private Connection connection;
    @Setter
    private ConnectionEventListener connectionEventListener;

    /**
     * 创建当前组件实例。
     */
    public Iec104ConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        String host = resolveHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("IEC104 host is required");
        }
        int port = resolvePort() != null ? resolvePort() : 2404;
        int timeout = resolveTimeout();

        InetAddress address = InetAddress.getByName(host);
        ClientConnectionBuilder builder = new ClientConnectionBuilder(address)
                .setPort(port)
                .setConnectionTimeout(timeout);
        if (connectionEventListener != null) {
            builder.setConnectionEventListener(connectionEventListener);
        }
        connection = builder.build();
        Thread.sleep(200L);
        connection.startDataTransfer();
        connectionParams.put("host", host);
        connectionParams.put("port", port);
        connectionParams.put("timeout", timeout);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isStopped()) {
                connection.stopDataTransfer();
            }
        } finally {
            try {
                connection.close();
            } finally {
                connection = null;
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (connection == null || connection.isStopped()) {
            throw new IllegalStateException("IEC104 connection is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // IEC104 has no separate 认证 phase in 当前 implementation.
    }

    @Override
    public Connection getClient() {
        return connection;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveTimeout() {
        if (config.getConnectTimeout() != null && config.getConnectTimeout() > 0) {
            return config.getConnectTimeout();
        }
        if (config.getTimeout() != null && config.getTimeout() > 0) {
            return config.getTimeout();
        }
        return 5000;
    }
}
