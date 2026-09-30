package com.wangbin.collector.api.exception;

import com.wangbin.collector.api.error.ApiErrorResponseFactory;
import com.wangbin.collector.common.web.result.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 管理接口参数校验异常处理器。 */
@RestControllerAdvice
public class ApiValidationExceptionHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
                errors.put(violation.getPropertyPath().toString(), violation.getMessage()));
        return error(request, errors);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(request, errors);
    }

    @ExceptionHandler(AlarmQueryValidationException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handleAlarmQueryValidation(
            AlarmQueryValidationException exception, HttpServletRequest request) {
        return error(request, Map.of("request", exception.getMessage()));
    }

    private ResponseEntity<ApiResult<Map<String, String>>> error(HttpServletRequest request,
                                                                  Map<String, String> data) {
        return ApiErrorResponseFactory.response(request, HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR", "请求参数校验失败", data);
    }
}
