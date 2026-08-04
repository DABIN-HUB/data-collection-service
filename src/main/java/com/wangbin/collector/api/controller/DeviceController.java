package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.DeviceConsoleApplicationService;
import com.wangbin.collector.api.controller.dto.DeviceControllerResponse;
import com.wangbin.collector.api.validation.ApiValidationConstants;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
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
 * <p>只负责 HTTP 路由和参数校验，设备控制台业务编排由应用服务处理。</p>
 */
@Validated
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceConsoleApplicationService deviceConsoleApplicationService;

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
        return deviceConsoleApplicationService.startDevice(deviceId);
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
        return deviceConsoleApplicationService.startLocalDevice(deviceId);
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
        return deviceConsoleApplicationService.stopDevice(deviceId);
    }

    /**
     * 重新加载全部设备配置。
     *
     * @return 重载结果
     */
    @PostMapping("/reload")
    public DeviceControllerResponse<Object> reloadAllDevices() {
        return deviceConsoleApplicationService.reloadAllDevices();
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
        return deviceConsoleApplicationService.getDeviceStatus(deviceId);
    }

    /**
     * 查询全部采集统计。
     *
     * @return 全部采集统计响应
     */
    @GetMapping("/statistics")
    public DeviceControllerResponse<Map<String, Map<String, Object>>> getAllStatistics() {
        return deviceConsoleApplicationService.getAllStatistics();
    }

    /**
     * 查询正在运行的设备列表。
     *
     * @return 正在运行的设备列表响应
     */
    @GetMapping("/running")
    public DeviceControllerResponse<List<String>> getRunningDevices() {
        return deviceConsoleApplicationService.getRunningDevices();
    }

    /**
     * 查询全部设备运行快照。
     *
     * @return 设备运行快照响应
     */
    @GetMapping("/runtime")
    public DeviceControllerResponse<List<DeviceRuntimeSnapshot>> getDeviceRuntimeSnapshots() {
        return deviceConsoleApplicationService.getDeviceRuntimeSnapshots();
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
        return deviceConsoleApplicationService.isDeviceRunning(deviceId);
    }
}