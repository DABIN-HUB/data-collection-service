package com.wangbin.collector.core.collector.protocol.iec;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.utils.JsonDataPointLoader;
import com.wangbin.collector.core.processor.ProcessResult;
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

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class Iec104CollectorTest {

    @Test
    void sequenceMeasurementDeliversEveryConsecutiveIoa() {
        RecordingCollector collector = new RecordingCollector();
        DataPoint first = createWritablePoint("M_ME_NC_1:4001");
        DataPoint second = createWritablePoint("M_ME_NC_1:4002");
        collector.buildReadPlans("device", List.of(first, second));
        ASdu sequence = measurements(true, CauseOfTransmission.INTERROGATED_BY_STATION,
                4001, 10.5f, 20.5f);

        Map<String, Object> result = collector.receive(sequence);

        assertEquals(10.5f, result.get("4001"));
        assertEquals(20.5f, result.get("4002"));
        assertEquals(10.5f, collector.published.get(first.getPointId()));
        assertEquals(20.5f, collector.published.get(second.getPointId()));
    }

    @Test
    void interrogationMeasurementFulfillsPendingReadWithoutDuplicatePush() {
        RecordingCollector collector = new RecordingCollector();
        DataPoint point = createWritablePoint("M_ME_NC_1:4001");
        collector.buildReadPlans("device", List.of(point));
        CompletableFuture<Object> pending = collector.pending(1, 13, 4001);

        collector.receive(measurements(false,
                CauseOfTransmission.INTERROGATED_BY_STATION, 4001, 17.25f));

        assertEquals(17.25f, pending.getNow(null));
        assertFalse(collector.published.containsKey(point.getPointId()));
    }

    @Test
    void interrogationMeasurementWithoutPendingReadEntersTelemetry() {
        RecordingCollector collector = new RecordingCollector();
        DataPoint point = createWritablePoint("M_ME_NC_1:4001");
        collector.buildReadPlans("device", List.of(point));

        collector.receive(measurements(false,
                CauseOfTransmission.INTERROGATED_BY_STATION, 4001, 17.25f));

        assertEquals(17.25f, collector.published.get(point.getPointId()));
    }

    @Test
    void pollingInterrogationMeasurementDoesNotDuplicateTelemetry() {
        RecordingCollector collector = new RecordingCollector();
        DataPoint point = createWritablePoint("M_ME_NC_1:4001");
        point.setCollectionMode("POLLING");
        collector.buildReadPlans("device", List.of(point));

        collector.receive(measurements(false,
                CauseOfTransmission.INTERROGATED_BY_STATION, 4001, 17.25f));

        assertFalse(collector.published.containsKey(point.getPointId()));
    }

    @Test
    void shift8SequenceUsesWireAddressStrideBeforeLogicalDecode() {
        RecordingCollector collector = new RecordingCollector();
        ReflectionTestUtils.setField(collector, "ioaEncodingMode", Iec104IoaEncodingMode.SHIFT8_COMPAT);
        DataPoint first = createWritablePoint("M_ME_NC_1:4001");
        DataPoint second = createWritablePoint("M_ME_NC_1:4002");
        collector.buildReadPlans("device", List.of(first, second));

        Map<String, Object> result = collector.receive(shiftedMeasurements(4001, 10.5f, 20.5f));

        assertEquals(10.5f, result.get("4001"));
        assertEquals(20.5f, result.get("4002"));
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

    private static final class RecordingCollector extends Iec104Collector {
        private final Map<String, Object> published = new java.util.HashMap<>();

        private Map<String, Object> receive(ASdu asdu) {
            return handleResponse(null, asdu);
        }

        private CompletableFuture<Object> pending(int ca, int type, int ioa) {
            return registerPendingRequest(ca, type, ioa);
        }

        @Override
        protected ProcessResult ingestPushedValue(DataPoint point, Object rawValue) {
            published.put(point.getPointId(), rawValue);
            return null;
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
        Connection connection = mock(Connection.class);
        Iec104Collector collector = createCollector(connection);
        ReflectionTestUtils.setField(collector, "ioaEncodingMode", Iec104IoaEncodingMode.SHIFT8_COMPAT);
        ReflectionTestUtils.setField(collector, "ioaFieldLength", 3);
        DataPoint point = createWritablePoint("100");

        org.mockito.Mockito.doAnswer(invocation -> {
            ReflectionTestUtils.invokeMethod(collector, "completeRequest", 1, null, 100, 42);
            return null;
        }).when(connection).readCommand(eq(1), eq(25600));

        assertEquals(42, collector.doReadPoints(List.of(point)).get(point.getPointId()));
        verify(connection).readCommand(eq(1), eq(25600));
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
        return point;
    }
}
