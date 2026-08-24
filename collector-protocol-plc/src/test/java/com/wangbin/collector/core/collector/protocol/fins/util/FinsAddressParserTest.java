package com.wangbin.collector.core.collector.protocol.fins.util;

import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsMemoryArea;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(1, address.readUnitCount());
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
    }
}