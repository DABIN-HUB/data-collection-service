package com.wangbin.collector.core.collector.protocol.bacnet.transport;

import com.fazecast.jSerialComm.SerialPort;
import com.wangbin.collector.common.enums.Parity;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JSerialCommBacnetSerialChannel implements BacnetSerialChannel {

    private final String serialPortName;
    private final int baudRate;
    private final int dataBits;
    private final int stopBits;
    private final String parity;
    private final int readTimeoutMs;
    private final int writeTimeoutMs;

    private volatile SerialPort serialPort;

    public JSerialCommBacnetSerialChannel(String serialPortName,
                                          int baudRate,
                                          int dataBits,
                                          int stopBits,
                                          String parity,
                                          int readTimeoutMs,
                                          int writeTimeoutMs) {
        this.serialPortName = serialPortName;
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        this.readTimeoutMs = readTimeoutMs;
        this.writeTimeoutMs = writeTimeoutMs;
    }

    @Override
    public synchronized void open() {
        if (serialPort != null && serialPort.isOpen()) {
            return;
        }
        if (serialPortName == null || serialPortName.isBlank()) {
            throw new IllegalStateException("BACnet MS/TP serialPort is required");
        }
        SerialPort port = SerialPort.getCommPort(serialPortName);
        port.setComPortParameters(baudRate, dataBits, resolveStopBits(), resolveParity());
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                readTimeoutMs,
                writeTimeoutMs);
        if (!port.openPort()) {
            throw new IllegalStateException("Open BACnet MS/TP serial port failed: " + serialPortName);
        }
        serialPort = port;
        log.info("BACnet MS/TP serial port opened, port={}, baudRate={}", serialPortName, baudRate);
    }

    @Override
    public boolean isOpen() {
        return serialPort != null && serialPort.isOpen();
    }

    @Override
    public synchronized void write(byte[] data) {
        ensureOpen();
        if (data == null || data.length == 0) {
            return;
        }
        int written = (int) serialPort.writeBytes(data, data.length);
        if (written != data.length) {
            throw new IllegalStateException("BACnet MS/TP serial write incomplete: expected="
                    + data.length + ", actual=" + written);
        }
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length, long timeoutMs) {
        ensureOpen();
        if (buffer == null || length <= 0) {
            return 0;
        }
        int resolvedTimeout = timeoutMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) timeoutMs);
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                resolvedTimeout,
                writeTimeoutMs);
        byte[] target = offset == 0 && length == buffer.length ? buffer : new byte[length];
        int read = (int) serialPort.readBytes(target, length);
        if (read > 0 && target != buffer) {
            System.arraycopy(target, 0, buffer, offset, read);
        }
        return read;
    }

    @Override
    public synchronized void close() {
        SerialPort port = serialPort;
        serialPort = null;
        if (port != null && port.isOpen()) {
            port.closePort();
            log.info("BACnet MS/TP serial port closed, port={}", serialPortName);
        }
    }

    private int resolveStopBits() {
        return stopBits == 2 ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT;
    }

    private int resolveParity() {
        if (parity == null || parity.isBlank()) {
            return SerialPort.NO_PARITY;
        }
        try {
            return switch (Parity.fromName(parity.trim().toLowerCase())) {
                case even -> SerialPort.EVEN_PARITY;
                case odd -> SerialPort.ODD_PARITY;
                case none -> SerialPort.NO_PARITY;
            };
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown serial parity '{}', fallback to NO_PARITY", parity);
            return SerialPort.NO_PARITY;
        }
    }

    private void ensureOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("BACnet MS/TP serial port is not open");
        }
    }
}