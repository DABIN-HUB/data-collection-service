package com.wangbin.collector.storage.buffer;

/**
 * 历史批量写入结果，记录批量失败后每行进入的补偿归宿。
 */
public record HistoryBatchWriteResult(boolean directSuccess,
                                      int rows,
                                      int redisBufferedRows,
                                      int localBufferedRows,
                                      int droppedRows,
                                      int disabledRows) {

    /**
     * 批量直写成功。
     */
    public static HistoryBatchWriteResult directSuccess(int rows) {
        return new HistoryBatchWriteResult(true, rows, 0, 0, 0, 0);
    }

    /**
     * 没有可处理的数据。
     */
    public static HistoryBatchWriteResult empty() {
        return directSuccess(0);
    }

    /**
     * 缓冲关闭导致整批无法进入补偿链路。
     */
    public static HistoryBatchWriteResult disabled(int rows) {
        return new HistoryBatchWriteResult(false, rows, 0, 0, 0, rows);
    }

    /**
     * 非直写成功的行数。
     */
    public int fallbackRows() {
        return redisBufferedRows + localBufferedRows + droppedRows + disabledRows;
    }
}
