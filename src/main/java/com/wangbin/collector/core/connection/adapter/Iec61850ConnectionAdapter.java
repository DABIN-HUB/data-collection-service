package com.wangbin.collector.core.connection.adapter;

import com.beanit.iec61850bean.ClientAssociation;
import com.beanit.iec61850bean.ClientEventListener;
import com.beanit.iec61850bean.ClientSap;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.net.InetAddress;

/**
 * IEC61850 连接 适配器.
 */
public class Iec61850ConnectionAdapter extends AbstractConnectionAdapter<ClientAssociation> {

    private ClientSap clientSap;
    private ClientAssociation association;
    private ClientEventListener clientEventListener;

    /**
     * 创建当前组件实例。
     */
    public Iec61850ConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    public void setClientEventListener(ClientEventListener clientEventListener) {
        this.clientEventListener = clientEventListener;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        String host = resolveHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("IEC61850 host is required");
        }
        int port = resolvePort() != null ? resolvePort() : 102;
        int timeout = resolveTimeout();

        clientSap = new ClientSap();
        clientSap.setResponseTimeout(timeout);
        InetAddress address = InetAddress.getByName(host);
        association = clientSap.associate(address, port, null, clientEventListener);
        association.setResponseTimeout(timeout);
        connectionParams.put("host", host);
        connectionParams.put("port", port);
        connectionParams.put("timeout", timeout);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        if (association != null) {
            try {
                association.close();
            } finally {
                association = null;
            }
        }
        clientSap = null;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (association == null) {
            throw new IllegalStateException("IEC61850 association is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // IEC61850 association handshake completes during 连接.
    }

    @Override
    public ClientAssociation getClient() {
        return association;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveTimeout() {
        if (config.getTimeout() != null && config.getTimeout() > 0) {
            return config.getTimeout();
        }
        if (config.getReadTimeout() != null && config.getReadTimeout() > 0) {
            return config.getReadTimeout();
        }
        return 10000;
    }
}
