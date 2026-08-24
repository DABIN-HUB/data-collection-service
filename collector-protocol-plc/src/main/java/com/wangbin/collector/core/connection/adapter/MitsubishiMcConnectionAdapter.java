package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class MitsubishiMcConnectionAdapter extends AbstractConnectionAdapter<Socket> {

    private static final int HEADER_LENGTH = 9;
    private static final int ASCII_HEADER_LENGTH = 18;
    private static final int HEADER_4E_LENGTH = 13;

    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    /**
     * 创建当前组件实例。
     */
    public MitsubishiMcConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        String host = resolveHost();
        Integer port = resolvePort();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Invalid Mitsubishi MC connection host");
        }
        int resolvedPort = port != null && port > 0 ? port : 5000;
        int readTimeout = resolveReadTimeout();

        log.info("正在连接 Mitsubishi MC 套接字, 主机={}, 端口={}, connectTimeout={}, readTimeout={}",
                host, resolvedPort, config.getConnectTimeout(), readTimeout);

        socket = new Socket();
        socket.setKeepAlive(Boolean.TRUE.equals(config.getKeepAlive()));
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, resolvedPort), config.getConnectTimeout());
        socket.setSoTimeout(readTimeout);
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        setConnectionParam("host", host);
        setConnectionParam("port", resolvedPort);
        log.info("Mitsubishi MC 连接 已创建:{}:{}", host, resolvedPort);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } finally {
            inputStream = null;
        }
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } finally {
            outputStream = null;
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } finally {
            socket = null;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            throw new IllegalStateException("Mitsubishi MC socket is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // MC over TCP has no separate 认证 phase in this 采集器.
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSend(byte[] data) {
        try {
            if (outputStream == null) {
                throw new IllegalStateException("Mitsubishi MC output stream is not initialized");
            }
            outputStream.write(data);
            outputStream.flush();
        } catch (Exception e) {
            throw new IllegalStateException("Mitsubishi MC send failed", e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected byte[] doReceive() {
        try {
            return readFrame(resolveReadTimeout());
        } catch (Exception e) {
            throw new IllegalStateException("Mitsubishi MC receive failed", e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected byte[] doReceive(long timeout) {
        try {
            return readFrame(timeout > 0 ? (int) timeout : resolveReadTimeout());
        } catch (Exception e) {
            throw new IllegalStateException("Mitsubishi MC receive failed", e);
        }
    }

    @Override
    public Socket getClient() {
        return socket;
    }

    @Override
    public boolean isConnected() {
        return super.isConnected() && socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * 执行当前业务逻辑。
     */
    public synchronized byte[] exchange(byte[] request, long timeoutMs) throws Exception {
        send(request);
        return receive(timeoutMs);
    }

    /**
     * 查询并返回业务数据。
     */
    private byte[] readFrame(int timeoutMs) throws Exception {
        if (socket == null || inputStream == null) {
            throw new IllegalStateException("Mitsubishi MC socket is not initialized");
        }
        socket.setSoTimeout(timeoutMs);
        int first = inputStream.read();
        if (first < 0) {
            throw new EOFException("Mitsubishi MC socket closed while reading response");
        }
        return switch (first & 0xFF) {
            case 0xD0 -> read3eBinaryFrame((byte) first);
            case 0xD4 -> read4eBinaryFrame((byte) first);
            case 'D' -> read3eAsciiFrame((byte) first);
            default -> throw new IllegalArgumentException(String.format("Unexpected Mitsubishi MC response header byte: 0x%02X", first & 0xFF));
        };
    }

    /**
     * 查询并返回业务数据。
     */
    private byte[] read3eBinaryFrame(byte firstByte) throws Exception {
        byte[] remainder = readFully(HEADER_LENGTH - 1);
        byte[] header = new byte[HEADER_LENGTH];
        header[0] = firstByte;
        System.arraycopy(remainder, 0, header, 1, remainder.length);
        int declaredLength = (header[7] & 0xFF) | ((header[8] & 0xFF) << 8);
        if (declaredLength < 2) {
            log.warn("Mitsubishi MC response declared length is 异常, length={}", declaredLength);
        }
        byte[] body = readFully(declaredLength);
        byte[] frame = new byte[HEADER_LENGTH + declaredLength];
        System.arraycopy(header, 0, frame, 0, HEADER_LENGTH);
        System.arraycopy(body, 0, frame, HEADER_LENGTH, declaredLength);
        return frame;
    }

    /**
     * 查询并返回业务数据。
     */
    private byte[] read4eBinaryFrame(byte firstByte) throws Exception {
        byte[] remainder = readFully(HEADER_4E_LENGTH - 1);
        byte[] header = new byte[HEADER_4E_LENGTH];
        header[0] = firstByte;
        System.arraycopy(remainder, 0, header, 1, remainder.length);
        int declaredLength = (header[11] & 0xFF) | ((header[12] & 0xFF) << 8);
        if (declaredLength < 2) {
            log.warn("Mitsubishi MC 4E response declared length is 异常, length={}", declaredLength);
        }
        byte[] body = readFully(declaredLength);
        byte[] frame = new byte[HEADER_4E_LENGTH + declaredLength];
        System.arraycopy(header, 0, frame, 0, HEADER_4E_LENGTH);
        System.arraycopy(body, 0, frame, HEADER_4E_LENGTH, declaredLength);
        return frame;
    }

    /**
     * 查询并返回业务数据。
     */
    private byte[] read3eAsciiFrame(byte firstByte) throws Exception {
        byte[] remainder = readFully(ASCII_HEADER_LENGTH - 1);
        byte[] header = new byte[ASCII_HEADER_LENGTH];
        header[0] = firstByte;
        System.arraycopy(remainder, 0, header, 1, remainder.length);
        int declaredLength = Integer.parseInt(new String(header, 14, 4, java.nio.charset.StandardCharsets.US_ASCII), 16);
        if (declaredLength < 4) {
            log.warn("Mitsubishi MC ASCII response declared length is 异常, length={}", declaredLength);
        }
        byte[] body = readFully(declaredLength);
        byte[] frame = new byte[ASCII_HEADER_LENGTH + declaredLength];
        System.arraycopy(header, 0, frame, 0, ASCII_HEADER_LENGTH);
        System.arraycopy(body, 0, frame, ASCII_HEADER_LENGTH, declaredLength);
        return frame;
    }

    /**
     * 查询并返回业务数据。
     */
    private byte[] readFully(int length) throws Exception {
        byte[] target = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = inputStream.read(target, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Mitsubishi MC socket closed while reading response");
            }
            offset += read;
        }
        return target;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveReadTimeout() {
        Integer readTimeout = config.getReadTimeout();
        if (readTimeout != null && readTimeout > 0) {
            return readTimeout;
        }
        Integer timeout = config.getTimeout();
        if (timeout != null && timeout > 0) {
            return timeout;
        }
        return 5000;
    }
}
