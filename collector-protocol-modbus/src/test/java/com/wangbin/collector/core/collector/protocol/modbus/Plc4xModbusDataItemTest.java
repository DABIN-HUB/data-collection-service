package com.wangbin.collector.core.collector.protocol.modbus;

import org.apache.plc4x.java.api.value.PlcValue;
import org.apache.plc4x.java.modbus.readwrite.DataItem;
import org.apache.plc4x.java.modbus.readwrite.ModbusDataType;
import org.apache.plc4x.java.spi.generation.ReadBufferByteBased;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Plc4xModbusDataItemTest {

    @Test
    void shouldParseUnsignedRegisterArrayWithPlc4x() throws Exception {
        byte[] raw = {0x12, 0x34, 0x56, 0x78};

        PlcValue parsed = DataItem.staticParse(
                new ReadBufferByteBased(raw), ModbusDataType.UINT, 2, false);

        assertTrue(parsed.isList());
        List<? extends PlcValue> values = parsed.getList();
        assertEquals(2, values.size());
        assertEquals(0x1234, ((Number) values.get(0).getObject()).intValue());
        assertEquals(0x5678, ((Number) values.get(1).getObject()).intValue());
    }
}
