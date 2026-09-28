package com.wangbin.collector.core.collector.protocol.ethernetip;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.protocol.ethernetip.domain.EtherNetIpTagAddress;
import com.wangbin.collector.core.collector.protocol.ethernetip.util.EtherNetIpAddressParser;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import com.wangbin.collector.core.connection.adapter.EtherNetIpConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessResult;
import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class EtherNetIpCollectorTest {

    @Test
    void batchWriteFutureFailureNeverRetriesIndividualWrites() throws Exception {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        PlcConnection client = mock(PlcConnection.class);
        PlcWriteRequest.Builder builder = mock(PlcWriteRequest.Builder.class);
        PlcWriteRequest request = mock(PlcWriteRequest.class);
        prepareCommandCollector(collector, mock(ConfigManager.class));
        when(((EtherNetIpConnectionAdapter) ReflectionTestUtils.getField(collector, "connectionAdapter")).getClient()).thenReturn(client);
        when(client.writeRequestBuilder()).thenReturn(builder);
        when(builder.addTagAddress(any(), any(), any())).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        CompletableFuture<PlcWriteResponse> failure = new CompletableFuture<>();
        failure.completeExceptionally(new IllegalStateException("transport failed"));
        org.mockito.Mockito.doReturn(failure).when(request).execute();
        assertEquals(Map.of("p1", false), collector.writePoints(Map.of(point("p1", "tag", "Tag1", "RW"), 1)));
        verify(request, times(1)).execute();
        assertTrue(ReflectionTestUtils.getField(collector, "connectionAdapter") != null);
    }

    @Test
    void transportFailureInvalidatesSessionWithoutReplayingBatchWrite() throws Exception {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        PlcConnection client = mock(PlcConnection.class);
        PlcWriteRequest.Builder builder = mock(PlcWriteRequest.Builder.class);
        PlcWriteRequest request = mock(PlcWriteRequest.class);
        prepareCommandCollector(collector, mock(ConfigManager.class));
        when(((EtherNetIpConnectionAdapter) ReflectionTestUtils.getField(collector, "connectionAdapter")).getClient()).thenReturn(client);
        when(client.writeRequestBuilder()).thenReturn(builder);
        when(builder.addTagAddress(any(), any(), any())).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        CompletableFuture<PlcWriteResponse> failure = new CompletableFuture<>();
        failure.completeExceptionally(new IOException("socket closed"));
        org.mockito.Mockito.doReturn(failure).when(request).execute();
        assertEquals(Map.of("p1", false), collector.writePoints(Map.of(point("p1", "tag", "Tag1", "RW"), 1)));
        verify(request, times(1)).execute();
        assertNull(ReflectionTestUtils.getField(collector, "connectionAdapter"));
        assertFalse(collector.isConnected());
    }

    @Test
    void batchReadFutureFailureIsFatalRatherThanNullResults() throws Exception {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        PlcConnection client = mock(PlcConnection.class);
        PlcReadRequest.Builder builder = mock(PlcReadRequest.Builder.class);
        PlcReadRequest request = mock(PlcReadRequest.class);
        prepareCommandCollector(collector, mock(ConfigManager.class));
        when(((EtherNetIpConnectionAdapter) ReflectionTestUtils.getField(collector, "connectionAdapter")).getClient()).thenReturn(client);
        when(client.readRequestBuilder()).thenReturn(builder);
        when(builder.addTagAddress(any(), any())).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        CompletableFuture<PlcReadResponse> failure = new CompletableFuture<>();
        failure.completeExceptionally(new IllegalStateException("transport failed"));
        org.mockito.Mockito.doReturn(failure).when(request).execute();
        assertThrows(CollectorException.class, () -> collector.readPoints(List.of(point("p1", "tag", "Tag1", "R"))));
        verify(request, times(1)).execute();
    }

    @Test
    void batchWritePreservesEachFieldResponse() throws Exception {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        PlcConnection client = mock(PlcConnection.class);
        PlcWriteRequest.Builder builder = mock(PlcWriteRequest.Builder.class);
        PlcWriteRequest request = mock(PlcWriteRequest.class);
        PlcWriteResponse response = mock(PlcWriteResponse.class);
        prepareCommandCollector(collector, mock(ConfigManager.class));
        when(((EtherNetIpConnectionAdapter) ReflectionTestUtils.getField(collector, "connectionAdapter")).getClient()).thenReturn(client);
        when(client.writeRequestBuilder()).thenReturn(builder);
        when(builder.addTagAddress(any(), any(), any())).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        org.mockito.Mockito.doReturn(CompletableFuture.completedFuture(response)).when(request).execute();
        when(response.getResponseCode("p1")).thenReturn(PlcResponseCode.OK);
        when(response.getResponseCode("p2")).thenReturn(PlcResponseCode.NOT_FOUND);
        Map<DataPoint, Object> values = new LinkedHashMap<>();
        values.put(point("p1", "tag1", "Tag1", "RW"), 1);
        values.put(point("p2", "tag2", "Tag2", "RW"), 2);
        assertEquals(Map.of("p1", true, "p2", false), collector.writePoints(values));
        verify(request, times(1)).execute();
    }

    @Test
    void invalidPointDoesNotBlockValidBatchReadAndAllInvalidStartupFails() throws Exception {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        PlcConnection client = mock(PlcConnection.class);
        PlcReadRequest.Builder builder = mock(PlcReadRequest.Builder.class);
        PlcReadRequest request = mock(PlcReadRequest.class);
        PlcReadResponse response = mock(PlcReadResponse.class);
        prepareCommandCollector(collector, mock(ConfigManager.class));
        when(((EtherNetIpConnectionAdapter) ReflectionTestUtils.getField(collector, "connectionAdapter")).getClient()).thenReturn(client);
        when(client.readRequestBuilder()).thenReturn(builder);
        when(builder.addTagAddress(any(), any())).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        org.mockito.Mockito.doReturn(CompletableFuture.completedFuture(response)).when(request).execute();
        when(response.getResponseCode("p1")).thenReturn(PlcResponseCode.OK);
        PlcValue value = mock(PlcValue.class);
        when(value.isInteger()).thenReturn(true);
        when(value.getInteger()).thenReturn(42);
        when(response.getPlcValue("p1")).thenReturn(value);
        DataPoint valid = point("p1", "tag", "Tag1", "R");
        DataPoint invalid = point("p2", "bad", " ", "R");
        collector.rebuildReadPlans("dev-1", List.of(invalid, valid));
        Map<String, Object> values = collector.readPoints(List.of(invalid, valid));
        assertNull(values.get("p2"));
        assertEquals(42.0, values.get("p1"));
        assertThrows(IllegalStateException.class, () -> collector.rebuildReadPlans("dev-1", List.of(invalid)));
    }

    @Test
    void timeoutCancelsPendingWriteWithoutRetry() throws Exception {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        PlcConnection client = mock(PlcConnection.class);
        PlcWriteRequest.Builder builder = mock(PlcWriteRequest.Builder.class);
        PlcWriteRequest request = mock(PlcWriteRequest.class);
        prepareCommandCollector(collector, mock(ConfigManager.class));
        ReflectionTestUtils.setField(collector, "timeout", 1);
        when(((EtherNetIpConnectionAdapter) ReflectionTestUtils.getField(collector, "connectionAdapter")).getClient()).thenReturn(client);
        when(client.writeRequestBuilder()).thenReturn(builder);
        when(builder.addTagAddress(any(), any(), any())).thenReturn(builder);
        when(builder.build()).thenReturn(request);
        CompletableFuture<PlcWriteResponse> pending = new CompletableFuture<>();
        org.mockito.Mockito.doReturn(pending).when(request).execute();
        assertEquals(Map.of("p1", false), collector.writePoints(Map.of(point("p1", "tag", "Tag1", "RW"), 1)));
        assertTrue(pending.isCancelled());
        verify(request, times(1)).execute();
    }

    @Test
    void exactArrayLengthIsRequiredForBothDirections() {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        DataPoint point = point("a", "array", "ArrayTag:DINT:3", "RW");
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point);
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(
                collector, "coerceWriteArrayValue", List.of(1, 2), address, point));
        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue value = mock(PlcValue.class);
        when(response.getPlcValue("a")).thenReturn(value);
        when(value.isList()).thenReturn(true);
        when(value.getLength()).thenReturn(2);
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                collector, "extractValue", response, "a", point, address));
    }

    @Test
    void statusDoesNotExposeRawConnectionString() throws Exception {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        prepareCommandCollector(collector, mock(ConfigManager.class));
        assertFalse(collector.getDeviceStatus().containsKey("connectionString"));
    }

    @Test
    void shouldRouteReadAndWriteCommandsThroughConfiguredPoints() throws Exception {
        ConfigManager configManager = mock(ConfigManager.class);
        DataPoint point = point("p1", "temperature", "MainProgram.Tag1", "RW");
        point.setAdditionalConfig(Map.of("reportField", "temp_report"));
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));

        TestableEtherNetIpCollector collector = new TestableEtherNetIpCollector();
        collector.readValue = 18;
        prepareCommandCollector(collector, configManager);

        @SuppressWarnings("unchecked")
        Map<String, Object> readResult = (Map<String, Object>) collector.executeCommand(
                "read", Map.of("pointRef", "temp_report"));
        assertEquals("p1", readResult.get("pointId"));
        assertEquals(18.0, readResult.get("value"));

        @SuppressWarnings("unchecked")
        Map<String, Object> writeResult = (Map<String, Object>) collector.executeCommand(
                "write", Map.of("pointCode", "temperature", "value", 30));
        assertEquals(true, writeResult.get("success"));
        assertEquals(30.0, collector.lastWriteValue);
    }

    @Test
    void shouldKeepSubscriptionUnsupportedForCurrentPlc4xLogixPath() throws Exception {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        prepareCommandCollector(collector, mock(ConfigManager.class));

        CollectorException exception = assertThrows(CollectorException.class,
                () -> collector.subscribe(List.of(point("p1", "temperature", "MainProgram.Tag1", "R"))));
        assertFalse(exception.getMessage().isBlank());

        Map<String, Object> status = collector.getDeviceStatus();
        assertFalse((Boolean) status.get("subscribable"));
    }

    @Test
    void shouldPassThroughArrayReadsAndWritesWithoutScalarProcessing() throws Exception {
        ConfigManager configManager = mock(ConfigManager.class);
        DataPoint point = point("p2", "temperatures", "Program.Main.ArrayTag:DINT:3", "RW");
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));

        TestableEtherNetIpCollector collector = new TestableEtherNetIpCollector();
        collector.readValue = List.of(11, 12, 13);
        prepareCommandCollector(collector, configManager);

        Object readResult = collector.readPoint(point);
        assertEquals(List.of(11, 12, 13), readResult);

        ProcessResult processResult = collector.getLatestProcessResult("p2");
        assertTrue(processResult.isSuccess());
        assertEquals(true, processResult.getMetadata("arrayValue"));
        assertEquals(Integer.valueOf(3), processResult.<Integer>getMetadata("arraySize"));

        assertTrue(collector.writePoint(point, List.of(21, 22, 23)));
        assertEquals(List.of(21, 22, 23), collector.lastWriteValue);
    }

    @Test
    void shouldRejectArrayPointsWithScalarTransformSettings() throws Exception {
        ConfigManager configManager = mock(ConfigManager.class);
        DataPoint point = point("p3", "temperatures", "Program.Main.ArrayTag:DINT:3", "R");
        point.setScalingFactor(0.1d);
        when(configManager.getDataPoints("dev-1")).thenReturn(List.of(point));

        TestableEtherNetIpCollector collector = new TestableEtherNetIpCollector();
        collector.readValue = List.of(1, 2, 3);
        prepareCommandCollector(collector, configManager);

        CollectorException exception = assertThrows(CollectorException.class, () -> collector.readPoint(point));
        assertFalse(exception.getMessage().isBlank());
    }

    @Test
    void shouldPreferDriverDataTypeWhenCoercingScalarWriteValue() {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        DataPoint point = point("p4", "temperature", "MainProgram.Tag1", "RW");
        point.setDataType("DINT");
        point.setAdditionalConfig(Map.of("driverDataType", "REAL"));
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point);

        Object coerced = ReflectionTestUtils.invokeMethod(collector, "coerceWriteScalarValue", "12.5", address, point);

        assertTrue(coerced instanceof Float);
        assertEquals(12.5f, (Float) coerced, 0.0001f);
    }

    @Test
    void shouldUseResolvedDriverTypeWhenReadingScalarValue() {
        EtherNetIpCollector collector = new EtherNetIpCollector();
        DataPoint point = point("p4", "temperature", "MainProgram.Tag1", "R");
        point.setDataType("DINT");
        point.setAdditionalConfig(Map.of("driverDataType", "REAL"));
        EtherNetIpTagAddress address = EtherNetIpAddressParser.parse(point);

        PlcValue plcValue = mock(PlcValue.class);
        when(plcValue.isFloat()).thenReturn(true);
        when(plcValue.getFloat()).thenReturn(12.5f);

        Object value = ReflectionTestUtils.invokeMethod(collector, "coerceScalarValue", plcValue, ReflectionTestUtils.invokeMethod(collector, "resolvePointType", point, address));

        assertTrue(value instanceof Float);
        assertEquals(12.5f, (Float) value, 0.0001f);
    }

    private void prepareCommandCollector(EtherNetIpCollector collector, ConfigManager configManager) throws Exception {
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", com.wangbin.collector.core.processor.DataQualityProcessorTestSupport.create());
        ReflectionTestUtils.setField(collector, "configManager", configManager);
        ReflectionTestUtils.setField(collector, "devicePointResolver", new DevicePointResolver(configManager));

        EtherNetIpConnectionAdapter connectionAdapter = mock(EtherNetIpConnectionAdapter.class);
        when(connectionAdapter.isConnected()).thenReturn(true);
        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);
    }

    private DeviceInfo device() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-1");
        deviceInfo.setDeviceName("ethernet-ip-device");
        deviceInfo.setProtocolType("ETHERNET_IP");
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
        point.setDataType("DINT");
        point.setReadWrite(readWrite);
        point.setStatus(1);
        return point;
    }

    private static final class TestableEtherNetIpCollector extends EtherNetIpCollector {

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