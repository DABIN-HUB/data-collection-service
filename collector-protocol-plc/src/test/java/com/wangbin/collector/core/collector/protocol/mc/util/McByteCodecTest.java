package com.wangbin.collector.core.collector.protocol.mc.util;

import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDriverType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class McByteCodecTest {

    @Test
    void shouldEncodeAndDecodeUint32LittleEndian() {
        McAddress address = new McAddress("D100", "D100", McDeviceCode.D, 100, McDriverType.UINT32, 1, null, null);

        byte[] encoded = McByteCodec.encode(address, 0x12345678L);
        Object decoded = McByteCodec.decode(address, encoded);

        assertArrayEquals(new byte[]{0x78, 0x56, 0x34, 0x12}, encoded);
        assertEquals(0x12345678L, decoded);
    }

    @Test
    void shouldEncodeAndDecodeBitArray() {
        McAddress address = new McAddress("M0[3]", "M0[3]", McDeviceCode.M, 0, McDriverType.BOOL, 3, null, null);

        byte[] encoded = McByteCodec.encode(address, List.of(true, false, true));
        Object decoded = McByteCodec.decode(address, encoded);

        assertArrayEquals(new byte[]{0x01, 0x01}, encoded);
        assertEquals(List.of(true, false, true), decoded);
    }

    @Test
    void shouldEncodeAndDecodeString() {
        McAddress address = new McAddress("D200", "D200", McDeviceCode.D, 200, McDriverType.STRING, 1, 6, null);

        byte[] encoded = McByteCodec.encode(address, "AB");
        Object decoded = McByteCodec.decode(address, encoded);

        assertArrayEquals(new byte[]{0x41, 0x42, 0x00, 0x00, 0x00, 0x00}, encoded);
        assertEquals("AB", decoded);
    }
}
