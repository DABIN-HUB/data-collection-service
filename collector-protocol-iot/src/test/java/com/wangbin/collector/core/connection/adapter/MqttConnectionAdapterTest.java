package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MqttConnectionAdapterTest {

    @Test
    void absentWillTopicDoesNotConfigureWillForEitherProtocolVersion() throws Exception {
        for (String version : new String[]{"v3", "v5"}) {
            for (String topic : new String[]{null, "", "   ",
                    String.valueOf((char) 9) + (char) 13 + (char) 10, String.valueOf((char) 0x2003)}) {
                DeviceConnection config = connection(version);
                Map<String, Object> ext = new LinkedHashMap<>(config.getExtJson());
                ext.put("willTopic", topic);
                config.setExtJson(ext);
                MqttConnectionAdapter adapter = new MqttConnectionAdapter(device(), config);
                try {
                    configureOptions(adapter, version);
                    if ("v3".equals(version)) {
                        MqttConnectOptions options = (MqttConnectOptions) ReflectionTestUtils.getField(
                                adapter, "connectionOptionsV3");
                        assertNull(options.getWillDestination());
                        assertNull(options.getWillMessage());
                        assertFalse(options.isAutomaticReconnect());
                    } else {
                        MqttConnectionOptions options = (MqttConnectionOptions) ReflectionTestUtils.getField(
                                adapter, "connectionOptionsV5");
                        assertNull(options.getWillDestination());
                        assertNull(options.getWillMessage());
                        assertFalse(options.isAutomaticReconnect());
                    }
                } finally {
                    adapter.closeResources();
                }
            }
        }
    }

    @Test
    void validWillPreservesTopicPayloadAndConnectionOptionsForEitherProtocolVersion() throws Exception {
        for (String version : new String[]{"v3", "v5"}) {
            DeviceConnection config = connection(version);
            config.setClientId("wangbin-r1-client");
            config.setUsername("wangbin-r1-user");
            config.setPassword("wangbin-r1-placeholder");
            config.setConnectTimeout(7000);
            config.setHeartbeatInterval(42000);
            config.setExtJson(Map.of("version", version, "cleanSession", false,
                    "willTopic", "wangbin/${deviceId}/status ", "willMessage", "离线",
                    "willQos", 2, "willRetained", true));
            MqttConnectionAdapter adapter = new MqttConnectionAdapter(device(), config);
            try {
                configureOptions(adapter, version);
                assertEquals("wangbin-r1-client", ReflectionTestUtils.getField(adapter, "clientId"));
                if ("v3".equals(version)) {
                    MqttConnectOptions options = (MqttConnectOptions) ReflectionTestUtils.getField(
                            adapter, "connectionOptionsV3");
                    assertEquals("wangbin/dev-mqtt/status ", options.getWillDestination());
                    assertArrayEquals("离线".getBytes(StandardCharsets.UTF_8), options.getWillMessage().getPayload());
                    assertEquals(2, options.getWillMessage().getQos());
                    assertTrue(options.getWillMessage().isRetained());
                    assertEquals(config.getUsername(), options.getUserName());
                    assertArrayEquals(config.getPassword().toCharArray(), options.getPassword());
                    assertFalse(options.isCleanSession());
                    assertFalse(options.isAutomaticReconnect());
                    assertEquals(7, options.getConnectionTimeout());
                    assertEquals(42, options.getKeepAliveInterval());
                } else {
                    MqttConnectionOptions options = (MqttConnectionOptions) ReflectionTestUtils.getField(
                            adapter, "connectionOptionsV5");
                    assertEquals("wangbin/dev-mqtt/status ", options.getWillDestination());
                    assertArrayEquals("离线".getBytes(StandardCharsets.UTF_8), options.getWillMessage().getPayload());
                    assertEquals(2, options.getWillMessage().getQos());
                    assertTrue(options.getWillMessage().isRetained());
                    assertEquals(config.getUsername(), options.getUserName());
                    assertArrayEquals(config.getPassword().getBytes(StandardCharsets.UTF_8), options.getPassword());
                    assertFalse(options.isCleanStart());
                    assertFalse(options.isAutomaticReconnect());
                    assertEquals(7, options.getConnectionTimeout());
                    assertEquals(42, options.getKeepAliveInterval());
                }
            } finally {
                adapter.closeResources();
            }
        }
    }

    @Test
    void nonblankInvalidWillTopicIsRejectedForEitherProtocolVersion() {
        for (String version : new String[]{"v3", "v5"}) {
            for (String topic : new String[]{"wangbin/+", "wangbin/#", "wangbin/\0/status",
                    "wangbin/${unknown}/status", "x".repeat(65536)}) {
                DeviceConnection config = connection(version);
                config.setExtJson(Map.of("version", version, "willTopic", topic));
                assertThrows(IllegalArgumentException.class, () -> new MqttConnectionAdapter(device(), config));
            }
        }
    }

    @Test
    void v3ReconnectKeepsClientAndRestoresTrackedSubscriptionOnlyOnExplicitReconnect() throws Exception {
        DeviceConnection config = connection("v3");
        MqttConnectionAdapter adapter = new MqttConnectionAdapter(device(), config);
        org.eclipse.paho.client.mqttv3.MqttAsyncClient client =
                mock(org.eclipse.paho.client.mqttv3.MqttAsyncClient.class);
        org.eclipse.paho.client.mqttv3.IMqttToken token =
                mock(org.eclipse.paho.client.mqttv3.IMqttToken.class);
        when(token.getGrantedQos()).thenReturn(new int[]{1});
        java.util.concurrent.atomic.AtomicBoolean connected = new java.util.concurrent.atomic.AtomicBoolean();
        when(client.isConnected()).thenAnswer(invocation -> connected.get());
        when(client.connect(any(MqttConnectOptions.class))).thenAnswer(invocation -> {
            connected.set(true);
            return token;
        });
        when(client.disconnect()).thenReturn(token);
        when(client.subscribe("devices/+/data", 1)).thenReturn(token);
        when(client.unsubscribe(any(String[].class))).thenReturn(token);
        ReflectionTestUtils.setField(adapter, "mqttClientV3", client);
        try {
            configureOptions(adapter, "v3");
            assertFalse(((MqttConnectOptions) ReflectionTestUtils.getField(adapter,
                    "connectionOptionsV3")).isAutomaticReconnect());
            trackedTopics(adapter).put("devices/+/data", 1);
            adapter.connectionLost(new IllegalStateException("broker lost"));
            adapter.connectComplete(true, "tcp://broker:1883");
            verify(client, never()).subscribe(anyString(), anyInt());
            adapter.reconnect();
            verify(client).subscribe("devices/+/data", 1);
            verify(client, never()).close();
            assertEquals(ConnectionStatus.CONNECTED, adapter.getMetrics().getStatus());
            assertTrue(adapter.isSubscribed("devices/+/data"));
        } finally {
            adapter.closeResources();
        }
        verify(client).close();
    }

    @Test
    void v5ReconnectKeepsClientAndRestoresTrackedSubscriptionOnlyOnExplicitReconnect() throws Exception {
        DeviceConnection config = connection("v5");
        MqttConnectionAdapter adapter = new MqttConnectionAdapter(device(), config);
        org.eclipse.paho.mqttv5.client.MqttAsyncClient client =
                mock(org.eclipse.paho.mqttv5.client.MqttAsyncClient.class);
        org.eclipse.paho.mqttv5.client.IMqttToken token = mock(org.eclipse.paho.mqttv5.client.IMqttToken.class);
        when(token.getReasonCodes()).thenReturn(new int[]{1});
        java.util.concurrent.atomic.AtomicBoolean connected = new java.util.concurrent.atomic.AtomicBoolean();
        when(client.isConnected()).thenAnswer(invocation -> connected.get());
        when(client.connect(any(MqttConnectionOptions.class))).thenAnswer(invocation -> {
            connected.set(true);
            return token;
        });
        when(client.disconnect()).thenReturn(token);
        when(client.subscribe("devices/+/data", 1)).thenReturn(token);
        when(client.unsubscribe(any(String[].class))).thenReturn(token);
        ReflectionTestUtils.setField(adapter, "mqttClientV5", client);
        try {
            configureOptions(adapter, "v5");
            assertFalse(((MqttConnectionOptions) ReflectionTestUtils.getField(adapter,
                    "connectionOptionsV5")).isAutomaticReconnect());
            trackedTopics(adapter).put("devices/+/data", 1);
            adapter.disconnected(null);
            adapter.connectComplete(true, "tcp://broker:1883");
            verify(client, never()).subscribe(anyString(), anyInt());
            adapter.reconnect();
            verify(client).subscribe("devices/+/data", 1);
            verify(client, never()).close();
            assertEquals(ConnectionStatus.CONNECTED, adapter.getMetrics().getStatus());
            assertTrue(adapter.isSubscribed("devices/+/data"));
        } finally {
            adapter.closeResources();
        }
        verify(client).close();
    }

    @Test
    void trustAllSocketFactoryIsOnlyInstalledWhenSslAndExplicitSkipVerifyAreEnabled() throws Exception {
        for (String version : new String[]{"v3", "v5"}) {
            DeviceConnection secure = connection(version);
            secure.setSslEnabled(true);
            MqttConnectionAdapter defaultTrust = new MqttConnectionAdapter(device(), secure);
            try {
                configureOptions(defaultTrust, version);
                assertNull(socketFactory(defaultTrust, version));
            } finally {
                defaultTrust.closeResources();
            }

            DeviceConnection insecure = connection(version);
            insecure.setSslEnabled(true);
            insecure.setExtJson(Map.of("version", version, "insecureSkipVerify", true));
            MqttConnectionAdapter trustAll = new MqttConnectionAdapter(device(), insecure);
            try {
                configureOptions(trustAll, version);
                assertNotNull(socketFactory(trustAll, version));
            } finally {
                trustAll.closeResources();
            }
        }
    }

    private Object socketFactory(MqttConnectionAdapter adapter, String version) {
        return "v3".equals(version)
                ? ((MqttConnectOptions) ReflectionTestUtils.getField(adapter, "connectionOptionsV3")).getSocketFactory()
                : ((MqttConnectionOptions) ReflectionTestUtils.getField(adapter, "connectionOptionsV5")).getSocketFactory();
    }

    private void configureOptions(MqttConnectionAdapter adapter, String version) {
        ReflectionTestUtils.invokeMethod(adapter, "v3".equals(version)
                ? "configureV3Options" : "configureV5Options");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> trackedTopics(MqttConnectionAdapter adapter) {
        return (Map<String, Integer>) ReflectionTestUtils.getField(adapter, "subscribedTopics");
    }

    private DeviceInfo device() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("dev-mqtt");
        device.setProtocolType("MQTT");
        return device;
    }

    private DeviceConnection connection(String version) {
        DeviceConnection config = new DeviceConnection();
        config.setConnectionType("MQTT");
        config.setInitialReconnectDelay(0);
        config.setExtJson(Map.of("version", version));
        return config;
    }
}
