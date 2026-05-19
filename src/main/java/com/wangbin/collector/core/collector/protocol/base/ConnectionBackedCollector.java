package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import lombok.extern.slf4j.Slf4j;

/**
 * Base collector for protocols backed by ConnectionManager-managed adapters.
 */
@Slf4j
public abstract class ConnectionBackedCollector extends BaseCollector {

    protected <A extends ConnectionAdapter<?>> A createAndConnectAdapter(
            Class<A> adapterType,
            String adapterName) throws Exception {
        return createAndConnectAdapter(null, adapterType, adapterName);
    }

    protected <A extends ConnectionAdapter<?>> A createAndConnectAdapter(
            DeviceConnection connectionConfig,
            Class<A> adapterType,
            String adapterName) throws Exception {
        try {
            ConnectionAdapter<?> adapter = createManagedConnection(connectionConfig);
            A typedAdapter = requireAdapterType(adapter, adapterType, adapterName);
            connectManagedConnection();
            return typedAdapter;
        } catch (Exception e) {
            removeManagedConnection(adapterName);
            throw e;
        }
    }

    protected ConnectionAdapter<?> createManagedConnection() {
        return createManagedConnection(null);
    }

    protected ConnectionAdapter<?> createManagedConnection(DeviceConnection connectionConfig) {
        if (connectionManager == null) {
            throw new IllegalStateException("Connection manager is not initialized");
        }
        if (deviceInfo == null) {
            throw new IllegalStateException("Device info is not initialized");
        }
        return connectionConfig != null
                ? connectionManager.createConnection(deviceInfo, connectionConfig)
                : connectionManager.createConnection(deviceInfo);
    }

    protected void connectManagedConnection() {
        if (connectionManager == null) {
            throw new IllegalStateException("Connection manager is not initialized");
        }
        if (deviceInfo == null) {
            throw new IllegalStateException("Device info is not initialized");
        }
        connectionManager.connect(deviceInfo.getDeviceId());
    }

    protected <A extends ConnectionAdapter<?>> A requireAdapterType(
            ConnectionAdapter<?> adapter,
            Class<A> adapterType,
            String adapterName) {
        if (!adapterType.isInstance(adapter)) {
            throw new IllegalStateException(adapterName + " connection adapter type mismatch");
        }
        return adapterType.cast(adapter);
    }

    protected void removeManagedConnection(String adapterName) {
        if (connectionManager == null || deviceInfo == null) {
            return;
        }
        try {
            connectionManager.removeConnection(deviceInfo.getDeviceId());
        } catch (Exception e) {
            log.warn("Remove {} connection failed: {}", adapterName, deviceInfo.getDeviceId(), e);
        }
    }
}
