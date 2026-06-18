package com.wangbin.collector.core.collector.protocol.iec;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.utils.JsonDataPointLoader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.Connection;
import org.openmuc.j60870.ie.IeBinaryStateInformation;
import org.openmuc.j60870.ie.IeQualifierOfSetPointCommand;
import org.openmuc.j60870.ie.IeRegulatingStepCommand;
import org.openmuc.j60870.ie.IeScaledValue;
import org.openmuc.j60870.ie.IeShortFloat;
import org.openmuc.j60870.ie.IeSingleCommand;
import org.openmuc.j60870.ie.IeTime56;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class Iec104CollectorTest {

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
                "writeSelect", true,
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
        assertTrue(qualifierCaptor.getValue().isSelect());
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
