package com.wangbin.collector.storage.service;

import com.taosdata.jdbc.TaosPrepareStatement;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.TdengineTableRows;
import com.wangbin.collector.storage.repository.TelemetryInsertRow;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TDengine WebSocket STMT 列式绑定写入实现，运行时必须使用支持 TaosPrepareStatement 的连接。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "telemetry.tdengine", name = "enabled", havingValue = "true")
public class WebSocketStmtTdengineTelemetryWriter implements TdengineTelemetryWriter, DisposableBean {

    private static final int POINT_KEY_BYTES = 128;
    private static final int POINT_ID_BYTES = 128;
    private static final int POINT_CODE_BYTES = 128;
    private static final int POINT_NAME_BYTES = 256;
    private static final int VALUE_TEXT_BYTES = 1024;
    private static final int UNIT_BYTES = 64;
    private static final int MESSAGE_BYTES = 1024;
    private static final int RAW_JSON_BYTES = 2048;
    private static final int PROCESSED_JSON_BYTES = 2048;
    private static final int METADATA_JSON_BYTES = 4096;
    private static final String INSERT_COLUMNS = " (tbname,ts,point_key,event_ts,point_id,point_code,point_name,"
            + "value_text,unit,quality,success,message,raw_json,processed_json,metadata_json) VALUES "
            + "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final TdengineProperties properties;
    private final Map<Long, Connection> openedConnections = new ConcurrentHashMap<>();
    private final ThreadLocal<Connection> threadConnection = new ThreadLocal<>();

    @Override
    public TdengineWriteMode mode() {
        return TdengineWriteMode.WS_STMT;
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
        String url = websocketUrl();
        try {
            Connection connection = connectionForCurrentThread(url);
            long connectionAcquireNanos = System.nanoTime() - connectionStartedAt;
            long sqlStartedAt = System.nanoTime();
            String stableName = DirectJdbcTdengineTelemetryWriter.qualifiedTable(
                    target.database(), TimeSeriesService.resolveV2Name(properties.getSuperTable()));
            String tableName = target.subTable();
            String sql = "INSERT INTO " + stableName + INSERT_COLUMNS;
            long sqlBuildNanos = System.nanoTime() - sqlStartedAt;
            long executeStartedAt = System.nanoTime();
            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                if (!(preparedStatement instanceof TaosPrepareStatement statement)) {
                    throw new SQLException("TDengine WebSocket STMT 连接未返回 TaosPrepareStatement: "
                            + preparedStatement.getClass().getName());
                }
                bindRows(statement, tableName, rows);
                statement.columnDataExecuteBatch();
            }
            long executeNanos = System.nanoTime() - executeStartedAt;
            return TdengineWriteOutcome.success(rows.size(), 1, false,
                    connectionAcquireNanos, sqlBuildNanos, executeNanos, System.nanoTime() - startedAt);
        } catch (SQLException exception) {
            closeCurrentThreadConnection();
            long total = System.nanoTime() - startedAt;
            throw new TdengineWriteException("TDengine WebSocket STMT 批量写入失败", exception,
                    TdengineWriteOutcome.success(rows.size(), 1, false, 0L, 0L, total, total));
        }
    }

    @Override
    public TdengineWriteOutcome writeMultiTableBatch(String database, List<TdengineTableRows> tables) {
        if (tables == null || tables.isEmpty()) {
            return TdengineWriteOutcome.success(0, 0, true, 0L, 0L, 0L, 0L);
        }
        long startedAt = System.nanoTime();
        long connectionAcquireNanos = 0L;
        long sqlBuildNanos = 0L;
        long dbExecuteNanos = 0L;
        int rows = 0;
        try {
            for (TdengineTableRows table : tables) {
                if (table == null || table.rows() == null || table.rows().isEmpty()) {
                    continue;
                }
                TdengineWriteOutcome outcome = writeBatch(
                        new TdengineWriteTarget(database, table.subTable()), table.rows());
                rows += outcome.rows();
                connectionAcquireNanos += outcome.connectionAcquireNanos();
                sqlBuildNanos += outcome.sqlBuildNanos();
                dbExecuteNanos += outcome.dbExecuteNanos();
            }
            return TdengineWriteOutcome.success(rows, tables.size(), true,
                    connectionAcquireNanos, sqlBuildNanos, dbExecuteNanos, System.nanoTime() - startedAt);
        } catch (RuntimeException exception) {
            long total = System.nanoTime() - startedAt;
            throw new TdengineWriteException("TDengine WebSocket STMT 跨表批量写入失败", exception,
                    TdengineWriteOutcome.success(rows, tables.size(), true,
                            connectionAcquireNanos, sqlBuildNanos, dbExecuteNanos, total));
        }
    }

    private void bindRows(TaosPrepareStatement statement,
                          String tableName,
                          List<TelemetryInsertRow> rows) throws SQLException {
        statement.setString(1, repeatedTableNames(tableName, rows.size()), 256);
        statement.setTimestamp(2, longValues(rows, TelemetryInsertRow::getEventTs));
        statement.setString(3, stringValues(rows, TelemetryInsertRow::getPointKey), POINT_KEY_BYTES);
        statement.setLong(4, longValues(rows, TelemetryInsertRow::getEventTs));
        statement.setNString(5, stringValues(rows, TelemetryInsertRow::getPointId), POINT_ID_BYTES);
        statement.setNString(6, stringValues(rows, TelemetryInsertRow::getPointCode), POINT_CODE_BYTES);
        statement.setNString(7, stringValues(rows, TelemetryInsertRow::getPointName), POINT_NAME_BYTES);
        statement.setNString(8, stringValues(rows, TelemetryInsertRow::getValueText), VALUE_TEXT_BYTES);
        statement.setNString(9, stringValues(rows, TelemetryInsertRow::getUnit), UNIT_BYTES);
        statement.setInt(10, intValues(rows));
        statement.setBoolean(11, booleanValues(rows));
        statement.setNString(12, stringValues(rows, TelemetryInsertRow::getMessage), MESSAGE_BYTES);
        statement.setNString(13, stringValues(rows, TelemetryInsertRow::getRawJson), RAW_JSON_BYTES);
        statement.setNString(14, stringValues(rows, TelemetryInsertRow::getProcessedJson), PROCESSED_JSON_BYTES);
        statement.setNString(15, stringValues(rows, TelemetryInsertRow::getMetadataJson), METADATA_JSON_BYTES);
        statement.columnDataAddBatch();
    }

    private String websocketUrl() {
        String configured = properties.getWrite().getWebsocketUrl();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "jdbc:TAOS-WS://127.0.0.1:6041/" + properties.getDatabase();
    }

    private Connection connectionForCurrentThread(String url) throws SQLException {
        Connection connection = threadConnection.get();
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(url, connectionProperties());
            threadConnection.set(connection);
            openedConnections.put(Thread.currentThread().getId(), connection);
        }
        return connection;
    }

    private void closeCurrentThreadConnection() {
        Connection connection = threadConnection.get();
        threadConnection.remove();
        openedConnections.remove(Thread.currentThread().getId());
        closeQuietly(connection);
    }

    @Override
    public void destroy() {
        for (Connection connection : openedConnections.values()) {
            closeQuietly(connection);
        }
        openedConnections.clear();
        threadConnection.remove();
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // WS_STMT 关闭失败不影响进程停机，后续写入会重新建连。
        }
    }

    private Properties connectionProperties() {
        Properties result = new Properties();
        String username = properties.getWrite().getWebsocketUsername();
        String password = properties.getWrite().getWebsocketPassword();
        if (username != null && !username.isBlank()) {
            result.setProperty("user", username);
        }
        if (password != null && !password.isBlank()) {
            result.setProperty("password", password);
        }
        return result;
    }

    private List<Long> longValues(List<TelemetryInsertRow> rows, LongValue value) {
        List<Long> result = new ArrayList<>(rows.size());
        for (TelemetryInsertRow row : rows) {
            result.add(value.get(row));
        }
        return result;
    }

    private List<String> repeatedTableNames(String tableName, int size) {
        List<String> result = new ArrayList<>(Math.max(0, size));
        for (int index = 0; index < size; index++) {
            result.add(tableName);
        }
        return result;
    }

    private List<String> stringValues(List<TelemetryInsertRow> rows, StringValue value) {
        List<String> result = new ArrayList<>(rows.size());
        for (TelemetryInsertRow row : rows) {
            result.add(value.get(row));
        }
        return result;
    }

    private List<Integer> intValues(List<TelemetryInsertRow> rows) {
        List<Integer> result = new ArrayList<>(rows.size());
        for (TelemetryInsertRow row : rows) {
            result.add(row.getQuality());
        }
        return result;
    }

    private List<Boolean> booleanValues(List<TelemetryInsertRow> rows) {
        List<Boolean> result = new ArrayList<>(rows.size());
        for (TelemetryInsertRow row : rows) {
            result.add(row.getSuccess());
        }
        return result;
    }

    private interface LongValue {
        Long get(TelemetryInsertRow row);
    }

    private interface StringValue {
        String get(TelemetryInsertRow row);
    }
}
