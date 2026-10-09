package com.wangbin.collector.core.collector.manager;


import com.wangbin.collector.common.constant.CommonMapKeys;
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
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class CollectionManager {

    private final CollectorFactory collectorFactory;
    private final ConnectionManager connectionManager;
    private CollectionTaskGuard collectionTaskGuard;
    private volatile boolean closed;

    @Autowired(required = false)
    public void setCollectionTaskGuard(CollectionTaskGuard collectionTaskGuard) {
        this.collectionTaskGuard = collectionTaskGuard;
    }

    @Getter
    private final Map<String, ProtocolCollector> collectors = new ConcurrentHashMap<>();

    /** 每个设备保留实例所有权直到旧网络操作退出，绝不让迟到操作访问新连接别名。 */
    private final Map<String, CollectorOwnership> collectorOwnerships = new ConcurrentHashMap<>();

    private static final class CollectorOwnership {
        private final ProtocolCollector collector;
        private long generation;
        private int operations;
        private boolean retired;
        private boolean destroying;

        private CollectorOwnership(ProtocolCollector collector) {
            this.collector = collector;
        }
    }

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
        closed = true;
        log.info("正在销毁 采集 管理器");
        destroyAllCollectors();
        log.info("采集 管理器 已销毁");
    }

    /**
     * 注册设备采集器。
     */
    public void registerDevice(DeviceInfo deviceInfo) throws CollectorException {
        String deviceId = deviceInfo.getDeviceId();

        collectorOwnerships.compute(deviceId, (id, existing) -> {
            if (closed) throw new CollectorException("采集器管理器已关闭", deviceId, null);
            if (existing != null) {
                synchronized (existing) {
                    if (existing.retired) {
                        throw new CollectorException("旧采集器连接操作尚未退出，请在资源释放后重试启动", deviceId, null);
                    }
                    return existing;
                }
            }
            try {
                ProtocolCollector collector = collectorFactory.createCollector(deviceInfo);
                if (closed) {
                    try {
                        collector.destroy();
                    } finally {
                        cleanupConnection(deviceId);
                    }
                    throw new CollectorException("采集器管理器已关闭，取消登记", deviceId, null);
                }
                collectors.put(deviceId, collector);
                log.info("设备已注册:{}", deviceId);
                return new CollectorOwnership(collector);
            } catch (Exception e) {
                log.error("注册设备失败:{}", deviceId, e);
                throw new CollectorException("Failed to register device", deviceId, null, e);
            }
        });
    }

    /**
     * 重建设备协议读取计划。
     */
    public void bindRuntimeGeneration(String deviceId, long generation) {
        CollectorOwnership ownership = requireOwnership(deviceId);
        synchronized (ownership) {
            if (ownership.retired || (ownership.generation != 0L && ownership.generation != generation)) {
                throw new CollectorException("采集器运行代次不能重新绑定", deviceId, null);
            }
            ownership.generation = generation;
            ownership.collector.setRuntimeGeneration(generation);
        }
    }

    /** 仅当前所有者可绑定启动准备阶段的配置版本，迟到结果继续携带旧版本。 */
    public void bindRuntimeConfigurationVersion(String deviceId, long generation, long version) {
        CollectorOwnership ownership = requireOwnership(deviceId);
        synchronized (ownership) {
            if (ownership.retired || ownership.generation != generation) {
                throw new CollectorException("采集器配置版本不能跨代次绑定", deviceId, null);
            }
            ownership.collector.setRuntimeConfigurationVersion(version);
        }
    }

    public void rebuildReadPlans(String deviceId, List<DataPoint> points) throws CollectorException {
        withOwnedCollector(deviceId, collector -> {
            requireCapability(deviceId, collector, ReadPlanCapable.class, "rebuild read plans").rebuildReadPlans(deviceId, points);
            return null;
        });
    }

    /**
     * 注销设备采集器。
     */
    public void unregisterDevice(String deviceId) throws CollectorException {
        retireOwnership(deviceId, collectorOwnerships.get(deviceId));
    }

    /**
     * 启动失败路径下尽力清理设备资源。
     */
    public void cleanupDevice(String deviceId) {
        retireOwnership(deviceId, collectorOwnerships.get(deviceId));
    }

    /**
     * 连接已注册设备。
     */
    public void connectDevice(String deviceId, long generation) throws CollectorException {
        operateOwnedCollector(deviceId, generation, false);
    }

    public void connectDevice(String deviceId) throws CollectorException {
        operateOwnedCollector(deviceId, 0L, false);
    }

    /**
     * 断开已注册设备。
     */
    public void disconnectDevice(String deviceId) throws CollectorException {
        withOwnedCollector(deviceId, collector -> {
            collector.disconnect();
            return null;
        });
    }

    /**
     * 重连已注册设备。
     */
    public void reconnectDevice(String deviceId, long generation) throws CollectorException {
        operateOwnedCollector(deviceId, generation, true);
    }

    public void reconnectDevice(String deviceId) throws CollectorException {
        operateOwnedCollector(deviceId, 0L, true);
    }

    /**
     * 读取单个点位。
     */
    public Object readPoint(String deviceId, DataPoint point) throws CollectorException {
        return withOwnedCollector(deviceId, collector ->
                requireCapability(deviceId, collector, ReadableCollector.class, "read point").readPoint(point));
    }

    /**
     * 批量读取点位。
     */
    public Map<String, Object> readPoints(String deviceId, List<DataPoint> points) throws CollectorException {
        return withOwnedCollector(deviceId, collector ->
                requireCapability(deviceId, collector, ReadableCollector.class, "read points").readPoints(points));
    }

    /**
     * 写入单个点位。
     */
    public boolean writePoint(String deviceId, DataPoint point, Object value) throws CollectorException {
        return withOwnedCollector(deviceId, collector ->
                requireCapability(deviceId, collector, WritableCollector.class, "write point").writePoint(point, value));
    }

    /**
     * 批量写入点位。
     */
    public Map<String, Boolean> writePoints(String deviceId, Map<DataPoint, Object> points) throws CollectorException {
        return withOwnedCollector(deviceId, collector ->
                requireCapability(deviceId, collector, WritableCollector.class, "write points").writePoints(points));
    }

    /**
     * 订阅点位。
     */
    public void subscribePoints(String deviceId, List<DataPoint> points) throws CollectorException {
        withOwnedCollector(deviceId, collector -> {
            requireCapability(deviceId, collector, SubscribableCollector.class, "subscribe points").subscribe(points);
            return null;
        });
    }

    /**
     * 取消订阅点位。
     */
    public void unsubscribePoints(String deviceId, List<DataPoint> points) throws CollectorException {
        withOwnedCollector(deviceId, collector -> {
            requireCapability(deviceId, collector, SubscribableCollector.class, "unsubscribe points").unsubscribe(points);
            return null;
        });
    }

    /**
     * 获取协议采集器状态。
     */
    public Map<String, Object> getDeviceStatus(String deviceId) throws CollectorException {
        return withOwnedCollector(deviceId, ProtocolCollector::getDeviceStatus);
    }

    /**
     * 执行采集器命令。
     */
    public Object executeCommand(String deviceId, String command, Map<String, Object> params)
            throws CollectorException {
        return withOwnedCollector(deviceId, collector ->
                requireCapability(deviceId, collector, CommandableCollector.class, "execute command").executeCommand(command, params));
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
        info.put(CommonMapKeys.DEVICE_ID, deviceId);
        info.put("collectorType", collector.getCollectorType());
        info.put(CommonMapKeys.IS_CONNECTED, collector.isConnected());
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
        for (String deviceId : new ArrayList<>(collectorOwnerships.keySet())) {
            try {
                cleanupDevice(deviceId);
            } catch (Exception e) {
                log.error("销毁采集器失败, 设备={}", deviceId, e);
            }
        }
    }

    /**
     * 清理或删除业务数据。
     */
    /** 旧代次只能释放自己登记的实例，不能按 deviceId 断开新实例。 */
    public void cleanupDeviceIfGeneration(String deviceId, long generation) {
        CollectorOwnership ownership = collectorOwnerships.get(deviceId);
        if (ownership == null) return;
        synchronized (ownership) {
            if (ownership.generation != generation) return;
        }
        retireOwnership(deviceId, ownership);
    }

    private CollectorOwnership requireOwnership(String deviceId) {
        CollectorOwnership ownership = collectorOwnerships.get(deviceId);
        if (ownership == null) throw new CollectorException("Device is not registered", deviceId, null);
        return ownership;
    }

    private void operateOwnedCollector(String deviceId, long generation, boolean reconnect) {
        withOwnedCollector(deviceId, generation, collector -> {
            if (reconnect && collector.isConnected()) collector.disconnect();
            CollectorOwnership ownership = collectorOwnerships.get(deviceId);
            if (ownership == null) return null;
            synchronized (ownership) {
                if (ownership.retired) return null;
            }
            collector.connect();
            return null;
        });
    }

    private <T> T withOwnedCollector(String deviceId, CollectorOperation<T> operation) {
        long generation = 0L;
        if (collectionTaskGuard != null) {
            CollectionTaskGuard.CollectionTaskContext context = collectionTaskGuard.captureCurrentContext();
            if (context != null && deviceId.equals(context.deviceId())) generation = context.generation();
        }
        return withOwnedCollector(deviceId, generation, operation);
    }

    private <T> T withOwnedCollector(String deviceId, long generation, CollectorOperation<T> operation) {
        CollectorOwnership ownership = requireOwnership(deviceId);
        synchronized (ownership) {
            if (ownership.retired || collectorOwnerships.get(deviceId) != ownership
                    || (generation > 0L && (ownership.generation != generation
                    || (collectionTaskGuard != null && !collectionTaskGuard.isCurrent(deviceId, generation))))) {
                throw new CollectorException("采集器所有权或运行代次已失效", deviceId, null);
            }
            ownership.operations++;
        }
        try {
            return operation.run(ownership.collector);
        } catch (CollectorException e) {
            throw e;
        } catch (Exception e) {
            throw new CollectorException("设备协议操作失败", deviceId, null, e);
        } finally {
            synchronized (ownership) {
                ownership.operations--;
            }
            destroyRetiredOwnership(deviceId, ownership);
        }
    }

    @FunctionalInterface
    private interface CollectorOperation<T> {
        T run(ProtocolCollector collector) throws Exception;
    }

    private void retireOwnership(String deviceId, CollectorOwnership ownership) {
        if (ownership == null) return;
        synchronized (ownership) {
            if (collectorOwnerships.get(deviceId) != ownership) return;
            ownership.retired = true;
            collectors.remove(deviceId, ownership.collector);
        }
        destroyRetiredOwnership(deviceId, ownership);
    }

    private void destroyRetiredOwnership(String deviceId, CollectorOwnership ownership) {
        synchronized (ownership) {
            if (!ownership.retired || ownership.operations != 0 || ownership.destroying) return;
            ownership.destroying = true;
        }
        Exception failure = null;
        try {
            ownership.collector.destroy();
        } catch (Exception e) {
            failure = e;
        }
        try {
            // 同设备的新实例尚不允许登记，旧采集器的别名清理不会触及新连接。
            cleanupConnection(deviceId);
        } catch (Exception e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) {
            synchronized (ownership) {
                // 保留已退役所有权，重复 STOP 可重试清理，新 START 不能复用失败资源。
                ownership.destroying = false;
            }
            log.error("注销采集器资源失败, 设备={}", deviceId, failure);
            throw new CollectorException("Failed to unregister device", deviceId, null, failure);
        }
        collectorOwnerships.remove(deviceId, ownership);
    }

    private void cleanupConnection(String deviceId) {
        try {
            if (connectionManager != null) {
                connectionManager.removeConnection(deviceId);
            }
        } catch (Exception e) {
            throw new CollectorException("清理设备连接失败", deviceId, null, e);
        }
    }
}