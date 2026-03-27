package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.ConfigBundle;
import com.wangbin.collector.api.controller.dto.ConfigDiffResponse;
import com.wangbin.collector.api.controller.dto.ConfigExportResponse;
import com.wangbin.collector.api.controller.dto.ConfigImportRequest;
import com.wangbin.collector.api.controller.dto.ConfigImportResult;
import com.wangbin.collector.api.controller.dto.ConfigSummaryResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.model.DeviceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配置治理接口
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private static final Set<String> SYNC_TYPES = Set.of("device", "points", "connection", "collection", "all");

    private final ConfigManager configManager;
    private final ConfigSyncService configSyncService;
    private final CollectionService collectionService;

    @GetMapping("/summary")
    public Map<String, Object> getSummary() {
        Map<String, Object> stats = configManager.getCacheStats();
        long lastSync = configSyncService.getLastSyncTime();
        long interval = configSyncService.getSyncInterval();
        Long nextSync = lastSync > 0 && interval > 0 ? lastSync + interval : null;

        ConfigSummaryResponse response = ConfigSummaryResponse.builder()
                .cacheStats(stats)
                .lastSyncTime(lastSync > 0 ? lastSync : null)
                .nextSyncTime(nextSync)
                .syncInterval(interval > 0 ? interval : null)
                .serviceId(configSyncService.getServiceId())
                .listenerCount(configSyncService.getListenerCount())
                .build();
        return success(response);
    }

    @GetMapping("/devices")
    public Map<String, Object> getAllDevices() {
        List<DeviceInfo> devices = configManager.getAllDevices();
        Map<String, Object> data = new HashMap<>();
        data.put("devices", devices);
        data.put("count", devices.size());
        return success(data);
    }

    @GetMapping("/device/{deviceId}")
    public Map<String, Object> getDevice(@PathVariable String deviceId) {
        DeviceInfo local = configManager.getDevice(deviceId);
        DeviceInfo remote = configSyncService.getDeviceConfigs().get(deviceId);
        if (local == null && remote == null) {
            return error("设备不存在: " + deviceId);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", deviceId);
        data.put("local", local);
        data.put("remote", remote);
        data.put("inSync", Objects.equals(local, remote));
        return success(data);
    }

    @GetMapping("/device/{deviceId}/points")
    public Map<String, Object> getDevicePoints(@PathVariable String deviceId,
                                               @RequestParam(value = "includeAdaptive", defaultValue = "false")
                                               boolean includeAdaptive) {
        if (!configManager.containsDevice(deviceId)) {
            return error("设备不存在: " + deviceId);
        }
        List<DataPoint> points = includeAdaptive
                ? configManager.getDataPointsAndAdaptiveConfig(deviceId)
                : configManager.getDataPoints(deviceId);
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", deviceId);
        data.put("count", points.size());
        data.put("points", points);
        return success(data);
    }

    @GetMapping("/device/{deviceId}/connection")
    public Map<String, Object> getDeviceConnection(@PathVariable String deviceId) {
        if (!configManager.containsDevice(deviceId)) {
            return error("设备不存在: " + deviceId);
        }
        DeviceConnection connection = configManager.getConnectionConfig(deviceId);
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", deviceId);
        data.put("connection", connection);
        return success(data);
    }

    @GetMapping("/device/{deviceId}/diff")
    public Map<String, Object> diff(@PathVariable String deviceId) {
        DeviceInfo local = configManager.getDevice(deviceId);
        DeviceInfo remote = configSyncService.getDeviceConfigs().get(deviceId);
        if (local == null && remote == null) {
            return error("无法比对，设备不存在: " + deviceId);
        }
        DeviceConnection localConn = configManager.getConnectionConfig(deviceId);
        DeviceConnection remoteConn = configSyncService.getConnectionConfigs().get(deviceId);

        List<DataPoint> localPoints = configManager.getDataPoints(deviceId);
        List<DataPoint> remotePoints = configSyncService.getPointConfigs()
                .getOrDefault(deviceId, Collections.emptyList());

        ConfigDiffResponse response = buildDiffResponse(local, remote, localConn, remoteConn,
                localPoints, remotePoints);
        return success(response);
    }

    @PutMapping("/device/{deviceId}")
    public Map<String, Object> updateDevice(@PathVariable String deviceId,
                                            @RequestBody DeviceInfo device) {
        if (device == null) {
            return error("请求体不能为空");
        }
        device.setDeviceId(deviceId);
        boolean updated = configManager.updateDeviceConfig(device);
        return updated ? success("设备配置已更新", Map.of("deviceId", deviceId))
                : error("更新设备配置失败: " + deviceId);
    }

    @PutMapping("/device/{deviceId}/points")
    public Map<String, Object> updatePoints(@PathVariable String deviceId,
                                            @RequestBody List<DataPoint> points) {
        if (CollectionUtils.isEmpty(points)) {
            return error("数据点列表不能为空");
        }
        boolean updated = configManager.updateDataPoints(deviceId, points);
        return updated ? success("数据点配置已更新", Map.of("deviceId", deviceId, "count", points.size()))
                : error("更新数据点配置失败: " + deviceId);
    }

    @PutMapping("/device/{deviceId}/connection")
    public Map<String, Object> updateConnection(@PathVariable String deviceId,
                                                @RequestBody DeviceConnection connection) {
        if (connection == null) {
            return error("连接配置不能为空");
        }
        connection.setDeviceId(deviceId);
        boolean updated = configManager.updateConnectionConfig(deviceId, connection);
        return updated ? success("连接配置已更新", Map.of("deviceId", deviceId))
                : error("更新连接配置失败: " + deviceId);
    }

    @PostMapping("/device/{deviceId}/refresh")
    public Map<String, Object> refreshDevice(@PathVariable String deviceId) {
        boolean refreshed = configManager.refreshDeviceConfig(deviceId);
        return refreshed ? success("设备配置已刷新", Map.of("deviceId", deviceId))
                : error("刷新失败，设备配置不完整: " + deviceId);
    }

    @PostMapping("/device/{deviceId}/clear")
    public Map<String, Object> clearDevice(@PathVariable String deviceId) {
        boolean cleared = configManager.clearDeviceConfig(deviceId);
        return cleared ? success("设备配置缓存已清空", Map.of("deviceId", deviceId))
                : error("设备配置不存在或已清空: " + deviceId);
    }

    @PostMapping("/sync")
    public Map<String, Object> triggerFullSync() {
        configSyncService.triggerManualSync();
        return success("已触发异步全量同步任务", null);
    }

    @PostMapping("/sync/{type}")
    public Map<String, Object> triggerPartialSync(@PathVariable String type,
                                                  @RequestParam(value = "deviceId", required = false)
                                                  String deviceId) {
        String normalized = type.toLowerCase(Locale.ROOT);
        if (!SYNC_TYPES.contains(normalized)) {
            return error("不支持的同步类型: " + type);
        }
        configSyncService.notifyConfigUpdate(normalized, deviceId);
        return success("已触发 " + normalized + " 同步", Map.of("deviceId", deviceId));
    }

    @GetMapping("/sync/status")
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("serviceId", configSyncService.getServiceId());
        data.put("lastSyncTime", configSyncService.getLastSyncTime());
        data.put("syncInterval", configSyncService.getSyncInterval());
        data.put("listenerCount", configSyncService.getListenerCount());
        return success(data);
    }

    @GetMapping("/export")
    public Map<String, Object> exportConfigs() {
        List<DeviceContext> contexts = configManager.getAllDeviceContexts();
        List<ConfigBundle> bundles = contexts.stream()
                .map(ctx -> ConfigBundle.builder()
                        .device(ctx.getDeviceInfo())
                        .connection(ctx.getConnectionConfig())
                        .points(ctx.getDataPoints())
                        .build())
                .collect(Collectors.toList());
        ConfigExportResponse response = ConfigExportResponse.builder()
                .bundles(bundles)
                .build();
        return success(response);
    }

    @PostMapping("/import")
    public Map<String, Object> importConfigs(@RequestBody ConfigImportRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getBundles())) {
            return error("导入内容不能为空");
        }
        int successCount = 0;
        List<String> failedDevices = new ArrayList<>();
        List<String> touchedDevices = new ArrayList<>();

        for (ConfigBundle bundle : request.getBundles()) {
            String deviceId = resolveDeviceId(bundle);
            if (!StringUtils.hasText(deviceId)) {
                failedDevices.add("UNKNOWN");
                continue;
            }
            boolean ok = true;
            if (bundle.getDevice() != null) {
                bundle.getDevice().setDeviceId(deviceId);
                ok = configManager.updateDeviceConfig(bundle.getDevice()) && ok;
            }
            if (bundle.getConnection() != null) {
                bundle.getConnection().setDeviceId(deviceId);
                ok = configManager.updateConnectionConfig(deviceId, bundle.getConnection()) && ok;
            }
            if (!CollectionUtils.isEmpty(bundle.getPoints())) {
                ok = configManager.updateDataPoints(deviceId, bundle.getPoints()) && ok;
            }
            if (ok) {
                successCount++;
                touchedDevices.add(deviceId);
            } else {
                failedDevices.add(deviceId);
            }
        }

        if (request.isReloadAfterImport() && !touchedDevices.isEmpty()) {
            collectionService.reloadAllDevices();
        }

        ConfigImportResult result = ConfigImportResult.builder()
                .total(request.getBundles().size())
                .success(successCount)
                .failedDevices(failedDevices)
                .build();
        return success("导入完成", result);
    }

    private ConfigDiffResponse buildDiffResponse(DeviceInfo localDevice,
                                                 DeviceInfo remoteDevice,
                                                 DeviceConnection localConn,
                                                 DeviceConnection remoteConn,
                                                 List<DataPoint> localPoints,
                                                 List<DataPoint> remotePoints) {
        Map<String, DataPoint> localMap = indexPoints(localPoints);
        Map<String, DataPoint> remoteMap = indexPoints(remotePoints);

        Set<String> missing = new LinkedHashSet<>(remoteMap.keySet());
        missing.removeAll(localMap.keySet());

        Set<String> extra = new LinkedHashSet<>(localMap.keySet());
        extra.removeAll(remoteMap.keySet());

        List<String> changed = localMap.entrySet().stream()
                .filter(entry -> remoteMap.containsKey(entry.getKey())
                        && !Objects.equals(entry.getValue(), remoteMap.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        return ConfigDiffResponse.builder()
                .deviceChanged(!Objects.equals(localDevice, remoteDevice))
                .connectionChanged(!Objects.equals(localConn, remoteConn))
                .missingPointCodes(new ArrayList<>(missing))
                .extraPointCodes(new ArrayList<>(extra))
                .changedPointCodes(changed)
                .build();
    }

    private Map<String, DataPoint> indexPoints(List<DataPoint> points) {
        if (CollectionUtils.isEmpty(points)) {
            return Collections.emptyMap();
        }
        return points.stream()
                .filter(Objects::nonNull)
                .filter(point -> StringUtils.hasText(point.getPointCode()))
                .collect(Collectors.toMap(DataPoint::getPointCode,
                        Function.identity(),
                        (left, right) -> left,
                        HashMap::new));
    }

    private String resolveDeviceId(ConfigBundle bundle) {
        if (bundle == null) {
            return null;
        }
        if (bundle.getDevice() != null && StringUtils.hasText(bundle.getDevice().getDeviceId())) {
            return bundle.getDevice().getDeviceId();
        }
        if (bundle.getConnection() != null && StringUtils.hasText(bundle.getConnection().getDeviceId())) {
            return bundle.getConnection().getDeviceId();
        }
        return null;
    }

    private Map<String, Object> success(Object data) {
        return success("OK", data);
    }

    private Map<String, Object> success(String message, Object data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "success");
        payload.put("message", message);
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("data", data);
        return payload;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "error");
        payload.put("message", message);
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }
}
