package com.wangbin.collector.core.collector.protocol.iec101.codec;

import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101Frame;
import com.wangbin.collector.core.collector.protocol.iec101.domain.Iec101FrameType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Iec101Ft12CodecTest {

    @Test
    void shouldEncodeAndDecodeFixedFrame() {
        Iec101Frame request = new Iec101Frame(Iec101FrameType.FIXED, 0x49, 1, new byte[0]);

        byte[] encoded = Iec101Ft12Codec.encode(request, 1);
        Iec101Frame decoded = Iec101Ft12Codec.decode(encoded, 1);

        assertArrayEquals(new byte[]{0x10, 0x49, 0x01, 0x4A, 0x16}, encoded);
        assertEquals(Iec101FrameType.FIXED, decoded.type());
        assertEquals(1, decoded.linkAddress());
    }

    @Test
    void shouldEncodeAndDecodeVariableFrame() {
        Iec101Frame request = new Iec101Frame(
                Iec101FrameType.VARIABLE, 0x53, 0x1234, new byte[]{100, 1, 6, 0, 1, 0, 0, 0, 0, 20});

        byte[] encoded = Iec101Ft12Codec.encode(request, 2);
        Iec101Frame decoded = Iec101Ft12Codec.decode(encoded, 2);

        assertEquals(Iec101FrameType.VARIABLE, decoded.type());
        assertEquals(0x1234, decoded.linkAddress());
        assertArrayEquals(request.userData(), decoded.userData());
    }

    @Test
    void shouldRejectInvalidChecksum() {
        byte[] encoded = Iec101Ft12Codec.encode(
                new Iec101Frame(Iec101FrameType.FIXED, 0x49, 1, new byte[0]), 1);
        encoded[encoded.length - 2]++;

        assertThrows(IllegalArgumentException.class, () -> Iec101Ft12Codec.decode(encoded, 1));
    }
}
