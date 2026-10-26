package com.wangbin.collector.api.exception;

import com.wangbin.collector.api.controller.ConfigController;
import com.wangbin.collector.api.error.ApiErrorResponseFactory;
import com.wangbin.collector.common.web.result.ApiResult;
import com.wangbin.collector.common.domain.enums.DataQuality;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.config.manager.ConfigVersionConflictException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import com.wangbin.collector.core.config.persistence.ConfigurationConflictException;
import com.wangbin.collector.core.config.persistence.WangbinConfigurationPersistenceException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;


/**
 * 配置治理接口统一异常响应处理器。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ConfigController.class)
public class ConfigApiExceptionHandler {

    /** 本地编辑与 Bundle 使用同一个 JVM 版本冲突契约。 */
    @ExceptionHandler(ConfigVersionConflictException.class)
    public ResponseEntity<ApiResult<Object>> handleVersionConflict(
            ConfigVersionConflictException exception, HttpServletRequest request) {
        return ApiErrorResponseFactory.response(request, HttpStatus.CONFLICT,
                "CONFIG_VERSION_CONFLICT", "设备配置已被其他操作更新，请刷新后重新确认修改",
                Map.of("deviceId", exception.getDeviceId(), "expectedVersion", exception.getExpectedVersion(),
                        "currentVersion", exception.getCurrentVersion()));
    }

    /** 协议配置校验不能落入返回 HTTP 200 的通用采集异常处理器，也不回显参数值。 */
    @ExceptionHandler(CollectorException.class)
    public ResponseEntity<ApiResult<Object>> handleCollectorConfiguration(
            CollectorException exception, HttpServletRequest request) {
        boolean invalid = exception.getDataQuality() == DataQuality.CONFIG_ERROR;
        return ApiErrorResponseFactory.response(request,
                invalid ? HttpStatus.BAD_REQUEST : HttpStatus.SERVICE_UNAVAILABLE,
                "CONFIG_OPERATION_FAILED", invalid ? "协议配置不合法，请检查连接及点位参数" : "配置操作暂不可用，请稍后重试", null);
    }

    /** 保持局部更新与完整配置更新的非法参数响应一致，禁止输出原始异常负载。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Object>> handleInvalidConfiguration(
            IllegalArgumentException exception, HttpServletRequest request) {
        return ApiErrorResponseFactory.response(request, HttpStatus.BAD_REQUEST,
                "CONFIG_OPERATION_FAILED", "配置字段不合法，请检查设备、连接及点位配置", null);
    }

    /** 区分持久修订与 JVM 版本；已提交异常不能被包装成事务回滚。 */
    @ExceptionHandler(WangbinConfigurationPersistenceException.class)
    public ResponseEntity<ApiResult<Object>> handlePersistenceException(
            WangbinConfigurationPersistenceException exception, HttpServletRequest request) {
        boolean conflict = exception.getOutcome() == WangbinConfigurationPersistenceException.Outcome.CONFLICT;
        HttpStatus status = conflict ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
        String message = switch (exception.getOutcome()) {
            case CONFLICT -> "配置持久化修订冲突，请重新加载后核对修改";
            case WRITE_FAILED -> "配置持久化失败，本次修改未提交，请稍后重试";
            case COMMITTED_RECONCILED -> "配置已提交且已完成权威对账，请重新加载核对，不要重复提交";
            case COMMITTED_RECOVERY_REQUIRED -> "配置已提交，但运行配置需要恢复，请刷新核对，不要重复提交";
            case COMMITTED_APPLY_FAILED -> "配置已提交，但变更应用失败，请刷新核对，不要重复提交";
            case CONFIGURATION_UNAVAILABLE -> "当前配置不可用，请刷新后重试";
        };
        return ApiErrorResponseFactory.response(request, status,
                conflict ? "CONFIG_PERSISTENCE_CONFLICT" : "CONFIG_PERSISTENCE_UNAVAILABLE", message,
                persistenceMetadata(exception.isCommitted(), exception.isReconciled(),
                        conflict || exception.isCommitted(), exception.getDeviceIds()));
    }

    /** Phase 1 持久冲突同样不能落入通用 HTTP 200 异常响应。 */
    @ExceptionHandler(ConfigurationConflictException.class)
    public ResponseEntity<ApiResult<Object>> handlePersistenceConflict(
            ConfigurationConflictException exception, HttpServletRequest request) {
        return ApiErrorResponseFactory.response(request, HttpStatus.CONFLICT,
                "CONFIG_PERSISTENCE_CONFLICT", "配置持久化修订冲突，请重新加载后核对修改",
                persistenceMetadata(false, false, true, List.of()));
    }

    private Map<String, Object> persistenceMetadata(boolean committed, boolean reconciled,
                                                     boolean reloadRequired, List<String> deviceIds) {
        return Map.of("dimension", "PERSISTENCE_REVISION", "committed", committed,
                "reconciled", reconciled, "reloadRequired", reloadRequired, "deviceIds", deviceIds);
    }

    /** 不回显 Jackson 异常及其 cause，字段值可能包含凭据。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Object>> handleUnreadableConfiguration(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return ApiErrorResponseFactory.response(request, HttpStatus.BAD_REQUEST,
                "CONFIG_OPERATION_FAILED", "配置 JSON 格式不正确，请检查字段类型和结构", null);
    }


    /**
     * 处理配置接口业务异常。
     *
     * @param exception 配置接口异常
     * @return 统一异常响应
     */
    @ExceptionHandler(ConfigApiException.class)
    public ResponseEntity<ApiResult<Object>> handleConfigApiException(ConfigApiException exception,
                                                                        HttpServletRequest request) {
        String machineCode = exception.getHttpStatus().value() == 409
                ? "CONFIG_VERSION_CONFLICT" : "CONFIG_OPERATION_FAILED";
        return ApiErrorResponseFactory.response(request, exception.getHttpStatus(),
                machineCode, exception.getMessage(), exception.getData());
    }
}
