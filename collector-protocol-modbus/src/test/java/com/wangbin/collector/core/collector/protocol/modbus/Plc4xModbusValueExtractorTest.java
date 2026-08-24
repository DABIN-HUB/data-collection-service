package com.wangbin.collector.core.collector.protocol.modbus;

import com.wangbin.collector.common.enums.Parity;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.value.PlcValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Plc4xModbusValueExtractorTest {

    @Test
    void shouldExtractRegisterBytesFromPlcList() {
        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue root = plcList(plcNumber(0x337E), plcNumber(0x3379));
        when(response.getPlcValue("value")).thenReturn(root);

        byte[] raw = Plc4xModbusValueExtractor.registerBytes(response, "value", 2);

        assertArrayEquals(new byte[]{0x33, 0x7E, 0x33, 0x79}, raw);
    }

    @Test
    void shouldPreserveUnsignedRegisterBits() {
        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue root = plcList(plcNumber(0x0000), plcNumber(0x8000), plcNumber(0xFFFF));
        when(response.getPlcValue("value")).thenReturn(root);

        byte[] raw = Plc4xModbusValueExtractor.registerBytes(response, "value", 3);

        assertArrayEquals(new byte[]{0x00, 0x00, (byte) 0x80, 0x00, (byte) 0xFF, (byte) 0xFF}, raw);
    }

    @Test
    void shouldExtractSingleRegisterFromScalarValue() {
        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue value = plcNumber(0x1234);
        when(response.getPlcValue("value")).thenReturn(value);

        byte[] raw = Plc4xModbusValueExtractor.registerBytes(response, "value", 1);

        assertArrayEquals(new byte[]{0x12, 0x34}, raw);
    }

    @Test
    void shouldRejectRegisterResponseWithMissingValue() {
        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue root = plcList(plcNumber(0x1234));
        when(response.getPlcValue("value")).thenReturn(root);

        assertThrows(
                IllegalStateException.class,
                () -> Plc4xModbusValueExtractor.registerBytes(response, "value", 2));
    }

    @Test
    void shouldRejectRegisterResponseWithUnexpectedValue() {
        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue root = plcList(plcNumber(0x1234), plcNumber(0x5678), plcNumber(0x789A));
        when(response.getPlcValue("value")).thenReturn(root);

        assertThrows(
                IllegalStateException.class,
                () -> Plc4xModbusValueExtractor.registerBytes(response, "value", 2));
    }

    @Test
    void shouldExtractCoilBytesFromPlcList() {
        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue root = plcList(plcBool(true), plcBool(false), plcBool(true));
        when(response.getPlcValue("value")).thenReturn(root);

        byte[] raw = Plc4xModbusValueExtractor.coilBytes(response, "value", 3, Parity.none);

        assertArrayEquals(new byte[]{0x05}, raw);
    }

    private PlcValue plcList(PlcValue... values) {
        PlcValue plcValue = mock(PlcValue.class);
        when(plcValue.isNull()).thenReturn(false);
        when(plcValue.isList()).thenReturn(true);
        doReturn(List.of(values)).when(plcValue).getList();
        return plcValue;
    }

    private PlcValue plcNumber(int value) {
        PlcValue plcValue = mock(PlcValue.class);
        when(plcValue.isNull()).thenReturn(false);
        when(plcValue.isList()).thenReturn(false);
        when(plcValue.getObject()).thenReturn(value);
        return plcValue;
    }

    private PlcValue plcBool(boolean value) {
        PlcValue plcValue = mock(PlcValue.class);
        when(plcValue.isNull()).thenReturn(false);
        when(plcValue.isList()).thenReturn(false);
        when(plcValue.getObject()).thenReturn(value);
        return plcValue;
    }
}
