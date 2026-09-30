package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.ConfigBundle;
import com.wangbin.collector.api.controller.dto.ConfigExportResponse;
import com.wangbin.collector.api.controller.dto.ConfigImportRequest;
import com.wangbin.collector.api.controller.dto.ConfigImportResult;
import com.wangbin.collector.api.exception.ConfigApiException;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.security.SensitiveConfigSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 配置导入导出应用服务。
 *
 * <p>承接控制台配置包交换用例，包括导出脱敏、导入配置包补全、敏感占位恢复和批量原子提交。</p>
 */
@Service
@RequiredArgsConstructor
public class ConfigImportExportApplicationService {

    private final ConfigManager configManager;
    private final CollectionService collectionService;
    private final SensitiveConfigSanitizer sensitiveConfigSanitizer;

    /**
     * 导出当前设备配置。
     *
     * @return 配置导出响应
     */
    public ApiResult<ConfigExportResponse> exportConfigs() {
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
    public ApiResult<ConfigImportResult> importConfigs(ConfigImportRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getBundles())) {
            return error("导入内容不能为空");
        }
        List<DeviceContext> importContexts = new ArrayList<>();
        List<String> deviceIds = new ArrayList<>();
        for (ConfigBundle bundle : request.getBundles()) {
            ApiResult<ConfigImportResult> invalidResponse = appendImportContext(bundle, importContexts, deviceIds);
            if (invalidResponse != null) {
                return invalidResponse;
            }
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
     * 补充导入配置上下文。
     */
    private ApiResult<ConfigImportResult> appendImportContext(ConfigBundle bundle,
                                                               List<DeviceContext> importContexts,
                                                               List<String> deviceIds) {
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
        return null;
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
}
