package com.wangbin.collector.core.collector.protocol.dlt645.codec;

import com.wangbin.collector.core.collector.protocol.dlt645.Dlt645ProtocolException;
import com.wangbin.collector.core.collector.protocol.dlt645.domain.Dlt645Address;
import com.wangbin.collector.core.collector.protocol.dlt645.domain.Dlt645Frame;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Dlt645FrameCodecTest {

    @Test
    void shouldEncodeAndDecodeFrameWithWakeUpBytes() throws Exception {
        Dlt645Frame request = new Dlt645Frame(
                new Dlt645Address("123456789012"), 0x11, new byte[]{0x00, 0x00, 0x01, 0x00});

        byte[] encoded = Dlt645FrameCodec.encode(request, 4);
        Dlt645Frame decoded = Dlt645FrameCodec.decode(encoded);

        assertEquals("123456789012", decoded.address().value());
        assertEquals(0x11, decoded.control());
        assertArrayEquals(request.data(), decoded.data());
        assertEquals(0xFE, encoded[0] & 0xFF);
    }

    @Test
    void shouldRejectInvalidChecksum() {
        byte[] encoded = Dlt645FrameCodec.encode(new Dlt645Frame(
                new Dlt645Address("000000000001"), 0x11, new byte[]{0, 0, 1, 0}), 0);
        encoded[encoded.length - 2]++;

        assertThrows(Dlt645ProtocolException.class, () -> Dlt645FrameCodec.decode(encoded));
    }

    @Test
    void shouldConvertIdentifierAndBcdValue() throws Exception {
        assertArrayEquals(new byte[]{0x00, 0x01, 0x01, 0x02},
                Dlt645DataCodec.encodeDataIdentifier("02010100"));
        byte[] encoded = Dlt645DataCodec.encodeValue("1234.56", "BCD", "XXXX.XX");
        assertEquals(new BigDecimal("1234.56"),
                Dlt645DataCodec.decodeValue(encoded, "BCD", "XXXX.XX", 0));
    }
}
