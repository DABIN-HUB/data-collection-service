package com.wangbin.collector.core.collector.protocol.iec;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.utils.JsonDataPointLoader;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.DataQualityProcessor;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import com.wangbin.collector.core.alarm.AlarmStateTracker;
import com.wangbin.collector.core.alarm.InMemoryAlarmStateRepository;
import com.wangbin.collector.core.collector.ingress.TelemetryIngressService;
import com.wangbin.collector.core.connection.adapter.Iec104ConnectionAdapter;
import com.wangbin.collector.core.collector.protocol.iec.base.Iec104IoaEncodingMode;
import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.ASduType;
import org.openmuc.j60870.ie.InformationElement;
import org.openmuc.j60870.ie.InformationObject;
import org.openmuc.j60870.ie.IeQuality;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.Connection;
import org.openmuc.j60870.ConnectionEventListener;
import org.openmuc.j60870.ie.IeBinaryStateInformation;
import org.openmuc.j60870.ie.IeDoubleCommand;
import org.openmuc.j60870.ie.IeQualifierOfSetPointCommand;
import org.openmuc.j60870.ie.IeRegulatingStepCommand;
import org.openmuc.j60870.ie.IeScaledValue;
import org.openmuc.j60870.ie.IeShortFloat;
import org.openmuc.j60870.ie.IeSingleCommand;
import org.openmuc.j60870.ie.IeTime56;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Iec104CollectorTest {

    @Test
    void sequenceMeasurementDeliversEveryConsecutiveIoa() {
        RecordingCollector collector = new RecordingCollector();
        collector.prepareSession();
        DataPoint first = createWritablePoint("M_ME_NC_1:4001");
        DataPoint second = createWritablePoint("M_ME_NC_1:4002");
        collector.buildReadPlans("device", List.of(first, second));
        ASdu sequence = measurements(true, CauseOfTransmission.INTERROGATED_BY_STATION,
                4001, 10.5f, 20.5f);

        collector.receiveThroughListener(sequence);

        assertEquals(2, collector.publishedRecords().size());
        collector.assertPublished(first, 10.5d);
        collector.assertPublished(second, 20.5d);
    }

    @Test
    void interrogationMeasurementFulfillsPendingReadWithoutDuplicatePush() {
        RecordingCollector collector = new RecordingCollector();
        collector.prepareSession();
        DataPoint point = createWritablePoint("M_ME_NC_1:4001");
        collector.buildReadPlans("device", List.of(point));
        CompletableFuture<Object> pending = collector.pending(1, 13, 4001);
        CompletableFuture<Object> otherType = collector.pending(1, 3, 4001);
        CompletableFuture<Object> otherCommonAddress = collector.pending(2, 13, 4001);
        CompletableFuture<Object> otherIoa = collector.pending(1, 13, 4002);

        try {
            collector.receiveThroughListener(measurements(false,
                    CauseOfTransmission.INTERROGATED_BY_STATION, 4001, 17.25f));
            assertEquals(17.25f, collector.pendingValue(pending));
            assertEquals(13, collector.pendingRawTypeId(pending));
            assertEquals(0, collector.pendingQualityBits(pending));
            assertFalse(otherType.isDone());
            assertFalse(otherCommonAddress.isDone());
            assertFalse(otherIoa.isDone());
            assertEquals(0, collector.publishedRecords().size());

            collector.receiveThroughListener(measurements(false,
                    CauseOfTransmission.INTERROGATED_BY_STATION, 4001, 18.25f));

            assertEquals(1, collector.publishedRecords().size());
            collector.assertPublished(point, 18.25d);
        } finally {
            pending.cancel(false);
            otherType.cancel(false);
            otherCommonAddress.cancel(false);
            otherIoa.cancel(false);
        }
    }

    @Test
    void interrogationMeasurementWithoutPendingReadEntersTelemetry() {
        RecordingCollector collector = new RecordingCollector();
        collector.prepareSession();
        DataPoint point = createWritablePoint("M_ME_NC_1:4001");
        collector.buildReadPlans("device", List.of(point));

        collector.receiveThroughListener(measurements(false,
                CauseOfTransmission.INTERROGATED_BY_STATION, 4001, 17.25f));

        assertEquals(1, collector.publishedRecords().size());
        collector.assertPublished(point, 17.25d);
    }

    @Test
    void pollingInterrogationWithoutPendingReadEntersTelemetry() {
        RecordingCollector collector = new RecordingCollector();
        collector.prepareSession();
        DataPoint point = createWritablePoint("M_ME_NC_1:4001");
        point.setCollectionMode("POLLING");
        collector.buildReadPlans("device", List.of(point));

        collector.receiveThroughListener(measurements(false,
                CauseOfTransmission.INTERROGATED_BY_STATION, 4001, 17.25f));

        assertEquals(1, collector.publishedRecords().size());
        collector.assertPublished(point, 17.25d);
    }

    @Test
    void shift8SequenceUsesWireAddressStrideBeforeLogicalDecode() {
        RecordingCollector collector = new RecordingCollector();
        collector.prepareSession();
        ReflectionTestUtils.setField(collector, "ioaEncodingMode", Iec104IoaEncodingMode.SHIFT8_COMPAT);
        DataPoint first = createWritablePoint("M_ME_NC_1:4001");
        DataPoint second = createWritablePoint("M_ME_NC_1:4002");
        collector.buildReadPlans("device", List.of(first, second));

        collector.receiveThroughListener(shiftedMeasurements(4001, 10.5f, 20.5f));

        assertEquals(2, collector.publishedRecords().size());
        collector.assertPublished(first, 10.5d);
        collector.assertPublished(second, 20.5d);
    }

    @Test
    void staleListenerFromReplacedSessionCannotPublishMeasurement() {
        RecordingCollector collector = new RecordingCollector();
        collector.prepareSession();
        DataPoint point = createWritablePoint("M_ME_NC_1:4001");
        collector.buildReadPlans("device", List.of(point));
        collector.receiveThroughListener(measurements(false,
                CauseOfTransmission.INTERROGATED_BY_STATION, 4001, 10.0f));
        assertEquals(1, collector.publishedRecords().size());
        collector.assertPublished(point, 10.0d);

        Iec104ConnectionAdapter replacement = mock(Iec104ConnectionAdapter.class);
        Connection replacementClient = mock(Connection.class);
        when(replacement.getClient()).thenReturn(replacementClient);
        when(replacementClient.isClosed()).thenReturn(false);
        when(replacementClient.isStopped()).thenReturn(false);
        ReflectionTestUtils.setField(collector, "currentAdapter", replacement);
        ReflectionTestUtils.setField(collector, "connection", replacementClient);

        collector.receiveThroughListener(measurements(false,
                CauseOfTransmission.INTERROGATED_BY_STATION, 4001, 22.5f));

        assertEquals(1, collector.publishedRecords().size());
        assertEquals(10.0d, collector.publishedRecords().get(0).result().getFinalValue());
    }

    private static ASdu measurements(boolean sequence, CauseOfTransmission cot,
                                     int firstIoa, float... values) {
        InformationObject[] objects;
        if (sequence) {
            InformationElement[][] rows = new InformationElement[values.length][];
            for (int i = 0; i < values.length; i++) {
                rows[i] = new InformationElement[]{new IeShortFloat(values[i]),
                        new IeQuality(false, false, false, false, false)};
            }
            objects = new InformationObject[]{new InformationObject(firstIoa, rows)};
        } else {
            objects = new InformationObject[]{new InformationObject(firstIoa,
                    new IeShortFloat(values[0]), new IeQuality(false, false, false, false, false))};
        }
        return new ASdu(ASduType.M_ME_NC_1, sequence, cot, false, false, 0, 1, objects);
    }

    private static ASdu shiftedMeasurements(int firstLogicalIoa, float... values) {
        InformationElement[][] rows = new InformationElement[values.length][];
        for (int i = 0; i < values.length; i++) {
            rows[i] = new InformationElement[]{new IeShortFloat(values[i]),
                    new IeQuality(false, false, false, false, false)};
        }
        InformationObject[] objects = new InformationObject[]{
                new InformationObject(firstLogicalIoa << 8, rows)};
        return new ASdu(ASduType.M_ME_NC_1, true,
                CauseOfTransmission.INTERROGATED_BY_STATION, false, false, 0, 1, objects);
    }

    private record PublishedSample(String deviceId, String pointId, Long generation,
                                   ProcessResult result) {
    }

    private static final class RecordingCollector extends Iec104Collector {
        private final List<PublishedSample> published = new ArrayList<>();
        private long receiveStartedAt;
        private long receiveFinishedAt;
        private Connection client;
        private Iec104ConnectionAdapter adapter;
        private ConnectionEventListener listener;

        private void receive(ASdu asdu) {
            handleResponse(null, asdu);
        }

        private void receiveThroughListener(ASdu asdu) {
            receiveStartedAt = System.currentTimeMillis();
            try {
                listener.newASdu(client, asdu);
            } finally {
                receiveFinishedAt = System.currentTimeMillis();
            }
        }

        private List<PublishedSample> publishedRecords() {
            return List.copyOf(published);
        }

        private void assertPublished(DataPoint point, Object expectedValue) {
            PublishedSample sample = published.stream()
                    .filter(record -> point.getPointId().equals(record.pointId()))
                    .findFirst()
                    .orElseThrow();
            ProcessResult result = sample.result();
            assertEquals("device", sample.deviceId());
            assertEquals(7L, sample.generation());
            assertEquals(point.getPointId(), sample.pointId());
            assertEquals(expectedValue, result.getFinalValue());
            assertTrue(result.isSuccess());
            assertEquals("DataQualityProcessor", result.getProcessorName());
            assertEquals(100, result.getQuality());
            assertEquals("PUSH", result.getMetadata(ProcessResultMetadataKeys.SOURCE));
            assertEquals(Long.valueOf(3L), result.getMetadata(ProcessResultMetadataKeys.CONFIG_VERSION));
            assertEquals(expectedValue, result.getMetadata(ProcessResultMetadataKeys.PROCESSED_VALUE));
            Long observedAt = result.getMetadata(ProcessResultMetadataKeys.COLLECT_TIME);
            assertTrue(observedAt != null && observedAt >= receiveStartedAt && observedAt <= receiveFinishedAt);
            Long sampleObservedAt = result.getMetadata("iec104ObservedAt");
            assertTrue(sampleObservedAt != null && sampleObservedAt >= receiveStartedAt
                    && sampleObservedAt <= receiveFinishedAt);
            assertEquals(sampleObservedAt, observedAt);
            assertEquals("LOCAL_RECEIVE", result.getMetadata("iec104TimeOrigin"));
            assertFalse(result.getMetadata().containsKey("iec104SourceTime"));
            assertEquals(Integer.valueOf(13), result.getMetadata("iec104TypeId"));
            assertEquals(Integer.valueOf(0), result.getMetadata("iec104QualityBits"));
        }

        private void prepareSession() {
            init(deviceInfo());
            client = mock(Connection.class);
            adapter = mock(Iec104ConnectionAdapter.class);
            when(adapter.getClient()).thenReturn(client);
            when(client.isClosed()).thenReturn(false);
            when(client.isStopped()).thenReturn(false);
            DataQualityProcessor processor = new DataQualityProcessor(
                    null, new AlarmStateTracker(new InMemoryAlarmStateRepository()));
            TelemetryIngressService ingress = mock(TelemetryIngressService.class);
            doAnswer(invocation -> {
                String deviceId = invocation.getArgument(0);
                ProcessResult result = invocation.getArgument(2);
                DataPoint point = invocation.getArgument(1);
                Long generation = invocation.getArgument(3);
                published.add(new PublishedSample(deviceId, point.getPointId(), generation, result.snapshot()));
                return null;
            }).when(ingress).append(any(), any(DataPoint.class), any(ProcessResult.class), any(Long.class));
            ReflectionTestUtils.setField(this, "dataQualityProcessor", processor);
            ReflectionTestUtils.setField(this, "telemetryIngressService", ingress);
            ReflectionTestUtils.setField(this, "currentAdapter", adapter);
            ReflectionTestUtils.setField(this, "connection", client);
            ReflectionTestUtils.setField(this, "connected", true);
            ReflectionTestUtils.setField(this, "dataTransferStopped", false);
            setRuntimeGeneration(7L);
            setRuntimeConfigurationVersion(3L);
            listener = ReflectionTestUtils.invokeMethod(this, "createConnectionEventListener", adapter);
        }

        private com.wangbin.collector.common.domain.entity.DeviceInfo deviceInfo() {
            com.wangbin.collector.common.domain.entity.DeviceInfo info =
                    new com.wangbin.collector.common.domain.entity.DeviceInfo();
            info.setDeviceId("device");
            info.setDeviceName("iec-test");
            info.setProtocolType("IEC104");
            return info;
        }

        private CompletableFuture<Object> pending(int ca, int type, int ioa) {
            return registerPendingRequest(ca, type, ioa);
        }

        private Object pendingValue(CompletableFuture<Object> future) {
            return ((CacheEntry) future.getNow(null)).value();
        }

        private Integer pendingRawTypeId(CompletableFuture<Object> future) {
            return ((CacheEntry) future.getNow(null)).rawTypeId();
        }

        private int pendingQualityBits(CompletableFuture<Object> future) {
            return ((CacheEntry) future.getNow(null)).qualityBits();
        }

    }

    @Test
    void shouldUsePointLevelCommonAddressForWrites() throws Exception {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);
        DataPoint point = createWritablePoint("C_SC_NA_1:10");
        point.setCommonAddress(9);

        collector.doWritePoint(point, 1.0d);

        ArgumentCaptor<IeSingleCommand> captor = ArgumentCaptor.forClass(IeSingleCommand.class);
        verify(connection).singleCommand(eq(9), eq(CauseOfTransmission.ACTIVATION), eq(10), captor.capture());
        assertTrue(captor.getValue().isCommandStateOn());
    }

    @Test
    void shouldSupportFlexibleWriteValueParsing() throws Exception {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);

        DataPoint stepPoint = createWritablePoint("C_RC_NA_1:11");
        collector.doWritePoint(stepPoint, "RAISE");
        ArgumentCaptor<IeRegulatingStepCommand> stepCaptor =
                ArgumentCaptor.forClass(IeRegulatingStepCommand.class);
        verify(connection).regulatingStepCommand(
                eq(1), eq(CauseOfTransmission.ACTIVATION), eq(11), stepCaptor.capture());
        assertEquals(2, stepCaptor.getValue().getCommandState().getId());

        DataPoint bitPoint = createWritablePoint("C_BO_NA_1:12");
        collector.doWritePoint(bitPoint, "0x0f");
        ArgumentCaptor<IeBinaryStateInformation> bitCaptor =
                ArgumentCaptor.forClass(IeBinaryStateInformation.class);
        verify(connection).bitStringCommand(
                eq(1), eq(CauseOfTransmission.ACTIVATION), eq(12), bitCaptor.capture());
        assertEquals(15, bitCaptor.getValue().getValue());

        DataPoint scaledPoint = createWritablePoint("C_SE_NB_1:13");
        collector.doWritePoint(scaledPoint, 12.0d);
        ArgumentCaptor<IeScaledValue> scaledCaptor = ArgumentCaptor.forClass(IeScaledValue.class);
        ArgumentCaptor<IeQualifierOfSetPointCommand> qualifierCaptor =
                ArgumentCaptor.forClass(IeQualifierOfSetPointCommand.class);
        verify(connection).setScaledValueCommand(
                eq(1), eq(CauseOfTransmission.ACTIVATION), eq(13), scaledCaptor.capture(), qualifierCaptor.capture());
        assertEquals(12, scaledCaptor.getValue().getUnnormalizedValue());
        assertEquals(0, qualifierCaptor.getValue().getQl());
        assertFalse(qualifierCaptor.getValue().isSelect());
    }

    @Test
    void shouldUseDedicatedWriteBindingForReadWritePoint() throws Exception {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);

        DataPoint point = createWritablePoint("M_ME_NC_1:1");
        point.setCommonAddress(1);
        point.setAdditionalConfig(Map.of(
                "writeAddress", "C_SE_NC_1:101",
                "writeCommonAddress", 2,
                "writeSelect", false,
                "writeQl", 7
        ));

        collector.doWritePoint(point, 12.5d);

        ArgumentCaptor<IeShortFloat> valueCaptor = ArgumentCaptor.forClass(IeShortFloat.class);
        ArgumentCaptor<IeQualifierOfSetPointCommand> qualifierCaptor =
                ArgumentCaptor.forClass(IeQualifierOfSetPointCommand.class);
        verify(connection).setShortFloatCommand(
                eq(2),
                eq(CauseOfTransmission.ACTIVATION),
                eq(101),
                valueCaptor.capture(),
                qualifierCaptor.capture());
        assertEquals(12.5f, valueCaptor.getValue().getValue(), 0.001f);
        assertEquals(7, qualifierCaptor.getValue().getQl());
        assertFalse(qualifierCaptor.getValue().isSelect());
    }

    @Test
    void shouldRejectSelectBeforeExecuteInsteadOfSendingSelectOnly() {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);
        DataPoint point = createWritablePoint("M_ME_NC_1:1");
        point.setAdditionalConfig(Map.of(
                "writeAddress", "C_SE_NC_1:101",
                "writeSelect", true
        ));

        assertThrows(IllegalArgumentException.class, () -> collector.doWritePoint(point, 12.5d));
        verifyNoInteractions(connection);
    }

    @Test
    void shouldRejectConflictingWriteAddressAndPointType() {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);
        DataPoint point = createWritablePoint("M_ME_NC_1:1");
        point.setAdditionalConfig(Map.of(
                "writeAddress", "C_SC_NA_1:101",
                "typeId", 46
        ));

        assertThrows(IllegalArgumentException.class, () -> collector.doWritePoint(point, 1.0d));
        verifyNoInteractions(connection);
    }

    @Test
    void shouldUseTimedWriteCommandWhenWriteAddressTypeIsTimed() throws Exception {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);

        DataPoint point = createWritablePoint("M_ME_NC_1:1");
        point.setAdditionalConfig(Map.of(
                "writeAddress", "C_SE_TC_1:101",
                "writeCommonAddress", 2
        ));

        collector.doWritePoint(point, 12.5d);

        ArgumentCaptor<IeShortFloat> valueCaptor = ArgumentCaptor.forClass(IeShortFloat.class);
        verify(connection).setShortFloatCommandWithTimeTag(
                eq(2),
                eq(CauseOfTransmission.ACTIVATION),
                eq(101),
                valueCaptor.capture(),
                org.mockito.ArgumentMatchers.any(IeQualifierOfSetPointCommand.class),
                any(IeTime56.class));
        assertEquals(12.5f, valueCaptor.getValue().getValue(), 0.001f);
    }

    @Test
    void shouldRejectUntypedWriteAddress() {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);
        DataPoint point = createWritablePoint("M_ME_NC_1:1");
        point.setAdditionalConfig(Map.of("writeAddress", "101"));

        assertThrows(IllegalArgumentException.class, () -> collector.doWritePoint(point, 12.5d));
    }

    @Test
    void shouldRejectWriteTimeTagConfig() {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);
        DataPoint point = createWritablePoint("M_ME_NC_1:1");
        point.setAdditionalConfig(Map.of(
                "writeAddress", "C_SE_NC_1:101",
                "writeTimeTag", true
        ));

        assertThrows(IllegalArgumentException.class, () -> collector.doWritePoint(point, 12.5d));
    }

    @Test
    void shouldParseStringCommandParameters() throws Exception {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);

        collector.doExecuteCommand(1, "single_command", Map.of(
                "commonAddress", "7",
                "address", "15",
                "state", "1.0"
        ));

        ArgumentCaptor<IeSingleCommand> captor = ArgumentCaptor.forClass(IeSingleCommand.class);
        verify(connection).singleCommand(eq(7), eq(CauseOfTransmission.ACTIVATION), eq(15), captor.capture());
        assertTrue(captor.getValue().isCommandStateOn());
    }

    @Test
    void shouldUseCollectorDefaultCommonAddressWhenBaseCommandFallbackIsOne() throws Exception {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);
        ReflectionTestUtils.setField(collector, "commonAddress", 8);

        collector.doExecuteCommand(1, "single_command", Map.of(
                "address", "16",
                "state", "1"
        ));

        verify(connection).singleCommand(eq(8), eq(CauseOfTransmission.ACTIVATION), eq(16), org.mockito.ArgumentMatchers.any(IeSingleCommand.class));
    }

    @Test
    void shouldEncodeShift8ForReadCommand() throws Exception {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);
        ReflectionTestUtils.setField(collector, "ioaEncodingMode", Iec104IoaEncodingMode.SHIFT8_COMPAT);
        ReflectionTestUtils.setField(collector, "ioaFieldLength", 3);

        collector.doExecuteCommand(1, "read_command", Map.of("address", 100));

        verify(connection).readCommand(eq(1), eq(25600));
    }

    @Test
    void shouldEncodeShift8ForPointWrite() throws Exception {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);
        ReflectionTestUtils.setField(collector, "ioaEncodingMode", Iec104IoaEncodingMode.SHIFT8_COMPAT);
        ReflectionTestUtils.setField(collector, "ioaFieldLength", 3);
        DataPoint point = createWritablePoint("C_SC_NA_1:100");

        collector.doWritePoint(point, 1.0d);

        verify(connection).singleCommand(eq(1), eq(CauseOfTransmission.ACTIVATION), eq(25600), any(IeSingleCommand.class));
    }

    @Test
    void shouldEncodeShift8ForDoubleCommand() throws Exception {
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);
        ReflectionTestUtils.setField(collector, "ioaEncodingMode", Iec104IoaEncodingMode.SHIFT8_COMPAT);
        ReflectionTestUtils.setField(collector, "ioaFieldLength", 3);
        DataPoint point = createWritablePoint("C_DC_NA_1:100");

        collector.doWritePoint(point, "ON");

        verify(connection).doubleCommand(eq(1), eq(CauseOfTransmission.ACTIVATION), eq(25600), any(IeDoubleCommand.class));
    }

    @Test
    void shouldEncodeShift8ForBatchReadLogicalKey() throws Exception {
        RecordingCollector collector = new RecordingCollector();
        collector.prepareSession();
        ReflectionTestUtils.setField(collector, "ioaEncodingMode", Iec104IoaEncodingMode.SHIFT8_COMPAT);
        ReflectionTestUtils.setField(collector, "ioaFieldLength", 3);
        DataPoint point = createWritablePoint("M_ME_NC_1:100");

        doAnswer(invocation -> {
            collector.receive(shiftedMeasurements(100, 42.0f));
            return null;
        }).when(collector.client).readCommand(eq(1), eq(25600));

        assertEquals(42.0d, collector.readPoints(List.of(point)).get(point.getPointId()));
        verify(collector.client).readCommand(eq(1), eq(25600));
    }

    @Test
    void shouldLoadTopLevelCommonAddressFromJson() {
        List<DataPoint> points = JsonDataPointLoader.loadDataPointsFromJsonString("""
                [
                  {
                    "pointId": "p1",
                    "pointName": "Point-1",
                    "address": "1",
                    "readWrite": "RW",
                    "commonAddress": 6
                  }
                ]
                """);

        assertEquals(1, points.size());
        assertEquals(6, points.get(0).getCommonAddress());
    }

    private Iec104Collector createCollector(Connection connection) {
        Iec104Collector collector = new Iec104Collector();
        ReflectionTestUtils.setField(collector, "connection", connection);
        ReflectionTestUtils.setField(collector, "commonAddress", 1);
        ReflectionTestUtils.setField(collector, "timeTag", false);
        return collector;
    }

    private DataPoint createWritablePoint(String address) {
        DataPoint point = new DataPoint();
        point.setPointId("p-" + address);
        point.setPointName(address);
        point.setAddress(address);
        point.setReadWrite("RW");
        point.setDeviceId("device");
        point.setStatus(1);
        return point;
    }
}
