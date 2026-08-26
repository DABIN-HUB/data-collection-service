package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.ConfigBundle;
import com.wangbin.collector.api.controller.dto.DeviceIdResponse;
import com.wangbin.collector.api.controller.dto.LocalDeviceConfigRequest;
import com.wangbin.collector.api.controller.dto.LocalDeviceConfigResponse;
import com.wangbin.collector.api.exception.ConfigApiException;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 本地临时设备配置应用服务。
 *
 * <p>只承接本地临时设备的创建、更新、读取和删除用例，保持远端配置不可被本地入口误删。</p>
 */
@Service
@RequiredArgsConstructor
public class LocalDeviceConfigApplicationService {

    private final ConfigManager configManager;
    private final CollectionService collectionService;

    /**
     * 创建本地临时设备。
     *
     * @param request 本地临时设备配置
     * @return 保存结果
     */
    public ApiResult<LocalDeviceConfigResponse> createLocalDevice(LocalDeviceConfigRequest request) {
        return saveLocalDevice(request, null, request != null && request.isOverwrite());
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
        return saveLocalDevice(request, deviceId, true);
    }

    /**
     * 查询本地临时设备配置。
     *
     * @param deviceId 本地设备唯一标识
     * @return 本地临时设备配置响应
     */
    public ApiResult<LocalDeviceConfigResponse> getLocalDevice(String deviceId) {
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
    public ApiResult<DeviceIdResponse> deleteLocalDevice(String deviceId) {
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
        } catch (RuntimeException exception) {
            return error(exception.getMessage());
        }
    }

    /**
     * 保存本地临时设备配置。
     */
    private ApiResult<LocalDeviceConfigResponse> saveLocalDevice(LocalDeviceConfigRequest request,
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
        } catch (RuntimeException exception) {
            return error(exception.getMessage());
        }
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
        throw new ConfigApiException(HttpStatus.BAD_REQUEST, message, null);
    }
}
