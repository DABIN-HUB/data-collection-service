package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Base 采集器 for protocols backed by ConnectionManager-managed adapters.
 */
@Slf4j
public abstract class ConnectionBackedCollector extends BaseCollector {

    private final AtomicReference<ConnectionAdapter<?>> managedConnection = new AtomicReference<>();
    private boolean destroyed;

    @Override
    public synchronized void connect() {
        if (destroyed) throw new IllegalStateException("采集器已退役");
        super.connect();
    }

    @Override
    public synchronized void disconnect() {
        try {
            super.disconnect();
        } finally {
            releaseManagedConnection();
        }
    }

    @Override
    public synchronized void destroy() {
        destroyed = true;
        try {
            super.destroy();
        } finally {
            releaseManagedConnection();
        }
    }

    /**
     * 解析或转换业务数据。
     */
    protected String resolvePointCacheKey(DataPoint point) {
        String cacheKey = firstNonBlank(
                point != null ? point.getPointId() : null,
                point != null ? point.getAddress() : null,
                point != null ? point.getPointCode() : null
        );
        if (cacheKey == null) {
            throw new IllegalArgumentException("Point cache key cannot be resolved");
        }
        return cacheKey;
    }

    /**
     * 解析或转换业务数据。
     */
    protected String resolvePointTagName(DataPoint point) {
        if (point != null && hasText(point.getPointId())) {
            return point.getPointId();
        }
        return resolvePointCacheKey(point);
    }

    /**
     * 创建并返回业务对象。
     */
    protected <A extends ConnectionAdapter<?>> A createAndConnectAdapter(
            Class<A> adapterType,
            String adapterName) throws Exception {
        return createAndConnectAdapter(null, adapterType, adapterName);
    }

    /**
     * 创建并返回业务对象。
     */
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

    /**
     * 创建并返回业务对象。
     */
    protected ConnectionAdapter<?> createManagedConnection() {
        return createManagedConnection(null);
    }

    /**
     * 创建并返回业务对象。
     */
    protected synchronized ConnectionAdapter<?> createManagedConnection(DeviceConnection connectionConfig) {
        if (connectionManager == null) {
            throw new IllegalStateException("Connection manager is not initialized");
        }
        if (deviceInfo == null) {
            throw new IllegalStateException("Device info is not initialized");
        }
        if (destroyed) throw new IllegalStateException("采集器已退役");
        ConnectionAdapter<?> adapter = connectionManager.createConnection(deviceInfo, connectionConfig, runtimeGeneration);
        managedConnection.set(adapter);
        return adapter;
    }

    /**
     * 处理连接生命周期。
     */
    protected synchronized void connectManagedConnection() {
        if (connectionManager == null) {
            throw new IllegalStateException("Connection manager is not initialized");
        }
        if (deviceInfo == null) {
            throw new IllegalStateException("Device info is not initialized");
        }
        ConnectionAdapter<?> adapter = managedConnection.get();
        if (destroyed || adapter == null
                || !connectionManager.connectIfOwned(deviceInfo.getDeviceId(), adapter)) {
            throw new IllegalStateException("采集器连接所有权已失效");
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    protected <A extends ConnectionAdapter<?>> A requireAdapterType(
            ConnectionAdapter<?> adapter,
            Class<A> adapterType,
            String adapterName) {
        if (!adapterType.isInstance(adapter)) {
            throw new IllegalStateException(adapterName + " connection adapter type mismatch");
        }
        return adapterType.cast(adapter);
    }

    /**
     * 清理或删除业务数据。
     */
    protected void removeManagedConnection(String adapterName) {
        releaseManagedConnection();
    }

    @Override
    public void releaseManagedConnection() {
        // 协议失效路径可能持有自己的锁，释放不能反向等待采集器生命周期锁。
        ConnectionAdapter<?> adapter = managedConnection.get();
        if (adapter == null || connectionManager == null) return;
        connectionManager.removeConnectionIfOwned(adapter.getDeviceId(), adapter);
        managedConnection.compareAndSet(adapter, null);
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
                return value;
            }
        }
        return null;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
