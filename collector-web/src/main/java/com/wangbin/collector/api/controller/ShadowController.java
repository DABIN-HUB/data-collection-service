package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.DeviceShadowDeltaResponse;
import com.wangbin.collector.api.controller.dto.DeviceShadowResponse;
import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.common.web.result.ResultCode;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备影子查询和 desired 状态管理接口。
 */
@RestController
@RequestMapping("/api/shadow")
@RequiredArgsConstructor
public class ShadowController {

    private static final List<String> RESERVED_KEYS = List.of(
            "id", "messageId", "version", "method", "deviceId", "timestamp", "source",
            "shadowVersion", "expectedVersion"
    );

    private final ShadowManager shadowManager;

    @GetMapping("/{deviceId}")
    public ApiResult<DeviceShadowResponse> getShadow(@PathVariable String deviceId) {
        Map<String, Object> document = shadowManager.getShadowDocument(deviceId);
        if (document == null) {
            return ApiResult.error(ResultCode.DATA_NOT_FOUND.getCode(), "设备影子不存在");
        }
        return ApiResult.success(DeviceShadowResponse.from(document));
    }

    @GetMapping("/{deviceId}/delta")
    public ApiResult<DeviceShadowDeltaResponse> getDelta(@PathVariable String deviceId) {
        Map<String, Object> delta = shadowManager.getShadowDelta(deviceId);
        if (delta == null) {
            return ApiResult.error(ResultCode.DATA_NOT_FOUND.getCode(), "设备影子不存在");
        }
        return ApiResult.success(DeviceShadowDeltaResponse.from(delta));
    }

    @GetMapping("/{deviceId}/history")
    public ApiResult<List<Map<String, Object>>> getHistory(@PathVariable String deviceId,
                                                           @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.success(shadowManager.getShadowHistory(deviceId, limit));
    }

    @PostMapping("/{deviceId}/desired")
    public ApiResult<DeviceShadowResponse> updateDesired(@PathVariable String deviceId,
                                                         @RequestBody Map<String, Object> request) {
        Map<String, Object> desired = extractDesired(request);
        if (desired.isEmpty()) {
            return ApiResult.error(ResultCode.PARAM_ERROR.getCode(), "desired 属性不能为空");
        }
        String source = request != null && request.get(CommonMapKeys.SOURCE) != null
                ? String.valueOf(request.get(CommonMapKeys.SOURCE))
                : "api";
        try {
            return ApiResult.success(DeviceShadowResponse.from(
                    shadowManager.updateDesired(deviceId, desired, source, extractExpectedVersion(request))));
        } catch (ShadowManager.ShadowVersionConflictException e) {
            throw e;
        } catch (IllegalStateException e) {
            return ApiResult.error(ResultCode.PARAM_ERROR.getCode(), e.getMessage());
        }
    }

    /**
     * 解析或转换业务数据。
     */
    @ExceptionHandler(ShadowManager.ShadowVersionConflictException.class)
    public ResponseEntity<ApiResult<Map<String, Object>>> handleShadowVersionConflict(
            ShadowManager.ShadowVersionConflictException exception) {
        Map<String, Object> data = Map.of(
                "expectedVersion", exception.getExpectedVersion(),
                "currentVersion", exception.getActualVersion() == null ? 0L : exception.getActualVersion());
        ApiResult<Map<String, Object>> result = ApiResult.error(ResultCode.OPERATION_FAILED.getCode(), "设备影子已发生变化，请重新读取后确认 desired");
        result.setData(data);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }

    private Long extractExpectedVersion(Map<String, Object> request) {
        if (request == null) {
            return null;
        }
        Object value = request.get("shadowVersion");
        if (value == null) {
            value = request.get("expectedVersion");
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @DeleteMapping("/{deviceId}/desired")
    public ApiResult<DeviceShadowResponse> clearDesired(@PathVariable String deviceId,
                                                        @RequestParam(required = false) List<String> fields,
                                                        @RequestParam(required = false) Long expectedVersion) {
        Map<String, Object> document = shadowManager.clearDesired(deviceId, fields, expectedVersion);
        if (document == null) {
            return ApiResult.error(ResultCode.DATA_NOT_FOUND.getCode(), "设备影子不存在");
        }
        return ApiResult.success(DeviceShadowResponse.from(document));
    }

    /**
     * 解析或转换业务数据。
     */
    private Map<String, Object> extractDesired(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> fromState = extractNestedMap(request.get("state"), "desired");
        if (!fromState.isEmpty()) {
            return fromState;
        }
        Map<String, Object> desired = asStringObjectMap(request.get("desired"));
        if (!desired.isEmpty()) {
            return desired;
        }
        Map<String, Object> properties = asStringObjectMap(request.get("properties"));
        if (!properties.isEmpty()) {
            return properties;
        }
        Map<String, Object> params = asStringObjectMap(request.get("params"));
        if (!params.isEmpty()) {
            return params;
        }

        Map<String, Object> direct = new LinkedHashMap<>();
        request.forEach((key, value) -> {
            if (key != null && !RESERVED_KEYS.contains(key)) {
                direct.put(key, value);
            }
        });
        return direct;
    }

    /**
     * 解析或转换业务数据。
     */
    private Map<String, Object> extractNestedMap(Object root, String key) {
        Map<String, Object> map = asStringObjectMap(root);
        if (map.isEmpty()) {
            return Map.of();
        }
        return asStringObjectMap(map.get(key));
    }

    /**
     * 执行当前业务逻辑。
     */
    private Map<String, Object> asStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null) {
                result.put(String.valueOf(k), v);
            }
        });
        return result;
    }
}
