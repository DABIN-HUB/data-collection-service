package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.DeviceStatisticsResponse;
import com.wangbin.collector.api.controller.dto.DeviceOperationResponse;
import com.wangbin.collector.api.controller.dto.DeviceStatusResponse;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 设备控制台应用服务。
 *
 * <p>负责承接设备启停、重载和运行状态查询等控制台编排逻辑，控制器只保留路由和参数校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceConsoleApplicationService {

    private final CollectionService collectionService;

    /**
     * 启动指定设备采集。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备启动结果
     */
    public ApiResult<DeviceOperationResponse> startDevice(String deviceId) {
        return operate(deviceId, "START", () -> collectionService.startDevice(deviceId), "设备启动成功", "设备已启动或启动失败");
    }

    /**
     * 启动本地临时设备采集。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备启动结果
     */
    public ApiResult<DeviceOperationResponse> startLocalDevice(String deviceId) {
        return operate(deviceId, "START_LOCAL", () -> collectionService.startLocalDevice(deviceId), "本地临时设备启动成功", "设备不是本地临时设备，或启动失败");
    }

    /**
     * 停止指定设备采集。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备停止结果
     */
    public ApiResult<DeviceOperationResponse> stopDevice(String deviceId) {
        return operate(deviceId, "STOP", () -> collectionService.stopDevice(deviceId), "设备已停止", "设备停止失败或已经停止");
    }

    private ApiResult<DeviceOperationResponse> operate(String deviceId, String action, BooleanSupplier operation,
                                                        String successMessage, String failureMessage) {
        long acceptedAt = System.currentTimeMillis();
        String operationId = UUID.randomUUID().toString();
        try {
            boolean accepted = operation.getAsBoolean();
            DeviceRuntimeSnapshot runtime = collectionService.getDeviceRuntimeSnapshot(deviceId);
            DeviceOperationResponse response = DeviceOperationResponse.builder().operationId(operationId)
                    .deviceId(deviceId).action(action).accepted(accepted).acceptedAt(acceptedAt)
                    .completedAt(System.currentTimeMillis()).runtime(runtime).build();
            ApiResult<DeviceOperationResponse> result = accepted
                    ? ApiResult.statusSuccess(successMessage, response)
                    : ApiResult.statusError(failureMessage, response);
            return result.withDeviceId(deviceId);
        } catch (Exception exception) {
            log.error("设备操作失败，设备={}，action={}", deviceId, action, exception);
            DeviceOperationResponse response = DeviceOperationResponse.builder().operationId(operationId)
                    .deviceId(deviceId).action(action).accepted(false).acceptedAt(acceptedAt)
                    .completedAt(System.currentTimeMillis()).runtime(collectionService.getDeviceRuntimeSnapshot(deviceId)).build();
            String operationErrorPrefix = "STOP".equals(action) ? "停止异常: " : "启动异常: ";
            return ApiResult.<DeviceOperationResponse>statusError(operationErrorPrefix + exception.getMessage(), response)
                    .withDeviceId(deviceId);
        }
    }
    /** 重新加载全部设备配置。 */
    public ApiResult<Object> reloadAllDevices() {
        try {
            collectionService.reloadAllDevices();
            return ApiResult.statusSuccess("已触发设备重新加载", null);
        } catch (Exception exception) {
            log.error("重新加载所有设备失败", exception);
            return ApiResult.statusError("重新加载异常: " + exception.getMessage(), null);
        }
    }

    /**
     * 查询指定设备采集器状态。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备状态响应
     */
    public ApiResult<DeviceStatusResponse> getDeviceStatus(String deviceId) {
        try {
            Map<String, Object> status = collectionService.getDeviceStatus(deviceId);
            return ApiResult.deviceSuccessData(deviceId, DeviceStatusResponse.from(status));
        } catch (Exception exception) {
            log.error("获取设备状态失败，设备={}", deviceId, exception);
            return ApiResult.<DeviceStatusResponse>statusError("获取状态失败: " + exception.getMessage(), null)
                    .withDeviceId(deviceId);
        }
    }

    /**
     * 查询全部采集统计。
     *
     * @return 全部采集统计响应
     */
    public ApiResult<Map<String, DeviceStatisticsResponse>> getAllStatistics() {
        try {
            Map<String, Map<String, Object>> stats = collectionService.getAllStatistics();
            return ApiResult.statusSuccess(null, toStatisticsResponses(stats));
        } catch (Exception exception) {
            log.error("获取采集统计失败", exception);
            return ApiResult.statusError("获取统计异常: " + exception.getMessage(), null);
        }
    }

    /**
     * 查询正在运行的设备列表。
     *
     * @return 正在运行的设备列表响应
     */
    public ApiResult<List<String>> getRunningDevices() {
        try {
            List<String> devices = collectionService.getRunningDevices();
            return ApiResult.statusSuccess(null, devices).withCount(devices.size());
        } catch (Exception exception) {
            log.error("获取运行设备列表失败", exception);
            return ApiResult.statusError("获取设备列表异常: " + exception.getMessage(), null);
        }
    }

    public ApiResult<DeviceRuntimeSnapshot> getDeviceRuntimeSnapshot(String deviceId) {
        try {
            return ApiResult.statusSuccess(null, collectionService.getDeviceRuntimeSnapshot(deviceId));
        } catch (Exception exception) {
            log.error("获取设备运行快照失败，设备={}", deviceId, exception);
            return ApiResult.statusError("获取设备运行快照异常: " + exception.getMessage(), null);
        }
    }

    /** 查询全部设备运行快照。 */
    public ApiResult<List<DeviceRuntimeSnapshot>> getDeviceRuntimeSnapshots() {
        try {
            List<DeviceRuntimeSnapshot> snapshots = collectionService.getDeviceRuntimeSnapshots();
            return ApiResult.statusSuccess(null, snapshots).withCount(snapshots.size());
        } catch (Exception exception) {
            log.error("获取设备运行快照失败", exception);
            return ApiResult.statusError("获取设备运行快照异常: " + exception.getMessage(), null);
        }
    }

    /**
     * 查询指定设备是否正在运行。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备运行状态响应
     */
    public ApiResult<Object> isDeviceRunning(String deviceId) {
        try {
            boolean running = collectionService.isDeviceRunning(deviceId);
            return ApiResult.deviceSuccessData(deviceId, null).withRunning(running);
        } catch (Exception exception) {
            log.error("查询设备运行状态失败，设备={}", deviceId, exception);
            return ApiResult.deviceError(deviceId, "查询运行状态异常: " + exception.getMessage());
        }
    }

    private Map<String, DeviceStatisticsResponse> toStatisticsResponses(Map<String, Map<String, Object>> stats) {
        Map<String, DeviceStatisticsResponse> responses = new LinkedHashMap<>();
        if (stats == null || stats.isEmpty()) {
            return responses;
        }
        stats.forEach((deviceId, value) -> responses.put(deviceId, DeviceStatisticsResponse.from(value)));
        return responses;
    }
}
