package com.wangbin.collector.api.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 配置治理接口异常。
 */
@Getter
public class ConfigApiException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final Object data;

    /**
     * 创建当前组件实例。
     */
    public ConfigApiException(HttpStatus httpStatus, String message, Object data) {
        super(message);
        this.httpStatus = httpStatus;
        this.data = data;
    }
}
