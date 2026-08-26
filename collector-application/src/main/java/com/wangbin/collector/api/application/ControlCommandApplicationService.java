package com.wangbin.collector.api.application;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 控制命令应用服务。
 *
 * <p>负责点位写入、批量写入和协议命令下发编排，不直接改变采集调度主链路。</p>
 */
@Service
@RequiredArgsConstructor
public class ControlCommandApplicationService {

    private static final String ERROR_POINT_NOT_FOUND = "点位不存在";
    private static final String ERROR_POINT_NOT_WRITABLE = "点位不可写";
    private static final String ERROR_PENDING = "等待写入";
    private static final String ERROR_PROTOCOL_WRITE_FALSE = "协议写入返回失败";
    private static final String ERROR_DUPLICATE_POINT_VALUE_CONFLICT = "同一批次重复映射到同一点位且写入值不一致";

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
    public ApiResult<PointWriteResultResponse> writePoint(String deviceId,
                                                          String pointRef,
                                                          PointWriteRequest request) {
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
            ApiResult<PointWriteResultResponse> result = ApiResult.error(
                    ResultCode.OPERATION_FAILED.getCode(), "点位写入失败");
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
    public ApiResult<BatchPointWriteResponse> writePoints(String deviceId, PointWriteRequest request) {
        if (request == null || CollectionUtils.isEmpty(request.getValues())) {
            return ApiResult.error(ResultCode.PARAM_ERROR.getCode(), "values 不能为空");
        }

        List<DataPoint> points = configManager.getDataPoints(deviceId);
        Map<DataPoint, Object> writePlan = new LinkedHashMap<>();
        Map<DataPoint, List<String>> submittedFieldsByPoint = new LinkedHashMap<>();
        Set<DataPoint> conflictPoints = new LinkedHashSet<>();
        Map<String, BatchPointWriteFieldResponse> fieldResults = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : request.getValues().entrySet()) {
            collectWritePlan(points, writePlan, submittedFieldsByPoint, conflictPoints, fieldResults, entry);
        }

        if (!writePlan.isEmpty()) {
            Map<String, Boolean> writeResults = collectionManager.writePoints(deviceId, writePlan);
            applyWriteResults(fieldResults, writePlan, submittedFieldsByPoint, writeResults);
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
            ApiResult<BatchPointWriteResponse> result = ApiResult.error(
                    ResultCode.OPERATION_FAILED.getCode(), "批量点位写入失败");
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
    public ApiResult<DeviceCommandResponse> executeCommand(String deviceId, DeviceCommandRequest request) {
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
     * 收集批量写入计划。
     *
     * @param points 设备点位列表
     * @param writePlan 实际写入计划
     * @param submittedFieldsByPoint 点位对应的原始提交字段
     * @param conflictPoints 本批次存在值冲突的点位
     * @param fieldResults 提交字段结果
     * @param entry 用户提交字段
     */
    private void collectWritePlan(List<DataPoint> points,
                                  Map<DataPoint, Object> writePlan,
                                  Map<DataPoint, List<String>> submittedFieldsByPoint,
                                  Set<DataPoint> conflictPoints,
                                  Map<String, BatchPointWriteFieldResponse> fieldResults,
                                  Map.Entry<String, Object> entry) {
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
            return;
        }

        DataPoint point = resolvedPoint.get();
        fieldResult.setMapped(true);
        fieldResult.setPointId(point.getPointId());
        fieldResult.setPointCode(point.getPointCode());
        if (!point.isWritable()) {
            fieldResult.setError(ERROR_POINT_NOT_WRITABLE);
            return;
        }

        submittedFieldsByPoint.computeIfAbsent(point, ignored -> new ArrayList<>()).add(field);
        if (conflictPoints.contains(point)) {
            markConflict(point, writePlan, submittedFieldsByPoint, fieldResults, conflictPoints);
            return;
        }

        if (writePlan.containsKey(point)) {
            Object plannedValue = writePlan.get(point);
            if (!Objects.equals(plannedValue, entry.getValue())) {
                markConflict(point, writePlan, submittedFieldsByPoint, fieldResults, conflictPoints);
            } else {
                fieldResult.setError(ERROR_PENDING);
            }
            return;
        }

        writePlan.put(point, entry.getValue());
        fieldResult.setError(ERROR_PENDING);
    }

    /**
     * 标记同一点位多字段提交值冲突。
     *
     * @param point 点位配置
     * @param writePlan 实际写入计划
     * @param submittedFieldsByPoint 点位对应的原始提交字段
     * @param fieldResults 提交字段结果
     * @param conflictPoints 本批次存在值冲突的点位
     */
    private void markConflict(DataPoint point,
                              Map<DataPoint, Object> writePlan,
                              Map<DataPoint, List<String>> submittedFieldsByPoint,
                              Map<String, BatchPointWriteFieldResponse> fieldResults,
                              Set<DataPoint> conflictPoints) {
        conflictPoints.add(point);
        writePlan.remove(point);
        for (String submittedField : submittedFieldsByPoint.getOrDefault(point, List.of())) {
            BatchPointWriteFieldResponse fieldResult = fieldResults.get(submittedField);
            if (fieldResult != null) {
                fieldResult.setSuccess(false);
                fieldResult.setError(ERROR_DUPLICATE_POINT_VALUE_CONFLICT);
            }
        }
    }

    /**
     * 将协议写入结果回填到提交字段结果。
     *
     * @param fieldResults 提交字段结果
     * @param writePlan 实际写入计划
     * @param submittedFieldsByPoint 点位对应的原始提交字段
     * @param writeResults 协议写入结果
     */
    private void applyWriteResults(Map<String, BatchPointWriteFieldResponse> fieldResults,
                                   Map<DataPoint, Object> writePlan,
                                   Map<DataPoint, List<String>> submittedFieldsByPoint,
                                   Map<String, Boolean> writeResults) {
        for (DataPoint point : writePlan.keySet()) {
            boolean success = resolveWriteSuccess(writeResults, point);
            for (String submittedField : submittedFieldsByPoint.getOrDefault(point, List.of())) {
                BatchPointWriteFieldResponse fieldResult = fieldResults.get(submittedField);
                if (fieldResult != null) {
                    fieldResult.setSuccess(success);
                    fieldResult.setError(success ? null : ERROR_PROTOCOL_WRITE_FALSE);
                }
            }
        }
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
        return matchesWriteSuccess(writeResults, point.getPointId())
                || matchesWriteSuccess(writeResults, point.getPointCode())
                || matchesWriteSuccess(writeResults, point.getReportField());
    }

    /**
     * 按协议返回字段判断单个点位是否写入成功。
     *
     * @param writeResults 协议写入结果
     * @param resultKey 协议结果键
     * @return 是否写入成功
     */
    private boolean matchesWriteSuccess(Map<String, Boolean> writeResults, String resultKey) {
        return StringUtils.hasText(resultKey) && Boolean.TRUE.equals(writeResults.get(resultKey));
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
