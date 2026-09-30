package com.wangbin.collector.core.collector.protocol.s7;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.s7.domain.S7Address;
import com.wangbin.collector.core.collector.protocol.s7.plan.S7ReadPlanBuilder;
import com.wangbin.collector.core.collector.protocol.s7.plan.S7ReadPlanItem;
import com.wangbin.collector.core.collector.protocol.s7.util.S7AddressParser;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.manager.ConfigSyncService;
import com.wangbin.collector.core.config.validator.S7ProtocolPointValidator;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.S7ConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessResult;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.messages.PlcSubscriptionEvent;
import org.apache.plc4x.java.api.messages.PlcSubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcSubscriptionResponse;
import org.apache.plc4x.java.api.messages.PlcUnsubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcUnsubscriptionResponse;
import org.apache.plc4x.java.api.metadata.PlcConnectionMetadata;
import org.apache.plc4x.java.api.model.PlcSubscriptionHandle;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void shouldExposeStructuredDiagnosticsAndConnectionInfo() throws Exception {
        ConfigManager configManager = mock(ConfigManager.class);
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setPort(102);
        connection.setExtJson(Map.of(
                "controllerType", "S7_1500",
                "subscriptionEnabled", true,
                "maxFieldsPerRequest", 32
        ));
        List<DataPoint> points = List.of(
                point("p1", "temperature", "DB1.DBW0", "R"),
                point("p2", "tempArray", "DB1:0:INT[4]", "R")
        );
        when(configManager.getDataPoints("dev-1")).thenReturn(points);
        when(configManager.getDeviceContext("dev-1")).thenReturn(DeviceContext.of(device(), connection, points));

        S7ConnectionAdapter connectionAdapter = mock(S7ConnectionAdapter.class);
        PlcConnection client = mock(PlcConnection.class);
        PlcConnectionMetadata metadata = mock(PlcConnectionMetadata.class);
        when(connectionAdapter.isConnected()).thenReturn(true);
        when(connectionAdapter.getClient()).thenReturn(client);
        when(connectionAdapter.getConnectionString()).thenReturn("s7://127.0.0.1:102?controller-type=S7_1500");
        when(client.getMetadata()).thenReturn(metadata);
        when(metadata.isSubscribeSupported()).thenReturn(true);

        S7Collector collector = new S7Collector();
        collector.init(device());
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);
        ReflectionTestUtils.setField(collector, "subscriptionSupported", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnostic = (Map<String, Object>) collector.executeCommand("diagnostic", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) diagnostic.get("summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> connectionInfo = (Map<String, Object>) diagnostic.get("connection");
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) diagnostic.get("capabilities");

        assertEquals("ABSOLUTE_ONLY", summary.get("addressingMode"));
        assertEquals(1, summary.get("arrayConfiguredPoints"));
        assertEquals("S7_1500", connectionInfo.get("controllerType"));
        assertEquals("GENERATED", connectionInfo.get("connectionSource"));
        assertEquals(false, connectionInfo.containsKey("connectionString"));
        assertEquals(false, connectionInfo.containsKey("plc4xConnectionString"));
        assertEquals(true, capabilities.get("subscribe"));
        assertEquals(true, capabilities.get("arrayReadWrite"));
        assertTrue(((List<?>) capabilities.get("subscriptionModes")).contains("MODE"));
        assertTrue(((List<?>) diagnostic.get("deploymentChecks")).size() >= 3);
        assertTrue(((List<?>) diagnostic.get("recommendedActions")).stream()
                .anyMatch(item -> item.toString().contains("optimized block access")));

        @SuppressWarnings("unchecked")
        Map<String, Object> directConnectionInfo = (Map<String, Object>) collector.executeCommand("connection_info", Map.of());
        assertEquals("S7_1500", directConnectionInfo.get("controllerType"));
    }

    @Test
    void shouldRegisterS7SubscriptionsAndProcessIncomingValues() throws Exception {
        S7Collector collector = new S7Collector();
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());

        DataPoint point = point("p1", "temperature", "DB1.DBW0", "R");
        point.setCollectionMode("SUBSCRIPTION");
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
        ReflectionTestUtils.setField(collector, "subscriptionSupported", true);

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

        @SuppressWarnings("unchecked")
        Map<String, Long> responseCodeStats = (Map<String, Long>) collector.getStatistics().get("responseCodeStats");
        assertEquals(1L, responseCodeStats.get("subscribe.OK"));
        assertEquals(1L, responseCodeStats.get("subscription-event.OK"));

        S7ConnectionAdapter replacement = mock(S7ConnectionAdapter.class);
        when(replacement.isConnected()).thenReturn(true);
        ReflectionTestUtils.setField(collector, "connectionAdapter", replacement);
        eventCaptor.getValue().accept(event);
        assertEquals(1L, collector.getStatistics().get("subscriptionEventCount"));
        assertEquals(42.0, collector.getLatestProcessResult("p1").getFinalValue());
    }

    @Test
    void shouldAutoSubscribeEventPointsAndServeLatestEventPayloadFromCache() throws Exception {
        S7Collector collector = new S7Collector();
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());

        DataPoint point = point("p1", "modeEvent", "MODE", "R");
        point.setCollectionMode("EVENT");
        point.setAdditionalConfig(Map.of("subscriptionMode", "MODE"));
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
        when(builder.addEventTagAddress(eq(fieldName), eq("MODE"), eventCaptor.capture())).thenReturn(builder);

        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);
        ReflectionTestUtils.setField(collector, "subscriptionSupported", true);

        collector.rebuildReadPlans("dev-1", List.of(point));

        PlcSubscriptionEvent event = mock(PlcSubscriptionEvent.class);
        PlcValue plcValue = mock(PlcValue.class);
        when(event.getResponseCode(fieldName)).thenReturn(PlcResponseCode.OK);
        when(event.getPlcValue(fieldName)).thenReturn(plcValue);
        when(plcValue.isNull()).thenReturn(false);
        when(plcValue.getObject()).thenReturn(Map.of("state", "RUN", "code", 7));

        eventCaptor.getValue().accept(event);

        ProcessResult processResult = collector.getLatestProcessResult("p1");
        assertNotNull(processResult);
        assertTrue(processResult.isSuccess());
        assertEquals(true, processResult.getMetadata("eventTriggered", false));
        assertEquals("S7_MODE", processResult.getMetadata("eventType"));

        @SuppressWarnings("unchecked")
        Map<String, Object> latestValue = (Map<String, Object>) collector.readPoint(point);
        assertEquals("MODE", latestValue.get("subscriptionMode"));
        assertEquals("RUN", latestValue.get("state"));
    }


    @Test
    void shouldRejectInvalidNewS7PointsWithoutChangingConfigVersion() {
        ConfigManager manager = new ConfigManager(mock(ConfigSyncService.class),
                mock(ApplicationEventPublisher.class), null, null);
        ReflectionTestUtils.setField(manager, "pointValidators", List.of(new S7ProtocolPointValidator()));
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("s7-validator");
        device.setProtocolType("SIEMENS_S7");
        assertTrue(manager.updateDeviceConfig(device));
        long version = manager.getDeviceConfigVersion(device.getDeviceId());

        DataPoint invalid = point("bad", "bad", "not-s7", "R");
        assertFalse(manager.updateDataPoints(device.getDeviceId(), List.of(invalid)));
        assertEquals(version, manager.getDeviceConfigVersion(device.getDeviceId()));
        assertTrue(manager.getDataPoints(device.getDeviceId()).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new S7ProtocolPointValidator().validate(List.of(invalid)));

        DataPoint event = point("event", "event", "MODE", "R");
        event.setCollectionMode("EVENT");
        new S7ProtocolPointValidator().validate(List.of(event));

        DataPoint invalidEvent = point("invalid-event", "invalid-event", "not-s7", "R");
        invalidEvent.setCollectionMode("EVENT");
        assertThrows(IllegalArgumentException.class,
                () -> new S7ProtocolPointValidator().validate(List.of(invalidEvent)));
        invalidEvent.setAdditionalConfig(Map.of("subscriptionMode", "OTHER"));
        assertThrows(IllegalArgumentException.class,
                () -> new S7ProtocolPointValidator().validate(List.of(invalidEvent)));
        invalidEvent.setAdditionalConfig(Map.of());
        invalidEvent.setAddress(null);
        assertThrows(IllegalArgumentException.class,
                () -> new S7ProtocolPointValidator().validate(List.of(invalidEvent)));
    }

    @Test
    void shouldCommitOnlyCompleteS7SubscriptionBatch() throws Exception {
        SubscriptionFixture fixture = subscriptionFixture();
        DataPoint first = cyclicPoint("p1");
        DataPoint second = cyclicPoint("p2");
        when(fixture.response().getResponseCode("p1")).thenReturn(PlcResponseCode.OK);
        when(fixture.response().getResponseCode("p2")).thenReturn(PlcResponseCode.OK);
        when(fixture.response().getSubscriptionHandle("p1")).thenReturn(mock(PlcSubscriptionHandle.class));
        when(fixture.response().getSubscriptionHandle("p2")).thenReturn(mock(PlcSubscriptionHandle.class));

        fixture.collector().subscribe(List.of(first, second));

        assertEquals(2, fixture.collector().getDeviceStatus().get("activeSubscriptions"));
        assertEquals(2, fixture.collector().getDeviceStatus().get("configuredSubscriptionPoints"));
        assertEquals(2, ((Map<?, ?>) ReflectionTestUtils.getField(fixture.collector(), "subscribedPointMap")).size());
    }

    @Test
    void shouldRollbackAllNewS7HandlesOnPartialSubscriptionFailure() throws Exception {
        SubscriptionFixture fixture = subscriptionFixture();
        PlcSubscriptionHandle firstHandle = mock(PlcSubscriptionHandle.class);
        PlcSubscriptionHandle thirdHandle = mock(PlcSubscriptionHandle.class);
        when(fixture.response().getResponseCode("p1")).thenReturn(PlcResponseCode.OK);
        when(fixture.response().getResponseCode("p2")).thenReturn(PlcResponseCode.ACCESS_DENIED);
        when(fixture.response().getResponseCode("p3")).thenReturn(PlcResponseCode.OK);
        when(fixture.response().getSubscriptionHandle("p1")).thenReturn(firstHandle);
        when(fixture.response().getSubscriptionHandle("p3")).thenReturn(thirdHandle);

        assertThrows(Exception.class, () -> fixture.collector().subscribe(List.of(
                cyclicPoint("p1"), cyclicPoint("p2"), cyclicPoint("p3"))));

        verify(fixture.unsubscriptionRequest()).execute();
        org.mockito.ArgumentCaptor<java.util.Collection<PlcSubscriptionHandle>> handles =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(fixture.unsubscriptionBuilder()).addHandles(handles.capture());
        assertEquals(List.of(firstHandle, thirdHandle), List.copyOf(handles.getValue()));
        assertEquals(0, fixture.collector().getDeviceStatus().get("activeSubscriptions"));
        assertEquals(0, fixture.collector().getDeviceStatus().get("configuredSubscriptionPoints"));
        assertEquals(1L, fixture.collector().getStatistics().get("subscriptionRegisterFailureCount"));
        assertEquals(0, ((Map<?, ?>) ReflectionTestUtils.getField(fixture.collector(), "subscribedPointMap")).size());
    }

    @Test
    void shouldRejectNullS7SubscriptionHandleAndRollbackEarlierHandle() throws Exception {
        SubscriptionFixture fixture = subscriptionFixture();
        when(fixture.response().getResponseCode("p1")).thenReturn(PlcResponseCode.OK);
        when(fixture.response().getResponseCode("p2")).thenReturn(PlcResponseCode.OK);
        when(fixture.response().getSubscriptionHandle("p1")).thenReturn(mock(PlcSubscriptionHandle.class));

        assertThrows(Exception.class, () -> fixture.collector().subscribe(List.of(cyclicPoint("p1"), cyclicPoint("p2"))));

        verify(fixture.unsubscriptionRequest()).execute();
        assertEquals(0, fixture.collector().getDeviceStatus().get("activeSubscriptions"));
        assertEquals(0, fixture.collector().getDeviceStatus().get("configuredSubscriptionPoints"));
    }

    @Test
    void shouldKeepPrimaryS7SubscribeFailureWhenRollbackFails() throws Exception {
        SubscriptionFixture fixture = subscriptionFixture();
        when(fixture.response().getResponseCode("p1")).thenReturn(PlcResponseCode.OK);
        when(fixture.response().getResponseCode("p2")).thenReturn(PlcResponseCode.ACCESS_DENIED);
        when(fixture.response().getSubscriptionHandle("p1")).thenReturn(mock(PlcSubscriptionHandle.class));
        when(fixture.unsubscriptionRequest().execute()).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("rollback unavailable")));

        Exception failure = assertThrows(Exception.class, () -> fixture.collector().doSubscribe(
                List.of(cyclicPoint("p1"), cyclicPoint("p2"))));

        assertTrue(failure.getMessage().contains("p2"));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(0, fixture.collector().getDeviceStatus().get("activeSubscriptions"));
        assertEquals(0, fixture.collector().getDeviceStatus().get("configuredSubscriptionPoints"));
    }

    @Test
    void shouldReplaceS7SubscriptionWithoutDuplicateHandles() throws Exception {
        SubscriptionFixture fixture = subscriptionFixture();
        PlcSubscriptionHandle original = mock(PlcSubscriptionHandle.class);
        PlcSubscriptionHandle replacement = mock(PlcSubscriptionHandle.class);
        when(fixture.response().getResponseCode("p1")).thenReturn(PlcResponseCode.OK);
        when(fixture.response().getSubscriptionHandle("p1")).thenReturn(original, replacement);
        DataPoint point = cyclicPoint("p1");

        fixture.collector().subscribe(List.of(point));
        fixture.collector().subscribe(List.of(point));

        verify(fixture.request(), times(2)).execute();
        verify(fixture.unsubscriptionRequest()).execute();
        assertEquals(1, fixture.collector().getDeviceStatus().get("activeSubscriptions"));
        assertEquals(1, ((Map<?, ?>) ReflectionTestUtils.getField(fixture.collector(), "subscribedPointMap")).size());
    }

    @Test
    void shouldIsolateInvalidS7PollingPointDuringStartupPlanRebuild() throws Exception {
        PlannedReadS7Collector collector = new PlannedReadS7Collector();
        preparePlannedReadCollector(collector);
        DataPoint first = point("p1", "first", "DB1.DBW0", "R");
        DataPoint second = point("p2", "second", "DB2.DBW0", "R");
        DataPoint third = point("p3", "third", "DB3.DBW0", "R");
        DataPoint invalid = point("p4", "invalid", "DB1.BAD0", "R");
        List<DataPoint> points = List.of(first, second, third, invalid);
        ConfigManager config = mock(ConfigManager.class);
        when(config.getDataPoints("dev-1")).thenReturn(points);
        ReflectionTestUtils.setField(collector, "configManager", config);
        collector.tagBatchValues.put("p1", 11);
        collector.tagBatchValues.put("p2", 22);
        collector.tagBatchValues.put("p3", 33);

        collector.rebuildReadPlans("dev-1", points);
        Map<String, Object> values = collector.readPoints(points);

        assertEquals(11.0, values.get("p1"));
        assertEquals(22.0, values.get("p2"));
        assertEquals(33.0, values.get("p3"));
        assertEquals(null, values.get("p4"));
        assertEquals(3, collector.tagBatchInvocationCount);
        assertEquals(3, collector.getDeviceStatus().get("plannedReadBatchCount"));
        assertEquals(1, collector.getDeviceStatus().get("invalidConfiguredPoints"));
        assertEquals(false, collector.getDeviceStatus().get("deploymentReady"));
        assertEquals("p4", collector.getDeviceStatus().get("lastFailedPointId"));
    }

    @Test
    void shouldRejectStartupPlanWhenAllS7PollingPointsAreInvalid() throws Exception {
        S7Collector collector = new S7Collector();
        collector.init(device());
        List<DataPoint> points = List.of(point("p1", "bad", "DB1.BAD0", "R"));
        ConfigManager config = mock(ConfigManager.class);
        when(config.getDataPoints("dev-1")).thenReturn(points);
        ReflectionTestUtils.setField(collector, "configManager", config);

        assertThrows(IllegalStateException.class, () -> collector.rebuildReadPlans("dev-1", points));
        assertEquals(0, collector.getDeviceStatus().get("plannedReadBatchCount"));
        assertEquals(1, collector.getDeviceStatus().get("invalidConfiguredPoints"));
        assertEquals(false, collector.getDeviceStatus().get("deploymentReady"));
    }

    @Test
    void shouldReadArrayPointAsPassThroughList() throws Exception {
        TestableS7Collector collector = new TestableS7Collector();
        collector.readValue = List.of(11, 12, 13);
        prepareArrayCollector(collector);

        DataPoint point = point("p1", "tempArray", "DB1.DBW0", "R");
        point.setAdditionalConfig(Map.of("arraySize", 3));

        Object value = collector.readPoint(point);

        assertTrue(value instanceof List<?>);
        assertEquals(List.of(11, 12, 13), value);
        ProcessResult processResult = collector.getLatestProcessResult("p1");
        assertNotNull(processResult);
        assertEquals(true, processResult.getMetadata("arrayValue"));
        assertEquals(Integer.valueOf(3), processResult.getMetadata("arraySize"));
    }

    @Test
    void shouldBatchReadArrayPointsAsPassThroughLists() throws Exception {
        TestableS7Collector collector = new TestableS7Collector();
        collector.batchReadValues = Map.of(
                "p1", List.of(1, 2),
                "p2", List.of(3, 4, 5)
        );
        prepareArrayCollector(collector);

        DataPoint first = point("p1", "arr1", "DB1.DBW0", "R");
        first.setAdditionalConfig(Map.of("arraySize", 2));
        DataPoint second = point("p2", "arr2", "DB1:10:INT[3]", "R");

        Map<String, Object> values = collector.readPoints(List.of(first, second));

        assertEquals(List.of(1, 2), values.get("p1"));
        assertEquals(List.of(3, 4, 5), values.get("p2"));
        assertEquals(Integer.valueOf(2), collector.getLatestProcessResult("p1").getMetadata("arraySize"));
        assertEquals(Integer.valueOf(3), collector.getLatestProcessResult("p2").getMetadata("arraySize"));
    }

    @Test
    void shouldCoerceArrayWriteValuesThroughS7Codec() throws Exception {
        TestableS7Collector collector = new TestableS7Collector();
        prepareArrayCollector(collector);

        DataPoint point = point("p1", "tempArray", "DB1.DBW0", "RW");
        point.setAdditionalConfig(Map.of("arraySize", 3));

        boolean success = collector.writePoint(point, List.of("1", "2", "3"));

        assertEquals(true, success);
        assertEquals(List.of(1, 2, 3), collector.lastWriteValue);
    }

    @Test
    void shouldRejectUnsupportedScalingForArrayPoint() throws Exception {
        TestableS7Collector collector = new TestableS7Collector();
        collector.readValue = List.of(1, 2);
        prepareArrayCollector(collector);

        DataPoint point = point("p1", "tempArray", "DB1.DBW0", "R");
        point.setAdditionalConfig(Map.of("arraySize", 2));
        point.setScalingFactor(2.0d);

        Exception exception = assertThrows(Exception.class, () -> collector.readPoint(point));

        assertTrue(exception.getCause() != null);
        assertTrue(exception.getCause().getMessage().contains("scalingFactor"));
    }

    @Test
    void shouldUsePointIdAsTagNameWithoutSanitizing() {
        S7Collector collector = new S7Collector();
        DataPoint point = point("point-01.test", "temperature", "DB1.DBW0", "R");

        String fieldName = ReflectionTestUtils.invokeMethod(collector, "tagName", point);

        assertEquals("point-01.test", fieldName);
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

    @Test
    void shouldDecodeFourByteIeee754RealThroughPlc4xDataItem() {
        S7Collector collector = new S7Collector();
        DataPoint first = point("p1", "realFirst", "DB1.DBD4", "R");
        DataPoint second = point("p2", "realSecond", "DB1.DBD8", "R");
        first.setDataType("FLOAT32");
        second.setDataType("FLOAT32");
        S7ReadPlanItem item = new S7ReadPlanBuilder().build(List.of(first, second), 64)
                .get(0).getItems().get(0);

        assertEquals("%DB1:4:REAL", item.getAddress().getPlc4xAddress());
        PlcValue value = ReflectionTestUtils.invokeMethod(collector, "parseBlockReadPlcValue",
                item, new byte[]{0x41, 0x48, 0x00, 0x00});
        assertNotNull(value);
        assertTrue(value.isFloat());
        assertEquals(12.5f, value.getFloat(), 0.0001f);
    }

    @Test
    void shouldUseBlockReadForContiguousNumericPlans() throws Exception {
        PlannedReadS7Collector collector = new PlannedReadS7Collector();
        collector.blockReadBytes = new byte[]{0, 10, 0, 20};
        preparePlannedReadCollector(collector);

        DataPoint first = point("p1", "temp1", "DB1.DBW0", "R");
        DataPoint second = point("p2", "temp2", "DB1.DBW2", "R");

        Map<String, Object> values = collector.readPoints(List.of(first, second));

        assertEquals(10.0, values.get("p1"));
        assertEquals(20.0, values.get("p2"));
        assertEquals("%DB1:0:BYTE[4]", collector.lastBlockReadAddress);
        assertEquals(1, collector.blockReadInvocationCount);
        assertEquals(0, collector.tagBatchInvocationCount);
    }

    @Test
    void shouldFallbackToTagBatchWhenBlockReadFails() throws Exception {
        PlannedReadS7Collector collector = new PlannedReadS7Collector();
        collector.failBlockRead = true;
        collector.tagBatchValues.put("p1", 11);
        collector.tagBatchValues.put("p2", 22);
        preparePlannedReadCollector(collector);

        DataPoint first = point("p1", "temp1", "DB1.DBW0", "R");
        DataPoint second = point("p2", "temp2", "DB1.DBW2", "R");

        Map<String, Object> values = collector.readPoints(List.of(first, second));

        assertEquals(11.0, values.get("p1"));
        assertEquals(22.0, values.get("p2"));
        assertEquals(1, collector.blockReadInvocationCount);
        assertEquals(1, collector.tagBatchInvocationCount);
    }

    @Test
    void shouldKeepHealthyFieldsWhenBatchHasPointLevelError() throws Exception {
        PlannedReadS7Collector collector = new PlannedReadS7Collector();
        preparePlannedReadCollector(collector);
        List<DataPoint> points = List.of(
                point("p1", "first", "DB1.DBW0", "R"),
                point("p2", "second", "DB2.DBW0", "R"),
                point("p3", "third", "DB3.DBW0", "R"));
        collector.tagBatchValues.put("p1", 11);
        collector.tagBatchValues.put("p3", 33);
        collector.tagBatchResponseCodes.put("p2", PlcResponseCode.NOT_FOUND);

        Map<String, Object> values = collector.readPoints(points);

        assertEquals(11.0, values.get("p1"));
        assertEquals(null, values.get("p2"));
        assertEquals(33.0, values.get("p3"));
        assertEquals(3, collector.tagBatchInvocationCount);
    }

    @Test
    void shouldNotMultiplyRequestsAfterBlockTimeout() throws Exception {
        PlannedReadS7Collector collector = new PlannedReadS7Collector();
        collector.timeoutBlockRead = true;
        preparePlannedReadCollector(collector);
        Map<String, Object> values = collector.readPoints(List.of(
                point("p1", "first", "DB1.DBW0", "R"),
                point("p2", "second", "DB1.DBW2", "R")));
        assertEquals(null, values.get("p1"));
        assertEquals(null, values.get("p2"));
        assertEquals(1, collector.blockReadInvocationCount);
        assertEquals(0, collector.tagBatchInvocationCount);
    }

    @Test
    void shouldMapBatchWriteResponsePerFieldAndNeverReplayAnUnconfirmedWrite() {
        S7Collector collector = new S7Collector();
        collector.init(device());
        S7ConnectionAdapter adapter = mock(S7ConnectionAdapter.class);
        PlcConnection client = mock(PlcConnection.class);
        PlcWriteRequest.Builder builder = mock(PlcWriteRequest.Builder.class);
        PlcWriteRequest request = mock(PlcWriteRequest.class);
        PlcWriteResponse response = mock(PlcWriteResponse.class);
        when(adapter.isConnected()).thenReturn(true);
        when(adapter.getClient()).thenReturn(client);
        when(client.writeRequestBuilder()).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        when(request.execute()).thenAnswer(invocation -> CompletableFuture.completedFuture(response))
                .thenAnswer(invocation -> CompletableFuture.failedFuture(
                        new java.util.concurrent.TimeoutException("unconfirmed")));
        when(response.getResponseCode("p1")).thenReturn(PlcResponseCode.OK);
        when(response.getResponseCode("p2")).thenReturn(PlcResponseCode.ACCESS_DENIED);
        ReflectionTestUtils.setField(collector, "connectionAdapter", adapter);
        Map<DataPoint, Object> points = new LinkedHashMap<>();
        points.put(point("p1", "first", "DB1.DBW0", "RW"), 1);
        points.put(point("p2", "second", "DB1.DBW2", "RW"), 2);

        Map<String, Boolean> first = ReflectionTestUtils.invokeMethod(collector, "doWritePoints", points);
        Map<String, Boolean> second = ReflectionTestUtils.invokeMethod(collector, "doWritePoints", points);

        assertEquals(Map.of("p1", true, "p2", false), first);
        assertEquals(Map.of("p1", false, "p2", false), second);
        verify(request, org.mockito.Mockito.times(2)).execute();
    }

    @Test
    void shouldInvalidateFatalTransportButKeepPointResponseErrorsConnected() throws Exception {
        S7Collector collector = new S7Collector();
        collector.init(device());
        S7ConnectionAdapter adapter = mock(S7ConnectionAdapter.class);
        PlcConnection client = mock(PlcConnection.class);
        PlcReadRequest.Builder builder = mock(PlcReadRequest.Builder.class);
        PlcReadRequest request = mock(PlcReadRequest.class);
        PlcReadResponse response = mock(PlcReadResponse.class);
        when(adapter.isConnected()).thenReturn(true);
        when(adapter.getClient()).thenReturn(client);
        when(client.readRequestBuilder()).thenReturn(builder);
        when(builder.addTagAddress(eq("p1"), anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        when(request.execute()).thenAnswer(invocation -> CompletableFuture.completedFuture(response))
                .thenAnswer(invocation -> CompletableFuture.failedFuture(new java.io.IOException("closed")));
        when(response.getResponseCode("p1")).thenReturn(PlcResponseCode.NOT_FOUND);
        ReflectionTestUtils.setField(collector, "connectionAdapter", adapter);
        DataPoint point = point("p1", "first", "DB1.DBW0", "R");

        assertThrows(Exception.class, () -> collector.readPoint(point));
        assertTrue(collector.isConnected());
        assertThrows(Exception.class, () -> collector.readPoint(point));
        assertFalse(collector.isConnected());
        verify(request, org.mockito.Mockito.times(2)).execute();
    }

    @Test
    void shouldInvalidateCurrentSessionAfterUnconfirmedReadTimeout() {
        S7Collector collector = new S7Collector();
        collector.init(device());
        S7ConnectionAdapter adapter = mock(S7ConnectionAdapter.class);
        when(adapter.isConnected()).thenReturn(true);
        ReflectionTestUtils.setField(collector, "connectionAdapter", adapter);
        ReflectionTestUtils.setField(collector, "timeout", 1);
        CompletableFuture<Object> future = new CompletableFuture<>();

        assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(collector, "await", future, adapter));
        assertTrue(future.isCancelled());
        assertFalse(collector.isConnected());
    }

    @Test
    void shouldNotInvalidateReplacementSessionForLateOldRequestFailure() {
        S7Collector collector = new S7Collector();
        collector.init(device());
        S7ConnectionAdapter oldSession = mock(S7ConnectionAdapter.class);
        S7ConnectionAdapter replacement = mock(S7ConnectionAdapter.class);
        when(replacement.isConnected()).thenReturn(true);
        ReflectionTestUtils.setField(collector, "connectionAdapter", replacement);

        CompletableFuture<Object> oldRequest = CompletableFuture.failedFuture(new java.io.IOException("old session closed"));
        assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(collector, "await", oldRequest, oldSession));
        assertTrue(collector.isConnected());
        assertEquals(replacement, ReflectionTestUtils.getField(collector, "connectionAdapter"));
    }

    private DataPoint cyclicPoint(String pointId) {
        DataPoint point = point(pointId, pointId, "DB1.DBW0", "R");
        point.setCollectionMode("SUBSCRIPTION");
        return point;
    }

    private SubscriptionFixture subscriptionFixture() {
        S7Collector collector = new S7Collector();
        collector.init(device());
        S7ConnectionAdapter adapter = mock(S7ConnectionAdapter.class);
        PlcConnection client = mock(PlcConnection.class);
        PlcConnectionMetadata metadata = mock(PlcConnectionMetadata.class);
        PlcSubscriptionRequest.Builder builder = mock(PlcSubscriptionRequest.Builder.class);
        PlcSubscriptionRequest request = mock(PlcSubscriptionRequest.class);
        PlcSubscriptionResponse response = mock(PlcSubscriptionResponse.class);
        PlcUnsubscriptionRequest.Builder unsubscriptionBuilder = mock(PlcUnsubscriptionRequest.Builder.class);
        PlcUnsubscriptionRequest unsubscriptionRequest = mock(PlcUnsubscriptionRequest.class);
        when(adapter.isConnected()).thenReturn(true);
        when(adapter.getClient()).thenReturn(client);
        when(client.getMetadata()).thenReturn(metadata);
        when(metadata.isSubscribeSupported()).thenReturn(true);
        when(client.subscriptionRequestBuilder()).thenReturn(builder);
        when(builder.addCyclicTagAddress(anyString(), anyString(), any(Duration.class), any())).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        when(request.execute()).thenAnswer(invocation -> CompletableFuture.completedFuture(response));
        when(client.unsubscriptionRequestBuilder()).thenReturn(unsubscriptionBuilder);
        when(unsubscriptionBuilder.build()).thenReturn(unsubscriptionRequest);
        PlcUnsubscriptionResponse unsubscriptionResponse = mock(PlcUnsubscriptionResponse.class);
        when(unsubscriptionRequest.execute()).thenAnswer(
                invocation -> CompletableFuture.completedFuture(unsubscriptionResponse));
        ReflectionTestUtils.setField(collector, "connectionAdapter", adapter);
        ReflectionTestUtils.setField(collector, "subscriptionSupported", true);
        return new SubscriptionFixture(collector, request, response, unsubscriptionBuilder, unsubscriptionRequest);
    }

    private record SubscriptionFixture(S7Collector collector,
                                       PlcSubscriptionRequest request,
                                       PlcSubscriptionResponse response,
                                       PlcUnsubscriptionRequest.Builder unsubscriptionBuilder,
                                       PlcUnsubscriptionRequest unsubscriptionRequest) {
    }

    private void prepareCommandCollector(TestableS7Collector collector, ConfigManager configManager) throws Exception {
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "devicePointResolver", new DevicePointResolver(configManager));

        S7ConnectionAdapter connectionAdapter = mock(S7ConnectionAdapter.class);
        when(connectionAdapter.isConnected()).thenReturn(true);
        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);
    }


    private void prepareArrayCollector(TestableS7Collector collector) throws Exception {
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());

        S7ConnectionAdapter connectionAdapter = mock(S7ConnectionAdapter.class);
        when(connectionAdapter.isConnected()).thenReturn(true);
        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);
    }

    private void preparePlannedReadCollector(PlannedReadS7Collector collector) throws Exception {
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());

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
        private Map<String, Object> batchReadValues = Map.of();
        private Object lastWriteValue;

        @Override
        protected Object doReadPoint(DataPoint point) {
            return readValue;
        }

        @Override
        protected Map<String, Object> doReadPoints(List<DataPoint> points) {
            return batchReadValues;
        }

        @Override
        protected boolean doWritePoint(DataPoint point, Object value) {
            S7Address address = S7AddressParser.parse(point);
            if (address.isScalar()) {
                lastWriteValue = value;
            } else {
                lastWriteValue = ReflectionTestUtils.invokeMethod(this, "coerceWriteValue", value, address, point);
            }
            return true;
        }
    }

    private static final class PlannedReadS7Collector extends S7Collector {

        private byte[] blockReadBytes;
        private boolean failBlockRead;
        private final Map<String, Object> tagBatchValues = new LinkedHashMap<>();
        private final Map<String, PlcResponseCode> tagBatchResponseCodes = new LinkedHashMap<>();
        private boolean timeoutBlockRead;
        private int blockReadInvocationCount;
        private int tagBatchInvocationCount;
        private String lastBlockReadAddress;

        @Override
        protected byte[] executeBlockReadBytes(com.wangbin.collector.core.collector.protocol.s7.plan.S7ReadPlan readPlan) throws Exception {
            blockReadInvocationCount++;
            lastBlockReadAddress = readPlan != null ? readPlan.getBlockReadAddress() : null;
            if (timeoutBlockRead) {
                throw new java.util.concurrent.TimeoutException("simulated batch timeout");
            }
            if (failBlockRead) {
                throw new IllegalStateException("simulated block read failure");
            }
            return blockReadBytes;
        }

        @Override
        protected PlcReadResponse executeTagBatchReadPlanRequest(com.wangbin.collector.core.collector.protocol.s7.plan.S7ReadPlan readPlan) {
            tagBatchInvocationCount++;
            PlcReadResponse response = mock(PlcReadResponse.class);
            for (DataPoint point : readPlan.getPoints()) {
                String fieldName = point.getPointId();
                when(response.getResponseCode(fieldName))
                        .thenReturn(tagBatchResponseCodes.getOrDefault(fieldName, PlcResponseCode.OK));
                Object value = tagBatchValues.get(point.getPointId());
                PlcValue plcValue = mock(PlcValue.class);
                when(plcValue.isNull()).thenReturn(false);
                when(plcValue.isList()).thenReturn(false);
                if (value instanceof Number number) {
                    when(plcValue.isInteger()).thenReturn(true);
                    when(plcValue.getInteger()).thenReturn(number.intValue());
                } else {
                    when(plcValue.getObject()).thenReturn(value);
                }
                when(response.getPlcValue(fieldName)).thenReturn(plcValue);
            }
            return response;
        }
    }

}