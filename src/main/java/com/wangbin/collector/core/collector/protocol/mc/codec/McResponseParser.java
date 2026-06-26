package com.wangbin.collector.core.collector.protocol.mc.codec;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;

public final class McResponseParser {

    private static final int HEADER_LENGTH = 9;
    private static final int END_CODE_OFFSET = 9;
    private static final int PAYLOAD_OFFSET = 11;
    private static final int ASCII_HEADER_LENGTH = 18;
    private static final int ASCII_END_CODE_OFFSET = 18;
    private static final int ASCII_PAYLOAD_OFFSET = 22;
    private static final int BINARY_4E_HEADER_LENGTH = 13;
    private static final int BINARY_4E_END_CODE_OFFSET = 13;
    private static final int BINARY_4E_PAYLOAD_OFFSET = 15;

    private McResponseParser() {
    }

    public static byte[] parseReadPayload(byte[] response) {
        validateResponse(response);
        return Arrays.copyOfRange(response, PAYLOAD_OFFSET, response.length);
    }

    public static void ensureWriteSuccess(byte[] response) {
        validateResponse(response);
    }

    public static byte[] parseAsciiReadPayload(byte[] response) {
        validateAsciiResponse(response);
        return Arrays.copyOfRange(response, ASCII_PAYLOAD_OFFSET, response.length);
    }

    public static void ensureAsciiWriteSuccess(byte[] response) {
        validateAsciiResponse(response);
    }

    public static byte[] parse4eBinaryReadPayload(byte[] response) {
        validate4eBinaryResponse(response);
        return Arrays.copyOfRange(response, BINARY_4E_PAYLOAD_OFFSET, response.length);
    }

    public static void ensure4eBinaryWriteSuccess(byte[] response) {
        validate4eBinaryResponse(response);
    }

    public static int readEndCode(byte[] response) {
        if (response == null || response.length < PAYLOAD_OFFSET) {
            return -1;
        }
        return readUInt16(response, END_CODE_OFFSET);
    }

    public static int readAsciiEndCode(byte[] response) {
        if (response == null || response.length < ASCII_PAYLOAD_OFFSET) {
            return -1;
        }
        return Integer.parseInt(new String(response, ASCII_END_CODE_OFFSET, 4, StandardCharsets.US_ASCII), 16);
    }

    public static int read4eBinaryEndCode(byte[] response) {
        if (response == null || response.length < BINARY_4E_PAYLOAD_OFFSET) {
            return -1;
        }
        return readUInt16(response, BINARY_4E_END_CODE_OFFSET);
    }

    private static void validateResponse(byte[] response) {
        if (response == null || response.length < PAYLOAD_OFFSET) {
            throw new IllegalArgumentException("MC response is too short");
        }
        if ((response[0] & 0xFF) != 0xD0 || (response[1] & 0xFF) != 0x00) {
            throw new IllegalArgumentException("Unexpected MC response subheader");
        }
        int declaredLength = readUInt16(response, 7);
        if (response.length != HEADER_LENGTH + declaredLength) {
            throw new IllegalArgumentException("MC response length mismatch: declared="
                    + declaredLength + ", actual=" + (response.length - HEADER_LENGTH));
        }
        int endCode = readUInt16(response, END_CODE_OFFSET);
        if (endCode != 0) {
            throw new IllegalStateException(String.format("MC request failed, endCode=0x%04X", endCode));
        }
    }

    private static void validateAsciiResponse(byte[] response) {
        if (response == null || response.length < ASCII_PAYLOAD_OFFSET) {
            throw new IllegalArgumentException("MC ASCII response is too short");
        }
        if (response[0] != 'D' || response[1] != '0' || response[2] != '0' || response[3] != '0') {
            throw new IllegalArgumentException("Unexpected MC ASCII response subheader");
        }
        int declaredLength = Integer.parseInt(new String(response, 14, 4, StandardCharsets.US_ASCII), 16);
        if (response.length != ASCII_HEADER_LENGTH + declaredLength) {
            throw new IllegalArgumentException("MC ASCII response length mismatch: declared="
                    + declaredLength + ", actual=" + (response.length - ASCII_HEADER_LENGTH));
        }
        int endCode = readAsciiEndCode(response);
        if (endCode != 0) {
            throw new IllegalStateException(String.format("MC request failed, endCode=0x%04X", endCode));
        }
    }

    private static void validate4eBinaryResponse(byte[] response) {
        if (response == null || response.length < BINARY_4E_PAYLOAD_OFFSET) {
            throw new IllegalArgumentException("MC 4E response is too short");
        }
        if ((response[0] & 0xFF) != 0xD4 || (response[1] & 0xFF) != 0x00) {
            throw new IllegalArgumentException("Unexpected MC 4E response subheader");
        }
        int declaredLength = readUInt16(response, 11);
        if (response.length != BINARY_4E_HEADER_LENGTH + declaredLength) {
            throw new IllegalArgumentException("MC 4E response length mismatch: declared="
                    + declaredLength + ", actual=" + (response.length - BINARY_4E_HEADER_LENGTH));
        }
        int endCode = read4eBinaryEndCode(response);
        if (endCode != 0) {
            throw new IllegalStateException(String.format("MC request failed, endCode=0x%04X", endCode));
        }
    }

    private static int readUInt16(byte[] response, int offset) {
        return (response[offset] & 0xFF) | ((response[offset + 1] & 0xFF) << 8);
    }
}
