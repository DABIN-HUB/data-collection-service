package com.wangbin.collector.core.collector.protocol.opc;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.domain.Plc4xOpcUaAddress;
import com.wangbin.collector.core.collector.protocol.opc.plc4x.util.Plc4xOpcUaAddressParser;
import com.wangbin.collector.core.connection.adapter.Plc4xOpcUaConnectionAdapter;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessResult;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.messages.PlcBrowseItem;
import org.apache.plc4x.java.api.messages.PlcBrowseRequest;
import org.apache.plc4x.java.api.messages.PlcBrowseResponse;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcSubscriptionEvent;
import org.apache.plc4x.java.api.messages.PlcSubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcSubscriptionResponse;
import org.apache.plc4x.java.api.messages.PlcUnsubscriptionRequest;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.metadata.PlcConnectionMetadata;
import org.apache.plc4x.java.api.model.PlcSubscriptionHandle;
import org.apache.plc4x.java.api.model.PlcTag;
import org.apache.plc4x.java.api.types.PlcResponseCode;
import org.apache.plc4x.java.api.value.PlcValue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Plc4xOpcUaCollectorTest {

    @Test
    void shouldExecuteReadWriteAndBrowseCommandsThroughPlc4xApi() throws Exception {
        Plc4xOpcUaCollector collector = new Plc4xOpcUaCollector();
        prepareConnectedCollector(collector);

        Plc4xOpcUaConnectionAdapter connectionAdapter = mock(Plc4xOpcUaConnectionAdapter.class);
        PlcConnection connection = mock(PlcConnection.class);
        PlcConnectionMetadata metadata = mock(PlcConnectionMetadata.class);
        PlcReadRequest.Builder readBuilder = mock(PlcReadRequest.Builder.class);
        PlcReadRequest readRequest = mock(PlcReadRequest.class);
        PlcReadResponse readResponse = mock(PlcReadResponse.class);
        PlcWriteRequest.Builder writeBuilder = mock(PlcWriteRequest.Builder.class);
        PlcWriteRequest writeRequest = mock(PlcWriteRequest.class);
        PlcWriteResponse writeResponse = mock(PlcWriteResponse.class);
        PlcBrowseRequest.Builder browseBuilder = mock(PlcBrowseRequest.Builder.class);
        PlcBrowseRequest browseRequest = mock(PlcBrowseRequest.class);
        PlcBrowseResponse browseResponse = mock(PlcBrowseResponse.class);
        PlcBrowseItem browseItem = mock(PlcBrowseItem.class);
        PlcTag browseTag = mock(PlcTag.class);
        PlcValue readValue = mock(PlcValue.class);

        when(connectionAdapter.isConnected()).thenReturn(true);
        when(connectionAdapter.getClient()).thenReturn(connection);
        when(connection.getMetadata()).thenReturn(metadata);
        when(metadata.isBrowseSupported()).thenReturn(true);
        when(metadata.isSubscribeSupported()).thenReturn(true);

        when(connection.readRequestBuilder()).thenReturn(readBuilder);
        when(readBuilder.addTagAddress(eq("node0"), eq("ns=2;s=Channel1.Device1.Tag1;REAL"))).thenReturn(readBuilder);
        when(readBuilder.build()).thenReturn(readRequest);
        when(readRequest.execute()).thenAnswer(invocation -> CompletableFuture.completedFuture(readResponse));
        when(readResponse.getResponseCode("node0")).thenReturn(PlcResponseCode.OK);
        when(readResponse.getPlcValue("node0")).thenReturn(readValue);
        when(readValue.isNull()).thenReturn(false);
        when(readValue.isList()).thenReturn(false);
        when(readValue.isFloat()).thenReturn(true);
        when(readValue.getFloat()).thenReturn(12.5f);

        when(connection.writeRequestBuilder()).thenReturn(writeBuilder);
        when(writeBuilder.addTagAddress(eq("node"), eq("ns=2;s=Channel1.Device1.Tag1;REAL"), eq(25.0f))).thenReturn(writeBuilder);
        when(writeBuilder.build()).thenReturn(writeRequest);
        when(writeRequest.execute()).thenAnswer(invocation -> CompletableFuture.completedFuture(writeResponse));
        when(writeResponse.getResponseCode("node")).thenReturn(PlcResponseCode.OK);

        when(connection.browseRequestBuilder()).thenReturn(browseBuilder);
        when(browseBuilder.addQuery(eq("browse"), eq("ns=0;i=84"))).thenReturn(browseBuilder);
        when(browseBuilder.build()).thenReturn(browseRequest);
        when(browseRequest.execute()).thenAnswer(invocation -> CompletableFuture.completedFuture(browseResponse));
        when(browseResponse.getResponseCode("browse")).thenReturn(PlcResponseCode.OK);
        when(browseResponse.getValues("browse")).thenReturn(List.of(browseItem));
        when(browseItem.getName()).thenReturn("Objects");
        when(browseItem.getTag()).thenReturn(browseTag);
        when(browseTag.toString()).thenReturn("ns=0;i=85");
        when(browseItem.isReadable()).thenReturn(true);
        when(browseItem.isWritable()).thenReturn(false);
        when(browseItem.isSubscribable()).thenReturn(true);
        when(browseItem.isPublishable()).thenReturn(false);
        when(browseItem.isArray()).thenReturn(false);
        when(browseItem.getChildren()).thenReturn(Collections.emptyMap());
        when(browseItem.getOptions()).thenReturn(Collections.emptyMap());

        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readResult = (List<Map<String, Object>>) collector.executeCommand(
                "read", Map.of("nodeId", "ns=2;s=Channel1.Device1.Tag1", "dataType", "FLOAT"));
        assertEquals(1, readResult.size());
        assertEquals("ns=2;s=Channel1.Device1.Tag1", readResult.get(0).get("nodeId"));
        assertEquals(12.5d, ((Number) readResult.get(0).get("value")).doubleValue(), 0.0001d);

        @SuppressWarnings("unchecked")
        Map<String, Object> writeResult = (Map<String, Object>) collector.executeCommand(
                "write", Map.of("nodeId", "ns=2;s=Channel1.Device1.Tag1", "dataType", "FLOAT", "value", 25));
        assertEquals("success", writeResult.get("status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> browseResult = (List<Map<String, Object>>) collector.executeCommand("browse", Collections.emptyMap());
        assertEquals(1, browseResult.size());
        assertEquals("Objects", browseResult.get(0).get("name"));
        assertEquals("ns=0;i=85", browseResult.get(0).get("tagAddress"));
    }

    @Test
    void shouldRegisterSubscriptionsAndProcessIncomingValues() throws Exception {
        Plc4xOpcUaCollector collector = new Plc4xOpcUaCollector();
        prepareConnectedCollector(collector);

        Plc4xOpcUaConnectionAdapter connectionAdapter = mock(Plc4xOpcUaConnectionAdapter.class);
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

        PlcUnsubscriptionRequest.Builder unsubscriptionBuilder = mock(PlcUnsubscriptionRequest.Builder.class);
        PlcUnsubscriptionRequest unsubscriptionRequest = mock(PlcUnsubscriptionRequest.class);
        when(connection.unsubscriptionRequestBuilder()).thenReturn(unsubscriptionBuilder);
        when(unsubscriptionBuilder.addHandles(org.mockito.ArgumentMatchers.<Collection<PlcSubscriptionHandle>>any()))
                .thenReturn(unsubscriptionBuilder);
        when(unsubscriptionBuilder.build()).thenReturn(unsubscriptionRequest);
        when(unsubscriptionRequest.execute()).thenReturn(null);

        ArgumentCaptor<Consumer<PlcSubscriptionEvent>> eventCaptor = ArgumentCaptor.forClass(Consumer.class);
        when(builder.addCyclicTagAddress(eq("p1"), eq("ns=2;s=Channel1.Device1.Tag1;REAL"), any(Duration.class), eventCaptor.capture()))
                .thenReturn(builder);

        ReflectionTestUtils.setField(collector, "connectionAdapter", connectionAdapter);

        DataPoint point = point("p1", "temperature", "ns=2;s=Channel1.Device1.Tag1", "FLOAT");
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
        when(plcValue.isFloat()).thenReturn(true);
        when(plcValue.getFloat()).thenReturn(42.5f);

        eventCaptor.getValue().accept(event);

        ProcessResult processResult = collector.getLatestProcessResult("p1");
        assertNotNull(processResult);
        assertTrue(processResult.isSuccess());
        assertEquals(42.5d, ((Number) processResult.getFinalValue()).doubleValue(), 0.0001d);

        collector.unsubscribe(List.of(point));

        Map<String, Object> unsubscribeStatus = collector.getDeviceStatus();
        assertEquals(0, unsubscribeStatus.get("activeSubscriptions"));
    }

    @Test
    void shouldPreferDriverDataTypeWhenCoercingWriteValue() {
        Plc4xOpcUaCollector collector = new Plc4xOpcUaCollector();
        DataPoint point = point("p2", "payload", "ns=2;s=Payload", "INT");
        point.setAdditionalConfig(Map.of("driverDataType", "BYTE_ARRAY"));
        Plc4xOpcUaAddress address = Plc4xOpcUaAddressParser.parse(point);

        Object value = ReflectionTestUtils.invokeMethod(collector, "coerceWriteValue", "abc", address, point);

        assertTrue(value instanceof byte[]);
        assertArrayEquals("abc".getBytes(), (byte[]) value);
    }

    @Test
    void shouldUseResolvedDriverTypeWhenExtractingValues() {
        Plc4xOpcUaCollector collector = new Plc4xOpcUaCollector();
        DataPoint point = point("p3", "temperature", "ns=2;s=Temp", "INT");
        point.setAdditionalConfig(Map.of("driverDataType", "REAL"));
        Plc4xOpcUaAddress address = Plc4xOpcUaAddressParser.parse(point);

        PlcValue plcValue = mock(PlcValue.class);
        when(plcValue.isNull()).thenReturn(false);
        when(plcValue.isList()).thenReturn(false);
        when(plcValue.isFloat()).thenReturn(true);
        when(plcValue.getFloat()).thenReturn(9.5f);

        Object value = ReflectionTestUtils.invokeMethod(collector, "extractValue", plcValue, point, address);

        assertTrue(value instanceof Float);
        assertEquals(9.5f, (Float) value, 0.0001f);
    }

    @Test
    void shouldConvertOpcUaArrayReadAndWriteValues() {
        Plc4xOpcUaCollector collector = new Plc4xOpcUaCollector();
        DataPoint point = point("p-array", "values", "ns=2;s=Values", "INT");
        point.setAdditionalConfig(Map.of("driverDataType", "INT", "arraySize", 2));
        Plc4xOpcUaAddress address = Plc4xOpcUaAddressParser.parse(point);

        PlcValue arrayValue = mock(PlcValue.class);
        PlcValue first = mock(PlcValue.class);
        PlcValue second = mock(PlcValue.class);
        when(arrayValue.isNull()).thenReturn(false);
        when(arrayValue.isList()).thenReturn(true);
        when(arrayValue.getLength()).thenReturn(2);
        when(arrayValue.getIndex(0)).thenReturn(first);
        when(arrayValue.getIndex(1)).thenReturn(second);
        when(first.isInteger()).thenReturn(true);
        when(second.isInteger()).thenReturn(true);
        when(first.getInteger()).thenReturn(7);
        when(second.getInteger()).thenReturn(8);

        Object readValue = ReflectionTestUtils.invokeMethod(collector, "extractValue", arrayValue, point, address);
        Object writeValue = ReflectionTestUtils.invokeMethod(
                collector, "coerceWriteValue", new int[]{9, 10}, address, point);

        assertEquals(List.of(7, 8), readValue);
        assertEquals(List.of(9, 10), writeValue);
    }

    private void prepareConnectedCollector(Plc4xOpcUaCollector collector) throws Exception {
        collector.init(device());
        ReflectionTestUtils.setField(collector, "dataQualityProcessor", new DataQualityProcessor(null));
        ReflectionTestUtils.setField(collector, "connected", true);
        ReflectionTestUtils.setField(collector, "connectionStatus", "CONNECTED");
    }

    private DeviceInfo device() {
        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-opcua-plc4x");
        deviceInfo.setDeviceName("opcua-plc4x-device");
        deviceInfo.setProtocolType("OPC_UA_PLC4X");
        deviceInfo.setCollectionInterval(1000);
        return deviceInfo;
    }

    private DataPoint point(String pointId, String pointCode, String address, String dataType) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointCode);
        point.setDeviceId("dev-opcua-plc4x");
        point.setAddress(address);
        point.setDataType(dataType);
        point.setReadWrite("RW");
        point.setStatus(1);
        point.setCollectionMode("SUBSCRIPTION");
        return point;
    }
}
