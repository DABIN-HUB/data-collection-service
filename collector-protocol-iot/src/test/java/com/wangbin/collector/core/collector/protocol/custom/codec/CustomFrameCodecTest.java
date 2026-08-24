package com.wangbin.collector.core.collector.protocol.custom.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CustomFrameCodecTest {

    @Test
    void shouldEncodeAndDecodeLengthFieldFrame() throws Exception {
        DeviceConnection config = config(Map.of(
                "frameMode", "LENGTH_FIELD",
                "lengthFieldLength", 4,
                "initialBytesToStrip", 4,
                "prependLengthField", true));
        byte[] payload = new byte[]{0x01, 0x02, 0x03};

        byte[] frame = CustomFrameCodec.encode(payload, config);

        assertArrayEquals(new byte[]{0x00, 0x00, 0x00, 0x03, 0x01, 0x02, 0x03}, frame);
        assertArrayEquals(payload, CustomFrameCodec.decode(new ByteArrayInputStream(frame), config));
    }

    @Test
    void shouldDecodeDelimiterAndFixedLengthFrames() throws Exception {
        DeviceConnection delimiterConfig = config(Map.of(
                "frameMode", "DELIMITER",
                "delimiterHex", "0D0A",
                "stripDelimiter", true));
        DeviceConnection fixedConfig = config(Map.of(
                "frameMode", "FIXED_LENGTH",
                "fixedFrameLength", 3));

        assertArrayEquals(new byte[]{0x11, 0x22}, CustomFrameCodec.decode(
                new ByteArrayInputStream(new byte[]{0x11, 0x22, 0x0D, 0x0A}), delimiterConfig));
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, CustomFrameCodec.decode(
                new ByteArrayInputStream(new byte[]{0x01, 0x02, 0x03}), fixedConfig));
    }

    private DeviceConnection config(Map<String, Object> values) {
        DeviceConnection connection = new DeviceConnection();
        connection.setExtJson(new LinkedHashMap<>(values));
        return connection;
    }
}
