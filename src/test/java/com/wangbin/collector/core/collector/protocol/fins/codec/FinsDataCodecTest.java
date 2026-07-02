package com.wangbin.collector.core.collector.protocol.fins.codec;

import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.util.FinsAddressParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinsDataCodecTest {

    @Test
    void shouldDecodeInt32WithLittleWordOrder() {
        FinsAddress address = FinsAddressParser.parse("DM:100", "INT32", Map.of("wordOrder", "LITTLE_ENDIAN"));

        Object value = FinsDataCodec.decode(new byte[]{0x33, 0x44, 0x11, 0x22}, address);

        assertEquals(0x11223344, value);
    }

    @Test
    void shouldEncodeAndDecodeString() {
        FinsAddress address = FinsAddressParser.parse("DM:200#4", "STRING", Map.of());

        byte[] encoded = FinsDataCodec.encode("AB", address);
        Object decoded = FinsDataCodec.decode(encoded, address);

        assertArrayEquals(new byte[]{0x41, 0x42, 0x00, 0x00}, encoded);
        assertEquals("AB", decoded);
    }

    @Test
    void shouldDecodeBitPayload() {
        FinsAddress address = FinsAddressParser.parse("DM:100.3", "BOOLEAN", Map.of());

        Object decoded = FinsDataCodec.decode(new byte[]{0x01}, address);

        assertTrue((Boolean) decoded);
    }
}