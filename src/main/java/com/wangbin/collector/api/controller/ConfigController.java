package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.ConfigBundle;
import com.wangbin.collector.api.controller.dto.ConfigDiffResponse;
import com.wangbin.collector.api.controller.dto.ConfigExportResponse;
import com.wangbin.collector.api.controller.dto.ConfigImportRequest;
import com.wangbin.collector.api.controller.dto.ConfigImportResult;
import com.wangbin.collector.api.controller.dto.ConfigSummaryResponse;
import com.wangbin.collector.api.controller.dto.LocalDeviceConfigRequest;
import com.wangbin.collector.api.exception.ConfigApiException;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.model.ConfigUpdateType;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.security.SensitiveConfigSanitizer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    private final ConfigManager configManager;
    private final ConfigSyncService configSyncService;
    private final CollectionService collectionService;
    private final SensitiveConfigSanitizer sensitiveConfigSanitizer;
    private final PointRuntimeStateService pointRuntimeStateService;

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

    /**
     * 创建并返回业务对象。
     */
    @PostMapping("/local/devices")
    public Map<String, Object> createLocalDevice(@Valid @RequestBody LocalDeviceConfigRequest request) {
        return saveLocalDevice(request, null, request != null && request.isOverwrite());
    }

    /**
     * 更新或刷新业务状态。
     */
    @PutMapping("/local/device/{deviceId}")
    public Map<String, Object> updateLocalDevice(@PathVariable String deviceId,
                                                 @Valid @RequestBody LocalDeviceConfigRequest request) {
        return saveLocalDevice(request, deviceId, true);
    }

    @GetMapping("/local/device/{deviceId}")
    public Map<String, Object> getLocalDevice(@PathVariable String deviceId) {
        if (!configManager.isLocalTemporaryDevice(deviceId)) {
            return error("只能读取本地临时设备配置: " + deviceId);
        }
        ConfigBundle bundle = ConfigBundle.builder()
                .device(configManager.getDevice(deviceId))
                .connection(configManager.getConnectionConfig(deviceId))
                .points(configManager.getDataPoints(deviceId))
                .build();
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", deviceId);
        data.put("configSource", ConfigManager.CONFIG_SOURCE_LOCAL);
        data.put("temporaryConfig", true);
        data.put("bundle", bundle);
        return success(data);
    }

    /**
     * 清理或删除业务数据。
     */
    @DeleteMapping("/local/device/{deviceId}")
    public Map<String, Object> deleteLocalDevice(@PathVariable String deviceId) {
        try {
            if (!configManager.isLocalTemporaryDevice(deviceId)) {
                return error("只能删除本地临时设备配置: " + deviceId);
            }
            if (collectionService.isDeviceRunning(deviceId) && !collectionService.stopDevice(deviceId)) {
                return error("设备正在运行且停止失败，未删除: " + deviceId);
            }
            boolean deleted = configManager.deleteLocalDeviceConfig(deviceId);
            return deleted
                    ? success("本地临时设备已删除", Map.of("deviceId", deviceId,
                    "configSource", ConfigManager.CONFIG_SOURCE_LOCAL,
                    "temporaryConfig", true))
                    : error("本地临时设备不存在: " + deviceId);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    @GetMapping("/device/{deviceId}")
    public Map<String, Object> getDevice(@PathVariable String deviceId) {
        DeviceInfo local = configManager.getDevice(deviceId);
        DeviceInfo remote = configSyncService.getDeviceConfigs().get(deviceId);
        if (local == null && remote == null) {
            return notFound("设备不存在: " + deviceId);
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
            return notFound("设备不存在: " + deviceId);
        }
        List<DataPoint> points = configManager.getDataPoints(deviceId);
        List<DataPoint> responsePoints = includeAdaptive
                ? points.stream().map(point -> withRuntimeState(deviceId, point)).toList()
                : points;
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", deviceId);
        data.put("count", responsePoints.size());
        data.put("points", responsePoints);
        return success(data);
    }

    /**
     * 执行当前业务逻辑。
     */
    private DataPoint withRuntimeState(String deviceId, DataPoint point) {
        DataPoint response = new DataPoint();
        BeanUtils.copyProperties(point, response);
        PointRuntimeStateSnapshot state = pointRuntimeStateService.snapshot(deviceId, point);
        response.setCurrentCollectionInterval(state.currentCollectionInterval());
        response.setStableCount(state.stableCount());
        response.setLastValue(state.lastValue());
        response.setChangeRate(state.changeRate());
        response.setLastAdjustTime(state.lastAdjustTime());
        return response;
    }

    @GetMapping("/device/{deviceId}/connection")
    public Map<String, Object> getDeviceConnection(@PathVariable String deviceId) {
        if (!configManager.containsDevice(deviceId)) {
            return notFound("设备不存在: " + deviceId);
        }
        DeviceConnection connection = configManager.getConnectionConfig(deviceId);
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", deviceId);
        data.put("connection", sensitiveConfigSanitizer.sanitize(connection));
        return success(data);
    }

    /**
     * 执行当前业务逻辑。
     */
    @GetMapping("/device/{deviceId}/diff")
    public Map<String, Object> diff(@PathVariable String deviceId) {
        DeviceInfo local = configManager.getDevice(deviceId);
        DeviceInfo remote = configSyncService.getDeviceConfigs().get(deviceId);
        if (local == null && remote == null) {
            return notFound("无法比对，设备不存在: " + deviceId);
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

    /**
     * 更新或刷新业务状态。
     */
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

    /**
     * 更新或刷新业务状态。
     */
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

    /**
     * 更新或刷新业务状态。
     */
    @PutMapping("/device/{deviceId}/connection")
    public Map<String, Object> updateConnection(@PathVariable String deviceId,
                                                @RequestBody DeviceConnection connection) {
        if (connection == null) {
            return error("连接配置不能为空");
        }
        connection.setDeviceId(deviceId);
        sensitiveConfigSanitizer.restoreMaskedValues(
                connection, configManager.getConnectionConfig(deviceId));
        boolean updated = configManager.updateConnectionConfig(deviceId, connection);
        return updated ? success("连接配置已更新", Map.of("deviceId", deviceId))
                : error("更新连接配置失败: " + deviceId);
    }

    /**
     * 更新或刷新业务状态。
     */
    @PostMapping("/device/{deviceId}/refresh")
    public Map<String, Object> refreshDevice(@PathVariable String deviceId) {
        boolean refreshed = configManager.refreshDeviceConfig(deviceId);
        return refreshed ? success("设备配置已刷新", Map.of("deviceId", deviceId))
                : error("刷新失败，设备配置不完整: " + deviceId);
    }

    /**
     * 清理或删除业务数据。
     */
    @PostMapping("/device/{deviceId}/clear")
    public Map<String, Object> clearDevice(@PathVariable String deviceId) {
        boolean cleared = configManager.clearDeviceConfig(deviceId);
        return cleared ? success("设备配置缓存已清空", Map.of("deviceId", deviceId))
                : error("设备配置不存在或已清空: " + deviceId);
    }

    /**
     * 更新或刷新业务状态。
     */
    @PostMapping("/sync")
    public Map<String, Object> triggerFullSync() {
        configSyncService.triggerManualSync();
        return success("已触发异步全量同步任务", null);
    }

    /**
     * 更新或刷新业务状态。
     */
    @PostMapping("/sync/{type}")
    public Map<String, Object> triggerPartialSync(@PathVariable String type,
                                                  @RequestParam(value = "deviceId", required = false)
                                                  String deviceId) {
        ConfigUpdateType updateType = ConfigUpdateType.fromValue(type)
                .filter(value -> value != ConfigUpdateType.LOCAL && value != ConfigUpdateType.LOCAL_DELETE)
                .orElse(null);
        if (updateType == null) {
            return error("不支持的同步类型: " + type);
        }
        configSyncService.notifyConfigUpdate(updateType.getValue(), deviceId);
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", deviceId);
        return success("已触发 " + updateType.getValue() + " 同步", data);
    }

    @GetMapping("/sync/status")
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> data = new HashMap<>();
        data.put("serviceId", configSyncService.getServiceId());
        data.put("lastSyncTime", configSyncService.getLastSyncTime());
        data.put("syncInterval", configSyncService.getSyncInterval());
        data.put("listenerCount", configSyncService.getListenerCount());
        data.put("consecutiveFailures", configSyncService.getConsecutiveFailures());
        data.put("lastFailureTime", configSyncService.getLastFailureTime());
        data.put("sourceVersion", configSyncService.getSourceVersion());
        data.put("snapshotDeviceCount", configSyncService.getSnapshotDeviceCount());
        return success(data);
    }

    /**
     * 执行当前业务逻辑。
     */
    @GetMapping("/export")
    public Map<String, Object> exportConfigs() {
        List<DeviceContext> contexts = configManager.getAllDeviceContexts();
        List<ConfigBundle> bundles = contexts.stream()
                .map(ctx -> ConfigBundle.builder()
                        .device(ctx.getDeviceInfo())
                        .connection(sensitiveConfigSanitizer.sanitize(ctx.getConnectionConfig()))
                        .points(ctx.getDataPoints())
                        .build())
                .collect(Collectors.toList());
        ConfigExportResponse response = ConfigExportResponse.builder()
                .bundles(bundles)
                .build();
        return success(response);
    }

    /**
     * 执行当前业务逻辑。
     */
    @PostMapping("/import")
    public Map<String, Object> importConfigs(@Valid @RequestBody ConfigImportRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getBundles())) {
            return error("导入内容不能为空");
        }
        List<DeviceContext> importContexts = new ArrayList<>();
        List<String> deviceIds = new ArrayList<>();
        for (ConfigBundle bundle : request.getBundles()) {
            String deviceId = resolveDeviceId(bundle);
            if (!StringUtils.hasText(deviceId)) {
                return error("导入内容存在缺少设备ID的配置");
            }
            DeviceInfo device = bundle.getDevice() != null
                    ? bundle.getDevice() : configManager.getDevice(deviceId);
            if (device == null) {
                return error("导入设备不存在且未提供设备基础信息: " + deviceId);
            }
            device.setDeviceId(deviceId);

            DeviceConnection connection = bundle.getConnection() != null
                    ? bundle.getConnection() : configManager.getConnectionConfig(deviceId);
            if (connection != null) {
                connection.setDeviceId(deviceId);
                sensitiveConfigSanitizer.restoreMaskedValues(
                        connection, configManager.getConnectionConfig(deviceId));
            }
            List<DataPoint> points = CollectionUtils.isEmpty(bundle.getPoints())
                    ? configManager.getDataPoints(deviceId) : bundle.getPoints();
            importContexts.add(DeviceContext.of(device, connection, points));
            deviceIds.add(deviceId);
        }

        boolean imported = configManager.replaceDeviceContextsAtomically(importContexts);
        if (!imported) {
            ConfigImportResult result = ConfigImportResult.builder()
                    .total(request.getBundles().size())
                    .success(0)
                    .failedDevices(deviceIds)
                    .build();
            return error("导入失败，现有配置未发生变化", result);
        }
        if (request.isReloadAfterImport()) {
            collectionService.reloadAllDevices();
        }

        ConfigImportResult result = ConfigImportResult.builder()
                .total(request.getBundles().size())
                .success(request.getBundles().size())
                .failedDevices(Collections.emptyList())
                .build();
        return success("导入完成", result);
    }

    /**
     * 创建并返回业务对象。
     */
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

    /**
     * 执行当前业务逻辑。
     */
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

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 写入或持久化业务数据。
     */
    private Map<String, Object> saveLocalDevice(LocalDeviceConfigRequest request,
                                                String pathDeviceId,
                                                boolean overwrite) {
        if (request == null || request.getDevice() == null) {
            return error("本地设备配置不能为空");
        }
        DeviceInfo device = request.getDevice();
        if (StringUtils.hasText(pathDeviceId)) {
            device.setDeviceId(pathDeviceId);
        }
        if (!StringUtils.hasText(device.getDeviceId())) {
            return error("deviceId 不能为空");
        }
        try {
            boolean saved = configManager.saveLocalDeviceConfig(
                    device,
                    request.getConnection(),
                    request.getPoints(),
                    overwrite || request.isOverwrite());
            if (!saved) {
                return error("保存本地临时设备失败: " + device.getDeviceId());
            }

            boolean started = false;
            if (request.isStartAfterSave()) {
                started = collectionService.startLocalDevice(device.getDeviceId());
            }

            Map<String, Object> data = new HashMap<>();
            data.put("deviceId", device.getDeviceId());
            data.put("configSource", ConfigManager.CONFIG_SOURCE_LOCAL);
            data.put("temporaryConfig", true);
            data.put("started", started);
            data.put("pointCount", request.getPoints() != null ? request.getPoints().size() : 0);
            String message = request.isStartAfterSave() && !started
                    ? "本地临时设备已保存，但启动失败"
                    : "本地临时设备已保存";
            return success(message, data);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 构造标准业务结果。
     */
    private Map<String, Object> success(Object data) {
        return success("OK", data);
    }

    /**
     * 构造标准业务结果。
     */
    private Map<String, Object> success(String message, Object data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "success");
        payload.put("message", message);
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("data", data);
        return payload;
    }

    /**
     * 构造标准业务结果。
     */
    private Map<String, Object> error(String message) {
        return error(message, null);
    }

    /**
     * 构造标准业务结果。
     */
    private Map<String, Object> error(String message, Object data) {
        throw new ConfigApiException(HttpStatus.BAD_REQUEST, message, data);
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> notFound(String message) {
        throw new ConfigApiException(HttpStatus.NOT_FOUND, message, null);
    }
}
