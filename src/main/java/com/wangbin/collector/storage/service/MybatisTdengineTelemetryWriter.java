package com.wangbin.collector.storage.service;

import com.wangbin.collector.storage.repository.DataRepository;
import com.wangbin.collector.storage.repository.TdengineTableRows;
import com.wangbin.collector.storage.repository.TelemetryInsertRow;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 既有 MyBatis TDengine 写入实现，作为默认基线和回滚路径。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class MybatisTdengineTelemetryWriter implements TdengineTelemetryWriter {

    private final DataRepository dataRepository;

    @Override
    public TdengineWriteMode mode() {
        return TdengineWriteMode.MYBATIS_REST;
    }

    @Override
    public TdengineWriteOutcome writeSingle(TdengineWriteTarget target, TelemetryInsertRow row) {
        long startedAt = System.nanoTime();
        dataRepository.insertTelemetryV2(
                target.database(),
                target.subTable(),
                row.getEventTs(),
                row.getPointKey(),
                row.getPointId(),
                row.getPointCode(),
                row.getPointName(),
                row.getValueText(),
                row.getUnit(),
                row.getQuality(),
                row.getSuccess(),
                row.getMessage(),
                row.getRawJson(),
                row.getProcessedJson(),
                row.getMetadataJson()
        );
        long total = System.nanoTime() - startedAt;
        return TdengineWriteOutcome.success(1, 1, false, 0L, 0L, total, total);
    }

    @Override
    public TdengineWriteOutcome writeBatch(TdengineWriteTarget target, List<TelemetryInsertRow> rows) {
        long startedAt = System.nanoTime();
        int size = rows == null ? 0 : rows.size();
        dataRepository.insertTelemetryV2Batch(target.database(), target.subTable(), rows);
        long total = System.nanoTime() - startedAt;
        return TdengineWriteOutcome.success(size, 1, false, 0L, 0L, total, total);
    }

    @Override
    public TdengineWriteOutcome writeMultiTableBatch(String database, List<TdengineTableRows> tables) {
        long startedAt = System.nanoTime();
        int rows = countRows(tables);
        int tableCount = tables == null ? 0 : tables.size();
        dataRepository.insertTelemetryV2MultiTableBatch(database, tables);
        long total = System.nanoTime() - startedAt;
        return TdengineWriteOutcome.success(rows, tableCount, true, 0L, 0L, total, total);
    }

    private int countRows(List<TdengineTableRows> tables) {
        if (tables == null) {
            return 0;
        }
        int rows = 0;
        for (TdengineTableRows table : tables) {
            if (table != null && table.rows() != null) {
                rows += table.rows().size();
            }
        }
        return rows;
    }
}
