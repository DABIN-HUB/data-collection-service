package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 将本地设备 ID 转换为启动准备结果，不负责连接、调度注册和停止清理。
 */
@Slf4j
@Component
public class DeviceStartPreparer {

    private final ConfigManager configManager;
    private final CollectorProperties collectorProperties;
    private final CollectionTaskGuard collectionTaskGuard;
    private final PointRuntimeStateService pointRuntimeStateService;
    private final SchedulerRuntimeState runtimeState;
    private final ReconnectCoordinator reconnectCoordinator;

    public DeviceStartPreparer(ConfigManager configManager,
                               CollectorProperties collectorProperties,
                               CollectionTaskGuard collectionTaskGuard,
                               PointRuntimeStateService pointRuntimeStateService,
                               SchedulerRuntimeState runtimeState,
                               ReconnectCoordinator reconnectCoordinator) {
        this.configManager = configManager;
        this.collectorProperties = collectorProperties;
        this.collectionTaskGuard = collectionTaskGuard;
        this.pointRuntimeStateService = pointRuntimeStateService;
        this.runtimeState = runtimeState;
        this.reconnectCoordinator = reconnectCoordinator;
    }

    StartPreparation prepare(String deviceId) throws Exception {
        long generation = 0L;
        boolean generationActivated = false;
        try {
            if (!runtimeState.markStartingIfNotActive(deviceId)) {
                return null;
            }

            DeviceInfo deviceInfo = configManager.getDevice(deviceId);
            if (deviceInfo == null) {
                runtimeState.clearStarting(deviceId);
                return null;
            }
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            if (dataPoints == null || dataPoints.isEmpty()) {
                runtimeState.clearStarting(deviceId);
                return null;
            }

            if (!runtimeState.isStarting(deviceId)) {
                return null;
            }
            if (collectorProperties.getAdaptiveCollection().isEnabled()) {
                pointRuntimeStateService.initializeDevice(deviceId, dataPoints);
            }
            if (!runtimeState.isStarting(deviceId)) {
                pointRuntimeStateService.removeDevice(deviceId);
                return null;
            }

            generation = collectionTaskGuard.activateNextGeneration(deviceId);
            generationActivated = true;
            runtimeState.markStartingGeneration(deviceId, generation);
            reconnectCoordinator.clear(deviceId);
            long connectTimeoutMs = resolveDeviceStartTimeoutMs(deviceId);
            return new StartPreparation(deviceInfo, List.copyOf(dataPoints), generation, connectTimeoutMs);
        } catch (Exception e) {
            if (generationActivated) {
                collectionTaskGuard.clearDeviceIfCurrent(deviceId, generation);
            }
            runtimeState.clearStarting(deviceId);
            reconnectCoordinator.clear(deviceId);
            throw e;
        }
    }

    List<String> getStartableDeviceIds() {
        List<String> startableDeviceIds = new ArrayList<>();
        for (String deviceId : configManager.getAllDeviceIds()) {
            try {
                DeviceContext context = configManager.getDeviceContext(deviceId);
                if (context != null
                        && context.getDeviceInfo() != null
                        && context.getConnectionConfig() != null) {
                    startableDeviceIds.add(deviceId);
                }
            } catch (Exception e) {
                log.error("启动设备失败, 设备={}", deviceId, e);
            }
        }
        return List.copyOf(startableDeviceIds);
    }

    void loadDataPointsAndAdaptiveConfig(String deviceId) {
        configManager.getDataPointsAndAdaptiveConfig(deviceId);
    }

    long resolveDeviceStartTimeoutMs(String deviceId) {
        long defaultTimeoutMs = Math.max(1000L, collectorProperties.getScheduler().getDeviceStartTimeoutMs());
        DeviceConnection connection = configManager.getConnectionConfig(deviceId);
        if (connection == null) {
            return defaultTimeoutMs;
        }

        Long configuredTimeout = firstPositive(
                toLong(connection.getConnectTimeout()),
                toLong(connection.getInt("connectTimeoutMs", null)),
                toLong(connection.getInt("connectTimeout", null)),
                toLong(connection.getTimeout()));
        if (configuredTimeout == null) {
            return defaultTimeoutMs;
        }
        return Math.max(1000L, Math.min(configuredTimeout, defaultTimeoutMs));
    }

    private Long firstPositive(Long... candidates) {
        if (candidates == null) {
            return null;
        }
        for (Long candidate : candidates) {
            if (candidate != null && candidate > 0) {
                return candidate;
            }
        }
        return null;
    }

    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}
