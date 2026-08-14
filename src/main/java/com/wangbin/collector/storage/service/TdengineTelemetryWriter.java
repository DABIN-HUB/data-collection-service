package com.wangbin.collector.storage.service;

import com.wangbin.collector.storage.repository.TdengineTableRows;
import com.wangbin.collector.storage.repository.TelemetryInsertRow;

import java.util.List;

/**
 * TDengine 遥测写入执行器，只负责已确定子表后的数据库 request，不承载 History fallback 语义。
 */
public interface TdengineTelemetryWriter {

    TdengineWriteMode mode();

    TdengineWriteOutcome writeSingle(TdengineWriteTarget target, TelemetryInsertRow row);

    TdengineWriteOutcome writeBatch(TdengineWriteTarget target, List<TelemetryInsertRow> rows);

    TdengineWriteOutcome writeMultiTableBatch(String database, List<TdengineTableRows> tables);
}
