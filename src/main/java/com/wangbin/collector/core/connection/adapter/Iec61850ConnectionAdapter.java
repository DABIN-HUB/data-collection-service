package com.wangbin.collector.core.connection.adapter;

import com.beanit.iec61850bean.ClientAssociation;
import com.beanit.iec61850bean.ClientEventListener;
import com.beanit.iec61850bean.ClientSap;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;

import java.net.InetAddress;

/**
 * IEC61850 connection adapter.
 */
public class Iec61850ConnectionAdapter extends AbstractConnectionAdapter<ClientAssociation> {

    private ClientSap clientSap;
    private ClientAssociation association;
    private ClientEventListener clientEventListener;

    public Iec61850ConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    public void setClientEventListener(ClientEventListener clientEventListener) {
        this.clientEventListener = clientEventListener;
    }

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

    @Override
    protected void doHeartbeat() {
        if (association == null) {
            throw new IllegalStateException("IEC61850 association is not active");
        }
    }

    @Override
    protected void doAuthenticate() {
        // IEC61850 association handshake completes during connect.
    }

    @Override
    public ClientAssociation getClient() {
        return association;
    }

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
