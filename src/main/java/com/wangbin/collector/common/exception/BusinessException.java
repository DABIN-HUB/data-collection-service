package com.wangbin.collector.common.exception;

import com.wangbin.collector.common.web.result.ResultCode;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务异常
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class BusinessException extends RuntimeException {

    private final int code;
    private final String message;
    private final Object data;

    /**
     * 创建当前组件实例。
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.message = message;
        this.data = null;
    }

    /**
     * 创建当前组件实例。
     */
    public BusinessException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 创建当前组件实例。
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
        this.data = null;
    }

    /**
     * 创建当前组件实例。
     */
    public BusinessException(ResultCode resultCode, Object data) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
        this.data = data;
    }

}
