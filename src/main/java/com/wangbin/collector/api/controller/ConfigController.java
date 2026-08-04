package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.ConfigConsoleApplicationService;
import com.wangbin.collector.api.controller.dto.ApiResponse;
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
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 配置治理控制器。
 *
 * <p>只负责配置管理 HTTP 路由和参数绑定，具体配置编排由应用服务处理。</p>
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigConsoleApplicationService configConsoleApplicationService;

    /**
     * 查询配置管理概览。
     *
     * @return 配置管理概览响应
     */
    @GetMapping("/summary")
    public ApiResponse<ConfigSummaryResponse> getSummary() {
        return configConsoleApplicationService.getSummary();
    }

    /**
     * 查询全部设备配置。
     *
     * @return 设备配置列表响应
     */
    @GetMapping("/devices")
    public ApiResponse<ConfigDeviceListResponse> getAllDevices() {
        return configConsoleApplicationService.getAllDevices();
    }

    /**
     * 创建本地临时设备。
     *
     * @param request 本地临时设备配置
     * @return 保存结果
     */
    @PostMapping("/local/devices")
    public ApiResponse<LocalDeviceConfigResponse> createLocalDevice(
            @Valid @RequestBody LocalDeviceConfigRequest request) {
        return configConsoleApplicationService.createLocalDevice(request);
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
        return configConsoleApplicationService.updateLocalDevice(deviceId, request);
    }

    /**
     * 查询本地临时设备配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 本地临时设备配置响应
     */
    @GetMapping("/local/device/{deviceId}")
    public ApiResponse<LocalDeviceConfigResponse> getLocalDevice(@PathVariable String deviceId) {
        return configConsoleApplicationService.getLocalDevice(deviceId);
    }

    /**
     * 删除本地临时设备。
     *
     * @param deviceId 本地设备唯一标识
     * @return 删除结果
     */
    @DeleteMapping("/local/device/{deviceId}")
    public ApiResponse<DeviceIdResponse> deleteLocalDevice(@PathVariable String deviceId) {
        return configConsoleApplicationService.deleteLocalDevice(deviceId);
    }

    /**
     * 查询单设备本地和远端配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 设备配置详情响应
     */
    @GetMapping("/device/{deviceId}")
    public ApiResponse<DeviceConfigDetailResponse> getDevice(@PathVariable String deviceId) {
        return configConsoleApplicationService.getDevice(deviceId);
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
        return configConsoleApplicationService.getDevicePoints(deviceId, includeAdaptive);
    }

    /**
     * 查询设备连接配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 已脱敏的连接配置响应
     */
    @GetMapping("/device/{deviceId}/connection")
    public ApiResponse<DeviceConnectionConfigResponse> getDeviceConnection(@PathVariable String deviceId) {
        return configConsoleApplicationService.getDeviceConnection(deviceId);
    }

    /**
     * 查询本地和远端配置差异。
     *
     * @param deviceId 本地设备唯一标识
     * @return 配置差异响应
     */
    @GetMapping("/device/{deviceId}/diff")
    public ApiResponse<ConfigDiffResponse> diff(@PathVariable String deviceId) {
        return configConsoleApplicationService.diff(deviceId);
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
        return configConsoleApplicationService.updateDevice(deviceId, device);
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
        return configConsoleApplicationService.updatePoints(deviceId, points);
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
        return configConsoleApplicationService.updateConnection(deviceId, connection);
    }

    /**
     * 刷新指定设备配置缓存。
     *
     * @param deviceId 本地设备唯一标识
     * @return 刷新结果
     */
    @PostMapping("/device/{deviceId}/refresh")
    public ApiResponse<DeviceIdResponse> refreshDevice(@PathVariable String deviceId) {
        return configConsoleApplicationService.refreshDevice(deviceId);
    }

    /**
     * 清空指定设备配置缓存。
     *
     * @param deviceId 本地设备唯一标识
     * @return 清理结果
     */
    @PostMapping("/device/{deviceId}/clear")
    public ApiResponse<DeviceIdResponse> clearDevice(@PathVariable String deviceId) {
        return configConsoleApplicationService.clearDevice(deviceId);
    }

    /**
     * 触发全量配置同步。
     *
     * @return 同步触发结果
     */
    @PostMapping("/sync")
    public ApiResponse<Object> triggerFullSync() {
        return configConsoleApplicationService.triggerFullSync();
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
        return configConsoleApplicationService.triggerPartialSync(type, deviceId);
    }

    /**
     * 查询配置同步状态。
     *
     * @return 配置同步状态响应
     */
    @GetMapping("/sync/status")
    public ApiResponse<ConfigSyncStatusResponse> getSyncStatus() {
        return configConsoleApplicationService.getSyncStatus();
    }

    /**
     * 导出当前设备配置。
     *
     * @return 配置导出响应
     */
    @GetMapping("/export")
    public ApiResponse<ConfigExportResponse> exportConfigs() {
        return configConsoleApplicationService.exportConfigs();
    }

    /**
     * 导入设备配置。
     *
     * @param request 配置导入请求
     * @return 配置导入结果
     */
    @PostMapping("/import")
    public ApiResponse<ConfigImportResult> importConfigs(@Valid @RequestBody ConfigImportRequest request) {
        return configConsoleApplicationService.importConfigs(request);
    }
}