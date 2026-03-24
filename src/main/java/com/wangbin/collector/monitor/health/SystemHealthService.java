package com.wangbin.collector.monitor.health;

import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.manager.ConnectionManager;
import com.wangbin.collector.monitor.health.HealthStatus.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聚合系统级健康信息的服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemHealthService {

    private final MultiLevelCacheManager multiLevelCacheManager;
    private final ConnectionManager connectionManager;
    private final CollectionServiceHealthTracker collectionServiceHealthTracker;

    public HealthStatus getSystemHealth() {
        Map<String, ComponentHealth> components = new LinkedHashMap<>();
        components.put("collectionService", buildCollectionServiceHealth());
        components.put("cache", buildCacheHealth());
        components.put("connections", buildConnectionHealth());
        components.put("application", ComponentHealth.builder()
                .name("application")
                .status(Status.UP)
                .message("Application is running")
                .build());

        Status aggregated = HealthStatus.aggregate(components.values());
        Status serviceStatus = collectionServiceHealthTracker.getCurrentStatus();
        Status overall = serviceStatus != Status.UNKNOWN ? serviceStatus : aggregated;
        return HealthStatus.builder()
                .status(overall)
                .components(components)
                .build();
    }

    private ComponentHealth buildCacheHealth() {
        try {
            Map<String, Object> cacheHealth = multiLevelCacheManager.getHealthStatus();
            Object overallStatus = cacheHealth.getOrDefault("overallStatus", "UNKNOWN");
            Status status = parseStatus(overallStatus);

            Map<String, Object> details = new LinkedHashMap<>(cacheHealth);
            details.remove("overallStatus");

            return ComponentHealth.builder()
                    .name("multiLevelCache")
                    .status(status)
                    .message("Multi level cache health")
                    .details(details)
                    .build();
        } catch (Exception e) {
            log.warn("获取缓存健康状态失败", e);
            return ComponentHealth.builder()
                    .name("multiLevelCache")
                    .status(Status.UNKNOWN)
                    .message("Failed to read cache health: " + e.getMessage())
                    .build();
        }
    }

    private ComponentHealth buildCollectionServiceHealth() {
        Status status = collectionServiceHealthTracker.getCurrentStatus();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runningDeviceCount", collectionServiceHealthTracker.getRunningDeviceCount());
        details.put("runningDevices", new ArrayList<>(collectionServiceHealthTracker.getRunningDevicesSnapshot()));
        details.put("lastStateChange", collectionServiceHealthTracker.getLastStateChange());

        String message = switch (status) {
            case UP -> "Collection service is running";
            case DOWN -> "Collection service is stopped";
            case DEGRADED -> "Collection service partially running";
            default -> "Collection service state unknown";
        };

        return ComponentHealth.builder()
                .name("collectionService")
                .status(status)
                .message(message)
                .details(details)
                .build();
    }

    private ComponentHealth buildConnectionHealth() {
        List<ConnectionAdapter> allConnections = connectionManager.getAllConnections();
        List<ConnectionAdapter> activeConnections = connectionManager.getActiveConnections();

        List<String> runningDevices = new ArrayList<>(collectionServiceHealthTracker.getRunningDevicesSnapshot());
        Map<String, ConnectionAdapter> connectionByDevice = allConnections.stream()
                .collect(Collectors.toMap(ConnectionAdapter::getDeviceId, adapter -> adapter, (a, b) -> a));

        List<String> disconnectedDevices = allConnections.stream()
                .filter(connection -> !connection.isConnected())
                .map(ConnectionAdapter::getDeviceId)
                .collect(Collectors.toList());

        List<String> missingConnections = runningDevices.stream()
                .filter(deviceId -> !connectionByDevice.containsKey(deviceId))
                .collect(Collectors.toList());

        List<String> offlineDevices = new ArrayList<>(disconnectedDevices);
        offlineDevices.addAll(missingConnections);

        int totalConnections = allConnections.size();
        int active = activeConnections.size();
        int expectedConnections = runningDevices.size();

        Status status;
        if (totalConnections == 0) {
            status = expectedConnections == 0 ? Status.UNKNOWN : Status.DEGRADED;
        } else if (offlineDevices.isEmpty()) {
            status = Status.UP;
        } else if (active == 0) {
            status = Status.DOWN;
        } else {
            status = Status.DEGRADED;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("expectedConnections", expectedConnections);
        details.put("runningDevices", runningDevices);
        details.put("totalConnections", totalConnections);
        details.put("activeConnections", active);
        details.put("disconnectedDevices", disconnectedDevices);
        details.put("missingConnections", missingConnections);
        details.put("offlineDevices", offlineDevices);
        details.put("lastStateChange", collectionServiceHealthTracker.getLastStateChange());

        return ComponentHealth.builder()
                .name("connections")
                .status(status)
                .message("Connection state snapshot")
                .details(details)
                .build();
    }

    private Status parseStatus(Object value) {
        if (value == null) {
            return Status.UNKNOWN;
        }

        if (value instanceof Status status) {
            return status;
        }

        if (value instanceof Boolean bool) {
            return bool ? Status.UP : Status.DOWN;
        }

        String normalized = value.toString().trim().toUpperCase();
        switch (normalized) {
            case "UP":
            case "HEALTHY":
            case "OK":
            case "RUNNING":
                return Status.UP;
            case "DOWN":
            case "UNHEALTHY":
            case "FAILED":
            case "ERROR":
            case "CRITICAL":
                return Status.DOWN;
            case "DEGRADED":
            case "WARN":
            case "WARNING":
                return Status.DEGRADED;
            default:
                try {
                    return Status.valueOf(normalized);
                } catch (IllegalArgumentException ignored) {
                    return Status.UNKNOWN;
                }
        }
    }
}
