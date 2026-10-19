package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.manager.ConnectionManager;
import com.wangbin.collector.core.connection.model.ConnectionMetrics;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeState;
import com.wangbin.collector.core.collector.runtime.RuntimeStateCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备与连接监控服务。
 */
@Service
@RequiredArgsConstructor
public class DeviceMonitorService {

    private final ConnectionManager connectionManager;
    private final RuntimeStateCoordinator runtimeStateCoordinator;

    public DeviceStatusSnapshot getDeviceStatus() {
        List<ConnectionAdapter> allConnections = connectionManager.getAllConnections();
        List<DeviceConnectionSnapshot> snapshots = new ArrayList<>();
        List<DeviceRuntimeSnapshot> runtimes = runtimeStateCoordinator.runtimeSnapshots();
        List<String> runningDevices = runtimes.stream().filter(DeviceRuntimeSnapshot::running)
                .map(DeviceRuntimeSnapshot::deviceId).toList();
        List<String> missingConnections = new ArrayList<>();
        // 按设备别名取共享连接；一个物理适配器不能冒充另一个设备的生命周期。
        for (DeviceRuntimeSnapshot runtime : runtimes) {
            String deviceId = runtime.deviceId();
            ConnectionAdapter adapter = connectionManager.getConnection(deviceId);
            if (adapter != null) {
                snapshots.add(buildSnapshot(deviceId, adapter, runtime));
            } else {
                snapshots.add(buildMissingSnapshot(deviceId, runtime));
                if (runtime.running()) missingConnections.add(deviceId);
            }
        }

        int activeConnections = (int) allConnections.stream()
                .filter(ConnectionAdapter::isConnected)
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
    private DeviceConnectionSnapshot buildSnapshot(String deviceId, ConnectionAdapter connection, DeviceRuntimeSnapshot runtime) {
        ConnectionMetrics metrics = connection.getMetrics();
        long idleTime = metrics != null ? metrics.getIdleTime() : 0;
        double successRate = metrics != null ? metrics.getSuccessRate() : 0.0;

        return DeviceConnectionSnapshot.builder()
                .deviceId(deviceId)
                .status(connection.getStatus())
                .connected(runtime.connected())
                .runtime(runtime)
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
    private DeviceConnectionSnapshot buildMissingSnapshot(String deviceId, DeviceRuntimeSnapshot runtime) {
        return DeviceConnectionSnapshot.builder()
                .deviceId(deviceId)
                .status(runtime.starting() ? ConnectionStatus.CONNECTING : ConnectionStatus.DISCONNECTED)
                .connected(runtime.connected())
                .expectedOnly(runtime.running() || runtime.starting())
                .runtime(runtime)
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
            DeviceRuntimeState.DeviceHealth health = snapshot.getRuntime().deviceHealth();
            if (health == DeviceRuntimeState.DeviceHealth.ONLINE_HEALTHY) {
                healthy++;
                return;
            }

            if (health == DeviceRuntimeState.DeviceHealth.DEGRADED
                    || (snapshot.getRuntime().running() && health == DeviceRuntimeState.DeviceHealth.OFFLINE)) {
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
