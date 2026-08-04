package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.OpsConsoleApplicationService;
import com.wangbin.collector.api.controller.dto.ApiResponse;
import com.wangbin.collector.api.controller.dto.OpsLogResponse;
import com.wangbin.collector.api.filter.AuthFilter;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgement;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgementQueryRequest;
import com.wangbin.collector.monitor.alert.AlarmAcknowledgementRequest;
import com.wangbin.collector.monitor.network.NetworkDiagnosticRequest;
import com.wangbin.collector.monitor.network.NetworkDiagnosticResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 控制台运维接口。
 *
 * <p>负责 HTTP 上下文解析和运维接口路由，具体业务编排由应用服务处理。</p>
 */
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private static final String LOCAL_CONSOLE_OPERATOR = "本机控制台";

    private final OpsConsoleApplicationService opsConsoleApplicationService;

    /**
     * 查询经过脱敏处理的最近运行日志。
     *
     * @param level 日志级别过滤条件
     * @param logger 日志器过滤条件
     * @param keyword 关键字过滤条件
     * @param limit 返回数量上限
     * @return 运维日志响应
     */
    @GetMapping("/logs")
    public ApiResponse<OpsLogResponse> logs(@RequestParam(required = false) String level,
                                            @RequestParam(required = false) String logger,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Integer limit) {
        return opsConsoleApplicationService.logs(level, logger, keyword, limit);
    }

    /**
     * 批量查询告警确认状态。
     *
     * @param request 告警确认查询请求
     * @return 告警确认状态响应
     */
    @PostMapping("/alarms/acknowledgements/query")
    public ApiResponse<Map<String, AlarmAcknowledgement>> acknowledgementStates(
            @Valid @RequestBody AlarmAcknowledgementQueryRequest request) {
        return opsConsoleApplicationService.acknowledgementStates(request);
    }

    /**
     * 幂等确认指定告警。
     *
     * @param alarmId 告警标识
     * @param request 告警确认请求
     * @param servletRequest HTTP 请求
     * @return 告警确认结果
     */
    @PostMapping("/alarms/{alarmId}/acknowledge")
    public ApiResponse<AlarmAcknowledgement> acknowledge(@PathVariable String alarmId,
                                                         @Valid @RequestBody AlarmAcknowledgementRequest request,
                                                         HttpServletRequest servletRequest) {
        try {
            return opsConsoleApplicationService.acknowledge(alarmId, resolveOperator(servletRequest), request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * 对白名单目标执行受限网络检测。
     *
     * @param request 网络诊断请求
     * @return 网络诊断结果
     */
    @PostMapping("/network/diagnose")
    public ApiResponse<NetworkDiagnosticResult> diagnose(@Valid @RequestBody NetworkDiagnosticRequest request) {
        try {
            return opsConsoleApplicationService.diagnose(request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * 解析当前操作人。
     *
     * @param request HTTP 请求
     * @return 操作人标识
     */
    private String resolveOperator(HttpServletRequest request) {
        Object principal = request.getAttribute(AuthFilter.ATTR_PRINCIPAL);
        if (principal instanceof AuthFilter.AuthPrincipal authPrincipal) {
            return authPrincipal.getType() + ":" + authPrincipal.getId();
        }
        return LOCAL_CONSOLE_OPERATOR;
    }
}