package com.wangbin.collector.core.collector.runtime;

/** 响应已到达，但响应内容未能匹配到配置点位。 */
public class PointMappingException extends IllegalArgumentException {
    public PointMappingException(String message) {
        super(message);
    }

    public PointMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
