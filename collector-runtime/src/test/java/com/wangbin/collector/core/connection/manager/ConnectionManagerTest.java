package com.wangbin.collector.core.connection.manager;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.connection.factory.ConnectionFactory;
import com.wangbin.collector.core.port.ExceptionReporter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionManagerTest {

    @Test
    void createConnectionShouldReportExceptionThroughPort() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        ConfigManager configManager = mock(ConfigManager.class);
        ExceptionReporter exceptionReporter = mock(ExceptionReporter.class);
        ConnectionManager connectionManager = new ConnectionManager(
                connectionFactory,
                configManager,
                exceptionReporter,
                null);
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        DeviceConnection connection = new DeviceConnection();
        RuntimeException failure = new RuntimeException("create failed");
        when(connectionFactory.createConnection(deviceInfo, connection)).thenThrow(failure);

        assertThrows(CollectorException.class, () -> connectionManager.createConnection(deviceInfo, connection));

        verify(exceptionReporter).record(failure, "dev-1", null);
    }
}
