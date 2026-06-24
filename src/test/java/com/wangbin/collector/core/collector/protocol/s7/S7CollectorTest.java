package com.wangbin.collector.core.collector.protocol.s7;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;
import com.wangbin.collector.core.collector.protocol.s7.util.S7AddressParser;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.S7ConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessResult;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcSubscriptionEvent;
import org.apache.plc4x.java.api.messages.PlcSubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcSubscriptionResponse;
import org.apache.plc4x.java.api.metadata.PlcConnectionMetadata;
import org.apache.plc4x.java.api.model.PlcSubscriptionHandle;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S7CollectorTest {

    @Test
    void shouldRouteReadAndWriteCommandsThroughConfiguredPoints() throws Exception {
        ConfigManager configManager = mock(ConfigManager.class);
        DataPoint point = point("p1", "temperature", "DB1.DBW0", "RW");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));

        TestableS7Collector collector = new TestableS7Collector();
        collector.readValue = 12;
        prepareCommandCollector(collector, configManager);

        @SuppressWarnings("unchecked")
        Map<String, Object> readResult = (Map<String, Object>) collector.executeCommand(
                "read", Map.of("pointRef", "temperature"));
        assertEquals("p1", readResult.get("pointId"));
        assertEquals(12.0, readResult.get("value"));

        @SuppressWarnings("unchecked")
        Map<String, Object> writeResult = (Map<String, Object>) collector.executeCommand(
                "write", Map.of("pointCode", "temperature", "value", 25));
        assertEquals(true, writeResult.get("success"));
        assertEquals(25.0, collector.lastWriteValue);
    }

    @Test
    void shouldRegisterS7SubscriptionsAndProcessIncomingValues() throws Exception {
        S7Collector collector = new S7Collector();
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));

        DataPoint point = point("p1", "temperature", "DB1.DBW0", "R");
        String fieldName = ReflectionTestUtils.invokeMethod(collector, "tagName", point);

        S7ConnectionAdapter connectionAdapter = mock(S7ConnectionAdapter.class);
        PlcConnection connection = mock(PlcConnection.class);
        PlcConnectionMetadata metadata = mock(PlcConnectionMetadata.class);
        PlcSubscriptionRequest.Builder builder = mock(PlcSubscriptionRequest.Builder.class);
        PlcSubscriptionRequest request = mock(PlcSubscriptionRequest.class);
        PlcSubscriptionResponse response = mock(PlcSubscriptionResponse.class);
        PlcSubscriptionHandle handle = mock(PlcSubscriptionHandle.class);

        when(connectionAdapter.isConnected()).thenReturn(true);
        when(connectionAdapter.getClient()).thenReturn(connection);
        when(connection.getMetadata()).thenReturn(metadata);
        when(metadata.isSubscribeSupported()).thenReturn(true);
        when(connection.subscriptionRequestBuilder()).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        when(request.execute()).thenAnswer(invocation -> CompletableFuture.completedFuture(response));
        when(response.getResponseCode(fieldName)).thenReturn(PlcResponseCode.OK);
        when(response.getSubscriptionHandle(fieldName)).thenReturn(handle);

        ArgumentCaptor<Consumer<PlcSubscriptionEvent>> eventCaptor = ArgumentCaptor.forClass(Consumer.class);
        when(builder.addCyclicTagAddress(eq(fieldName), anyString(), any(Duration.class), eventCaptor.capture()))
                .thenReturn(builder);

        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);

        collector.subscribe(List.of(point));

        Map<String, Object> status = collector.getDeviceStatus();
        assertEquals(true, status.get("subscribable"));
        assertEquals(1, status.get("activeSubscriptions"));

        PlcSubscriptionEvent event = mock(PlcSubscriptionEvent.class);
        PlcValue plcValue = mock(PlcValue.class);
        when(event.getResponseCode(fieldName)).thenReturn(PlcResponseCode.OK);
        when(event.getPlcValue(fieldName)).thenReturn(plcValue);
        when(plcValue.isNull()).thenReturn(false);
        when(plcValue.isList()).thenReturn(false);
        when(plcValue.isInteger()).thenReturn(true);
        when(plcValue.getInteger()).thenReturn(42);

        eventCaptor.getValue().accept(event);

        ProcessResult processResult = collector.getLatestProcessResult("p1");
        assertNotNull(processResult);
        assertTrue(processResult.isSuccess());
        assertEquals(42.0, processResult.getFinalValue());
    }

    @Test
    void shouldPreferDriverDataTypeWhenCoercingWriteValue() {
        S7Collector collector = new S7Collector();
        DataPoint point = point("p1", "temperature", "DB1.DBD0", "RW");
        point.setDataType("INT");
        point.setAdditionalConfig(Map.of("driverDataType", "REAL"));
        S7Address address = S7AddressParser.parse(point);

        Object coerced = ReflectionTestUtils.invokeMethod(collector, "coerceWriteValue", "12.5", address, point);

        assertTrue(coerced instanceof Float);
        assertEquals(12.5f, (Float) coerced, 0.0001f);
    }

    @Test
    void shouldUseResolvedPlcTypeWhenExtractingValues() {
        S7Collector collector = new S7Collector();
        DataPoint point = point("p1", "temperature", "DB1.DBD0", "R");
        point.setDataType("INT");
        point.setAdditionalConfig(Map.of("driverDataType", "REAL"));
        S7Address address = S7AddressParser.parse(point);

        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue plcValue = mock(PlcValue.class);
        when(response.getPlcValue("p1")).thenReturn(plcValue);
        when(plcValue.isNull()).thenReturn(false);
        when(plcValue.isList()).thenReturn(false);
        when(plcValue.isFloat()).thenReturn(true);
        when(plcValue.getFloat()).thenReturn(12.5f);

        Object value = ReflectionTestUtils.invokeMethod(collector, "extractValue", response, "p1", point, address);

        assertTrue(value instanceof Float);
        assertEquals(12.5f, (Float) value, 0.0001f);
    }

    private void prepareCommandCollector(TestableS7Collector collector, ConfigManager configManager) throws Exception {
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "devicePointResolver", new DevicePointResolver(configManager));

        S7ConnectionAdapter connectionAdapter = mock(S7ConnectionAdapter.class);
        when(connectionAdapter.isConnected()).thenReturn(true);
        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);
    }

    private DeviceInfo device() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("s7-device");
        deviceInfo.setProtocolType("SIEMENS_S7");
        deviceInfo.setCollectionInterval(2000);
        return deviceInfo;
    }

    private DataPoint point(String pointId, String pointCode, String address, String readWrite) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointCode);
        point.setDeviceId("dev-1");
        point.setAddress(address);
        point.setDataType("INT");
        point.setReadWrite(readWrite);
        point.setStatus(1);
        return point;
    }

    private static final class TestableS7Collector extends S7Collector {

        private Object readValue;
        private Object lastWriteValue;

        @Override
        protected Object doReadPoint(DataPoint point) {
            return readValue;
        }

        @Override
        protected boolean doWritePoint(DataPoint point, Object value) {
            lastWriteValue = value;
            return true;
        }
    }
}