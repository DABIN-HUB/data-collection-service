package com.wangbin.collector.monitor.metrics;

import com.wangbin.collector.storage.config.TdengineProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TdengineMonitorServiceTest {

    @Mock
    private ObjectProvider<DataSource> dataSourceProvider;

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    private TdengineProperties properties;
    private TdengineMonitorService service;

    @BeforeEach
    void setUp() {
        properties = new TdengineProperties();
        service = new TdengineMonitorService(properties, dataSourceProvider);
    }

    @Test
    void shouldReturnDisabledWithoutOpeningConnection() {
        StorageMetricsSnapshot snapshot = service.getStorageMetrics();

        assertFalse(snapshot.isEnabled());
        assertEquals(StorageMetricsSnapshot.Status.DISABLED, snapshot.getStatus());
        verifyNoInteractions(dataSourceProvider);
    }

    @Test
    void shouldReturnOkWhenHealthQuerySucceeds() throws Exception {
        properties.setEnabled(true);
        when(dataSourceProvider.getIfAvailable()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("SELECT 1")).thenReturn(true);

        StorageMetricsSnapshot snapshot = service.getStorageMetrics();

        assertTrue(snapshot.isEnabled());
        assertEquals(StorageMetricsSnapshot.Status.OK, snapshot.getStatus());
    }

    @Test
    void shouldReturnErrorWhenConnectionFails() throws Exception {
        properties.setEnabled(true);
        when(dataSourceProvider.getIfAvailable()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenThrow(new SQLException("连接失败"));

        StorageMetricsSnapshot snapshot = service.getStorageMetrics();

        assertTrue(snapshot.isEnabled());
        assertEquals(StorageMetricsSnapshot.Status.ERROR, snapshot.getStatus());
    }
}
