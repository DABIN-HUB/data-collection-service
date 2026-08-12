package com.wangbin.collector.storage.service;

/**
 * TDengine 写入路径指标快照，用于区分 JDBC request 数、跨表聚合效果和写入尾延迟。
 */
public record TdengineWriteMetrics(long writeRequests,
                                   long writtenRows,
                                   long singleTableWriteRequests,
                                   long multiTableWriteRequests,
                                   long writeFailures,
                                   long ensureSubTableCalls,
                                   long ensureSubTableCacheHits,
                                   long ensureSubTableCacheMisses,
                                   double averageRowsPerRequest,
                                   int rowsPerRequestP95,
                                   int rowsPerRequestMax,
                                   double averageTablesPerRequest,
                                   int tablesPerRequestP95,
                                   int tablesPerRequestMax,
                                   double writeLatencyP50Ms,
                                   double writeLatencyP95Ms,
                                   double writeLatencyP99Ms,
                                   double writeRequestsPerSecond,
                                   double writtenRowsPerSecond) {
}
