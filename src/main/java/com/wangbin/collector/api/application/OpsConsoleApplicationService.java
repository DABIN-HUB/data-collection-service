package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.OpsLogResponse;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgement;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgementQueryRequest;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgementRequest;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgementService;
import com.wangbin.collector.monitor.log.OperationLogger;
import com.wangbin.collector.monitor.network.NetworkDiagnosticRequest;
import com.wangbin.collector.monitor.network.NetworkDiagnosticResult;
import com.wangbin.collector.monitor.network.NetworkDiagnosticService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 控制台运维应用服务。
 *
 * <p>集中处理运维日志、告警确认和网络诊断编排，Web 层只负责 HTTP 上下文转换。</p>
 */
@Service
@RequiredArgsConstructor
public class OpsConsoleApplicationService {

    private final OperationLogger operationLogger;
    private final AlarmAcknowledgementService alarmAcknowledgementService;
    private final NetworkDiagnosticService networkDiagnosticService;

    /**
     * 查询经过脱敏处理的最近运行日志。
     *
     * @param level 日志级别过滤条件
     * @param logger 日志器过滤条件
     * @param keyword 关键字过滤条件
     * @param limit 返回数量上限
     * @return 运维日志响应
     */
    public ApiResult<OpsLogResponse> logs(String level, String logger, String keyword, Integer limit) {
        List<OperationLogger.OperationLogEntry> items = operationLogger.query(level, logger, keyword, limit);
        OpsLogResponse response = OpsLogResponse.builder()
                .totalBuffered(operationLogger.size())
                .count(items.size())
                .items(items)
                .build();
        return ApiResult.statusSuccess("运行日志查询成功", response);
    }

    /**
     * 批量查询告警确认状态。
     *
     * @param request 告警确认查询请求
     * @return 告警确认状态响应
     */
    public ApiResult<Map<String, AlarmAcknowledgement>> acknowledgementStates(
            AlarmAcknowledgementQueryRequest request) {
        return ApiResult.statusSuccess("告警确认状态查询成功",
                alarmAcknowledgementService.findAll(request.alarmIds()));
    }

    /**
     * 幂等确认指定告警。
     *
     * @param alarmId 告警标识
     * @param operator 当前操作人
     * @param request 告警确认请求
     * @return 告警确认结果
     */
    public ApiResult<AlarmAcknowledgement> acknowledge(String alarmId,
                                                         String operator,
                                                         AlarmAcknowledgementRequest request) {
        return ApiResult.statusSuccess("告警确认成功",
                alarmAcknowledgementService.acknowledge(alarmId, operator, request));
    }

    /**
     * 对白名单目标执行受限网络检测。
     *
     * @param request 网络诊断请求
     * @return 网络诊断结果
     */
    public ApiResult<NetworkDiagnosticResult> diagnose(NetworkDiagnosticRequest request) {
        return ApiResult.statusSuccess("网络检测完成", networkDiagnosticService.diagnose(request));
    }
}
