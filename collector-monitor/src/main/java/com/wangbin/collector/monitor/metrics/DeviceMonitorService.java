package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.manager.ConnectionManager;
import com.wangbin.collector.core.connection.model.ConnectionMetrics;
import com.wangbin.collector.monitor.health.CollectionServiceHealthTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 设备与连接监控服务。
 */
@Service
@RequiredArgsConstructor
public class DeviceMonitorService {

    private final ConnectionManager connectionManager;
    private final CollectionServiceHealthTracker collectionServiceHealthTracker;

    public DeviceStatusSnapshot getDeviceStatus() {
        List<ConnectionAdapter> allConnections = connectionManager.getAllConnections();
        Map<String, ConnectionAdapter> connectionByDevice = allConnections.stream()
                .collect(Collectors.toMap(ConnectionAdapter::getDeviceId, adapter -> adapter, (a, b) -> a, LinkedHashMap::new));

        List<DeviceConnectionSnapshot> snapshots = new ArrayList<>();
        List<String> runningDevices = new ArrayList<>(collectionServiceHealthTracker.getRunningDevicesSnapshot());
        List<String> missingConnections = new ArrayList<>();

        for (String deviceId : runningDevices) {
            ConnectionAdapter adapter = connectionByDevice.remove(deviceId);
            if (adapter != null) {
                snapshots.add(buildSnapshot(adapter));
            } else {
                snapshots.add(buildMissingSnapshot(deviceId));
                missingConnections.add(deviceId);
            }
        }

        for (ConnectionAdapter adapter : connectionByDevice.values()) {
            snapshots.add(buildSnapshot(adapter));
        }

        int activeConnections = (int) snapshots.stream()
                .filter(DeviceConnectionSnapshot::isConnected)
                .count();

        HealthCounter healthCounter = snapshots.stream()
                .collect(HealthCounter::new, HealthCounter::accept, HealthCounter::combine);

        return DeviceStatusSnapshot.builder()
                .totalConnections(allConnections.size())
                .activeConnections(activeConnections)
                .expectedConnections(runningDevices.size())
                .missingConnections(missingConnections)
                .healthyDevices(healthCounter.healthy)
                .warningDevices(healthCounter.warning)
                .dangerDevices(healthCounter.danger)
                .connections(snapshots)
                .build();
    }

    /**
     * 创建并返回业务对象。
     */
    private DeviceConnectionSnapshot buildSnapshot(ConnectionAdapter connection) {
        ConnectionMetrics metrics = connection.getMetrics();
        long idleTime = metrics != null ? metrics.getIdleTime() : 0;
        double successRate = metrics != null ? metrics.getSuccessRate() : 0.0;

        return DeviceConnectionSnapshot.builder()
                .deviceId(connection.getDeviceId())
                .status(connection.getStatus())
                .connected(connection.isConnected())
                .lastActivityTime(metrics != null ? metrics.getLastActivityTime() : 0L)
                .idleTime(idleTime)
                .bytesSent(metrics != null ? metrics.getBytesSent() : 0L)
                .bytesReceived(metrics != null ? metrics.getBytesReceived() : 0L)
                .errors(metrics != null ? metrics.getErrors() : 0L)
                .successRate(successRate)
                .connectionDuration(metrics != null ? metrics.getConnectionDuration() : 0L)
                .build();
    }

    /**
     * 创建并返回业务对象。
     */
    private DeviceConnectionSnapshot buildMissingSnapshot(String deviceId) {
        return DeviceConnectionSnapshot.builder()
                .deviceId(deviceId)
                .status(ConnectionStatus.CONNECTING)
                .connected(false)
                .expectedOnly(true)
                .lastActivityTime(0L)
                .idleTime(0L)
                .bytesSent(0L)
                .bytesReceived(0L)
                .errors(0L)
                .successRate(0.0)
                .connectionDuration(0L)
                .build();
    }

    /**
     * 定义当前模块的业务组件。
     */
    private static class HealthCounter {
        private int healthy;
        private int warning;
        private int danger;

        /**
         * 执行当前业务逻辑。
         */
        private void accept(DeviceConnectionSnapshot snapshot) {
            if (snapshot.isConnected() && snapshot.getErrors() == 0) {
                healthy++;
                return;
            }

            if (snapshot.getErrors() > 5 || (!snapshot.isConnected() && !snapshot.isExpectedOnly())) {
                danger++;
                return;
            }

            warning++;
        }

        /**
         * 执行当前业务逻辑。
         */
        private void combine(HealthCounter other) {
            this.healthy += other.healthy;
            this.warning += other.warning;
            this.danger += other.danger;
        }
    }
}
