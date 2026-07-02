package com.wangbin.collector.core.collector.protocol.fins.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsConnectionConfig;
import com.wangbin.collector.core.collector.protocol.fins.util.FinsAddressParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinsFrameCodecTest {

    @Test
    void shouldBuildReadRequest() {
        FinsConnectionConfig config = config();
        FinsAddress address = FinsAddressParser.parse("DM:100", "INT16", java.util.Map.of());

        byte[] request = FinsFrameCodec.buildReadRequest(config, 0x22, address);

        assertEquals(18, request.length);
        assertEquals(0x22, request[9] & 0xFF);
        assertEquals(0x82, request[12] & 0xFF);
        assertEquals(100, request[14] & 0xFF);
        assertEquals(1, request[17] & 0xFF);
    }

    @Test
    void shouldParseReadResponse() {
        byte[] response = new byte[]{
                (byte) 0xC0, 0, 2, 0, 1, 0, 0, 10, 0, 0x22,
                0x01, 0x01, 0x00, 0x00,
                0x12, 0x34
        };

        FinsFrameCodec.FinsResponse parsed = FinsFrameCodec.parseReadResponse(response, 0x22);

        assertTrue(parsed.success());
        assertEquals(0, parsed.endCode());
        assertArrayEquals(new byte[]{0x12, 0x34}, parsed.payload());
    }

    private FinsConnectionConfig config() {
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setPort(9600);
        LinkedHashMap<String, Object> ext = new LinkedHashMap<>();
        ext.put("plcNode", 1);
        ext.put("localNode", 10);
        connection.setExtJson(ext);
        return FinsConnectionConfig.from(connection);
    }
}