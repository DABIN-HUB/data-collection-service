package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.ApiResponse;
import com.wangbin.collector.api.controller.dto.ConfigBundle;
import com.wangbin.collector.api.controller.dto.ConfigDeviceListResponse;
import com.wangbin.collector.api.controller.dto.ConfigDiffResponse;
import com.wangbin.collector.api.controller.dto.ConfigExportResponse;
import com.wangbin.collector.api.controller.dto.ConfigImportRequest;
import com.wangbin.collector.api.controller.dto.ConfigImportResult;
import com.wangbin.collector.api.controller.dto.ConfigSummaryResponse;
import com.wangbin.collector.api.controller.dto.ConfigSyncStatusResponse;
import com.wangbin.collector.api.controller.dto.DeviceConfigDetailResponse;
import com.wangbin.collector.api.controller.dto.DeviceConnectionConfigResponse;
import com.wangbin.collector.api.controller.dto.DeviceIdResponse;
import com.wangbin.collector.api.controller.dto.DevicePointConfigResponse;
import com.wangbin.collector.api.controller.dto.LocalDeviceConfigRequest;
import com.wangbin.collector.api.controller.dto.LocalDeviceConfigResponse;
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
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
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
 * 配置治理控制器。
 *
 * <p>负责设备配置、连接配置、点位配置、本地临时设备和配置同步管理。</p>
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

    /**
     * 查询配置治理概览。
     *
     * @return 配置治理概览响应
     */
    @GetMapping("/summary")
    public ApiResponse<ConfigSummaryResponse> getSummary() {
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

    /**
     * 查询全部设备配置。
     *
     * @return 设备配置列表响应
     */
    @GetMapping("/devices")
    public ApiResponse<ConfigDeviceListResponse> getAllDevices() {
        List<DeviceInfo> devices = configManager.getAllDevices();
        ConfigDeviceListResponse response = ConfigDeviceListResponse.builder()
                .devices(devices)
                .count(devices.size())
                .build();
        return success(response);
    }

    /**
     * 创建本地临时设备。
     *
     * @param request 本地临时设备配置
     * @return 保存结果
     */
    @PostMapping("/local/devices")
    public ApiResponse<LocalDeviceConfigResponse> createLocalDevice(@Valid @RequestBody LocalDeviceConfigRequest request) {
        return saveLocalDevice(request, null, request != null && request.isOverwrite());
    }

    /**
     * 更新本地临时设备。
     *
     * @param deviceId 本地设备唯一标识
     * @param request 本地临时设备配置
     * @return 保存结果
     */
    @PutMapping("/local/device/{deviceId}")
    public ApiResponse<LocalDeviceConfigResponse> updateLocalDevice(@PathVariable String deviceId,
                                                                    @Valid @RequestBody LocalDeviceConfigRequest request) {
        return saveLocalDevice(request, deviceId, true);
    }

    /**
     * 查询本地临时设备配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 本地临时设备配置响应
     */
    @GetMapping("/local/device/{deviceId}")
    public ApiResponse<LocalDeviceConfigResponse> getLocalDevice(@PathVariable String deviceId) {
        if (!configManager.isLocalTemporaryDevice(deviceId)) {
            return error("只能读取本地临时设备配置: " + deviceId);
        }
        ConfigBundle bundle = ConfigBundle.builder()
                .device(configManager.getDevice(deviceId))
                .connection(configManager.getConnectionConfig(deviceId))
                .points(configManager.getDataPoints(deviceId))
                .build();
        LocalDeviceConfigResponse response = LocalDeviceConfigResponse.builder()
                .deviceId(deviceId)
                .configSource(ConfigManager.CONFIG_SOURCE_LOCAL)
                .temporaryConfig(true)
                .bundle(bundle)
                .build();
        return success(response);
    }

    /**
     * 删除本地临时设备。
     *
     * @param deviceId 本地设备唯一标识
     * @return 删除结果
     */
    @DeleteMapping("/local/device/{deviceId}")
    public ApiResponse<DeviceIdResponse> deleteLocalDevice(@PathVariable String deviceId) {
        try {
            if (!configManager.isLocalTemporaryDevice(deviceId)) {
                return error("只能删除本地临时设备配置: " + deviceId);
            }
            if (collectionService.isDeviceRunning(deviceId) && !collectionService.stopDevice(deviceId)) {
                return error("设备正在运行且停止失败，未删除: " + deviceId);
            }
            boolean deleted = configManager.deleteLocalDeviceConfig(deviceId);
            DeviceIdResponse response = DeviceIdResponse.builder()
                    .deviceId(deviceId)
                    .configSource(ConfigManager.CONFIG_SOURCE_LOCAL)
                    .temporaryConfig(true)
                    .build();
            return deleted ? success("本地临时设备已删除", response)
                    : error("本地临时设备不存在: " + deviceId);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 查询单设备本地和远端配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备配置详情响应
     */
    @GetMapping("/device/{deviceId}")
    public ApiResponse<DeviceConfigDetailResponse> getDevice(@PathVariable String deviceId) {
        DeviceInfo local = configManager.getDevice(deviceId);
        DeviceInfo remote = configSyncService.getDeviceConfigs().get(deviceId);
        if (local == null && remote == null) {
            return notFound("设备不存在: " + deviceId);
        }
        DeviceConfigDetailResponse response = DeviceConfigDetailResponse.builder()
                .deviceId(deviceId)
                .local(local)
                .remote(remote)
                .inSync(Objects.equals(local, remote))
                .build();
        return success(response);
    }

    /**
     * 查询设备点位配置。
     *
     * @param deviceId 本地设备唯一标识
     * @param includeAdaptive 是否包含运行期自适应字段
     * @return 设备点位配置响应
     */
    @GetMapping("/device/{deviceId}/points")
    public ApiResponse<DevicePointConfigResponse> getDevicePoints(@PathVariable String deviceId,
                                                                  @RequestParam(value = "includeAdaptive", defaultValue = "false")
                                                                  boolean includeAdaptive) {
        if (!configManager.containsDevice(deviceId)) {
            return notFound("设备不存在: " + deviceId);
        }
        List<DataPoint> points = configManager.getDataPoints(deviceId);
        List<DataPoint> responsePoints = includeAdaptive
                ? points.stream().map(point -> withRuntimeState(deviceId, point)).toList()
                : points;
        DevicePointConfigResponse response = DevicePointConfigResponse.builder()
                .deviceId(deviceId)
                .count(responsePoints.size())
                .points(responsePoints)
                .build();
        return success(response);
    }

    /**
     * 为点位配置补充运行期自适应状态。
     *
     * @param deviceId 本地设备唯一标识
     * @param point 点位配置
     * @return 带运行期状态的点位配置副本
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

    /**
     * 查询设备连接配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 已脱敏的连接配置响应
     */
    @GetMapping("/device/{deviceId}/connection")
    public ApiResponse<DeviceConnectionConfigResponse> getDeviceConnection(@PathVariable String deviceId) {
        if (!configManager.containsDevice(deviceId)) {
            return notFound("设备不存在: " + deviceId);
        }
        DeviceConnection connection = configManager.getConnectionConfig(deviceId);
        DeviceConnectionConfigResponse response = DeviceConnectionConfigResponse.builder()
                .deviceId(deviceId)
                .connection(sensitiveConfigSanitizer.sanitize(connection))
                .build();
        return success(response);
    }

    /**
     * 查询本地和远端配置差异。
     *
     * @param deviceId 本地设备唯一标识
     * @return 配置差异响应
     */
    @GetMapping("/device/{deviceId}/diff")
    public ApiResponse<ConfigDiffResponse> diff(@PathVariable String deviceId) {
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
     * 更新设备基础配置。
     *
     * @param deviceId 本地设备唯一标识
     * @param device 设备基础配置
     * @return 更新结果
     */
    @PutMapping("/device/{deviceId}")
    public ApiResponse<DeviceIdResponse> updateDevice(@PathVariable String deviceId,
                                                      @RequestBody DeviceInfo device) {
        if (device == null) {
            return error("请求体不能为空");
        }
        device.setDeviceId(deviceId);
        boolean updated = configManager.updateDeviceConfig(device);
        return updated ? success("设备配置已更新", DeviceIdResponse.builder().deviceId(deviceId).build())
                : error("更新设备配置失败: " + deviceId);
    }

    /**
     * 更新设备点位配置。
     *
     * @param deviceId 本地设备唯一标识
     * @param points 点位配置列表
     * @return 更新结果
     */
    @PutMapping("/device/{deviceId}/points")
    public ApiResponse<DeviceIdResponse> updatePoints(@PathVariable String deviceId,
                                                      @RequestBody List<DataPoint> points) {
        if (CollectionUtils.isEmpty(points)) {
            return error("数据点列表不能为空");
        }
        boolean updated = configManager.updateDataPoints(deviceId, points);
        DeviceIdResponse response = DeviceIdResponse.builder()
                .deviceId(deviceId)
                .count(points.size())
                .build();
        return updated ? success("数据点配置已更新", response)
                : error("更新数据点配置失败: " + deviceId);
    }

    /**
     * 更新设备连接配置。
     *
     * @param deviceId 本地设备唯一标识
     * @param connection 连接配置
     * @return 更新结果
     */
    @PutMapping("/device/{deviceId}/connection")
    public ApiResponse<DeviceIdResponse> updateConnection(@PathVariable String deviceId,
                                                          @RequestBody DeviceConnection connection) {
        if (connection == null) {
            return error("连接配置不能为空");
        }
        connection.setDeviceId(deviceId);
        sensitiveConfigSanitizer.restoreMaskedValues(connection, configManager.getConnectionConfig(deviceId));
        boolean updated = configManager.updateConnectionConfig(deviceId, connection);
        return updated ? success("连接配置已更新", DeviceIdResponse.builder().deviceId(deviceId).build())
                : error("更新连接配置失败: " + deviceId);
    }

    /**
     * 刷新指定设备配置缓存。
     *
     * @param deviceId 本地设备唯一标识
     * @return 刷新结果
     */
    @PostMapping("/device/{deviceId}/refresh")
    public ApiResponse<DeviceIdResponse> refreshDevice(@PathVariable String deviceId) {
        boolean refreshed = configManager.refreshDeviceConfig(deviceId);
        return refreshed ? success("设备配置已刷新", DeviceIdResponse.builder().deviceId(deviceId).build())
                : error("刷新失败，设备配置不完整: " + deviceId);
    }

    /**
     * 清空指定设备配置缓存。
     *
     * @param deviceId 本地设备唯一标识
     * @return 清理结果
     */
    @PostMapping("/device/{deviceId}/clear")
    public ApiResponse<DeviceIdResponse> clearDevice(@PathVariable String deviceId) {
        boolean cleared = configManager.clearDeviceConfig(deviceId);
        return cleared ? success("设备配置缓存已清空", DeviceIdResponse.builder().deviceId(deviceId).build())
                : error("设备配置不存在或已清空: " + deviceId);
    }

    /**
     * 触发全量配置同步。
     *
     * @return 同步触发结果
     */
    @PostMapping("/sync")
    public ApiResponse<Object> triggerFullSync() {
        configSyncService.triggerManualSync();
        return success("已触发异步全量同步任务", null);
    }

    /**
     * 触发指定类型配置同步。
     *
     * @param type 同步类型
     * @param deviceId 可选设备标识
     * @return 同步触发结果
     */
    @PostMapping("/sync/{type}")
    public ApiResponse<DeviceIdResponse> triggerPartialSync(@PathVariable String type,
                                                            @RequestParam(value = "deviceId", required = false)
                                                            String deviceId) {
        ConfigUpdateType updateType = ConfigUpdateType.fromValue(type)
                .filter(value -> value != ConfigUpdateType.LOCAL && value != ConfigUpdateType.LOCAL_DELETE)
                .orElse(null);
        if (updateType == null) {
            return error("不支持的同步类型: " + type);
        }
        configSyncService.notifyConfigUpdate(updateType.getValue(), deviceId);
        return success("已触发 " + updateType.getValue() + " 同步",
                DeviceIdResponse.builder().deviceId(deviceId).build());
    }

    /**
     * 查询配置同步状态。
     *
     * @return 配置同步状态响应
     */
    @GetMapping("/sync/status")
    public ApiResponse<ConfigSyncStatusResponse> getSyncStatus() {
        ConfigSyncStatusResponse response = ConfigSyncStatusResponse.builder()
                .serviceId(configSyncService.getServiceId())
                .lastSyncTime(configSyncService.getLastSyncTime())
                .syncInterval(configSyncService.getSyncInterval())
                .listenerCount(configSyncService.getListenerCount())
                .consecutiveFailures(configSyncService.getConsecutiveFailures())
                .lastFailureTime(configSyncService.getLastFailureTime())
                .sourceVersion(configSyncService.getSourceVersion())
                .snapshotDeviceCount(configSyncService.getSnapshotDeviceCount())
                .build();
        return success(response);
    }

    /**
     * 导出当前设备配置。
     *
     * @return 配置导出响应
     */
    @GetMapping("/export")
    public ApiResponse<ConfigExportResponse> exportConfigs() {
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
     * 导入设备配置。
     *
     * @param request 配置导入请求
     * @return 配置导入结果
     */
    @PostMapping("/import")
    public ApiResponse<ConfigImportResult> importConfigs(@Valid @RequestBody ConfigImportRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getBundles())) {
            return error("导入内容不能为空");
        }
        List<DeviceContext> importContexts = new ArrayList<>();
        List<String> deviceIds = new ArrayList<>();
        for (ConfigBundle bundle : request.getBundles()) {
            String deviceId = resolveDeviceId(bundle);
            if (!StringUtils.hasText(deviceId)) {
                return error("导入内容存在缺少设备 ID 的配置");
            }
            DeviceInfo device = bundle.getDevice() != null ? bundle.getDevice() : configManager.getDevice(deviceId);
            if (device == null) {
                return error("导入设备不存在且未提供设备基础信息: " + deviceId);
            }
            device.setDeviceId(deviceId);

            DeviceConnection connection = bundle.getConnection() != null
                    ? bundle.getConnection() : configManager.getConnectionConfig(deviceId);
            if (connection != null) {
                connection.setDeviceId(deviceId);
                sensitiveConfigSanitizer.restoreMaskedValues(connection, configManager.getConnectionConfig(deviceId));
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
     * 构建本地与远端配置差异。
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
     * 按点位编码索引点位配置。
     *
     * @param points 点位配置列表
     * @return 点位编码到点位配置的映射
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
     * 从配置包中解析设备标识。
     *
     * @param bundle 配置包
     * @return 本地设备唯一标识
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
     * 保存本地临时设备配置。
     */
    private ApiResponse<LocalDeviceConfigResponse> saveLocalDevice(LocalDeviceConfigRequest request,
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

            LocalDeviceConfigResponse response = LocalDeviceConfigResponse.builder()
                    .deviceId(device.getDeviceId())
                    .configSource(ConfigManager.CONFIG_SOURCE_LOCAL)
                    .temporaryConfig(true)
                    .started(started)
                    .pointCount(request.getPoints() != null ? request.getPoints().size() : 0)
                    .build();
            String message = request.isStartAfterSave() && !started
                    ? "本地临时设备已保存，但启动失败"
                    : "本地临时设备已保存";
            return success(message, response);
        } catch (RuntimeException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 构建成功响应。
     */
    private <T> ApiResponse<T> success(T data) {
        return success("OK", data);
    }

    /**
     * 构建成功响应。
     */
    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.success(message, data);
    }

    /**
     * 抛出参数错误响应。
     */
    private <T> ApiResponse<T> error(String message) {
        return error(message, null);
    }

    /**
     * 抛出参数错误响应。
     */
    private <T> ApiResponse<T> error(String message, Object data) {
        throw new ConfigApiException(HttpStatus.BAD_REQUEST, message, data);
    }

    /**
     * 抛出资源不存在响应。
     */
    private <T> ApiResponse<T> notFound(String message) {
        throw new ConfigApiException(HttpStatus.NOT_FOUND, message, null);
    }
}