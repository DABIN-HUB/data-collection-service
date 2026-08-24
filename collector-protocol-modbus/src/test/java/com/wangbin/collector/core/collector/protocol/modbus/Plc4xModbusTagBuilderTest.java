package com.wangbin.collector.core.collector.protocol.modbus;

import com.wangbin.collector.core.collector.protocol.modbus.domain.RegisterType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Plc4xModbusTagBuilderTest {

    @Test
    void shouldBuildHoldingRegisterBatchTagWithUnsignedRegisters() {
        String tag = Plc4xModbusTagBuilder.build(RegisterType.HOLDING_REGISTER, 0, 2, 1);

        assertEquals("holding-register:1:UINT[2]{unit-id: 1}", tag);
    }

    @Test
    void shouldBuildInputRegisterScalarTagWithUnsignedRegister() {
        String tag = Plc4xModbusTagBuilder.build(RegisterType.INPUT_REGISTER, 9, 1, 2);

        assertEquals("input-register:10:UINT{unit-id: 2}", tag);
    }

    @Test
    void shouldBuildBitAreaTagWithBooleanValues() {
        String coilTag = Plc4xModbusTagBuilder.build(RegisterType.COIL, 0, 8, 1);
        String discreteInputTag = Plc4xModbusTagBuilder.build(RegisterType.DISCRETE_INPUT, 4, 1, 1);

        assertEquals("coil:1:BOOL[8]{unit-id: 1}", coilTag);
        assertEquals("discrete-input:5:BOOL{unit-id: 1}", discreteInputTag);
    }

    @Test
    void shouldRejectInvalidAddressAndQuantity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Plc4xModbusTagBuilder.build(RegisterType.HOLDING_REGISTER, -1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> Plc4xModbusTagBuilder.build(RegisterType.HOLDING_REGISTER, 0, 0, 1));
    }
}
