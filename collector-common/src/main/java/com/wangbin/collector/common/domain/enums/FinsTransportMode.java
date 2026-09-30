package com.wangbin.collector.common.domain.enums;

import java.util.Locale;

/**
 * FINS 连接传输模式；未配置时保留历史 AUTO 配置入口。
 */
public enum FinsTransportMode {
    UDP,
    TCP,
    AUTO;

    /**
     * 解析显式传输模式，拒绝未知值而不是将其当作 AUTO。
     */
    public static FinsTransportMode from(Object value) {
        if (value == null || value.toString().isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(value.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("OMRON_FINS transport must be UDP, TCP or AUTO", exception);
        }
    }
}
