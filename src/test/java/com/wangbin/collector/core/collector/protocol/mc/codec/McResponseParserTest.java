package com.wangbin.collector.core.collector.protocol.mc.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McResponseParserTest {

    @Test
    void shouldParseSuccessfulReadPayload() {
        byte[] payload = new byte[]{0x78, 0x56, 0x34, 0x12};

        byte[] parsed = McResponseParser.parseReadPayload(response(0, payload));

        assertArrayEquals(payload, parsed);
    }

    @Test
    void shouldRejectErrorEndCode() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> McResponseParser.ensureWriteSuccess(response(0x0051, new byte[0])));

        assertEquals("MC request failed, endCode=0x0051", exception.getMessage());
    }

    @Test
    void shouldParseSuccessfulAsciiReadPayload() {
        byte[] payload = "1234".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        byte[] parsed = McResponseParser.parseAsciiReadPayload(asciiResponse(0, payload));

        assertArrayEquals(payload, parsed);
    }

    @Test
    void shouldParseSuccessful4eReadPayload() {
        byte[] payload = new byte[]{0x12, 0x34};

        byte[] parsed = McResponseParser.parse4eBinaryReadPayload(response4e(0, payload));

        assertArrayEquals(payload, parsed);
    }

    private byte[] response(int endCode, byte[] payload) {
        byte[] safePayload = payload != null ? payload : new byte[0];
        int dataLength = 2 + safePayload.length;
        byte[] frame = new byte[9 + dataLength];
        frame[0] = (byte) 0xD0;
        frame[1] = 0x00;
        frame[2] = 0x00;
        frame[3] = (byte) 0xFF;
        frame[4] = (byte) 0xFF;
        frame[5] = 0x03;
        frame[6] = 0x00;
        frame[7] = (byte) (dataLength & 0xFF);
        frame[8] = (byte) ((dataLength >> 8) & 0xFF);
        frame[9] = (byte) (endCode & 0xFF);
        frame[10] = (byte) ((endCode >> 8) & 0xFF);
        System.arraycopy(safePayload, 0, frame, 11, safePayload.length);
        return frame;
    }

    private byte[] asciiResponse(int endCode, byte[] payload) {
        byte[] safePayload = payload != null ? payload : new byte[0];
        int dataLength = 4 + safePayload.length;
        String frame = "D00000FF03FF00"
                + String.format("%04X", dataLength)
                + String.format("%04X", endCode)
                + new String(safePayload, java.nio.charset.StandardCharsets.US_ASCII);
        return frame.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private byte[] response4e(int endCode, byte[] payload) {
        byte[] safePayload = payload != null ? payload : new byte[0];
        int dataLength = 2 + safePayload.length;
        byte[] frame = new byte[13 + dataLength];
        frame[0] = (byte) 0xD4;
        frame[1] = 0x00;
        frame[11] = (byte) (dataLength & 0xFF);
        frame[12] = (byte) ((dataLength >> 8) & 0xFF);
        frame[13] = (byte) (endCode & 0xFF);
        frame[14] = (byte) ((endCode >> 8) & 0xFF);
        System.arraycopy(safePayload, 0, frame, 15, safePayload.length);
        return frame;
    }
}
