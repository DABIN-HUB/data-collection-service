package com.wangbin.collector.core.connection.manager;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.connection.factory.ConnectionFactory;
import com.wangbin.collector.core.port.ExceptionReporter;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.model.ConnectionMetrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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

    @Test
    void removeConnectionClosesResourcesEvenWhenAdapterIsAlreadyDisconnected() throws Exception {
        Fixture fixture = new Fixture(null);
        ConnectionAdapter<?> adapter = fixture.add("dev-1", false, false);

        fixture.manager.removeConnection("dev-1");
        fixture.manager.removeConnection("dev-1");

        verify(adapter, times(1)).closeResources();
        verify(adapter, never()).disconnect();
        assertNull(fixture.manager.getConnection("dev-1"));
    }

    @Test
    void removeConnectedConnectionClosesResourcesOnlyOnce() throws Exception {
        Fixture fixture = new Fixture(null);
        ConnectionAdapter<?> adapter = fixture.add("dev-connected", true, true);

        fixture.manager.removeConnection("dev-connected");
        fixture.manager.closeAllConnections();

        verify(adapter, times(1)).closeResources();
        assertNull(fixture.manager.getConnection("dev-connected"));
    }

    @Test
    void closingAllConnectionsContinuesWhenOneResourceCloseThrows() throws Exception {
        Fixture fixture = new Fixture(null);
        ConnectionAdapter<?> first = fixture.add("dev-1", false, false);
        ConnectionAdapter<?> second = fixture.add("dev-2", false, false);
        doThrow(new IllegalStateException("close failed")).when(first).closeResources();

        fixture.manager.closeAllConnections();

        verify(first).closeResources();
        verify(second).closeResources();
        assertEquals(0, fixture.manager.getAllConnections().size());
    }

    @Test
    void disconnectedHeartbeatReconnectsOnlyWhenAutoReconnectEnabled() throws Exception {
        Fixture fixture = new Fixture(null);
        ConnectionAdapter<?> enabled = fixture.add("enabled", false, true);
        ConnectionAdapter<?> disabled = fixture.add("disabled", false, false);

        fixture.manager.startHeartbeatMonitor();

        verify(enabled).reconnect();
        verify(disabled, never()).reconnect();
        verify(enabled, never()).heartbeat();
        verify(disabled, never()).heartbeat();
    }

    @Test
    void healthyConnectedHeartbeatDoesNotReconnect() throws Exception {
        Fixture fixture = new Fixture(null);
        ConnectionAdapter<?> adapter = fixture.add("dev-connected", true, true);
        ConnectionMetrics metrics = new ConnectionMetrics();
        metrics.setLastActivityTime(System.currentTimeMillis());
        when(adapter.getMetrics()).thenReturn(metrics);

        fixture.manager.startHeartbeatMonitor();

        verify(adapter).heartbeat();
        verify(adapter, never()).reconnect();
    }

    @Test
    void timedOutConnectedHeartbeatReconnectsInsteadOfSendingHeartbeat() throws Exception {
        Fixture fixture = new Fixture(null);
        ConnectionAdapter<?> adapter = fixture.add("dev-1", true, true);
        ConnectionMetrics metrics = new ConnectionMetrics();
        metrics.setLastActivityTime(System.currentTimeMillis() - 10_000);
        when(adapter.getMetrics()).thenReturn(metrics);
        fixture.configs.get("dev-1").setHeartbeatTimeout(1);

        fixture.manager.startHeartbeatMonitor();

        verify(adapter).reconnect();
        verify(adapter, never()).heartbeat();
    }

    @Test
    void repeatedHeartbeatScanKeepsOnlyOneInFlightReconnectPerDevice() throws Exception {
        ExecutorService executor = mock(ExecutorService.class);
        Queue<Runnable> submitted = new ArrayDeque<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            submitted.add(invocation.getArgument(0));
            return mock(java.util.concurrent.Future.class);
        });
        Fixture fixture = new Fixture(executor);
        ConnectionAdapter<?> adapter = fixture.add("dev-1", false, true);

        fixture.manager.startHeartbeatMonitor();
        fixture.manager.startHeartbeatMonitor();
        assertEquals(1, submitted.size());
        submitted.remove().run();
        verify(adapter).reconnect();
        fixture.manager.startHeartbeatMonitor();
        assertEquals(1, submitted.size());
        submitted.remove().run();
        verify(adapter, times(2)).reconnect();
    }

    private static final class Fixture {
        private final ConnectionFactory factory = mock(ConnectionFactory.class);
        private final ConnectionManager manager;
        private final java.util.Map<String, DeviceConnection> configs = new java.util.HashMap<>();

        private Fixture(ExecutorService executor) {
            manager = new ConnectionManager(factory, mock(ConfigManager.class), null, executor);
        }

        private ConnectionAdapter<?> add(String id, boolean connected, boolean autoReconnect) {
            DeviceInfo device = new DeviceInfo();
            device.setDeviceId(id);
            DeviceConnection config = new DeviceConnection();
            config.setAutoReconnect(autoReconnect);
            configs.put(id, config);
            ConnectionAdapter<?> adapter = mock(ConnectionAdapter.class);
            when(adapter.getDeviceId()).thenReturn(id);
            when(adapter.isConnected()).thenReturn(connected);
            when(adapter.getConnectionConfig()).thenReturn(config);
            doReturn(adapter).when(factory).createConnection(device, config);
            assertSame(adapter, manager.createConnection(device, config));
            return adapter;
        }
    }
}
