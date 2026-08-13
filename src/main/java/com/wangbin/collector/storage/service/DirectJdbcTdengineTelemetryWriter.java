package com.wangbin.collector.storage.service;

import com.wangbin.collector.storage.repository.TdengineTableRows;
import com.wangbin.collector.storage.repository.TelemetryInsertRow;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 直接 JDBC Statement 写入实现，用于验证 MyBatis 参数绑定是否放大 TDengine 写入尾延迟。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class DirectJdbcTdengineTelemetryWriter implements TdengineTelemetryWriter {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String V2_COLUMNS = " (ts,point_key,event_ts,point_id,point_code,point_name,"
            + "value_text,unit,quality,success,message,raw_json,processed_json,metadata_json) VALUES ";

    private final DataSource dataSource;

    @Override
    public TdengineWriteMode mode() {
        return TdengineWriteMode.DIRECT_REST;
    }

    @Override
    public TdengineWriteOutcome writeSingle(TdengineWriteTarget target, TelemetryInsertRow row) {
        return writeBatch(target, List.of(row));
    }

    @Override
    public TdengineWriteOutcome writeBatch(TdengineWriteTarget target, List<TelemetryInsertRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return TdengineWriteOutcome.success(0, 0, false, 0L, 0L, 0L, 0L);
        }
        long startedAt = System.nanoTime();
        long connectionStartedAt = startedAt;
        try (Connection connection = dataSource.getConnection()) {
            long connectionAcquireNanos = System.nanoTime() - connectionStartedAt;
            long sqlStartedAt = System.nanoTime();
            String sql = buildSingleTableInsert(target, rows);
            long sqlBuildNanos = System.nanoTime() - sqlStartedAt;
            long executeStartedAt = System.nanoTime();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
            long executeNanos = System.nanoTime() - executeStartedAt;
            return TdengineWriteOutcome.success(rows.size(), 1, false,
                    connectionAcquireNanos, sqlBuildNanos, executeNanos, System.nanoTime() - startedAt);
        } catch (SQLException exception) {
            long total = System.nanoTime() - startedAt;
            throw new TdengineWriteException("TDengine Direct REST 批量写入失败", exception,
                    TdengineWriteOutcome.success(rows.size(), 1, false, 0L, 0L, total, total));
        }
    }

    @Override
    public TdengineWriteOutcome writeMultiTableBatch(String database, List<TdengineTableRows> tables) {
        if (tables == null || tables.isEmpty()) {
            return TdengineWriteOutcome.success(0, 0, true, 0L, 0L, 0L, 0L);
        }
        long startedAt = System.nanoTime();
        long connectionStartedAt = startedAt;
        int rows = countRows(tables);
        try (Connection connection = dataSource.getConnection()) {
            long connectionAcquireNanos = System.nanoTime() - connectionStartedAt;
            long sqlStartedAt = System.nanoTime();
            String sql = buildMultiTableInsert(database, tables);
            long sqlBuildNanos = System.nanoTime() - sqlStartedAt;
            long executeStartedAt = System.nanoTime();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
            long executeNanos = System.nanoTime() - executeStartedAt;
            return TdengineWriteOutcome.success(rows, tables.size(), true,
                    connectionAcquireNanos, sqlBuildNanos, executeNanos, System.nanoTime() - startedAt);
        } catch (SQLException exception) {
            long total = System.nanoTime() - startedAt;
            throw new TdengineWriteException("TDengine Direct REST 跨表批量写入失败", exception,
                    TdengineWriteOutcome.success(rows, tables.size(), true, 0L, 0L, total, total));
        }
    }

    static String buildSingleTableInsert(TdengineWriteTarget target, List<TelemetryInsertRow> rows) {
        StringBuilder builder = new StringBuilder(estimateSqlSize(rows));
        builder.append("INSERT INTO ")
                .append(qualifiedTable(target.database(), target.subTable()))
                .append(V2_COLUMNS);
        appendRows(builder, rows);
        return builder.toString();
    }

    static String buildMultiTableInsert(String database, List<TdengineTableRows> tables) {
        String safeDatabase = safeIdentifier(database);
        StringBuilder builder = new StringBuilder(estimateMultiTableSqlSize(tables));
        builder.append("INSERT INTO ");
        boolean firstTable = true;
        for (TdengineTableRows table : tables) {
            if (table == null || table.rows() == null || table.rows().isEmpty()) {
                continue;
            }
            if (!firstTable) {
                builder.append(' ');
            }
            firstTable = false;
            builder.append(safeDatabase)
                    .append('.')
                    .append(safeIdentifier(table.subTable()))
                    .append(V2_COLUMNS);
            appendRows(builder, table.rows());
        }
        return builder.toString();
    }

    static String qualifiedTable(String database, String table) {
        return safeIdentifier(database) + "." + safeIdentifier(table);
    }

    private static void appendRows(StringBuilder builder, List<TelemetryInsertRow> rows) {
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            appendRow(builder, rows.get(index));
        }
    }

    private static void appendRow(StringBuilder builder, TelemetryInsertRow row) {
        builder.append('(')
                .append(row.getEventTs()).append(',')
                .append(stringLiteral(row.getPointKey())).append(',')
                .append(row.getEventTs()).append(',')
                .append(stringLiteral(row.getPointId())).append(',')
                .append(stringLiteral(row.getPointCode())).append(',')
                .append(stringLiteral(row.getPointName())).append(',')
                .append(stringLiteral(row.getValueText())).append(',')
                .append(stringLiteral(row.getUnit())).append(',')
                .append(integerLiteral(row.getQuality())).append(',')
                .append(booleanLiteral(row.getSuccess())).append(',')
                .append(stringLiteral(row.getMessage())).append(',')
                .append(stringLiteral(row.getRawJson())).append(',')
                .append(stringLiteral(row.getProcessedJson())).append(',')
                .append(stringLiteral(row.getMetadataJson()))
                .append(')');
    }

    private static String safeIdentifier(String raw) {
        if (raw == null || !SAFE_IDENTIFIER.matcher(raw).matches()) {
            throw new IllegalArgumentException("TDengine 标识符非法: " + raw);
        }
        return raw;
    }

    private static String stringLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''").replace("\u0000", "") + "'";
    }

    private static String integerLiteral(Integer value) {
        return value == null ? "NULL" : value.toString();
    }

    private static String booleanLiteral(Boolean value) {
        return value == null ? "NULL" : value.toString();
    }

    private static int estimateSqlSize(List<TelemetryInsertRow> rows) {
        return 256 + Math.max(0, rows == null ? 0 : rows.size()) * 1024;
    }

    private static int estimateMultiTableSqlSize(List<TdengineTableRows> tables) {
        return 256 + countRows(tables) * 1024;
    }

    private static int countRows(List<TdengineTableRows> tables) {
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
