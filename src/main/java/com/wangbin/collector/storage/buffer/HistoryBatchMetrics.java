package com.wangbin.collector.storage.buffer;

/**
 * 历史批量写入的内部观测快照。
 */
public record HistoryBatchMetrics(long acceptedRows,
                                  long flushedBatches,
                                  long flushedRows,
                                  long batchWriteSuccess,
                                  long batchWriteFailure,
                                  long fallbackRows,
                                  int currentBufferedRows,
                                  int bufferedRowsPeak,
                                  double averageBatchSize,
                                  int batchSizeP50,
                                  int batchSizeP95,
                                  int batchSizeMax,
                                  double flushLatencyP50Ms,
                                  double flushLatencyP95Ms,
                                  double flushLatencyP99Ms,
                                  long oldestBufferedAgeMs,
                                  long shutdownFlushedRows) {
}
