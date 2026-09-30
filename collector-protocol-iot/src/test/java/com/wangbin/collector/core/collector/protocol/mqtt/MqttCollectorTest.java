package com.wangbin.collector.core.collector.protocol.mqtt;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MqttConnectionAdapter;
import com.wangbin.collector.core.connection.adapter.MqttReceivedMessage;
import com.wangbin.collector.core.connection.dispatch.MessageBatchDispatcher;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttCollectorTest {

    @Test
    void batchReadSkipsPointsWithoutReceivedValues() {
        MqttCollector collector = new MqttCollector();
        DataPoint point = point("missing", "sensor/missing", Map.of());

        assertTrue(collector.doReadPoints(List.of(point)).isEmpty());
    }

    @Test
    void plainTextAndJsonPayloadsUseExplicitEncodingAndJsonPaths() throws Exception {
        RecordingCollector collector = collector();
        DataPoint plain = point("plain", "sensor/plain", Map.of("payloadEncoding", "PLAIN_TEXT"));
        DataPoint json = point("json", "sensor/json", Map.of("payloadEncoding", "JSON", "jsonPath", "$.reading.temp"));
        DataPoint value = point("value", "sensor/value", Map.of("payloadEncoding", "JSON"));
        collector.rebuildReadPlans("device-1", List.of(plain, json, value));

        inbound(collector, "sensor/plain", "{\"value\":17}");
        inbound(collector, "sensor/json", "{\"reading\":{\"temp\":23}}");
        inbound(collector, "sensor/value", "{\"value\":19}");

        assertEquals("{\"value\":17}", collector.doReadPoint(plain));
        assertEquals(23, ((Number) collector.doReadPoint(json)).intValue());
        assertEquals(19, ((Number) collector.doReadPoint(value)).intValue());
        assertEquals(List.of("plain", "json", "value"), collector.ingestedIds);
    }

    @Test
    void base64AndHexPayloadsDecodeBeforeCachingAndPushing() throws Exception {
        RecordingCollector collector = collector();
        DataPoint base64 = point("base64", "encoded/b64", Map.of("payloadEncoding", "BASE64"));
        DataPoint hex = point("hex", "encoded/hex", Map.of("payloadEncoding", "HEX"));
        collector.rebuildReadPlans("device-1", List.of(base64, hex));

        inbound(collector, "encoded/b64", "5rWL6K+V");
        inbound(collector, "encoded/hex", "E6B58BE8AF95");

        assertEquals("测试", collector.doReadPoint(base64));
        assertEquals("测试", collector.doReadPoint(hex));
        assertEquals(List.of("base64", "hex"), collector.ingestedIds);
    }

    @Test
    void invalidPayloadForOnePointDoesNotPreventOtherBindingsFromReceiving() throws Exception {
        RecordingCollector collector = collector();
        DataPoint strict = point("strict", "shared/topic", Map.of("payloadEncoding", "JSON", "jsonPath", "$.reading"));
        DataPoint plain = point("plain", "shared/topic", Map.of("payloadEncoding", "PLAIN_TEXT"));
        collector.rebuildReadPlans("device-1", List.of(strict, plain));

        inbound(collector, "shared/topic", "{broken-json");

        assertNull(collector.doReadPoint(strict));
        assertEquals("{broken-json", collector.doReadPoint(plain));
        assertEquals(List.of("plain"), collector.ingestedIds);
    }

    @Test
    void oneJsonMessageUpdatesTwoPathsWhileMissingThirdPathDoesNotPush() throws Exception {
        RecordingCollector collector = collector();
        DataPoint temperature = point("temperature", "meter/reading", Map.of("jsonPath", "$.temperature"));
        DataPoint humidity = point("humidity", "meter/reading", Map.of("jsonPath", "$.humidity"));
        DataPoint missing = point("missing", "meter/reading", Map.of("jsonPath", "$.absent"));
        collector.rebuildReadPlans("device-1", List.of(temperature, humidity, missing));

        inbound(collector, "meter/reading", "{\"temperature\":21,\"humidity\":64}");

        assertEquals(21, ((Number) collector.doReadPoint(temperature)).intValue());
        assertEquals(64, ((Number) collector.doReadPoint(humidity)).intValue());
        assertNull(collector.doReadPoint(missing));
        assertEquals(Set.of("temperature", "humidity"), Set.copyOf(collector.ingestedIds));
        assertEquals(2, collector.ingestedIds.size());
    }

    @Test
    void duplicateBindingDoesNotDoubleCountAndLastUnsubscribeReleasesTopic() throws Exception {
        RecordingCollector collector = collector();
        MqttConnectionAdapter adapter = mock(MqttConnectionAdapter.class);
        Map<String, Integer> wireTopics = wireSubscriptions(adapter);
        ReflectionTestUtils.setField(collector, "mqttConnection", adapter);
        DataPoint first = point("first", "shared/topic", Map.of());
        DataPoint second = point("second", "shared/topic", Map.of());

        collector.doSubscribe(List.of(first, first, second));
        assertEquals(2, refCounts(collector).get("shared/topic"));
        verify(adapter, times(1)).subscribe("shared/topic", 1);
        collector.doUnsubscribe(List.of(first));
        assertEquals(1, refCounts(collector).get("shared/topic"));
        assertTrue(wireTopics.containsKey("shared/topic"));
        verify(adapter, never()).unsubscribe("shared/topic");
        collector.doUnsubscribe(List.of(second));

        assertFalse(wireTopics.containsKey("shared/topic"));
        assertFalse(refCounts(collector).containsKey("shared/topic"));
        verify(adapter).unsubscribe("shared/topic");
    }

    @Test
    void repeatedBindingOfSamePointCountsOnceAndSubscribesOnce() throws Exception {
        RecordingCollector collector = collector();
        MqttConnectionAdapter adapter = mock(MqttConnectionAdapter.class);
        wireSubscriptions(adapter);
        ReflectionTestUtils.setField(collector, "mqttConnection", adapter);
        DataPoint point = point("same", "sensor/same", Map.of());

        collector.doSubscribe(List.of(point, point));
        collector.rebuildReadPlans("device-1", List.of(point));

        assertEquals(1, refCounts(collector).get("sensor/same"));
        verify(adapter, times(1)).subscribe("sensor/same", 1);
    }

    @Test
    void rebuildRebindsChangedPointAndInvalidatesOldCachedValue() throws Exception {
        RecordingCollector collector = collector();
        MqttConnectionAdapter adapter = mock(MqttConnectionAdapter.class);
        Map<String, Integer> wireTopics = wireSubscriptions(adapter);
        ReflectionTestUtils.setField(collector, "mqttConnection", adapter);
        DataPoint old = point("sensor", "old/topic", Map.of());
        DataPoint replacement = point("sensor", "new/topic", Map.of());
        collector.rebuildReadPlans("device-1", List.of(old));
        inbound(collector, "old/topic", "1");

        collector.rebuildReadPlans("device-1", List.of(replacement));
        assertFalse(bindings(collector).containsKey("old/topic"));
        assertFalse(refCounts(collector).containsKey("old/topic"));
        assertEquals(Set.of("sensor"), bindings(collector).get("new/topic"));
        assertEquals(1, refCounts(collector).get("new/topic"));
        inbound(collector, "old/topic", "2");
        assertNull(collector.doReadPoint(replacement));
        inbound(collector, "new/topic", "3");

        assertEquals(3, ((Number) collector.doReadPoint(replacement)).intValue());
        assertFalse(wireTopics.containsKey("old/topic"));
        assertTrue(wireTopics.containsKey("new/topic"));
        verify(adapter).unsubscribe("old/topic");
    }

    @Test
    void unsubscribingPointKeepsExplicitBaseTopicUntilBaseIsRemoved() throws Exception {
        RecordingCollector collector = collector();
        MqttConnectionAdapter adapter = mock(MqttConnectionAdapter.class);
        Map<String, Integer> wireTopics = wireSubscriptions(adapter);
        ReflectionTestUtils.setField(collector, "mqttConnection", adapter);
        DataPoint point = point("sensor", "shared/topic", Map.of());
        collector.doSubscribe(List.of(point));
        collector.doExecuteCommand(0, "subscribe", Map.of("topic", "shared/topic"));

        collector.doUnsubscribe(List.of(point));
        assertTrue(wireTopics.containsKey("shared/topic"));
        collector.doExecuteCommand(0, "unsubscribe", Map.of("topic", "shared/topic"));

        assertFalse(wireTopics.containsKey("shared/topic"));
        verify(adapter).unsubscribe("shared/topic");
    }

    @Test
    void coldStartInboundDuringFirstWireSubscriptionIsAlreadyBound() throws Exception {
        RecordingCollector collector = collector();
        MqttConnectionAdapter adapter = mock(MqttConnectionAdapter.class);
        Map<String, Integer> wireTopics = wireSubscriptions(adapter);
        DataPoint point = point("cold", "cold/topic", Map.of("payloadEncoding", "PLAIN_TEXT"));
        ReflectionTestUtils.setField(collector, "mqttConnection", adapter);
        doAnswer(invocation -> {
            wireTopics.put(invocation.getArgument(0), invocation.getArgument(1));
            inbound(collector, "cold/topic", "first");
            return null;
        }).when(adapter).subscribe("cold/topic", 1);

        collector.rebuildReadPlans("device-1", List.of(point));

        assertEquals("first", collector.doReadPoint(point));
        assertEquals(List.of("cold"), collector.ingestedIds);
    }

    @Test
    void coldStartRegistersListenerBeforeConnectCanDeliverInbound() throws Exception {
        MqttConnectionAdapter adapter = mock(MqttConnectionAdapter.class);
        AtomicReference<Consumer<MqttReceivedMessage>> listener = new AtomicReference<>();
        doAnswer(call -> {
            listener.set(call.getArgument(0));
            return null;
        }).when(adapter).addMessageListener(any());
        ColdStartCollector collector = new ColdStartCollector(adapter, listener);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("device-1");
        device.setDeviceName("MQTT 测试设备");
        collector.init(device);
        DataPoint point = point("cold", "cold/topic", Map.of("payloadEncoding", "PLAIN_TEXT"));
        collector.pointsAtConnect = List.of(point);
        doAnswer(call -> {
            listener.get().accept(new MqttReceivedMessage("cold/topic",
                    "first".getBytes(StandardCharsets.UTF_8), 1, false, Map.of()));
            return null;
        }).when(adapter).subscribe("cold/topic", 1);

        collector.connect();

        assertTrue(collector.listenerPresentAtConnect);
        assertEquals("first", collector.doReadPoint(point));
        assertEquals(List.of("cold"), collector.ingestedIds);
        verify(adapter, times(1)).addMessageListener(any());
    }

    @Test
    void collectionAndStringDefaultTopicsSubscribeAsIndependentWireFilters() throws Exception {
        String first = "devices/${deviceId}/a";
        String second = "devices/${deviceId}/b";
        for (Object configured : List.of(List.of(first, second),
                new LinkedHashSet<>(List.of(first, second)), first + "," + second)) {
            ConfiguredCollector collector = new ConfiguredCollector(configured);
            DeviceInfo device = new DeviceInfo();
            device.setDeviceId("device-1");
            device.setDeviceName("MQTT 测试设备");
            collector.init(device);
            MqttConnectionAdapter adapter = mock(MqttConnectionAdapter.class);
            Map<String, Integer> wireTopics = wireSubscriptions(adapter);
            ReflectionTestUtils.setField(collector, "mqttConnection", adapter);

            collector.rebuildReadPlans("device-1", List.of());

            assertEquals(Set.of("devices/device-1/a", "devices/device-1/b"), wireTopics.keySet());
            assertEquals(1, refCounts(collector).get("devices/device-1/a"));
            assertEquals(1, refCounts(collector).get("devices/device-1/b"));
            verify(adapter, times(1)).subscribe("devices/device-1/a", 1);
            verify(adapter, times(1)).subscribe("devices/device-1/b", 1);
        }
    }

    @Test
    void wildcardTopicDispatchesLatestValueOnlyToMatchingPoint() throws Exception {
        RecordingCollector collector = collector();
        DataPoint matching = point("temperature", "factory/+/telemetry", Map.of("payloadEncoding", "PLAIN_TEXT"));
        DataPoint other = point("other", "factory/+/status", Map.of("payloadEncoding", "PLAIN_TEXT"));
        collector.rebuildReadPlans("device-1", List.of(matching, other));

        inbound(collector, "factory/device01/telemetry", "old");
        inbound(collector, "factory/device02/telemetry", "new");
        assertNull(collector.doReadPoint(other));
        inbound(collector, "factory/device02/status", "online");

        assertEquals("new", collector.doReadPoint(matching));
        assertEquals("online", collector.doReadPoint(other));
        assertEquals(List.of("temperature", "temperature", "other"), collector.ingestedIds);
        inbound(collector, "factory/device01/other", "ignored");
        assertEquals(List.of("temperature", "temperature", "other"), collector.ingestedIds);
    }

    @Test
    void dispatcherRejectionIncrementsDroppedMessagesWithoutCountingReceived() throws Exception {
        DeviceConnection config = new DeviceConnection();
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("device-1");
        MqttConnectionAdapter adapter = new MqttConnectionAdapter(device, config);
        @SuppressWarnings("unchecked")
        MessageBatchDispatcher<MqttReceivedMessage> realDispatcher =
                (MessageBatchDispatcher<MqttReceivedMessage>) ReflectionTestUtils.getField(adapter, "messageDispatcher");
        realDispatcher.stop();
        @SuppressWarnings("unchecked")
        MessageBatchDispatcher<MqttReceivedMessage> rejecting = mock(MessageBatchDispatcher.class);
        when(rejecting.enqueue(any())).thenReturn(false);
        ReflectionTestUtils.setField(adapter, "messageDispatcher", rejecting);
        try {
            org.eclipse.paho.client.mqttv3.MqttMessage message =
                    new org.eclipse.paho.client.mqttv3.MqttMessage("rejected".getBytes(StandardCharsets.UTF_8));
            adapter.messageArrived("devices/a", message);

            assertEquals(1L, adapter.getStatistics().get("droppedMessages"));
            assertEquals(0L, ((Number) adapter.getStatistics().get("messagesReceived")).longValue());
            adapter.resetStatistics();
            assertEquals(0L, adapter.getStatistics().get("droppedMessages"));
        } finally {
            adapter.closeResources();
        }
    }

    private static RecordingCollector collector() {
        RecordingCollector collector = new RecordingCollector();
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("device-1");
        device.setDeviceName("MQTT 测试设备");
        collector.init(device);
        ReflectionTestUtils.setField(collector, "mqttConnection", mock(MqttConnectionAdapter.class));
        return collector;
    }

    private static DataPoint point(String id, String topic, Map<String, Object> options) {
        DataPoint point = new DataPoint();
        point.setPointId(id);
        point.setPointName(id);
        point.setAddress(topic);
        point.setCollectionMode("SUBSCRIBE");
        point.setAdditionalConfig(options);
        return point;
    }

    private static void inbound(RecordingCollector collector, String topic, String payload) {
        ReflectionTestUtils.invokeMethod(collector, "handleInboundMessage",
                new MqttReceivedMessage(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false, Map.of()));
    }

    private static Map<String, Integer> wireSubscriptions(MqttConnectionAdapter adapter) throws Exception {
        Map<String, Integer> topics = new HashMap<>();
        when(adapter.isSubscribed(anyString())).thenAnswer(call -> topics.containsKey(call.getArgument(0)));
        when(adapter.getSubscribedQos(anyString())).thenAnswer(call -> topics.getOrDefault(call.getArgument(0), -1));
        doAnswer(call -> {
            topics.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(adapter).subscribe(anyString(), anyInt());
        doAnswer(call -> {
            topics.remove(call.getArgument(0));
            return null;
        }).when(adapter).unsubscribe(anyString());
        return topics;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> refCounts(MqttCollector collector) {
        return (Map<String, Integer>) ReflectionTestUtils.getField(collector, "topicRefCount");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> bindings(MqttCollector collector) {
        return (Map<String, Set<String>>) ReflectionTestUtils.getField(collector, "topicBindings");
    }

    private static class RecordingCollector extends MqttCollector {
        protected final List<String> ingestedIds = new ArrayList<>();

        @Override
        protected ProcessResult ingestPushedValue(DataPoint point, Object rawValue) {
            ingestedIds.add(point.getPointId());
            return null;
        }
    }

    private static final class ConfiguredCollector extends RecordingCollector {
        private final DeviceConnection connection = new DeviceConnection();

        private ConfiguredCollector(Object topics) {
            connection.setExtJson(Map.of("subscribeTopics", topics));
        }

        @Override
        protected DeviceConnection getCurrentConnectionConfig() {
            return connection;
        }
    }

    private static final class ColdStartCollector extends RecordingCollector {
        private final MqttConnectionAdapter adapter;
        private final AtomicReference<Consumer<MqttReceivedMessage>> listener;
        private boolean listenerPresentAtConnect;
        private List<DataPoint> pointsAtConnect;

        private ColdStartCollector(MqttConnectionAdapter adapter,
                                   AtomicReference<Consumer<MqttReceivedMessage>> listener) {
            this.adapter = adapter;
            this.listener = listener;
        }

        @Override
        protected ConnectionAdapter<?> createManagedConnection() {
            return adapter;
        }

        @Override
        protected void connectManagedConnection() {
            listenerPresentAtConnect = listener.get() != null;
            rebuildReadPlans("device-1", pointsAtConnect);
        }
    }
}
