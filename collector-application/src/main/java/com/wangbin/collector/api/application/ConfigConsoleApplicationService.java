package com.wangbin.collector.api.application;


import com.wangbin.collector.api.controller.dto.ConfigCacheStatsResponse;
import com.wangbin.collector.api.controller.dto.ConfigDeviceListResponse;
import com.wangbin.collector.api.controller.dto.ConfigDiffResponse;
import com.wangbin.collector.api.controller.dto.ConfigExportResponse;
import com.wangbin.collector.api.controller.dto.ConfigImportRequest;
import com.wangbin.collector.api.controller.dto.ConfigImportResult;
import com.wangbin.collector.api.controller.dto.ConfigSummaryResponse;
import com.wangbin.collector.api.controller.dto.ConfigSyncStatusResponse;
import com.wangbin.collector.api.controller.dto.DeviceConfigBundleRequest;
import com.wangbin.collector.api.controller.dto.DeviceConfigBundleResponse;
import com.wangbin.collector.api.controller.dto.DeviceConfigCommitResponse;
import com.wangbin.collector.api.controller.dto.DeviceConfigDetailResponse;
import com.wangbin.collector.api.controller.dto.DeviceConfigValidationResponse;
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
import com.wangbin.collector.core.config.manager.ConfigVersionConflictException;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.model.ConfigUpdateType;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.security.SensitiveConfigSanitizer;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import org.springframework.util.StringUtils;

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
        List<DeviceInfo> devices = configManager.getConfigurationDevices().stream().map(sensitiveConfigSanitizer::sanitize).toList();
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
        return deleteLocalDevice(deviceId, null);
    }

    public ApiResult<DeviceIdResponse> deleteLocalDevice(String deviceId, Long baseRevision) {
        return localDeviceConfigApplicationService.deleteLocalDevice(deviceId, baseRevision);
    }

    /**
     * 查询单设备本地和远端配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备配置详情响应
     */
    public ApiResult<DeviceConfigDetailResponse> getDevice(String deviceId) {
        DeviceInfo local = configManager.getConfigurationDevice(deviceId);
        DeviceInfo remote = configSyncService.getDeviceConfigs().get(deviceId);
        if (local == null && remote == null) {
            return notFound("设备不存在: " + deviceId);
        }
        DeviceConfigDetailResponse response = DeviceConfigDetailResponse.builder()
                .deviceId(deviceId)
                .local(sensitiveConfigSanitizer.sanitize(local))
                .remote(sensitiveConfigSanitizer.sanitize(remote))
                .inSync(Objects.equals(local, remote))
                .build();
        return success(response);
    }

    public ApiResult<DeviceConfigBundleResponse> getDeviceBundle(String deviceId) {
        ConfigManager.DeviceConfigurationSnapshot snapshot = configManager.getConfigurationDeviceSnapshot(deviceId);
        if (snapshot.context() == null) return notFound("设备不存在: " + deviceId);
        DeviceInfo device = snapshot.context().getDeviceInfo();
        DeviceConnection connection = sensitiveConfigSanitizer.sanitize(snapshot.context().copyConnectionConfig());
        boolean local = ConfigManager.CONFIG_SOURCE_LOCAL.equalsIgnoreCase(device.getConfigSource())
                && Boolean.TRUE.equals(device.getTemporaryConfig());
        return success(DeviceConfigBundleResponse.builder()
                .deviceId(deviceId)
                .configVersion(snapshot.configVersion())
                .persistedRevision(local ? snapshot.persistedRevision() : 0L)
                .device(sensitiveConfigSanitizer.sanitize(device))
                .connection(connection)
                .points(snapshot.context().copyDataPoints())
                .configSource(local ? ConfigManager.CONFIG_SOURCE_LOCAL : "remote")
                .temporaryConfig(local)
                .build());
    }

    public ApiResult<DeviceConfigValidationResponse> validateDeviceBundle(String deviceId, DeviceConfigBundleRequest request) {
        ConfigManager.DeviceConfigurationSnapshot snapshot = configManager.getConfigurationDeviceSnapshot(deviceId);
        long currentVersion = snapshot.configVersion();
        try {
            normalizeBundle(deviceId, request);
            sensitiveConfigSanitizer.restoreMaskedValues(request.getDevice(), snapshot.context() == null ? null : snapshot.context().getDeviceInfo());
            sensitiveConfigSanitizer.restoreMaskedValues(request.getConnection(), snapshot.context() == null ? null : snapshot.context().getConnectionConfig());
            configManager.validateDeviceContext(deviceId, request.getDevice(), request.getConnection(), request.getPoints());
            return success(DeviceConfigValidationResponse.builder().valid(true).currentVersion(currentVersion)
                    .currentRevision(snapshot.persistedRevision()).build());
        } catch (com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return success(DeviceConfigValidationResponse.builder().valid(false).currentVersion(currentVersion)
                    .currentRevision(snapshot.persistedRevision())
                    .errors(List.of("配置校验失败，请检查设备、连接及点位字段")).build());
        }
    }

    public ApiResult<DeviceConfigCommitResponse> commitDeviceBundle(String deviceId, DeviceConfigBundleRequest request) {
        normalizeBundle(deviceId, request);
        try {
            ConfigManager.DeviceConfigCommitResult result = configManager.replaceDeviceContextAtomically(
                    deviceId, request.getDevice(), request.getConnection(), request.getPoints(),
                    request.getBaseVersion(), request.getBaseRevision());
            return success("完整设备配置已保存，配置版本 v" + result.configVersion(),
                    DeviceConfigCommitResponse.builder().deviceId(deviceId).previousVersion(result.previousVersion())
                            .configVersion(result.configVersion()).persistedRevision(result.persistedRevision())
                            .pointCount(result.pointCount()).build());
        } catch (ConfigVersionConflictException exception) {
            if (exception.isPersistedRevision()) throw exception;
            throw new ConfigApiException(HttpStatus.CONFLICT,
                    "设备配置已被其他操作更新，请刷新后重新确认修改",
                    Map.of("deviceId", deviceId, "expectedVersion", exception.getExpectedVersion(),
                            "currentVersion", exception.getCurrentVersion()));
        } catch (IllegalArgumentException exception) {
            throw new ConfigApiException(HttpStatus.BAD_REQUEST, "配置字段不合法，请检查设备、连接及点位配置", null);
        }
    }

    /** SQLite 用户写入口不能把远端托管配置保存成仅内存成功。 */
    private void requireUserWritableDevice(String deviceId) {
        if (configManager.isSqlitePersistenceEnabled() && !configManager.isLocalTemporaryDevice(deviceId)) {
            throw new ConfigApiException(HttpStatus.BAD_REQUEST, "SQLite 模式仅支持本地设备配置写入，远端托管设备请通过远端权威来源修改", null);
        }
    }

    /** 完整与局部编辑均保留已有来源，不能通过客户端字段改变所有权。 */
    private void preserveSource(String deviceId, DeviceInfo incoming) {
        DeviceInfo existing = configManager.getConfigurationDevice(deviceId);
        if (existing == null) return;
        if (StringUtils.hasText(incoming.getConfigSource())
                && !Objects.equals(existing.getConfigSource(), incoming.getConfigSource())) {
            throw new ConfigApiException(HttpStatus.BAD_REQUEST, "禁止修改设备配置来源", null);
        }
        incoming.setConfigSource(existing.getConfigSource());
        incoming.setTemporaryConfig(existing.getTemporaryConfig());
    }

    private void validateIdentity(String deviceId, String payloadDeviceId) {
        if (StringUtils.hasText(payloadDeviceId) && !deviceId.equals(payloadDeviceId)) {
            throw new ConfigApiException(HttpStatus.BAD_REQUEST, "路径与配置 deviceId 不一致，禁止修改设备身份", null);
        }
    }

    private void normalizeBundle(String deviceId, DeviceConfigBundleRequest request) {
        if (request == null || request.getDevice() == null || request.getConnection() == null) {
            throw new ConfigApiException(HttpStatus.BAD_REQUEST, "配置 Bundle 不能为空", null);
        }
        requireUserWritableDevice(deviceId);
        preserveSource(deviceId, request.getDevice());
        validateIdentity(deviceId, request.getDevice().getDeviceId());
        validateIdentity(deviceId, request.getConnection().getDeviceId());
        request.getDevice().setDeviceId(deviceId);
        request.getConnection().setDeviceId(deviceId);
        if (request.getPoints() != null) {
            for (DataPoint point : request.getPoints()) {
                if (point == null) throw new ConfigApiException(HttpStatus.BAD_REQUEST, "点位不能为空", null);
                validateIdentity(deviceId, point.getDeviceId());
            }
            request.getPoints().forEach(point -> point.setDeviceId(deviceId));
        }
    }
    /** 查询设备点位配置。 */
    public ApiResult<DevicePointConfigResponse> getDevicePoints(String deviceId, boolean includeAdaptive) {
        DeviceContext context = configManager.getConfigurationPoints(deviceId);
        if (context == null) {
            return notFound("设备不存在: " + deviceId);
        }
        List<DataPoint> points = context.copyDataPoints();
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
        DeviceContext context = configManager.getConfigurationConnection(deviceId);
        if (context == null) {
            return notFound("设备不存在: " + deviceId);
        }
        DeviceConnection connection = context.copyConnectionConfig();
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
        return updateDevice(deviceId, device, null);
    }

    public ApiResult<DeviceIdResponse> updateDevice(String deviceId, DeviceInfo device, Long baseRevision) {
        if (device == null) {
            return error("请求体不能为空");
        }
        requireUserWritableDevice(deviceId);
        preserveSource(deviceId, device);
        validateIdentity(deviceId, device.getDeviceId());
        device.setDeviceId(deviceId);
        boolean updated = configManager.updateDeviceConfig(device, baseRevision);
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
        return updatePoints(deviceId, points, null);
    }

    public ApiResult<DeviceIdResponse> updatePoints(String deviceId, List<DataPoint> points, Long baseRevision) {
        requireUserWritableDevice(deviceId);
        if (points == null) {
            return error("数据点列表不能为空");
        }
        for (DataPoint point : points) {
            if (point == null) return error("点位不能为空");
            validateIdentity(deviceId, point.getDeviceId());
        }
        boolean updated = configManager.updateDataPoints(deviceId, points, baseRevision);
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
        return updateConnection(deviceId, connection, null);
    }

    public ApiResult<DeviceIdResponse> updateConnection(String deviceId, DeviceConnection connection, Long baseRevision) {
        if (connection == null) {
            return error("连接配置不能为空");
        }
        requireUserWritableDevice(deviceId);
        validateIdentity(deviceId, connection.getDeviceId());
        connection.setDeviceId(deviceId);
        // 掩码恢复由 Manager 在写锁内统一执行。
        boolean updated = configManager.updateConnectionConfig(deviceId, connection, baseRevision);
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
        DataPoint response = DeviceContext.copyPointConfiguration(point);
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
