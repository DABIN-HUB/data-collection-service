package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.storage.config.TdengineProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TdengineMonitorServiceConcurrencyTest {

    @Test
    void concurrentRequestDoesNotWaitForBlockedJdbcProbe() throws Exception {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        DataSource dataSource = mock(DataSource.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dataSource);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            throw new SQLException("simulated unavailable JDBC");
        });
        TdengineMonitorService service = new TdengineMonitorService(properties, provider);
        AtomicReference<StorageMetricsSnapshot> firstResult = new AtomicReference<>();
        Thread first = new Thread(() -> firstResult.set(service.getStorageMetrics()));
        first.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 3; index++) {
                StorageMetricsSnapshot busy = service.getStorageMetrics();
                assertEquals(StorageMetricsSnapshot.Status.ERROR, busy.getStatus());
                assertEquals("TDengine 连接检测进行中", busy.getMessage());
                assertEquals(0L, busy.getResponseTimeMs());
            }
        } finally {
            release.countDown();
            first.join(3_000L);
        }
        assertFalse(first.isAlive());
        assertEquals(StorageMetricsSnapshot.Status.ERROR, firstResult.get().getStatus());
    }
}
