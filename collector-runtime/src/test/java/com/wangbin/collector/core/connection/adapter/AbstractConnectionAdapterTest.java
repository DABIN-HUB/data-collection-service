package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractConnectionAdapterTest {

    @Test
    void reconnectShouldDisconnectExistingConnectionBeforeStartingNewConnect() throws Exception {
        TestConnectionAdapter adapter = new TestConnectionAdapter(device(), connection());

        adapter.connect();
        assertTrue(adapter.isConnected());
        assertTrue(adapter.clientOpen);

        adapter.reconnect();

        assertEquals(List.of("connect", "disconnect", "connect"), adapter.lifecycleEvents);
        assertEquals(2, adapter.connectCount);
        assertEquals(1, adapter.disconnectCount);
        assertTrue(adapter.disconnectObservedBeforeReconnectConnect);
        assertTrue(adapter.clientOpen);
        assertEquals(ConnectionStatus.CONNECTED, adapter.getStatus());
        assertEquals(ConnectionStatus.CONNECTED, adapter.getMetrics().getStatus());
        assertFalse(adapter.reconnectSawLeakedClient);
    }

    private DeviceInfo device() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("dev-conn");
        device.setProtocolType("TEST");
        return device;
    }

    private DeviceConnection connection() {
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("TEST");
        connection.setInitialReconnectDelay(0);
        connection.setMaxReconnectDelay(0);
        connection.setReconnectBackoffMultiplier(1.0);
        connection.setAutoReconnect(true);
        connection.setMaxReconnectAttempts(3);
        return connection;
    }

    private static final class TestConnectionAdapter extends AbstractConnectionAdapter<Object> {

        private final List<String> lifecycleEvents = new ArrayList<>();
        private int connectCount;
        private int disconnectCount;
        private boolean clientOpen;
        private boolean disconnectObservedBeforeReconnectConnect;
        private boolean reconnectSawLeakedClient;

        private TestConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
            super(deviceInfo, connectionConfig);
        }

        @Override
        protected void doConnect() {
            lifecycleEvents.add("connect");
            connectCount++;
            if (connectCount == 2) {
                disconnectObservedBeforeReconnectConnect = disconnectCount == 1;
                reconnectSawLeakedClient = clientOpen;
            }
            clientOpen = true;
        }

        @Override
        protected void doDisconnect() {
            lifecycleEvents.add("disconnect");
            disconnectCount++;
            clientOpen = false;
        }

        @Override
        protected void doSend(byte[] data) {
        }

        @Override
        protected byte[] doReceive() {
            return new byte[0];
        }

        @Override
        protected byte[] doReceive(long timeout) {
            return new byte[0];
        }

        @Override
        protected void doHeartbeat() {
        }

        @Override
        protected void doAuthenticate() {
        }

        @Override
        public Object getClient() {
            return clientOpen ? this : null;
        }
    }
}
