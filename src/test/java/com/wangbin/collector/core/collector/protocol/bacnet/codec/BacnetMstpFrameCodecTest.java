package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BacnetMstpFrameCodecTest {

    @Test
    void shouldEncodeAndDecodeDataFrame() {
        BacnetMstpFrame frame = new BacnetMstpFrame(
                BacnetMstpFrameType.BACNET_DATA_EXPECTING_REPLY,
                8,
                5,
                new byte[]{0x01, 0x02, 0x03, 0x04});

        byte[] encoded = BacnetMstpFrameCodec.encode(frame);
        BacnetMstpFrame decoded = BacnetMstpFrameCodec.decode(encoded);

        assertEquals(BacnetMstpFrameType.BACNET_DATA_EXPECTING_REPLY, decoded.frameType());
        assertEquals(8, decoded.destinationAddress());
        assertEquals(5, decoded.sourceAddress());
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04}, decoded.data());
    }

    @Test
    void shouldRejectCorruptedDataCrc() {
        BacnetMstpFrame frame = new BacnetMstpFrame(
                BacnetMstpFrameType.BACNET_DATA_NOT_EXPECTING_REPLY,
                8,
                5,
                new byte[]{0x11, 0x22, 0x33});
        byte[] encoded = BacnetMstpFrameCodec.encode(frame);
        encoded[encoded.length - 1] ^= 0x01;

        assertThrows(BacnetMstpFrameCodec.CrcException.class, () -> BacnetMstpFrameCodec.decode(encoded));
    }
}