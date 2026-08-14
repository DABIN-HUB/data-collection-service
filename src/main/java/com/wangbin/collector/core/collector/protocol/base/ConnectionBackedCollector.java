package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import lombok.extern.slf4j.Slf4j;

/**
 * Base 采集器 for protocols backed by ConnectionManager-managed adapters.
 */
@Slf4j
public abstract class ConnectionBackedCollector extends BaseCollector {

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

    /**
     * 处理连接生命周期。
     */
    protected void connectManagedConnection() {
        if (connectionManager == null) {
            throw new IllegalStateException("Connection manager is not initialized");
        }
        if (deviceInfo == null) {
            throw new IllegalStateException("Device info is not initialized");
        }
        connectionManager.connect(deviceInfo.getDeviceId());
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
        if (connectionManager == null || deviceInfo == null) {
            return;
        }
        try {
            connectionManager.removeConnection(deviceInfo.getDeviceId());
        } catch (Exception e) {
            log.warn("移除 {} 连接失败:{}", adapterName, deviceInfo.getDeviceId(), e);
        }
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
