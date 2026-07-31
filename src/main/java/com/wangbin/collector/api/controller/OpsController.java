package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.ApiResponse;
import com.wangbin.collector.api.filter.AuthFilter;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgement;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgementQueryRequest;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgementRequest;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgementService;
import com.wangbin.collector.monitor.log.OperationLogger;
import com.wangbin.collector.monitor.network.NetworkDiagnosticRequest;
import com.wangbin.collector.monitor.network.NetworkDiagnosticResult;
import com.wangbin.collector.monitor.network.NetworkDiagnosticService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台运维接口。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final OperationLogger operationLogger;
    private final AlarmAcknowledgementService alarmAcknowledgementService;
    private final NetworkDiagnosticService networkDiagnosticService;

    /**
     * 创建当前组件实例。
     */
    public OpsController(OperationLogger operationLogger,
                         AlarmAcknowledgementService alarmAcknowledgementService,
                         NetworkDiagnosticService networkDiagnosticService) {
        this.operationLogger = operationLogger;
        this.alarmAcknowledgementService = alarmAcknowledgementService;
        this.networkDiagnosticService = networkDiagnosticService;
    }

    /**
     * 查询经过脱敏处理的最近运行日志。
     */
    @GetMapping("/logs")
    public ApiResponse<Map<String, Object>> logs(@RequestParam(required = false) String level,
                                                  @RequestParam(required = false) String logger,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Integer limit) {
        List<OperationLogger.OperationLogEntry> items = operationLogger.query(level, logger, keyword, limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalBuffered", operationLogger.size());
        result.put("count", items.size());
        result.put("items", items);
        return ApiResponse.success("运行日志查询成功", result);
    }

    /**
     * 批量查询告警确认状态。
     */
    @PostMapping("/alarms/acknowledgements/query")
    public ApiResponse<Map<String, AlarmAcknowledgement>> acknowledgementStates(
            @Valid @RequestBody AlarmAcknowledgementQueryRequest request) {
        return ApiResponse.success("告警确认状态查询成功",
                alarmAcknowledgementService.findAll(request.alarmIds()));
    }

    /**
     * 幂等确认指定告警。
     */
    @PostMapping("/alarms/{alarmId}/acknowledge")
    public ApiResponse<AlarmAcknowledgement> acknowledge(@PathVariable String alarmId,
                                                          @Valid @RequestBody AlarmAcknowledgementRequest request,
                                                          HttpServletRequest servletRequest) {
        try {
            return ApiResponse.success("告警确认成功",
                    alarmAcknowledgementService.acknowledge(alarmId, resolveOperator(servletRequest), request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }
    /**
     * 对白名单目标执行受限网络检测。
     */
    @PostMapping("/network/diagnose")
    public ApiResponse<NetworkDiagnosticResult> diagnose(@Valid @RequestBody NetworkDiagnosticRequest request) {
        try {
            return ApiResponse.success("网络检测完成", networkDiagnosticService.diagnose(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveOperator(HttpServletRequest request) {
        Object principal = request.getAttribute(AuthFilter.ATTR_PRINCIPAL);
        if (principal instanceof AuthFilter.AuthPrincipal authPrincipal) {
            return authPrincipal.getType() + ":" + authPrincipal.getId();
        }
        return "本机控制台";
    }
}