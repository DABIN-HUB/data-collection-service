package com.wangbin.collector.core.collector.protocol.fins.util;

import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsMemoryArea;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinsAddressParserTest {

    @Test
    void shouldParseScalarWordAddress() {
        FinsAddress address = FinsAddressParser.parse("DM:100", "INT16", Map.of());

        assertEquals(FinsMemoryArea.DM, address.getMemoryArea());
        assertEquals(100, address.getWordAddress());
        assertEquals("INT16", address.getDataType());
        assertEquals(1, address.readUnitCount());
    }

    @Test
    void shouldParseBitAddress() {
        FinsAddress address = FinsAddressParser.parse("CIO:0.1", "BOOLEAN", Map.of());

        assertEquals(FinsMemoryArea.CIO, address.getMemoryArea());
        assertEquals(0, address.getWordAddress());
        assertEquals(1, address.getBitOffset());
        assertEquals("BOOLEAN", address.getDataType());
        assertTrue(address.isBitUnit());
        assertEquals(1, address.readUnitCount());
        FinsAddress dmBit = FinsAddressParser.parse("DM:100.3", "BOOLEAN", Map.of());
        assertEquals(3, dmBit.getBitOffset());
        assertEquals("BOOLEAN", dmBit.getDataType());
        assertTrue(dmBit.isBitUnit());
    }

    @Test
    void shouldNormalizeBoolAliasForBitAddress() {
        FinsAddress address = FinsAddressParser.parse("DM:100.3", "BOOL", Map.of());

        assertEquals(3, address.getBitOffset());
        assertEquals("BOOLEAN", address.getDataType());
        assertTrue(address.isBitUnit());
    }

    @Test
    void shouldParseStringLengthFromAddress() {
        FinsAddress address = FinsAddressParser.parse("DM:200#8", "STRING", Map.of());

        assertEquals(8, address.getStringLength());
        assertEquals(4, address.requiredStringWordCount());
    }

    @Test
    void shouldParseNumericArrayLength() {
        FinsAddress address = FinsAddressParser.parse("HR:10#2", "INT32", Map.of());

        assertEquals(2, address.getElementCount());
        assertEquals(4, address.readUnitCount());
    }

    @Test
    void shouldRejectBitAddressForNonBooleanType() {
        assertThrows(IllegalArgumentException.class,
                () -> FinsAddressParser.parse("DM:100.3", "INT16", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> FinsAddressParser.parse("DM:100.3", "UINT16", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> FinsAddressParser.parse("DM:100.3", "FLOAT", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> FinsAddressParser.parse("DM:100", "INT16", Map.of("bitIndex", 3)));
    }
}