package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.ConfigCacheStatsResponse;
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
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.model.ConfigUpdateType;
import com.wangbin.collector.core.config.security.SensitiveConfigSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 配置控制台应用服务。
 *
 * <p>保留控制台配置 API 的稳定入口，直接处理通用查询、更新和同步用例，
 * 本地临时设备、配置导入导出与差异计算委托给对应应用组件。</p>
 */
@Service
@RequiredArgsConstructor
public class ConfigConsoleApplicationService {

    private final ConfigManager configManager;
    private final ConfigSyncService configSyncService;
    private final SensitiveConfigSanitizer sensitiveConfigSanitizer;
    private final PointRuntimeStateService pointRuntimeStateService;
    private final LocalDeviceConfigApplicationService localDeviceConfigApplicationService;
    private final ConfigImportExportApplicationService configImportExportApplicationService;
    private final ConfigDiffCalculator configDiffCalculator;

    /**
     * 查询配置管理概览。
     *
     * @return 配置管理概览响应
     */
    public ApiResult<ConfigSummaryResponse> getSummary() {
        Map<String, Object> stats = configManager.getCacheStats();
        long lastSync = configSyncService.getLastSyncTime();
        long interval = configSyncService.getSyncInterval();
        Long nextSync = lastSync > 0 && interval > 0 ? lastSync + interval : null;

        ConfigSummaryResponse response = ConfigSummaryResponse.builder()
                .cacheStats(ConfigCacheStatsResponse.from(stats))
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
    public ApiResult<ConfigDeviceListResponse> getAllDevices() {
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
    public ApiResult<LocalDeviceConfigResponse> createLocalDevice(LocalDeviceConfigRequest request) {
        return localDeviceConfigApplicationService.createLocalDevice(request);
    }

    /**
     * 更新本地临时设备。
     *
     * @param deviceId 本地设备唯一标识
     * @param request 本地临时设备配置
     * @return 保存结果
     */
    public ApiResult<LocalDeviceConfigResponse> updateLocalDevice(String deviceId,
                                                                    LocalDeviceConfigRequest request) {
        return localDeviceConfigApplicationService.updateLocalDevice(deviceId, request);
    }

    /**
     * 查询本地临时设备配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 本地临时设备配置响应
     */
    public ApiResult<LocalDeviceConfigResponse> getLocalDevice(String deviceId) {
        return localDeviceConfigApplicationService.getLocalDevice(deviceId);
    }

    /**
     * 删除本地临时设备。
     *
     * @param deviceId 本地设备唯一标识
     * @return 删除结果
     */
    public ApiResult<DeviceIdResponse> deleteLocalDevice(String deviceId) {
        return localDeviceConfigApplicationService.deleteLocalDevice(deviceId);
    }

    /**
     * 查询单设备本地和远端配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备配置详情响应
     */
    public ApiResult<DeviceConfigDetailResponse> getDevice(String deviceId) {
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
    public ApiResult<DevicePointConfigResponse> getDevicePoints(String deviceId, boolean includeAdaptive) {
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
     * 查询设备连接配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 已脱敏的连接配置响应
     */
    public ApiResult<DeviceConnectionConfigResponse> getDeviceConnection(String deviceId) {
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
    public ApiResult<ConfigDiffResponse> diff(String deviceId) {
        DeviceInfo local = configManager.getDevice(deviceId);
        DeviceInfo remote = configSyncService.getDeviceConfigs().get(deviceId);
        if (local == null && remote == null) {
            return notFound("无法比较，设备不存在: " + deviceId);
        }
        DeviceConnection localConn = configManager.getConnectionConfig(deviceId);
        DeviceConnection remoteConn = configSyncService.getConnectionConfigs().get(deviceId);
        List<DataPoint> localPoints = configManager.getDataPoints(deviceId);
        List<DataPoint> remotePoints = configSyncService.getPointConfigs()
                .getOrDefault(deviceId, Collections.emptyList());

        ConfigDiffResponse response = configDiffCalculator.calculate(local, remote, localConn, remoteConn,
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
    public ApiResult<DeviceIdResponse> updateDevice(String deviceId, DeviceInfo device) {
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
    public ApiResult<DeviceIdResponse> updatePoints(String deviceId, List<DataPoint> points) {
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
    public ApiResult<DeviceIdResponse> updateConnection(String deviceId, DeviceConnection connection) {
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
    public ApiResult<DeviceIdResponse> refreshDevice(String deviceId) {
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
    public ApiResult<DeviceIdResponse> clearDevice(String deviceId) {
        boolean cleared = configManager.clearDeviceConfig(deviceId);
        return cleared ? success("设备配置缓存已清空", DeviceIdResponse.builder().deviceId(deviceId).build())
                : error("设备配置不存在或已清空: " + deviceId);
    }

    /**
     * 触发全量配置同步。
     *
     * @return 同步触发结果
     */
    public ApiResult<Object> triggerFullSync() {
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
    public ApiResult<DeviceIdResponse> triggerPartialSync(String type, String deviceId) {
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
    public ApiResult<ConfigSyncStatusResponse> getSyncStatus() {
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
    public ApiResult<ConfigExportResponse> exportConfigs() {
        return configImportExportApplicationService.exportConfigs();
    }

    /**
     * 导入设备配置。
     *
     * @param request 配置导入请求
     * @return 配置导入结果
     */
    public ApiResult<ConfigImportResult> importConfigs(ConfigImportRequest request) {
        return configImportExportApplicationService.importConfigs(request);
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
     * 构建成功响应。
     */
    private <T> ApiResult<T> success(T data) {
        return success("OK", data);
    }

    /**
     * 构建成功响应。
     */
    private <T> ApiResult<T> success(String message, T data) {
        return ApiResult.statusSuccess(message, data);
    }

    /**
     * 抛出参数错误响应。
     */
    private <T> ApiResult<T> error(String message) {
        return error(message, null);
    }

    /**
     * 抛出参数错误响应。
     */
    private <T> ApiResult<T> error(String message, Object data) {
        throw new ConfigApiException(HttpStatus.BAD_REQUEST, message, data);
    }

    /**
     * 抛出资源不存在响应。
     */
    private <T> ApiResult<T> notFound(String message) {
        throw new ConfigApiException(HttpStatus.NOT_FOUND, message, null);
    }
}
