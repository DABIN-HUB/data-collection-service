package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.storage.config.TdengineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Semaphore;

/**
 * TDengine 连通性监控服务。
 */
@Service
@RequiredArgsConstructor
public class TdengineMonitorService {

    private static final String HEALTH_QUERY = "SELECT 1";
    private static final int QUERY_TIMEOUT_SECONDS = 2;
    /** JDBC 建连可能阻塞原生读写，限制同时占用监控请求线程的探测数。 */
    private final Semaphore probePermit = new Semaphore(1);

    private final TdengineProperties tdengineProperties;
    private final ObjectProvider<DataSource> dataSourceProvider;

    public StorageMetricsSnapshot getStorageMetrics() {
        if (!tdengineProperties.isEnabled()) {
            return snapshot(false, StorageMetricsSnapshot.Status.DISABLED, "TDengine 未启用", 0L);
        }

        if (!probePermit.tryAcquire()) {
            return snapshot(true, StorageMetricsSnapshot.Status.ERROR, "TDengine 连接检测进行中", 0L);
        }

        long startTime = System.nanoTime();
        try {
            DataSource dataSource = dataSourceProvider.getIfAvailable();
            if (dataSource == null) {
                return snapshot(true, StorageMetricsSnapshot.Status.UNKNOWN, "TDengine 数据源不可用", elapsedMillis(startTime));
            }
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.execute(HEALTH_QUERY);
                return snapshot(true, StorageMetricsSnapshot.Status.OK, "TDengine 连接正常", elapsedMillis(startTime));
            } catch (SQLException exception) {
                return snapshot(true, StorageMetricsSnapshot.Status.ERROR, "TDengine 连接检测失败", elapsedMillis(startTime));
            }
        } finally {
            probePermit.release();
        }
    }

    /**
     * 查询并返回业务数据。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    private long elapsedMillis(long startTime) {
        return Math.max(0L, (System.nanoTime() - startTime) / 1_000_000L);
    }
}
