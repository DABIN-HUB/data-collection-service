package com.wangbin.collector.core.cache.ingress;

/**
 * 遥测入口过载缓冲结果。
 */
public record TelemetryIngressBufferResult(int inputItems,
                                           int redisBufferedItems,
                                           int localBufferedItems,
                                           int droppedItems) {

    public int bufferedItems() {
        return redisBufferedItems + localBufferedItems;
    }
}
