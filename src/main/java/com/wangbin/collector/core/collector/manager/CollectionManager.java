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
 * 管理设备采集器生命周期和协议操作。
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

    /**
     * 处理组件生命周期。
     */
    @PostConstruct
    public void init() {
        log.info("采集 管理器 已初始化");
    }

    /**
     * 处理组件生命周期。
     */
    @PreDestroy
    public void destroy() {
        log.info("正在销毁 采集 管理器");
        destroyAllCollectors();
        log.info("采集 管理器 已销毁");
    }

    /**
     * 注册设备采集器。
     */
    public void registerDevice(DeviceInfo deviceInfo) throws CollectorException {
        String deviceId = deviceInfo.getDeviceId();

        synchronized (collectors) {
            if (collectors.containsKey(deviceId)) {
                log.warn("设备 已存在 已注册:{}", deviceId);
                return;
            }

            try {
                ProtocolCollector collector = collectorFactory.createCollector(deviceInfo);
                collectors.put(deviceId, collector);
                log.info("设备 已注册:{}", deviceId);
            } catch (Exception e) {
                log.error("注册设备失败:{}", deviceId, e);
                throw new CollectorException("Failed to register device", deviceId, null, e);
            }
        }
    }

    /**
     * 重建设备协议读取计划。
     */
    public void rebuildReadPlans(String deviceId, List<DataPoint> points) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        ReadPlanCapable readPlanCapable = requireCapability(deviceId, collector, ReadPlanCapable.class,
                "rebuild read plans");
        readPlanCapable.rebuildReadPlans(deviceId, points);
    }

    /**
     * 注销设备采集器。
     */
    public void unregisterDevice(String deviceId) throws CollectorException {
        synchronized (collectors) {
            ProtocolCollector collector = collectors.remove(deviceId);
            Exception destroyFailure = null;
            if (collector != null) {
                try {
                    collector.destroy();
                    log.info("设备 已注销:{}", deviceId);
                } catch (Exception e) {
                    destroyFailure = e;
                    log.error("注销设备失败:{}", deviceId, e);
                }
            }
            cleanupConnection(deviceId);
            if (destroyFailure != null) {
                throw new CollectorException("Failed to unregister device", deviceId, null, destroyFailure);
            }
        }
    }

    /**
     * 启动失败路径下尽力清理设备资源。
     */
    public void cleanupDevice(String deviceId) {
        synchronized (collectors) {
            ProtocolCollector collector = collectors.remove(deviceId);
            if (collector != null) {
                try {
                    collector.destroy();
                } catch (Exception e) {
                    log.warn("清理采集器失败, 设备={}", deviceId, e);
                }
            }
            cleanupConnection(deviceId);
        }
    }

    /**
     * 连接已注册设备。
     */
    public void connectDevice(String deviceId) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        if (collector == null) {
            throw new CollectorException("Device is not registered", deviceId, null);
        }

        try {
            collector.connect();
            log.info("设备 已连接:{}", deviceId);
        } catch (Exception e) {
            log.error("连接设备失败:{}", deviceId, e);
            throw new CollectorException("Failed to connect device", deviceId, null, e);
        }
    }

    /**
     * 断开已注册设备。
     */
    public void disconnectDevice(String deviceId) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        if (collector == null) {
            throw new CollectorException("Device is not registered", deviceId, null);
        }

        try {
            collector.disconnect();
            log.info("设备 已断开:{}", deviceId);
        } catch (Exception e) {
            log.error("断开设备失败:{}", deviceId, e);
            throw new CollectorException("Failed to disconnect device", deviceId, null, e);
        }
    }

    /**
     * 重连已注册设备。
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
            log.info("设备重连成功:{}", deviceId);
        } catch (Exception e) {
            log.error("重连设备失败:{}", deviceId, e);
            throw new CollectorException("Failed to reconnect device", deviceId, null, e);
        }
    }

    /**
     * 读取单个点位。
     */
    public Object readPoint(String deviceId, DataPoint point) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        ReadableCollector readableCollector = requireCapability(deviceId, collector, ReadableCollector.class,
                "read point");
        return readableCollector.readPoint(point);
    }

    /**
     * 批量读取点位。
     */
    public Map<String, Object> readPoints(String deviceId, List<DataPoint> points) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        ReadableCollector readableCollector = requireCapability(deviceId, collector, ReadableCollector.class,
                "read points");
        return readableCollector.readPoints(points);
    }

    /**
     * 写入单个点位。
     */
    public boolean writePoint(String deviceId, DataPoint point, Object value) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        WritableCollector writableCollector = requireCapability(deviceId, collector, WritableCollector.class,
                "write point");
        return writableCollector.writePoint(point, value);
    }

    /**
     * 批量写入点位。
     */
    public Map<String, Boolean> writePoints(String deviceId, Map<DataPoint, Object> points) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        WritableCollector writableCollector = requireCapability(deviceId, collector, WritableCollector.class,
                "write points");
        return writableCollector.writePoints(points);
    }

    /**
     * 订阅点位。
     */
    public void subscribePoints(String deviceId, List<DataPoint> points) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        SubscribableCollector subscribableCollector = requireCapability(deviceId, collector,
                SubscribableCollector.class, "subscribe points");
        subscribableCollector.subscribe(points);
    }

    /**
     * 取消订阅点位。
     */
    public void unsubscribePoints(String deviceId, List<DataPoint> points) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        SubscribableCollector subscribableCollector = requireCapability(deviceId, collector,
                SubscribableCollector.class, "unsubscribe points");
        subscribableCollector.unsubscribe(points);
    }

    /**
     * 获取协议采集器状态。
     */
    public Map<String, Object> getDeviceStatus(String deviceId) throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        if (collector == null) {
            throw new CollectorException("Device is not registered", deviceId, null);
        }
        return collector.getDeviceStatus();
    }

    /**
     * 执行采集器命令。
     */
    public Object executeCommand(String deviceId, String command, Map<String, Object> params)
            throws CollectorException {
        ProtocolCollector collector = getCollector(deviceId);
        CommandableCollector commandableCollector = requireCapability(deviceId, collector,
                CommandableCollector.class, "execute command");
        return commandableCollector.executeCommand(command, params);
    }

    /**
     * 获取已注册采集器。
     */
    public ProtocolCollector getCollector(String deviceId) {
        return collectors.get(deviceId);
    }

    /**
     * 获取全部已注册设备 ID。
     */
    public List<String> getAllDeviceIds() {
        return new ArrayList<>(collectors.keySet());
    }

    /**
     * 获取当前已连接采集器。
     */
    public List<ProtocolCollector> getActiveCollectors() {
        return collectors.values().stream()
                .filter(ProtocolCollector::isConnected)
                .toList();
    }

    /**
     * 判断设备是否已连接。
     */
    public boolean isDeviceConnected(String deviceId) {
        ProtocolCollector collector = collectors.get(deviceId);
        return collector != null && collector.isConnected();
    }

    /**
     * 提供管理页面使用的基础状态。
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

    /**
     * 校验业务条件和参数边界。
     */
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
     * 销毁全部已注册采集器。
     */
    private void destroyAllCollectors() {
        for (ProtocolCollector collector : collectors.values()) {
            try {
                collector.destroy();
            } catch (Exception e) {
                log.error("销毁采集器失败:{}", collector.getCollectorType(), e);
            }
        }
        collectors.clear();
        log.info("全部 采集器 已销毁");
    }

    /**
     * 清理或删除业务数据。
     */
    private void cleanupConnection(String deviceId) {
        try {
            if (connectionManager != null) {
                connectionManager.removeConnection(deviceId);
            }
        } catch (Exception e) {
            log.warn("清理连接失败, 设备={}", deviceId, e);
        }
    }
}