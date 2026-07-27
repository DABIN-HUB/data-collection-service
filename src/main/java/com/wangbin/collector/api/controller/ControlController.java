package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.DeviceCommandRequest;
import com.wangbin.collector.api.controller.dto.PointWriteRequest;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.common.web.result.ResultCode;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 点位写入和协议命令控制接口。
 */
@RestController
@RequestMapping("/api/control")
@RequiredArgsConstructor
public class ControlController {

    private final ConfigManager configManager;
    private final CollectionManager collectionManager;
    private final DevicePointResolver devicePointResolver;

    @PostMapping("/device/{deviceId}/point/{pointRef}")
    public ApiResult<Map<String, Object>> writePoint(@PathVariable String deviceId,
                                                     @PathVariable String pointRef,
                                                     @Valid @RequestBody PointWriteRequest request) {
        if (request == null || request.getValue() == null) {
            return ApiResult.error(ResultCode.PARAM_ERROR.getCode(), "value 不能为空");
        }

        Optional<DataPoint> point = devicePointResolver.resolve(deviceId, pointRef);
        if (point.isEmpty()) {
            return ApiResult.error(ResultCode.DATA_NOT_FOUND.getCode(), "点位不存在: " + pointRef);
        }
        DataPoint dataPoint = point.get();
        if (!dataPoint.isWritable()) {
            return ApiResult.error(ResultCode.DATA_INVALID.getCode(), "点位不可写: " + pointRef);
        }

        boolean success = collectionManager.writePoint(deviceId, dataPoint, request.getValue());
        Map<String, Object> data = pointResult(dataPoint, request.getValue(), success, success ? null : "protocol write returned false");
        if (!success) {
            ApiResult<Map<String, Object>> result = ApiResult.error(ResultCode.OPERATION_FAILED.getCode(), "点位写入失败");
            result.setData(data);
            return result;
        }
        return ApiResult.success("点位写入成功", data);
    }

    @PostMapping("/device/{deviceId}/points")
    public ApiResult<Map<String, Object>> writePoints(@PathVariable String deviceId,
                                                      @Valid @RequestBody PointWriteRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getValues())) {
            return ApiResult.error(ResultCode.PARAM_ERROR.getCode(), "values 不能为空");
        }

        List<DataPoint> points = configManager.getDataPoints(deviceId);
        Map<DataPoint, Object> writePlan = new LinkedHashMap<>();
        Map<String, Map<String, Object>> fieldResults = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : request.getValues().entrySet()) {
            String field = entry.getKey();
            Map<String, Object> fieldResult = new LinkedHashMap<>();
            fieldResults.put(field, fieldResult);

            Optional<DataPoint> resolvedPoint = devicePointResolver.resolve(points, field);
            if (resolvedPoint.isEmpty()) {
                fieldResult.put("mapped", false);
                fieldResult.put("success", false);
                fieldResult.put("error", "point not found");
                continue;
            }

            DataPoint point = resolvedPoint.get();
            fieldResult.put("mapped", true);
            fieldResult.put("pointId", point.getPointId());
            fieldResult.put("pointCode", point.getPointCode());
            fieldResult.put("value", entry.getValue());
            if (!point.isWritable()) {
                fieldResult.put("success", false);
                fieldResult.put("error", "point is not writable");
                continue;
            }

            writePlan.put(point, entry.getValue());
            fieldResult.put("success", false);
            fieldResult.put("error", "pending");
        }

        if (!writePlan.isEmpty()) {
            Map<String, Boolean> writeResults = collectionManager.writePoints(deviceId, writePlan);
            applyWriteResults(fieldResults, writePlan, writeResults);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", deviceId);
        data.put("fields", fieldResults);
        data.put("total", request.getValues().size());
        data.put("mapped", fieldResults.values().stream().filter(item -> Boolean.TRUE.equals(item.get("mapped"))).count());
        data.put("success", fieldResults.values().stream().filter(item -> Boolean.TRUE.equals(item.get("success"))).count());

        boolean anySuccess = fieldResults.values().stream().anyMatch(item -> Boolean.TRUE.equals(item.get("success")));
        if (!anySuccess) {
            ApiResult<Map<String, Object>> result = ApiResult.error(ResultCode.OPERATION_FAILED.getCode(), "批量点位写入失败");
            result.setData(data);
            return result;
        }
        return ApiResult.success("批量点位写入完成", data);
    }

    @PostMapping("/device/{deviceId}/command")
    public ApiResult<Map<String, Object>> executeCommand(@PathVariable String deviceId,
                                                         @Valid @RequestBody DeviceCommandRequest request) {
        if (request == null || !StringUtils.hasText(request.getCommand())) {
            return ApiResult.error(ResultCode.PARAM_ERROR.getCode(), "command 不能为空");
        }
        Map<String, Object> params = request.getParams() != null ? request.getParams() : Map.of();
        Object commandResult = collectionManager.executeCommand(deviceId, request.getCommand(), params);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", deviceId);
        data.put("command", request.getCommand());
        data.put("params", params);
        data.put("result", commandResult);
        return ApiResult.success("命令执行完成", data);
    }

    private void applyWriteResults(Map<String, Map<String, Object>> fieldResults,
                                   Map<DataPoint, Object> writePlan,
                                   Map<String, Boolean> writeResults) {
        for (DataPoint point : writePlan.keySet()) {
            String field = resolveSubmittedField(fieldResults, point);
            if (!StringUtils.hasText(field)) {
                continue;
            }
            Map<String, Object> fieldResult = fieldResults.get(field);
            boolean success = resolveWriteSuccess(writeResults, point);
            fieldResult.put("success", success);
            if (success) {
                fieldResult.remove("error");
            } else {
                fieldResult.put("error", "protocol write returned false");
            }
        }
    }

    private String resolveSubmittedField(Map<String, Map<String, Object>> fieldResults, DataPoint point) {
        return fieldResults.entrySet().stream()
                .filter(entry -> point.getPointId() != null && point.getPointId().equals(entry.getValue().get("pointId")))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private boolean resolveWriteSuccess(Map<String, Boolean> writeResults, DataPoint point) {
        if (writeResults == null || writeResults.isEmpty() || point == null) {
            return false;
        }
        return Boolean.TRUE.equals(writeResults.get(point.getPointId()))
                || Boolean.TRUE.equals(writeResults.get(point.getPointCode()))
                || Boolean.TRUE.equals(writeResults.get(point.getReportField()));
    }

    private Map<String, Object> pointResult(DataPoint point, Object value, boolean success, String error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pointId", point.getPointId());
        data.put("pointCode", point.getPointCode());
        data.put("pointName", point.getPointName());
        data.put("value", value);
        data.put("success", success);
        if (StringUtils.hasText(error)) {
            data.put("error", error);
        }
        return data;
    }
}
