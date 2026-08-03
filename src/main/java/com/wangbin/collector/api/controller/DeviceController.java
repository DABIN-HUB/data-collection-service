package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.DeviceControllerResponse;
import com.wangbin.collector.api.validation.ApiValidationConstants;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 设备管理控制器。
 *
 * <p>只负责设备启停、重载和运行状态查询，不直接修改采集主链路。</p>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_ERROR = "error";

    private final CollectionService collectionService;

    /**
     * 启动指定设备采集。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备启动结果
     */
    @PostMapping("/{deviceId}/start")
    public DeviceControllerResponse<Object> startDevice(
            @PathVariable
            @Pattern(regexp = ApiValidationConstants.DEVICE_ID_PATTERN,
                    message = ApiValidationConstants.DEVICE_ID_MESSAGE) String deviceId) {
        try {
            boolean success = collectionService.startDevice(deviceId);
            if (success) {
                return DeviceControllerResponse.success(deviceId, "设备启动成功");
            }
            return DeviceControllerResponse.error(deviceId, "设备已启动或启动失败");
        } catch (Exception e) {
            log.error("启动设备失败，设备={}", deviceId, e);
            return DeviceControllerResponse.error(deviceId, "启动异常: " + e.getMessage());
        }
    }

    /**
     * 启动本地临时设备采集。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备启动结果
     */
    @PostMapping("/{deviceId}/start-local")
    public DeviceControllerResponse<Object> startLocalDevice(
            @PathVariable
            @Pattern(regexp = ApiValidationConstants.DEVICE_ID_PATTERN,
                    message = ApiValidationConstants.DEVICE_ID_MESSAGE) String deviceId) {
        try {
            boolean success = collectionService.startLocalDevice(deviceId);
            if (success) {
                return DeviceControllerResponse.success(deviceId, "本地临时设备启动成功");
            }
            return DeviceControllerResponse.error(deviceId, "设备不是本地临时设备，或启动失败");
        } catch (Exception e) {
            log.error("启动本地临时设备失败，设备={}", deviceId, e);
            return DeviceControllerResponse.error(deviceId, "启动异常: " + e.getMessage());
        }
    }

    /**
     * 停止指定设备采集。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备停止结果
     */
    @PostMapping("/{deviceId}/stop")
    public DeviceControllerResponse<Object> stopDevice(
            @PathVariable
            @Pattern(regexp = ApiValidationConstants.DEVICE_ID_PATTERN,
                    message = ApiValidationConstants.DEVICE_ID_MESSAGE) String deviceId) {
        try {
            boolean success = collectionService.stopDevice(deviceId);
            if (success) {
                return DeviceControllerResponse.success(deviceId, "设备已停止");
            }
            return DeviceControllerResponse.error(deviceId, "设备停止失败或已经停止");
        } catch (Exception e) {
            log.error("停止设备失败，设备={}", deviceId, e);
            return DeviceControllerResponse.error(deviceId, "停止异常: " + e.getMessage());
        }
    }

    /**
     * 重新加载全部设备配置。
     *
     * @return 重载结果
     */
    @PostMapping("/reload")
    public DeviceControllerResponse<Object> reloadAllDevices() {
        try {
            collectionService.reloadAllDevices();
            return DeviceControllerResponse.builder()
                    .status(STATUS_SUCCESS)
                    .message("已重新加载所有设备")
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("重新加载所有设备失败", e);
            return DeviceControllerResponse.builder()
                    .status(STATUS_ERROR)
                    .message("重新加载异常: " + e.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询指定设备采集器状态。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备状态响应
     */
    @GetMapping("/{deviceId}/status")
    public DeviceControllerResponse<Map<String, Object>> getDeviceStatus(
            @PathVariable
            @Pattern(regexp = ApiValidationConstants.DEVICE_ID_PATTERN,
                    message = ApiValidationConstants.DEVICE_ID_MESSAGE) String deviceId) {
        try {
            Map<String, Object> status = collectionService.getDeviceStatus(deviceId);
            return DeviceControllerResponse.successData(deviceId, status);
        } catch (Exception e) {
            log.error("获取设备状态失败，设备={}", deviceId, e);
            return DeviceControllerResponse.<Map<String, Object>>builder()
                    .deviceId(deviceId)
                    .status(STATUS_ERROR)
                    .message("获取状态失败: " + e.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询全部采集统计。
     *
     * @return 全部采集统计响应
     */
    @GetMapping("/statistics")
    public DeviceControllerResponse<Map<String, Map<String, Object>>> getAllStatistics() {
        try {
            Map<String, Map<String, Object>> stats = collectionService.getAllStatistics();
            return DeviceControllerResponse.<Map<String, Map<String, Object>>>builder()
                    .status(STATUS_SUCCESS)
                    .data(stats)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("获取采集统计失败", e);
            return DeviceControllerResponse.<Map<String, Map<String, Object>>>builder()
                    .status(STATUS_ERROR)
                    .message("获取统计异常: " + e.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询正在运行的设备列表。
     *
     * @return 正在运行的设备列表响应
     */
    @GetMapping("/running")
    public DeviceControllerResponse<List<String>> getRunningDevices() {
        try {
            List<String> devices = collectionService.getRunningDevices();
            return DeviceControllerResponse.<List<String>>builder()
                    .status(STATUS_SUCCESS)
                    .data(devices)
                    .count(devices.size())
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("获取运行设备列表失败", e);
            return DeviceControllerResponse.<List<String>>builder()
                    .status(STATUS_ERROR)
                    .message("获取设备列表异常: " + e.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询全部设备运行快照。
     *
     * @return 设备运行快照响应
     */
    @GetMapping("/runtime")
    public DeviceControllerResponse<List<DeviceRuntimeSnapshot>> getDeviceRuntimeSnapshots() {
        List<DeviceRuntimeSnapshot> snapshots = collectionService.getDeviceRuntimeSnapshots();
        return DeviceControllerResponse.<List<DeviceRuntimeSnapshot>>builder()
                .status(STATUS_SUCCESS)
                .data(snapshots)
                .count(snapshots.size())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 查询指定设备是否正在运行。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备运行状态响应
     */
    @GetMapping("/{deviceId}/running")
    public DeviceControllerResponse<Object> isDeviceRunning(
            @PathVariable
            @Pattern(regexp = ApiValidationConstants.DEVICE_ID_PATTERN,
                    message = ApiValidationConstants.DEVICE_ID_MESSAGE) String deviceId) {
        try {
            boolean running = collectionService.isDeviceRunning(deviceId);
            return DeviceControllerResponse.builder()
                    .deviceId(deviceId)
                    .status(STATUS_SUCCESS)
                    .running(running)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("查询设备运行状态失败，设备={}", deviceId, e);
            return DeviceControllerResponse.error(deviceId, "查询运行状态异常: " + e.getMessage());
        }
    }
}