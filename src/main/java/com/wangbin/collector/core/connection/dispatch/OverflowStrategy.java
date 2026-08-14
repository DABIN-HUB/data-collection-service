package com.wangbin.collector.core.connection.dispatch;

/**
 * 消息队列溢出策略
 */
public enum OverflowStrategy {
    BLOCK,
    DROP_LATEST,
    DROP_OLDEST;

    /**
     * 创建并返回业务对象。
     */
    public static OverflowStrategy from(String text) {
        if (text == null || text.isBlank()) {
            return BLOCK;
        }
        try {
            return OverflowStrategy.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return BLOCK;
        }
    }
}
