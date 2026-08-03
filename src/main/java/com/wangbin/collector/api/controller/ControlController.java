package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.BatchPointWriteFieldResponse;
import com.wangbin.collector.api.controller.dto.BatchPointWriteResponse;
import com.wangbin.collector.api.controller.dto.DeviceCommandRequest;
import com.wangbin.collector.api.controller.dto.DeviceCommandResponse;
import com.wangbin.collector.api.controller.dto.PointWriteRequest;
import com.wangbin.collector.api.controller.dto.PointWriteResultResponse;
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

    private static final String ERROR_POINT_NOT_FOUND = "点位不存在";
    private static final String ERROR_POINT_NOT_WRITABLE = "点位不可写";
    private static final String ERROR_PENDING = "等待写入";
    private static final String ERROR_PROTOCOL_WRITE_FALSE = "协议写入返回失败";

    private final ConfigManager configManager;
    private final CollectionManager collectionManager;
    private final DevicePointResolver devicePointResolver;

    /**
     * 写入单个点位。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointRef 点位标识、编码或上报字段
     * @param request 写入请求
     * @return 写入结果
     */
    @PostMapping("/device/{deviceId}/point/{pointRef}")
    public ApiResult<PointWriteResultResponse> writePoint(@PathVariable String deviceId,
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
        PointWriteResultResponse data = pointResult(dataPoint, request.getValue(), success,
                success ? null : ERROR_PROTOCOL_WRITE_FALSE);
        if (!success) {
            ApiResult<PointWriteResultResponse> result = ApiResult.error(ResultCode.OPERATION_FAILED.getCode(), "点位写入失败");
            result.setData(data);
            return result;
        }
        return ApiResult.success("点位写入成功", data);
    }

    /**
     * 批量写入点位。
     *
     * @param deviceId 本地设备唯一标识
     * @param request 批量写入请求
     * @return 批量写入结果
     */
    @PostMapping("/device/{deviceId}/points")
    public ApiResult<BatchPointWriteResponse> writePoints(@PathVariable String deviceId,
                                                          @Valid @RequestBody PointWriteRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getValues())) {
            return ApiResult.error(ResultCode.PARAM_ERROR.getCode(), "values 不能为空");
        }

        List<DataPoint> points = configManager.getDataPoints(deviceId);
        Map<DataPoint, Object> writePlan = new LinkedHashMap<>();
        Map<String, BatchPointWriteFieldResponse> fieldResults = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : request.getValues().entrySet()) {
            String field = entry.getKey();
            BatchPointWriteFieldResponse fieldResult = BatchPointWriteFieldResponse.builder()
                    .mapped(false)
                    .success(false)
                    .value(entry.getValue())
                    .build();
            fieldResults.put(field, fieldResult);

            Optional<DataPoint> resolvedPoint = devicePointResolver.resolve(points, field);
            if (resolvedPoint.isEmpty()) {
                fieldResult.setError(ERROR_POINT_NOT_FOUND);
                continue;
            }

            DataPoint point = resolvedPoint.get();
            fieldResult.setMapped(true);
            fieldResult.setPointId(point.getPointId());
            fieldResult.setPointCode(point.getPointCode());
            if (!point.isWritable()) {
                fieldResult.setError(ERROR_POINT_NOT_WRITABLE);
                continue;
            }

            writePlan.put(point, entry.getValue());
            fieldResult.setError(ERROR_PENDING);
        }

        if (!writePlan.isEmpty()) {
            Map<String, Boolean> writeResults = collectionManager.writePoints(deviceId, writePlan);
            applyWriteResults(fieldResults, writePlan, writeResults);
        }

        BatchPointWriteResponse data = BatchPointWriteResponse.builder()
                .deviceId(deviceId)
                .fields(fieldResults)
                .total(request.getValues().size())
                .mapped(fieldResults.values().stream().filter(item -> Boolean.TRUE.equals(item.getMapped())).count())
                .success(fieldResults.values().stream().filter(item -> Boolean.TRUE.equals(item.getSuccess())).count())
                .build();

        boolean anySuccess = fieldResults.values().stream().anyMatch(item -> Boolean.TRUE.equals(item.getSuccess()));
        if (!anySuccess) {
            ApiResult<BatchPointWriteResponse> result = ApiResult.error(ResultCode.OPERATION_FAILED.getCode(), "批量点位写入失败");
            result.setData(data);
            return result;
        }
        return ApiResult.success("批量点位写入完成", data);
    }

    /**
     * 执行协议命令。
     *
     * @param deviceId 本地设备唯一标识
     * @param request 命令请求
     * @return 命令执行结果
     */
    @PostMapping("/device/{deviceId}/command")
    public ApiResult<DeviceCommandResponse> executeCommand(@PathVariable String deviceId,
                                                           @Valid @RequestBody DeviceCommandRequest request) {
        if (request == null || !StringUtils.hasText(request.getCommand())) {
            return ApiResult.error(ResultCode.PARAM_ERROR.getCode(), "command 不能为空");
        }
        Map<String, Object> params = request.getParams() != null ? request.getParams() : Map.of();
        Object commandResult = collectionManager.executeCommand(deviceId, request.getCommand(), params);

        DeviceCommandResponse data = DeviceCommandResponse.builder()
                .deviceId(deviceId)
                .command(request.getCommand())
                .params(params)
                .result(commandResult)
                .build();
        return ApiResult.success("命令执行完成", data);
    }

    /**
     * 将协议写入结果回填到提交字段结果。
     *
     * @param fieldResults 提交字段结果
     * @param writePlan 实际写入计划
     * @param writeResults 协议写入结果
     */
    private void applyWriteResults(Map<String, BatchPointWriteFieldResponse> fieldResults,
                                   Map<DataPoint, Object> writePlan,
                                   Map<String, Boolean> writeResults) {
        for (DataPoint point : writePlan.keySet()) {
            String field = resolveSubmittedField(fieldResults, point);
            if (!StringUtils.hasText(field)) {
                continue;
            }
            BatchPointWriteFieldResponse fieldResult = fieldResults.get(field);
            boolean success = resolveWriteSuccess(writeResults, point);
            fieldResult.setSuccess(success);
            fieldResult.setError(success ? null : ERROR_PROTOCOL_WRITE_FALSE);
        }
    }

    /**
     * 根据点位反查用户提交字段。
     *
     * @param fieldResults 提交字段结果
     * @param point 点位配置
     * @return 用户提交字段
     */
    private String resolveSubmittedField(Map<String, BatchPointWriteFieldResponse> fieldResults, DataPoint point) {
        return fieldResults.entrySet().stream()
                .filter(entry -> point.getPointId() != null && point.getPointId().equals(entry.getValue().getPointId()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析协议批量写入结果。
     *
     * @param writeResults 协议写入结果
     * @param point 点位配置
     * @return 是否写入成功
     */
    private boolean resolveWriteSuccess(Map<String, Boolean> writeResults, DataPoint point) {
        if (writeResults == null || writeResults.isEmpty() || point == null) {
            return false;
        }
        return Boolean.TRUE.equals(writeResults.get(point.getPointId()))
                || Boolean.TRUE.equals(writeResults.get(point.getPointCode()))
                || Boolean.TRUE.equals(writeResults.get(point.getReportField()));
    }

    /**
     * 构造单点写入结果。
     *
     * @param point 点位配置
     * @param value 写入值
     * @param success 是否写入成功
     * @param error 错误信息
     * @return 单点写入结果
     */
    private PointWriteResultResponse pointResult(DataPoint point, Object value, boolean success, String error) {
        return PointWriteResultResponse.builder()
                .pointId(point.getPointId())
                .pointCode(point.getPointCode())
                .pointName(point.getPointName())
                .value(value)
                .success(success)
                .error(StringUtils.hasText(error) ? error : null)
                .build();
    }
}