package com.wangbin.collector.core.connection.serial;

import com.fazecast.jSerialComm.SerialPort;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 jSerialComm 的串口字节通道。
 */
@Slf4j
public class JSerialCommSerialChannel implements SerialChannel {

    private final SerialEndpoint endpoint;
    private volatile SerialPort serialPort;

    /**
     * 创建当前组件实例。
     */
    public JSerialCommSerialChannel(SerialEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public synchronized void open() {
        if (isOpen()) {
            return;
        }
        SerialPort port = SerialPort.getCommPort(endpoint.portName());
        port.setComPortParameters(endpoint.baudRate(), endpoint.dataBits(), resolveStopBits(), resolveParity());
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                endpoint.readTimeoutMs(), endpoint.writeTimeoutMs());
        if (!port.openPort()) {
            throw new IllegalStateException("打开串口失败: " + endpoint.portName());
        }
        serialPort = port;
        log.info("串口已打开，端口={}，波特率={}", endpoint.portName(), endpoint.baudRate());
    }

    @Override
    public boolean isOpen() {
        SerialPort port = serialPort;
        return port != null && port.isOpen();
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    public synchronized void write(byte[] data) {
        ensureOpen();
        if (data == null || data.length == 0) {
            return;
        }
        int written = (int) serialPort.writeBytes(data, data.length);
        if (written != data.length) {
            throw new IllegalStateException("串口写入不完整，期望=" + data.length + "，实际=" + written);
        }
    }

    /**
     * 查询并返回业务数据。
     */
    @Override
    public synchronized int read(byte[] buffer, int offset, int length, long timeoutMs) {
        ensureOpen();
        if (buffer == null || length <= 0) {
            return 0;
        }
        int timeout = timeoutMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) timeoutMs);
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                timeout, endpoint.writeTimeoutMs());
        byte[] target = offset == 0 && length == buffer.length ? buffer : new byte[length];
        int count = (int) serialPort.readBytes(target, length);
        if (count > 0 && target != buffer) {
            System.arraycopy(target, 0, buffer, offset, count);
        }
        return count;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public synchronized void close() {
        SerialPort port = serialPort;
        serialPort = null;
        if (port != null && port.isOpen()) {
            port.closePort();
            log.info("串口已关闭，端口={}", endpoint.portName());
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveStopBits() {
        return endpoint.stopBits() == 2 ? SerialPort.TWO_STOP_BITS : SerialPort.ONE_STOP_BIT;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveParity() {
        return switch (endpoint.parity()) {
            case "EVEN" -> SerialPort.EVEN_PARITY;
            case "ODD" -> SerialPort.ODD_PARITY;
            default -> SerialPort.NO_PARITY;
        };
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void ensureOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("串口尚未打开: " + endpoint.portName());
        }
    }
}
