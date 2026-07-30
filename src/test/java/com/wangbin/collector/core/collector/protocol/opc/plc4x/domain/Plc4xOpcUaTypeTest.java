package com.wangbin.collector.core.collector.protocol.opc.plc4x.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class Plc4xOpcUaTypeTest {

    @Test
    void shouldNormalizeDriverAliases() {
        assertEquals(Plc4xOpcUaType.SINT, Plc4xOpcUaType.fromDriverTextOrNull("SBYTE"));
        assertEquals(Plc4xOpcUaType.DATE_AND_TIME, Plc4xOpcUaType.fromDriverTextOrNull("DATE_TIME"));
        assertEquals(Plc4xOpcUaType.BYTESTRING, Plc4xOpcUaType.fromDriverTextOrNull("BINARY"));
    }

    @Test
    void shouldReturnNullForUnsupportedAlias() {
        assertNull(Plc4xOpcUaType.fromDriverTextOrNull("UNKNOWN"));
        assertNull(Plc4xOpcUaType.fromDriverTextOrNull(" "));
    }
}