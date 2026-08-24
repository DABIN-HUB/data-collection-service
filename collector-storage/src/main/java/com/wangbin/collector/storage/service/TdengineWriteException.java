package com.wangbin.collector.storage.service;

/**
 * TDengine writer 失败包装，保留失败前已采集到的分段耗时用于诊断尾延迟。
 */
public class TdengineWriteException extends RuntimeException {

    private final TdengineWriteOutcome outcome;

    public TdengineWriteException(String message, Throwable cause, TdengineWriteOutcome outcome) {
        super(message, cause);
        this.outcome = outcome;
    }

    public TdengineWriteOutcome outcome() {
        return outcome;
    }
}
