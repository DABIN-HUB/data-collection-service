package com.wangbin.collector.api.exception;

/** 告警查询输入不合法，仅用于显式的 API 参数校验。 */
public class AlarmQueryValidationException extends RuntimeException {

    public AlarmQueryValidationException(String message) {
        super(message);
    }
}
