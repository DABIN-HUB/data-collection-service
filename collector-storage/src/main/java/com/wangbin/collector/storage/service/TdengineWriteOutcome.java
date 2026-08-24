package com.wangbin.collector.storage.service;

/**
 * 单次 TDengine JDBC request 的写入结果和分段耗时。
 */
public record TdengineWriteOutcome(int rows,
                                   int tables,
                                   boolean multiTable,
                                   long connectionAcquireNanos,
                                   long sqlBuildNanos,
                                   long dbExecuteNanos,
                                   long totalWriteNanos) {

    static TdengineWriteOutcome success(int rows,
                                        int tables,
                                        boolean multiTable,
                                        long connectionAcquireNanos,
                                        long sqlBuildNanos,
                                        long dbExecuteNanos,
                                        long totalWriteNanos) {
        return new TdengineWriteOutcome(
                Math.max(0, rows),
                Math.max(0, tables),
                multiTable,
                Math.max(0L, connectionAcquireNanos),
                Math.max(0L, sqlBuildNanos),
                Math.max(0L, dbExecuteNanos),
                Math.max(0L, totalWriteNanos));
    }
}
