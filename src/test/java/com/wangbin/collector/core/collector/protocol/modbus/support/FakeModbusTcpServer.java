package com.wangbin.collector.core.collector.protocol.modbus.support;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用于验收 PLC4X Modbus TCP 链路的最小标准从站。
 */
public final class FakeModbusTcpServer implements AutoCloseable {

    private static final int MAX_REGISTER_ADDRESS = 65535;
    private static final int READ_HOLDING_REGISTERS = 0x03;
    private static final int WRITE_SINGLE_REGISTER = 0x06;
    private static final int WRITE_MULTIPLE_REGISTERS = 0x10;

    private final int[] holdingRegisters = new int[MAX_REGISTER_ADDRESS + 1];
    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger readRequestCount = new AtomicInteger();
    private final AtomicInteger writeRequestCount = new AtomicInteger();
    private final AtomicReference<RuntimeException> asyncFailure = new AtomicReference<>();
    private final Set<Socket> clientSockets = ConcurrentHashMap.newKeySet();
    private final List<Thread> clientThreads = new CopyOnWriteArrayList<>();
    private final Thread acceptThread;

    public FakeModbusTcpServer() throws Exception {
        this.serverSocket = new ServerSocket(0);
        this.acceptThread = new Thread(this::acceptLoop, "modbus-test-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public int readRequestCount() {
        verifyNoAsyncFailure();
        return readRequestCount.get();
    }

    public int writeRequestCount() {
        verifyNoAsyncFailure();
        return writeRequestCount.get();
    }

    public void setHoldingRegister(int oneBasedAddress, int value) {
        holdingRegisters[toProtocolAddress(oneBasedAddress)] = value & 0xFFFF;
    }

    public int getHoldingRegister(int oneBasedAddress) {
        verifyNoAsyncFailure();
        return holdingRegisters[toProtocolAddress(oneBasedAddress)];
    }

    public void incrementHoldingRegisters(int startOneBasedAddress, int endOneBasedAddress, int delta) {
        if (endOneBasedAddress < startOneBasedAddress) {
            throw new IllegalArgumentException("结束寄存器不能小于开始寄存器");
        }
        for (int address = startOneBasedAddress; address <= endOneBasedAddress; address++) {
            int protocolAddress = toProtocolAddress(address);
            holdingRegisters[protocolAddress] = (holdingRegisters[protocolAddress] + delta) & 0xFFFF;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                clientSockets.add(socket);
                Thread clientThread = new Thread(() -> handleClient(socket),
                        "modbus-test-client-" + socket.getPort());
                clientThread.setDaemon(true);
                clientThreads.add(clientThread);
                clientThread.start();
            } catch (SocketException exception) {
                if (running.get()) {
                    recordFailure("Modbus 测试从站监听异常", exception);
                }
            } catch (Exception exception) {
                recordFailure("Modbus 测试从站接受连接失败", exception);
            }
        }
    }

    private void handleClient(Socket socket) {
        try (socket;
             DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            while (running.get() && !socket.isClosed()) {
                RequestFrame request = readRequest(input);
                byte[] responsePdu = handleRequest(request.pdu());
                writeResponse(output, request, responsePdu);
            }
        } catch (EOFException | SocketException ignored) {
            // 客户端正常断开时结束当前会话。
        } catch (Exception exception) {
            if (running.get()) {
                recordFailure("Modbus 测试从站处理请求失败", exception);
            }
        } finally {
            clientSockets.remove(socket);
        }
    }

    private RequestFrame readRequest(DataInputStream input) throws Exception {
        int transactionId = input.readUnsignedShort();
        int protocolId = input.readUnsignedShort();
        int length = input.readUnsignedShort();
        int unitId = input.readUnsignedByte();
        if (protocolId != 0 || length < 2) {
            throw new IllegalArgumentException("非法 Modbus TCP 报文头");
        }
        byte[] pdu = input.readNBytes(length - 1);
        if (pdu.length != length - 1) {
            throw new EOFException("Modbus TCP 请求报文不完整");
        }
        return new RequestFrame(transactionId, unitId, pdu);
    }

    private byte[] handleRequest(byte[] pdu) {
        if (pdu.length < 5) {
            throw new IllegalArgumentException("Modbus TCP 请求 PDU 长度不足");
        }
        int functionCode = pdu[0] & 0xFF;
        return switch (functionCode) {
            case READ_HOLDING_REGISTERS -> readHoldingRegisters(pdu);
            case WRITE_SINGLE_REGISTER -> writeSingleRegister(pdu);
            case WRITE_MULTIPLE_REGISTERS -> writeMultipleRegisters(pdu);
            default -> exceptionResponse(functionCode, 0x01);
        };
    }

    private byte[] readHoldingRegisters(byte[] pdu) {
        int startAddress = readUnsignedShort(pdu, 1);
        int quantity = readUnsignedShort(pdu, 3);
        validateRange(startAddress, quantity);
        readRequestCount.incrementAndGet();
        byte[] response = new byte[2 + quantity * 2];
        response[0] = (byte) READ_HOLDING_REGISTERS;
        response[1] = (byte) (quantity * 2);
        for (int index = 0; index < quantity; index++) {
            writeUnsignedShort(response, 2 + index * 2, holdingRegisters[startAddress + index]);
        }
        return response;
    }

    private byte[] writeSingleRegister(byte[] pdu) {
        int address = readUnsignedShort(pdu, 1);
        validateRange(address, 1);
        holdingRegisters[address] = readUnsignedShort(pdu, 3);
        writeRequestCount.incrementAndGet();
        return pdu.clone();
    }

    private byte[] writeMultipleRegisters(byte[] pdu) {
        int startAddress = readUnsignedShort(pdu, 1);
        int quantity = readUnsignedShort(pdu, 3);
        validateRange(startAddress, quantity);
        int byteCount = pdu[5] & 0xFF;
        if (pdu.length != 6 + byteCount || byteCount != quantity * 2) {
            throw new IllegalArgumentException("Modbus 多寄存器写入报文长度不一致");
        }
        for (int index = 0; index < quantity; index++) {
            holdingRegisters[startAddress + index] = readUnsignedShort(pdu, 6 + index * 2);
        }
        writeRequestCount.incrementAndGet();
        byte[] response = new byte[5];
        response[0] = (byte) WRITE_MULTIPLE_REGISTERS;
        writeUnsignedShort(response, 1, startAddress);
        writeUnsignedShort(response, 3, quantity);
        return response;
    }

    private byte[] exceptionResponse(int functionCode, int exceptionCode) {
        return new byte[]{(byte) (functionCode | 0x80), (byte) exceptionCode};
    }

    private void writeResponse(DataOutputStream output, RequestFrame request, byte[] pdu) throws Exception {
        output.writeShort(request.transactionId());
        output.writeShort(0);
        output.writeShort(pdu.length + 1);
        output.writeByte(request.unitId());
        output.write(pdu);
        output.flush();
    }

    private int toProtocolAddress(int oneBasedAddress) {
        if (oneBasedAddress <= 0 || oneBasedAddress > MAX_REGISTER_ADDRESS + 1) {
            throw new IllegalArgumentException("寄存器地址超出范围: " + oneBasedAddress);
        }
        return oneBasedAddress - 1;
    }

    private void validateRange(int startAddress, int quantity) {
        if (startAddress < 0 || quantity <= 0 || startAddress + quantity > holdingRegisters.length) {
            throw new IllegalArgumentException("寄存器访问范围非法");
        }
    }

    private int readUnsignedShort(byte[] payload, int offset) {
        return ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
    }

    private void writeUnsignedShort(byte[] payload, int offset, int value) {
        payload[offset] = (byte) ((value >> 8) & 0xFF);
        payload[offset + 1] = (byte) (value & 0xFF);
    }

    private void recordFailure(String message, Exception exception) {
        asyncFailure.compareAndSet(null, new RuntimeException(message, exception));
    }

    private void verifyNoAsyncFailure() {
        RuntimeException failure = asyncFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        running.set(false);
        serverSocket.close();
        for (Socket socket : clientSockets) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 关闭阶段忽略已经断开的客户端。
            }
        }
        acceptThread.join(2000);
        for (Thread clientThread : clientThreads) {
            clientThread.join(2000);
        }
        verifyNoAsyncFailure();
    }

    private record RequestFrame(int transactionId, int unitId, byte[] pdu) {
    }
}
