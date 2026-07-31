package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.storage.config.TdengineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * TDengine 连通性监控服务。
 */
@Service
@RequiredArgsConstructor
public class TdengineMonitorService {

    private static final String HEALTH_QUERY = "SELECT 1";
    private static final int QUERY_TIMEOUT_SECONDS = 2;

    private final TdengineProperties tdengineProperties;
    private final ObjectProvider<DataSource> dataSourceProvider;

    public StorageMetricsSnapshot getStorageMetrics() {
        if (!tdengineProperties.isEnabled()) {
            return snapshot(false, StorageMetricsSnapshot.Status.DISABLED, "TDengine 未启用", 0L);
        }

        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return snapshot(true, StorageMetricsSnapshot.Status.UNKNOWN, "TDengine 数据源不可用", 0L);
        }

        long startTime = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.execute(HEALTH_QUERY);
            return snapshot(true, StorageMetricsSnapshot.Status.OK, "TDengine 连接正常", elapsedMillis(startTime));
        } catch (SQLException exception) {
            return snapshot(true, StorageMetricsSnapshot.Status.ERROR, "TDengine 连接检测失败", elapsedMillis(startTime));
        }
    }

    private StorageMetricsSnapshot snapshot(boolean enabled,
                                            StorageMetricsSnapshot.Status status,
                                            String message,
                                            long responseTimeMs) {
        return StorageMetricsSnapshot.builder()
                .enabled(enabled)
                .status(status)
                .message(message)
                .responseTimeMs(responseTimeMs)
                .build();
    }

    private long elapsedMillis(long startTime) {
        return Math.max(0L, (System.nanoTime() - startTime) / 1_000_000L);
    }
}
