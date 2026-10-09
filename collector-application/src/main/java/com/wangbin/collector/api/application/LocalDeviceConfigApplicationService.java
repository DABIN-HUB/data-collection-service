package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.ConfigBundle;
import com.wangbin.collector.api.controller.dto.DeviceIdResponse;
import com.wangbin.collector.api.controller.dto.LocalDeviceConfigRequest;
import com.wangbin.collector.api.controller.dto.LocalDeviceConfigResponse;
import com.wangbin.collector.api.exception.ConfigApiException;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.scheduler.DeviceLifecycleCoordinator;
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
    private final DeviceLifecycleCoordinator lifecycleCoordinator;

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
        ConfigManager.DeviceConfigurationSnapshot snapshot = configManager.getDeviceConfigurationSnapshot(deviceId);
        DeviceInfo device = snapshot.context() == null ? null : snapshot.context().getDeviceInfo();
        if (device == null || !ConfigManager.CONFIG_SOURCE_LOCAL.equalsIgnoreCase(device.getConfigSource())
                || !Boolean.TRUE.equals(device.getTemporaryConfig())) {
            return error("只能读取本地临时设备配置: " + deviceId);
        }
        ConfigBundle bundle = ConfigBundle.builder()
                .device(device)
                .connection(snapshot.context().copyConnectionConfig())
                .points(snapshot.context().copyDataPoints())
                .build();
        LocalDeviceConfigResponse response = LocalDeviceConfigResponse.builder()
                .deviceId(deviceId)
                .configSource(ConfigManager.CONFIG_SOURCE_LOCAL)
                .temporaryConfig(true)
                .bundle(bundle)
                .configVersion(snapshot.configVersion())
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
            if (StringUtils.hasText(device.getDeviceId()) && !pathDeviceId.equals(device.getDeviceId())) {
                return error("路径与配置 deviceId 不一致，禁止修改设备身份");
            }
            device.setDeviceId(pathDeviceId);
        }
        if (!StringUtils.hasText(device.getDeviceId())) {
            return error("deviceId 不能为空");
        }
        try {
            String deviceId = device.getDeviceId();
            long intentRevision = lifecycleCoordinator.getIntentRevision(deviceId);
            ConfigManager.DeviceConfigCommitResult commit = configManager.saveLocalDeviceConfigWithResult(
                    device,
                    request.getConnection(),
                    request.getPoints(),
                    overwrite || request.isOverwrite());
            if (commit == null) {
                return error("保存本地临时设备失败: " + device.getDeviceId());
            }

            long configVersion = commit.configVersion();
            boolean changed = commit.previousVersion() != configVersion;
            boolean started = false;
            String startStatus = "NOT_REQUESTED";
            String startError = null;
            com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot runtime = null;
            if (request.isStartAfterSave()) {
                try {
                    DeviceLifecycleCoordinator.StartAfterConfigSaveResult start =
                            lifecycleCoordinator.startDeviceAfterConfigSave(deviceId, intentRevision);
                    startStatus = start.status();
                    started = start.accepted() && !"RESTART_PENDING".equals(startStatus);
                    if ("STOP_SUPERSEDED".equals(startStatus)) startError = "保存期间已收到停止请求，设备保持停止";
                    else if (!start.accepted()) startError = "启动未被接受，请检查设备运行状态";
                } catch (RuntimeException exception) {
                    startStatus = "FAILED";
                    startError = "启动失败，请检查设备运行状态和服务日志";
                }
                try {
                    runtime = collectionService.getDeviceRuntimeSnapshot(deviceId);
                } catch (RuntimeException exception) {
                    // 快照暂不可用不能把已提交配置伪装成保存失败。
                }
            }

            LocalDeviceConfigResponse response = LocalDeviceConfigResponse.builder()
                    .deviceId(device.getDeviceId())
                    .configSource(ConfigManager.CONFIG_SOURCE_LOCAL)
                    .temporaryConfig(true)
                    .started(started)
                    .saved(true)
                    .changed(changed)
                    .configVersion(configVersion)
                    .startRequested(request.isStartAfterSave())
                    .startStatus(startStatus)
                    .startError(startError)
                    .runtime(runtime)
                    .pointCount(commit.pointCount())
                    .build();
            String message = switch (startStatus) {
                case "RESTART_PENDING" -> "本地临时设备已保存，配置重启待完成";
                case "STOP_SUPERSEDED" -> "本地临时设备已保存，已遵循停止请求，未启动";
                case "FAILED" -> "本地临时设备已保存，但启动失败";
                default -> "本地临时设备已保存";
            };
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
