package com.wangbin.collector.storage.buffer;

/**
 * 历史失败缓冲结果，用于区分已持久化、仅本地暂存和明确未保存。
 */
public enum HistoryBufferOutcome {

    /**
     * 已写入 Redis pending，可跨进程重启恢复。
     */
    REDIS_BUFFERED(true, true),

    /**
     * 已写入本地有界队列，仅当前 JVM 内可恢复。
     */
    LOCAL_BUFFERED(true, false),

    /**
     * 本地有界队列已满，数据已明确丢弃。
     */
    DROPPED(false, false),

    /**
     * 历史缓冲被配置关闭，数据未进入可靠缓冲。
     */
    DISABLED(false, false);

    private final boolean buffered;
    private final boolean durable;

    HistoryBufferOutcome(boolean buffered, boolean durable) {
        this.buffered = buffered;
        this.durable = durable;
    }

    /**
     * 是否至少获得了一个明确归宿。
     */
    public boolean buffered() {
        return buffered;
    }

    /**
     * 是否写入了可跨 JVM 恢复的持久缓冲。
     */
    public boolean durable() {
        return durable;
    }
}
