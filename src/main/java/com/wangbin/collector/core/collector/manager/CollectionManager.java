package com.wangbin.collector.core.collector.manager;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.factory.CollectorFactory;
import com.wangbin.collector.core.collector.protocol.base.CommandableCollector;
import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;
import com.wangbin.collector.core.collector.protocol.base.ReadPlanCapable;
import com.wangbin.collector.core.collector.protocol.base.ReadableCollector;
import com.wangbin.collector.core.collector.protocol.base.SubscribableCollector;
import com.wangbin.collector.core.collector.protocol.base.WritableCollector;
import com.wangbin.collector.core.connection.manager.ConnectionManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages device collector lifecycle and protocol operations.
 */
@Slf4j
@Service
public class CollectionManager {

    @Autowired
    private CollectorFactory collectorFactory;

    @Autowired
    private ConnectionManager connectionManager;

    @Getter
    private final Map<String, ProtocolCollector> collectors = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Collection manager initialized");
    }

    @PreDestroy
    public void destroy() {
        log.info("Destroying collection manager");
        destroyAllCollectors();
        log.info("Collection manager destroyed");
    }

    /**
     * Register a device collector.
     */
    public void registerDevice(DeviceInfo deviceInfo) throws CollectorException {
        String deviceId = deviceInfo.getDeviceId();

        synchronized (collectors) {
            if (collectors.containsKey(deviceId)) {
                log.warn("Device already registered: {}", deviceId);
                return;
            }

            try {
                ProtocolCollector collector = collectorFactory.createCollector(deviceInfo);
                collectors.put(deviceId, collector);
                log.info("Device registered: {}", deviceId);
            } catch (Exception e) {
                log.error("Failed to register device: {}", deviceId, e);
                throw new CollectorException("Failed to register device", deviceId, null, e);
            }
        }
    }

    /**
     * Rebuild protocol read plans for a device.
     */
    public void rebuildReadPlans(String deviceId, List<DataPoint> points) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        ReadPlanCapable readPlanCapable = requireCapability(deviceId, collector, ReadPlanCapable.class,
                "rebuild read plans");
        readPlanCapable.rebuildReadPlans(deviceId, points);
    }

    /**
     * Unregister a device collector.
     */
    public void unregisterDevice(String deviceId) throws CollectorException {
        synchronized (collectors) {
            ProtocolCollector collector = collectors.remove(deviceId);
            Exception destroyFailure = null;
            if (collector != null) {
                try {
                    collector.destroy();
                    log.info("Device unregistered: {}", deviceId);
                } catch (Exception e) {
                    destroyFailure = e;
                    log.error("Failed to unregister device: {}", deviceId, e);
                }
            }
            cleanupConnection(deviceId);
            if (destroyFailure != null) {
                throw new CollectorException("Failed to unregister device", deviceId, null, destroyFailure);
            }
        }
    }

    /**
     * Best-effort cleanup for failed startup paths.
     */
    public void cleanupDevice(String deviceId) {
        synchronized (collectors) {
            ProtocolCollector collector = collectors.remove(deviceId);
            if (collector != null) {
                try {
                    collector.destroy();
                } catch (Exception e) {
                    log.warn("Cleanup collector failed, device={}", deviceId, e);
                }
            }
            cleanupConnection(deviceId);
        }
    }

    /**
     * Connect a registered device.
     */
    public void connectDevice(String deviceId) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        if (collector == null) {
            throw new CollectorException("Device is not registered", deviceId, null);
        }

        try {
            collector.connect();
            log.info("Device connected: {}", deviceId);
        } catch (Exception e) {
            log.error("Failed to connect device: {}", deviceId, e);
            throw new CollectorException("Failed to connect device", deviceId, null, e);
        }
    }

    /**
     * Disconnect a registered device.
     */
    public void disconnectDevice(String deviceId) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        if (collector == null) {
            throw new CollectorException("Device is not registered", deviceId, null);
        }

        try {
            collector.disconnect();
            log.info("Device disconnected: {}", deviceId);
        } catch (Exception e) {
            log.error("Failed to disconnect device: {}", deviceId, e);
            throw new CollectorException("Failed to disconnect device", deviceId, null, e);
        }
    }

    /**
     * Reconnect a registered device.
     */
    public void reconnectDevice(String deviceId) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        if (collector == null) {
            throw new CollectorException("Device is not registered", deviceId, null);
        }

        try {
            if (collector.isConnected()) {
                collector.disconnect();
            }
            collector.connect();
            log.info("Device reconnected: {}", deviceId);
        } catch (Exception e) {
            log.error("Failed to reconnect device: {}", deviceId, e);
            throw new CollectorException("Failed to reconnect device", deviceId, null, e);
        }
    }

    /**
     * Read a single point.
     */
    public Object readPoint(String deviceId, DataPoint point) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        ReadableCollector readableCollector = requireCapability(deviceId, collector, ReadableCollector.class,
                "read point");
        return readableCollector.readPoint(point);
    }

    /**
     * Read multiple points.
     */
    public Map<String, Object> readPoints(String deviceId, List<DataPoint> points) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        ReadableCollector readableCollector = requireCapability(deviceId, collector, ReadableCollector.class,
                "read points");
        return readableCollector.readPoints(points);
    }

    /**
     * Write a single point.
     */
    public boolean writePoint(String deviceId, DataPoint point, Object value) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        WritableCollector writableCollector = requireCapability(deviceId, collector, WritableCollector.class,
                "write point");
        return writableCollector.writePoint(point, value);
    }

    /**
     * Write multiple points.
     */
    public Map<String, Boolean> writePoints(String deviceId, Map<DataPoint, Object> points) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        WritableCollector writableCollector = requireCapability(deviceId, collector, WritableCollector.class,
                "write points");
        return writableCollector.writePoints(points);
    }

    /**
     * Subscribe points.
     */
    public void subscribePoints(String deviceId, List<DataPoint> points) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        SubscribableCollector subscribableCollector = requireCapability(deviceId, collector,
                SubscribableCollector.class, "subscribe points");
        subscribableCollector.subscribe(points);
    }

    /**
     * Unsubscribe points.
     */
    public void unsubscribePoints(String deviceId, List<DataPoint> points) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        SubscribableCollector subscribableCollector = requireCapability(deviceId, collector,
                SubscribableCollector.class, "unsubscribe points");
        subscribableCollector.unsubscribe(points);
    }

    /**
     * Get protocol collector status.
     */
    public Map<String, Object> getDeviceStatus(String deviceId) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        if (collector == null) {
            throw new CollectorException("Device is not registered", deviceId, null);
        }
        return collector.getDeviceStatus();
    }

    /**
     * Execute a collector command.
     */
    public Object executeCommand(String deviceId, String command, Map<String, Object> params)
            throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        CommandableCollector commandableCollector = requireCapability(deviceId, collector,
                CommandableCollector.class, "execute command");
        return commandableCollector.executeCommand(command, params);
    }

    /**
     * Get a registered collector.
     */
    public ProtocolCollector getCollector(String deviceId) {
        return collectors.get(deviceId);
    }

    /**
     * Get all registered device ids.
     */
    public List<String> getAllDeviceIds() {
        return new ArrayList<>(collectors.keySet());
    }

    /**
     * Get currently connected collectors.
     */
    public List<ProtocolCollector> getActiveCollectors() {
        return collectors.values().stream()
                .filter(ProtocolCollector::isConnected)
                .toList();
    }

    /**
     * Whether a device is connected.
     */
    public boolean isDeviceConnected(String deviceId) {
        ProtocolCollector collector = collectors.get(deviceId);
        return collector != null && collector.isConnected();
    }

    /**
     * Basic status for management views.
     */
    public Map<String, Object> getDeviceBasicInfo(String deviceId) {
        ProtocolCollector collector = collectors.get(deviceId);
        if (collector == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> info = new HashMap<>();
        info.put("deviceId", deviceId);
        info.put("collectorType", collector.getCollectorType());
        info.put("isConnected", collector.isConnected());
        return info;
    }

    private <T> T requireCapability(String deviceId,
                                    ProtocolCollector collector,
                                    Class<T> capabilityType,
                                    String operation) {
        if (collector == null) {
            throw new CollectorException("Device is not registered", deviceId, null);
        }
        if (!capabilityType.isInstance(collector)) {
            throw new CollectorException("Collector does not support operation: " + operation, deviceId, null);
        }
        return capabilityType.cast(collector);
    }

    /**
     * Destroy all registered collectors.
     */
    private void destroyAllCollectors() {
        for (ProtocolCollector collector : collectors.values()) {
            try {
                collector.destroy();
            } catch (Exception e) {
                log.error("Failed to destroy collector: {}", collector.getCollectorType(), e);
            }
        }
        collectors.clear();
        log.info("All collectors destroyed");
    }

    private void cleanupConnection(String deviceId) {
        try {
            if (connectionManager != null) {
                connectionManager.removeConnection(deviceId);
            }
        } catch (Exception e) {
            log.warn("Cleanup connection failed, device={}", deviceId, e);
        }
    }
}