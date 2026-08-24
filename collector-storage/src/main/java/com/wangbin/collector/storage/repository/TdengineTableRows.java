package com.wangbin.collector.storage.repository;

import java.util.List;

/**
 * TDengine V2 跨子表批量写入的单个子表片段，调用方必须保证 subTable 已完成标识符清洗。
 */
public record TdengineTableRows(String subTable, List<TelemetryInsertRow> rows) {
}
