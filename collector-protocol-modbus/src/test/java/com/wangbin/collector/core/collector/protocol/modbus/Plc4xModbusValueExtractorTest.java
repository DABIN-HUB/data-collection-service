package com.wangbin.collector.core.collector.protocol.modbus;

import com.wangbin.collector.common.enums.Parity;
import com.wangbin.collector.core.collector.protocol.modbus.utils.ModbusUtils;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.value.PlcValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PLC4X Modbus 值提取器测试。
 */
class Plc4xModbusValueExtractorTest {

    @Test
    void shouldFlattenNestedBoolListReturnedByPlc4xForBitArrays() {
        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue root = listValue(listValue(boolValue(false), boolValue(true), boolValue(false)));
        when(response.getPlcValue("value")).thenReturn(root);

        byte[] bytes = Plc4xModbusValueExtractor.coilBytes(response, "value", 3, Parity.none);

        assertArrayEquals(ModbusUtils.buildCoilBytes(List.of(false, true, false), Parity.none), bytes);
    }

    @Test
    void shouldReadPackedByteArrayReturnedByPlc4xForBitArrays() {
        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue root = objectValue(new byte[]{0b0000_0101});
        when(response.getPlcValue("value")).thenReturn(root);

        byte[] bytes = Plc4xModbusValueExtractor.coilBytes(response, "value", 3, Parity.none);

        assertArrayEquals(ModbusUtils.buildCoilBytes(List.of(true, false, true), Parity.none), bytes);
    }

    @Test
    void shouldReadPackedNumberReturnedByPlc4xForBitArrays() {
        PlcReadResponse response = mock(PlcReadResponse.class);
        PlcValue root = objectValue(0b10_0000_0001);
        when(response.getPlcValue("value")).thenReturn(root);

        byte[] bytes = Plc4xModbusValueExtractor.coilBytes(response, "value", 10, Parity.none);

        assertArrayEquals(ModbusUtils.buildCoilBytes(
                List.of(true, false, false, false, false, false, false, false, false, true), Parity.none), bytes);
    }

    private static PlcValue listValue(PlcValue... values) {
        PlcValue value = mock(PlcValue.class);
        when(value.isNull()).thenReturn(false);
        when(value.isList()).thenReturn(true);
        doReturn(List.of(values)).when(value).getList();
        when(value.getObject()).thenReturn(List.of(values));
        return value;
    }

    private static PlcValue boolValue(boolean bool) {
        PlcValue value = mock(PlcValue.class);
        when(value.isNull()).thenReturn(false);
        when(value.isList()).thenReturn(false);
        when(value.getObject()).thenReturn(bool);
        return value;
    }

    private static PlcValue objectValue(Object object) {
        PlcValue value = mock(PlcValue.class);
        when(value.isNull()).thenReturn(false);
        when(value.isList()).thenReturn(false);
        when(value.getObject()).thenReturn(object);
        return value;
    }
}
