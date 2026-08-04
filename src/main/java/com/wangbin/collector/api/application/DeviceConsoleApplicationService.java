package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.DeviceControllerResponse;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 设备控制台应用服务。
 *
 * <p>负责承接设备启停、重载和运行状态查询等控制台编排逻辑，控制器只保留路由和参数校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceConsoleApplicationService {

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_ERROR = "error";

    private final CollectionService collectionService;

    /**
     * 启动指定设备采集。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备启动结果
     */
    public DeviceControllerResponse<Object> startDevice(String deviceId) {
        try {
            boolean success = collectionService.startDevice(deviceId);
            if (success) {
                return DeviceControllerResponse.success(deviceId, "设备启动成功");
            }
            return DeviceControllerResponse.error(deviceId, "设备已启动或启动失败");
        } catch (Exception exception) {
            log.error("启动设备失败，设备={}", deviceId, exception);
            return DeviceControllerResponse.error(deviceId, "启动异常: " + exception.getMessage());
        }
    }

    /**
     * 启动本地临时设备采集。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备启动结果
     */
    public DeviceControllerResponse<Object> startLocalDevice(String deviceId) {
        try {
            boolean success = collectionService.startLocalDevice(deviceId);
            if (success) {
                return DeviceControllerResponse.success(deviceId, "本地临时设备启动成功");
            }
            return DeviceControllerResponse.error(deviceId, "设备不是本地临时设备，或启动失败");
        } catch (Exception exception) {
            log.error("启动本地临时设备失败，设备={}", deviceId, exception);
            return DeviceControllerResponse.error(deviceId, "启动异常: " + exception.getMessage());
        }
    }

    /**
     * 停止指定设备采集。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备停止结果
     */
    public DeviceControllerResponse<Object> stopDevice(String deviceId) {
        try {
            boolean success = collectionService.stopDevice(deviceId);
            if (success) {
                return DeviceControllerResponse.success(deviceId, "设备已停止");
            }
            return DeviceControllerResponse.error(deviceId, "设备停止失败或已经停止");
        } catch (Exception exception) {
            log.error("停止设备失败，设备={}", deviceId, exception);
            return DeviceControllerResponse.error(deviceId, "停止异常: " + exception.getMessage());
        }
    }

    /**
     * 重新加载全部设备配置。
     *
     * @return 重载结果
     */
    public DeviceControllerResponse<Object> reloadAllDevices() {
        try {
            collectionService.reloadAllDevices();
            return DeviceControllerResponse.builder()
                    .status(STATUS_SUCCESS)
                    .message("已重新加载所有设备")
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("重新加载所有设备失败", exception);
            return DeviceControllerResponse.builder()
                    .status(STATUS_ERROR)
                    .message("重新加载异常: " + exception.getMessage())
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
    public DeviceControllerResponse<Map<String, Object>> getDeviceStatus(String deviceId) {
        try {
            Map<String, Object> status = collectionService.getDeviceStatus(deviceId);
            return DeviceControllerResponse.successData(deviceId, status);
        } catch (Exception exception) {
            log.error("获取设备状态失败，设备={}", deviceId, exception);
            return DeviceControllerResponse.<Map<String, Object>>builder()
                    .deviceId(deviceId)
                    .status(STATUS_ERROR)
                    .message("获取状态失败: " + exception.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询全部采集统计。
     *
     * @return 全部采集统计响应
     */
    public DeviceControllerResponse<Map<String, Map<String, Object>>> getAllStatistics() {
        try {
            Map<String, Map<String, Object>> stats = collectionService.getAllStatistics();
            return DeviceControllerResponse.<Map<String, Map<String, Object>>>builder()
                    .status(STATUS_SUCCESS)
                    .data(stats)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("获取采集统计失败", exception);
            return DeviceControllerResponse.<Map<String, Map<String, Object>>>builder()
                    .status(STATUS_ERROR)
                    .message("获取统计异常: " + exception.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询正在运行的设备列表。
     *
     * @return 正在运行的设备列表响应
     */
    public DeviceControllerResponse<List<String>> getRunningDevices() {
        try {
            List<String> devices = collectionService.getRunningDevices();
            return DeviceControllerResponse.<List<String>>builder()
                    .status(STATUS_SUCCESS)
                    .data(devices)
                    .count(devices.size())
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("获取运行设备列表失败", exception);
            return DeviceControllerResponse.<List<String>>builder()
                    .status(STATUS_ERROR)
                    .message("获取设备列表异常: " + exception.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询全部设备运行快照。
     *
     * @return 设备运行快照响应
     */
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
    public DeviceControllerResponse<Object> isDeviceRunning(String deviceId) {
        try {
            boolean running = collectionService.isDeviceRunning(deviceId);
            return DeviceControllerResponse.builder()
                    .deviceId(deviceId)
                    .status(STATUS_SUCCESS)
                    .running(running)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("查询设备运行状态失败，设备={}", deviceId, exception);
            return DeviceControllerResponse.error(deviceId, "查询运行状态异常: " + exception.getMessage());
        }
    }
}
