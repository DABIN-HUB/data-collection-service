package com.wangbin.collector.core.collector.protocol.mc.support;

import com.wangbin.collector.core.collector.protocol.mc.codec.McAsciiCodecSupport;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDriverType;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class FakeMcServer implements AutoCloseable {

    private static final int HEADER_3E_BINARY = 9;
    private static final int HEADER_4E_BINARY = 13;
    private static final int HEADER_3E_ASCII = 18;

    private final ServerSocket serverSocket;
    private final FakeMcMemoryModel memoryModel = new FakeMcMemoryModel();
    private final AtomicReference<RuntimeException> asyncFailure = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);

    private volatile boolean running = true;
    private volatile Integer forcedEndCode;
    private volatile boolean forceLengthMismatch;
    private volatile boolean forceUnexpectedSubheader;
    private volatile boolean force4eSerialMismatch;
    private volatile long responseDelayMs;
    private Thread acceptThread;

    public FakeMcServer() throws Exception {
        this.serverSocket = new ServerSocket(0);
        this.acceptThread = new Thread(this::acceptLoop, "fake-mc-server");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        awaitStart();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public FakeMcMemoryModel memory() {
        return memoryModel;
    }

    public void forceEndCode(int endCode) {
        this.forcedEndCode = endCode;
    }

    public void forceLengthMismatch(boolean value) {
        this.forceLengthMismatch = value;
    }

    public void forceUnexpectedSubheader(boolean value) {
        this.forceUnexpectedSubheader = value;
    }

    public void force4eSerialMismatch(boolean value) {
        this.force4eSerialMismatch = value;
    }

    public void setResponseDelayMs(long responseDelayMs) {
        this.responseDelayMs = responseDelayMs;
    }

    private void awaitStart() throws Exception {
        if (!started.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Fake MC server failed to start");
        }
    }

    private void acceptLoop() {
        started.countDown();
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                handleClient(socket);
            } catch (Exception e) {
                if (running) {
                    asyncFailure.compareAndSet(null, new RuntimeException("Fake MC server failed", e));
                }
            }
        }
    }

    private void handleClient(Socket socket) throws Exception {
        InputStream inputStream = socket.getInputStream();
        OutputStream outputStream = socket.getOutputStream();
        while (running && !socket.isClosed()) {
            try {
                byte[] request = readRequest(inputStream);
                byte[] response = handleRequest(request);
                if (responseDelayMs > 0) {
                    Thread.sleep(responseDelayMs);
                }
                outputStream.write(response);
                outputStream.flush();
            } catch (EOFException eof) {
                throw eof;
            } catch (Exception e) {
                asyncFailure.compareAndSet(null, new RuntimeException("Fake MC client handling failed", e));
                throw e;
            }
        }
    }

    private byte[] readRequest(InputStream inputStream) throws Exception {
        int first = inputStream.read();
        if (first < 0) {
            throw new EOFException("Fake MC socket closed");
        }
        return switch (first & 0xFF) {
            case 0x50 -> read3eBinaryRequest(inputStream, (byte) first);
            case 0x54 -> read4eBinaryRequest(inputStream, (byte) first);
            case '5' -> read3eAsciiRequest(inputStream, (byte) first);
            default -> throw new IllegalArgumentException(String.format("Unsupported fake MC request header byte: 0x%02X", first & 0xFF));
        };
    }

    private byte[] handleRequest(byte[] request) {
        verifyNoAsyncFailure();
        RequestModel model = parseRequest(request);
        return switch (model.command) {
            case 0x0401 -> buildReadResponse(model, model.bitUnit
                    ? memoryModel.readBits(model.deviceCode, model.deviceNumber, model.unitCount)
                    : memoryModel.readWords(model.deviceCode, model.deviceNumber, model.unitCount));
            case 0x0403 -> buildRandomReadResponse(model);
            case 0x1401 -> buildWriteResponse(model);
            case 0x1402 -> buildRandomWriteResponse(model);
            default -> throw new IllegalArgumentException("Unsupported fake MC command: 0x" + Integer.toHexString(model.command));
        };
    }

    private RequestModel parseRequest(byte[] request) {
        if (request.length >= 2 && (request[0] & 0xFF) == 0x50 && (request[1] & 0xFF) == 0x00) {
            return parse3eBinaryRequest(request);
        }
        if (request.length >= 2 && (request[0] & 0xFF) == 0x54 && (request[1] & 0xFF) == 0x00) {
            return parse4eBinaryRequest(request);
        }
        if (request.length >= 4 && request[0] == '5' && request[1] == '0' && request[2] == '0' && request[3] == '0') {
            return parse3eAsciiRequest(request);
        }
        throw new IllegalArgumentException("Unsupported fake MC request frame");
    }

    private RequestModel parse3eBinaryRequest(byte[] request) {
        RequestModel model = new RequestModel(FrameType.BINARY_3E);
        model.command = readUInt16(request, 11);
        if (model.command == 0x0401 || model.command == 0x1401) {
            model.subcommand = readUInt16(request, 13);
            model.bitUnit = model.subcommand == 0x0001;
            model.deviceNumber = read24(request, 15);
            model.deviceCode = resolveDeviceCode(request[18] & 0xFF);
            model.unitCount = readUInt16(request, 19);
            model.payload = slice(request, 21, request.length);
        } else {
            model.unitCount = request[13] & 0xFF;
            model.payload = slice(request, 15, request.length);
        }
        return model;
    }

    private RequestModel parse4eBinaryRequest(byte[] request) {
        RequestModel model = new RequestModel(FrameType.BINARY_4E);
        model.serialNo = readUInt16(request, 2);
        model.command = readUInt16(request, 15);
        if (model.command == 0x0401 || model.command == 0x1401) {
            model.subcommand = readUInt16(request, 17);
            model.bitUnit = model.subcommand == 0x0001;
            model.deviceNumber = read24(request, 19);
            model.deviceCode = resolveDeviceCode(request[22] & 0xFF);
            model.unitCount = readUInt16(request, 23);
            model.payload = slice(request, 25, request.length);
        } else {
            model.unitCount = request[17] & 0xFF;
            model.payload = slice(request, 19, request.length);
        }
        return model;
    }

    private RequestModel parse3eAsciiRequest(byte[] request) {
        RequestModel model = new RequestModel(FrameType.ASCII_3E);
        model.command = parseAsciiHex(request, 22, 4);
        if (model.command == 0x0401 || model.command == 0x1401) {
            model.subcommand = parseAsciiHex(request, 26, 4);
            model.bitUnit = model.subcommand == 0x0001;
            model.deviceNumber = parseAsciiDeviceNumber(new String(request, 30, 6, StandardCharsets.US_ASCII));
            model.deviceCode = McAsciiCodecSupport.parseDeviceCodeText(new String(request, 36, 2, StandardCharsets.US_ASCII));
            model.unitCount = parseAsciiHex(request, 38, 4);
            model.payload = slice(request, 42, request.length);
        } else {
            model.unitCount = parseAsciiHex(request, 26, 2);
            model.payload = slice(request, 28, request.length);
        }
        return model;
    }

    private byte[] buildRandomReadResponse(RequestModel model) {
        if (model.frameType == FrameType.ASCII_3E) {
            int offset = 0;
            StringBuilder payload = new StringBuilder();
            for (int i = 0; i < model.unitCount; i++) {
                int deviceNumber = parseAsciiDeviceNumber(new String(model.payload, offset, 6, StandardCharsets.US_ASCII));
                McDeviceCode deviceCode = McAsciiCodecSupport.parseDeviceCodeText(new String(model.payload, offset + 6, 2, StandardCharsets.US_ASCII));
                byte[] wordPayload = memoryModel.readWords(deviceCode, deviceNumber, 1);
                payload.append(new String(McAsciiCodecSupport.encodeWritePayload(
                        new McAddress("D0", "D0", deviceCode, deviceNumber, McDriverType.UINT16, 1, null, null),
                        wordPayload), StandardCharsets.US_ASCII));
                offset += 8;
            }
            return buildResponse(model, payload.toString().getBytes(StandardCharsets.US_ASCII), forcedEndCode != null ? forcedEndCode : 0);
        }

        byte[] payload = new byte[model.unitCount * 2];
        int offset = 0;
        int responseOffset = 0;
        for (int i = 0; i < model.unitCount; i++) {
            int deviceNumber = read24(model.payload, offset);
            McDeviceCode deviceCode = resolveDeviceCode(model.payload[offset + 3] & 0xFF);
            byte[] wordPayload = memoryModel.readWords(deviceCode, deviceNumber, 1);
            System.arraycopy(wordPayload, 0, payload, responseOffset, wordPayload.length);
            offset += 4;
            responseOffset += 2;
        }
        return buildResponse(model, payload, forcedEndCode != null ? forcedEndCode : 0);
    }

    private byte[] buildRandomWriteResponse(RequestModel model) {
        if (forcedEndCode == null || forcedEndCode == 0) {
            if (model.frameType == FrameType.ASCII_3E) {
                int offset = 0;
                for (int i = 0; i < model.unitCount; i++) {
                    int deviceNumber = parseAsciiDeviceNumber(new String(model.payload, offset, 6, StandardCharsets.US_ASCII));
                    McDeviceCode deviceCode = McAsciiCodecSupport.parseDeviceCodeText(new String(model.payload, offset + 6, 2, StandardCharsets.US_ASCII));
                    byte[] payload = slice(model.payload, offset + 8, offset + 12);
                    byte[] binaryPayload = McAsciiCodecSupport.decodeReadPayload(
                            new McAddress("D0", "D0", deviceCode, deviceNumber, McDriverType.UINT16, 1, null, null),
                            payload);
                    memoryModel.writeWords(deviceCode, deviceNumber, 1, binaryPayload);
                    offset += 12;
                }
            } else {
                int offset = 0;
                for (int i = 0; i < model.unitCount; i++) {
                    int deviceNumber = read24(model.payload, offset);
                    McDeviceCode deviceCode = resolveDeviceCode(model.payload[offset + 3] & 0xFF);
                    byte[] payload = new byte[]{model.payload[offset + 4], model.payload[offset + 5]};
                    memoryModel.writeWords(deviceCode, deviceNumber, 1, payload);
                    offset += 6;
                }
            }
        }
        return buildResponse(model, new byte[0], forcedEndCode != null ? forcedEndCode : 0);
    }

    private byte[] buildWriteResponse(RequestModel model) {
        if (forcedEndCode == null || forcedEndCode == 0) {
            if (model.bitUnit) {
                byte[] bitPayload = model.frameType == FrameType.ASCII_3E
                        ? McAsciiCodecSupport.decodeReadPayload(
                        new McAddress("M0", "M0", model.deviceCode, model.deviceNumber, McDriverType.BOOL, model.unitCount, null, null),
                        model.payload)
                        : model.payload;
                memoryModel.writeBits(model.deviceCode, model.deviceNumber, model.unitCount, bitPayload);
            } else {
                byte[] wordPayload = model.frameType == FrameType.ASCII_3E
                        ? decodeAsciiWordWritePayload(model.deviceCode, model.deviceNumber, model.unitCount, model.payload)
                        : model.payload;
                memoryModel.writeWords(model.deviceCode, model.deviceNumber, model.unitCount, wordPayload);
            }
        }
        return buildResponse(model, new byte[0], forcedEndCode != null ? forcedEndCode : 0);
    }

    private byte[] buildReadResponse(RequestModel model, byte[] binaryPayload) {
        byte[] payload = binaryPayload;
        if (model.frameType == FrameType.ASCII_3E) {
            McAddress address = new McAddress("A0", "A0", model.deviceCode, model.deviceNumber,
                    model.bitUnit ? McDriverType.BOOL : McDriverType.UINT16,
                    model.bitUnit ? model.unitCount : model.unitCount,
                    null, null);
            payload = model.bitUnit
                    ? McAsciiCodecSupport.encodeWritePayload(address, binaryPayload)
                    : McAsciiCodecSupport.encodeWritePayload(address, binaryPayload);
        }
        return buildResponse(model, payload, forcedEndCode != null ? forcedEndCode : 0);
    }

    private byte[] buildResponse(RequestModel model, byte[] payload, int endCode) {
        byte[] safePayload = payload != null ? payload : new byte[0];
        return switch (model.frameType) {
            case BINARY_3E -> build3eBinaryResponse(safePayload, endCode);
            case BINARY_4E -> build4eBinaryResponse(safePayload, endCode, model.serialNo);
            case ASCII_3E -> build3eAsciiResponse(safePayload, endCode);
        };
    }

    private byte[] build3eBinaryResponse(byte[] payload, int endCode) {
        int declaredLength = 2 + payload.length;
        int encodedLength = forceLengthMismatch ? declaredLength + 1 : declaredLength;
        byte[] response = new byte[HEADER_3E_BINARY + declaredLength];
        response[0] = forceUnexpectedSubheader ? (byte) 0x00 : (byte) 0xD0;
        response[1] = 0x00;
        response[7] = (byte) (encodedLength & 0xFF);
        response[8] = (byte) ((encodedLength >> 8) & 0xFF);
        response[9] = (byte) (endCode & 0xFF);
        response[10] = (byte) ((endCode >> 8) & 0xFF);
        System.arraycopy(payload, 0, response, 11, payload.length);
        return response;
    }

    private byte[] build4eBinaryResponse(byte[] payload, int endCode, int serialNo) {
        int declaredLength = 2 + payload.length;
        int encodedLength = forceLengthMismatch ? declaredLength + 1 : declaredLength;
        byte[] response = new byte[HEADER_4E_BINARY + declaredLength];
        response[0] = forceUnexpectedSubheader ? (byte) 0x00 : (byte) 0xD4;
        response[1] = 0x00;
        int effectiveSerial = force4eSerialMismatch ? ((serialNo + 1) & 0xFFFF) : serialNo;
        response[2] = (byte) (effectiveSerial & 0xFF);
        response[3] = (byte) ((effectiveSerial >> 8) & 0xFF);
        response[11] = (byte) (encodedLength & 0xFF);
        response[12] = (byte) ((encodedLength >> 8) & 0xFF);
        response[13] = (byte) (endCode & 0xFF);
        response[14] = (byte) ((endCode >> 8) & 0xFF);
        System.arraycopy(payload, 0, response, 15, payload.length);
        return response;
    }

    private byte[] build3eAsciiResponse(byte[] payload, int endCode) {
        String payloadText = new String(payload, StandardCharsets.US_ASCII);
        int declaredLength = 4 + payloadText.length();
        int encodedLength = forceLengthMismatch ? declaredLength + 1 : declaredLength;
        String response = (forceUnexpectedSubheader ? "0000" : "D000")
                + "00"
                + "FF"
                + "03FF"
                + "00"
                + String.format("%04X", encodedLength)
                + String.format("%04X", endCode)
                + payloadText;
        return response.getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] read3eBinaryRequest(InputStream inputStream, byte firstByte) throws Exception {
        byte[] remainder = readFully(inputStream, HEADER_3E_BINARY - 1);
        byte[] header = new byte[HEADER_3E_BINARY];
        header[0] = firstByte;
        System.arraycopy(remainder, 0, header, 1, remainder.length);
        int declaredLength = readUInt16(header, 7);
        byte[] body = readFully(inputStream, declaredLength);
        byte[] request = new byte[HEADER_3E_BINARY + declaredLength];
        System.arraycopy(header, 0, request, 0, HEADER_3E_BINARY);
        System.arraycopy(body, 0, request, HEADER_3E_BINARY, declaredLength);
        return request;
    }

    private byte[] read4eBinaryRequest(InputStream inputStream, byte firstByte) throws Exception {
        byte[] remainder = readFully(inputStream, HEADER_4E_BINARY - 1);
        byte[] header = new byte[HEADER_4E_BINARY];
        header[0] = firstByte;
        System.arraycopy(remainder, 0, header, 1, remainder.length);
        int declaredLength = readUInt16(header, 11);
        byte[] body = readFully(inputStream, declaredLength);
        byte[] request = new byte[HEADER_4E_BINARY + declaredLength];
        System.arraycopy(header, 0, request, 0, HEADER_4E_BINARY);
        System.arraycopy(body, 0, request, HEADER_4E_BINARY, declaredLength);
        return request;
    }

    private byte[] read3eAsciiRequest(InputStream inputStream, byte firstByte) throws Exception {
        byte[] remainder = readFully(inputStream, HEADER_3E_ASCII - 1);
        byte[] header = new byte[HEADER_3E_ASCII];
        header[0] = firstByte;
        System.arraycopy(remainder, 0, header, 1, remainder.length);
        int declaredLength = Integer.parseInt(new String(header, 14, 4, StandardCharsets.US_ASCII), 16);
        byte[] body = readFully(inputStream, declaredLength);
        byte[] request = new byte[HEADER_3E_ASCII + declaredLength];
        System.arraycopy(header, 0, request, 0, HEADER_3E_ASCII);
        System.arraycopy(body, 0, request, HEADER_3E_ASCII, declaredLength);
        return request;
    }

    private McDeviceCode resolveDeviceCode(int code) {
        for (McDeviceCode value : McDeviceCode.values()) {
            if (value.getCode() == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported fake MC device code: 0x" + Integer.toHexString(code));
    }

    private byte[] readFully(InputStream inputStream, int length) throws Exception {
        byte[] target = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = inputStream.read(target, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Fake MC socket closed");
            }
            offset += read;
        }
        return target;
    }

    private int readUInt16(byte[] payload, int offset) {
        return (payload[offset] & 0xFF) | ((payload[offset + 1] & 0xFF) << 8);
    }

    private int read24(byte[] payload, int offset) {
        return (payload[offset] & 0xFF)
                | ((payload[offset + 1] & 0xFF) << 8)
                | ((payload[offset + 2] & 0xFF) << 16);
    }

    private int parseAsciiHex(byte[] payload, int offset, int width) {
        return Integer.parseInt(new String(payload, offset, width, StandardCharsets.US_ASCII), 16);
    }

    private int parseAsciiDeviceNumber(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String normalized = text.trim().toUpperCase();
        if (normalized.matches(".*[A-F].*")) {
            return Integer.parseInt(normalized, 16);
        }
        return Integer.parseInt(normalized, 10);
    }

    private byte[] slice(byte[] payload, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, payload.length));
        int safeEnd = Math.max(safeStart, Math.min(end, payload.length));
        byte[] target = new byte[safeEnd - safeStart];
        System.arraycopy(payload, safeStart, target, 0, target.length);
        return target;
    }

    private byte[] decodeAsciiWordWritePayload(McDeviceCode deviceCode,
                                               int deviceNumber,
                                               int unitCount,
                                               byte[] payload) {
        McAddress address = new McAddress("D0[" + unitCount + "]", "D0[" + unitCount + "]",
                deviceCode, deviceNumber, McDriverType.UINT16, unitCount, null, null);
        return McAsciiCodecSupport.decodeReadPayload(address, payload);
    }

    private void verifyNoAsyncFailure() {
        RuntimeException failure = asyncFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        running = false;
        try {
            serverSocket.close();
        } finally {
            if (acceptThread != null) {
                acceptThread.join(2000);
            }
        }
    }

    private enum FrameType {
        BINARY_3E,
        ASCII_3E,
        BINARY_4E
    }

    private static final class RequestModel {
        private final FrameType frameType;
        private int command;
        private int subcommand;
        private boolean bitUnit;
        private McDeviceCode deviceCode;
        private int deviceNumber;
        private int unitCount;
        private int serialNo;
        private byte[] payload = new byte[0];

        private RequestModel(FrameType frameType) {
            this.frameType = frameType;
        }
    }
}
