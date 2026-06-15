package com.wangbin.collector.core.collector.protocol.ads;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.AdsConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessResult;
import org.apache.plc4x.java.api.PlcConnection;
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

class AdsCollectorTest {

    @Test
    void shouldRouteReadAndWriteCommandsThroughConfiguredPoints() throws Exception {
        ConfigManager configManager = mock(ConfigManager.class);
        DataPoint point = point("p1", "temperature", "MAIN.temperature", "RW");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));

        TestableAdsCollector collector = new TestableAdsCollector();
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
    void shouldRegisterAdsSubscriptionsAndProcessIncomingValues() throws Exception {
        AdsCollector collector = new AdsCollector();
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));

        AdsConnectionAdapter connectionAdapter = mock(AdsConnectionAdapter.class);
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
        when(response.getResponseCode("p1")).thenReturn(PlcResponseCode.OK);
        when(response.getSubscriptionHandle("p1")).thenReturn(handle);

        ArgumentCaptor<Consumer<PlcSubscriptionEvent>> eventCaptor = ArgumentCaptor.forClass(Consumer.class);
        when(builder.addCyclicTagAddress(eq("p1"), anyString(), any(Duration.class), eventCaptor.capture()))
                .thenReturn(builder);

        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);

        DataPoint point = point("p1", "temperature", "MAIN.temperature", "R");
        collector.subscribe(List.of(point));

        Map<String, Object> status = collector.getDeviceStatus();
        assertEquals(true, status.get("subscribable"));
        assertEquals(1, status.get("activeSubscriptions"));

        PlcSubscriptionEvent event = mock(PlcSubscriptionEvent.class);
        PlcValue plcValue = mock(PlcValue.class);
        when(event.getResponseCode("p1")).thenReturn(PlcResponseCode.OK);
        when(event.getPlcValue("p1")).thenReturn(plcValue);
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

    private void prepareCommandCollector(AdsCollector collector, ConfigManager configManager) throws Exception {
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "devicePointResolver", new DevicePointResolver(configManager));

        AdsConnectionAdapter connectionAdapter = mock(AdsConnectionAdapter.class);
        when(connectionAdapter.isConnected()).thenReturn(true);
        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);
    }

    private DeviceInfo device() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("ads-device");
        deviceInfo.setProtocolType("ADS");
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

    private static final class TestableAdsCollector extends AdsCollector {

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
