package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import lombok.extern.slf4j.Slf4j;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class MitsubishiMcConnectionAdapter extends AbstractConnectionAdapter<Socket> {

    private static final int HEADER_LENGTH = 9;
    private static final int ASCII_HEADER_LENGTH = 18;
    private static final int HEADER_4E_LENGTH = 13;
    // Official MC E71 maxima (including ASCII bit replies) fit below this bound;
    // current configured batches are considerably smaller.
    private static final int MAX_FRAME_SIZE = 8192;

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
        Socket active = socket;
        socket = null;
        inputStream = null;
        outputStream = null;
        // Closing the socket also closes both streams; never let a stream-close failure
        // prevent the underlying TCP session from being closed.
        if (active != null) {
            active.close();
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
            return readFrame(timeout > 0 ? (int) Math.min(timeout, Integer.MAX_VALUE) : resolveReadTimeout());
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
        long budgetMs = timeoutMs > 0 ? timeoutMs : resolveReadTimeout();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        try {
            send(request);
            long remaining = TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadline - System.nanoTime()));
            if (remaining <= 0) {
                throw new SocketTimeoutException("Mitsubishi MC exchange timed out before response");
            }
            byte[] response = receive(remaining);
            if (request != null && request.length > 0 && response.length > 0
                    && !matchesFrameType(request[0], response[0])) {
                throw new IOException("Unexpected Mitsubishi MC response frame type");
            }
            return response;
        } catch (Exception failure) {
            invalidateSession(failure);
            throw failure;
        }
    }

    private boolean matchesFrameType(byte requestFirst, byte responseFirst) {
        return switch (requestFirst & 0xFF) {
            case 0x50 -> (responseFirst & 0xFF) == 0xD0;
            case 0x54 -> (responseFirst & 0xFF) == 0xD4;
            case '5' -> responseFirst == 'D';
            default -> false;
        };
    }

    private void invalidateSession(Exception failure) {
        try {
            doDisconnect();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        } finally {
            status = ConnectionStatus.ERROR;
            metrics.setStatus(status);
            metrics.setLastError(failure.getMessage());
        }
    }

    /**
     * 查询并返回业务数据。
     */
    private byte[] readFrame(int timeoutMs) throws Exception {
        if (socket == null || inputStream == null) {
            throw new IllegalStateException("Mitsubishi MC socket is not initialized");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int first = readByte(deadline);
        return switch (first & 0xFF) {
            case 0xD0 -> read3eBinaryFrame((byte) first, deadline);
            case 0xD4 -> read4eBinaryFrame((byte) first, deadline);
            case 'D' -> read3eAsciiFrame((byte) first, deadline);
            default -> throw new IOException(String.format("Unexpected Mitsubishi MC response header byte: 0x%02X", first & 0xFF));
        };
    }

    /**
     * 查询并返回业务数据。
     */
    private byte[] read3eBinaryFrame(byte firstByte, long deadline) throws Exception {
        byte[] remainder = readFully(HEADER_LENGTH - 1, deadline);
        byte[] header = new byte[HEADER_LENGTH];
        header[0] = firstByte;
        System.arraycopy(remainder, 0, header, 1, remainder.length);
        if (header[1] != 0) {
            throw new IOException("Unexpected Mitsubishi MC 3E response subheader");
        }
        int declaredLength = (header[7] & 0xFF) | ((header[8] & 0xFF) << 8);
        validateFrameLength(HEADER_LENGTH, declaredLength, 2);
        byte[] body = readFully(declaredLength, deadline);
        byte[] frame = new byte[HEADER_LENGTH + declaredLength];
        System.arraycopy(header, 0, frame, 0, HEADER_LENGTH);
        System.arraycopy(body, 0, frame, HEADER_LENGTH, declaredLength);
        return frame;
    }

    /**
     * 查询并返回业务数据。
     */
    private byte[] read4eBinaryFrame(byte firstByte, long deadline) throws Exception {
        byte[] remainder = readFully(HEADER_4E_LENGTH - 1, deadline);
        byte[] header = new byte[HEADER_4E_LENGTH];
        header[0] = firstByte;
        System.arraycopy(remainder, 0, header, 1, remainder.length);
        if (header[1] != 0 || header[4] != 0 || header[5] != 0) {
            throw new IOException("Unexpected Mitsubishi MC 4E response subheader");
        }
        int declaredLength = (header[11] & 0xFF) | ((header[12] & 0xFF) << 8);
        validateFrameLength(HEADER_4E_LENGTH, declaredLength, 2);
        byte[] body = readFully(declaredLength, deadline);
        byte[] frame = new byte[HEADER_4E_LENGTH + declaredLength];
        System.arraycopy(header, 0, frame, 0, HEADER_4E_LENGTH);
        System.arraycopy(body, 0, frame, HEADER_4E_LENGTH, declaredLength);
        return frame;
    }

    /**
     * 查询并返回业务数据。
     */
    private byte[] read3eAsciiFrame(byte firstByte, long deadline) throws Exception {
        byte[] remainder = readFully(ASCII_HEADER_LENGTH - 1, deadline);
        byte[] header = new byte[ASCII_HEADER_LENGTH];
        header[0] = firstByte;
        System.arraycopy(remainder, 0, header, 1, remainder.length);
        if (header[1] != '0' || header[2] != '0' || header[3] != '0') {
            throw new IOException("Unexpected Mitsubishi MC ASCII response subheader");
        }
        int declaredLength;
        try {
            declaredLength = Integer.parseInt(new String(header, 14, 4, StandardCharsets.US_ASCII), 16);
        } catch (NumberFormatException malformed) {
            throw new IOException("Invalid Mitsubishi MC ASCII response length", malformed);
        }
        // ASCII frame length counts actual transmitted ASCII characters (bytes), not binary equivalents.
        validateFrameLength(ASCII_HEADER_LENGTH, declaredLength, 4);
        byte[] body = readFully(declaredLength, deadline);
        byte[] frame = new byte[ASCII_HEADER_LENGTH + declaredLength];
        System.arraycopy(header, 0, frame, 0, ASCII_HEADER_LENGTH);
        System.arraycopy(body, 0, frame, ASCII_HEADER_LENGTH, declaredLength);
        return frame;
    }

    /**
     * 查询并返回业务数据。
     */
    private void validateFrameLength(int headerLength, int declaredLength, int minimum) throws IOException {
        if (declaredLength < minimum || declaredLength > MAX_FRAME_SIZE - headerLength) {
            throw new IOException("Invalid Mitsubishi MC response length: " + declaredLength);
        }
    }

    private int readByte(long deadline) throws IOException {
        socket.setSoTimeout(remainingTimeout(deadline));
        int value = inputStream.read();
        if (value < 0) {
            throw new EOFException("Mitsubishi MC socket closed while reading response");
        }
        return value;
    }

    private byte[] readFully(int length, long deadline) throws IOException {
        byte[] target = new byte[length];
        int offset = 0;
        while (offset < length) {
            socket.setSoTimeout(remainingTimeout(deadline));
            int read = inputStream.read(target, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Mitsubishi MC socket closed while reading response");
            }
            offset += read;
        }
        return target;
    }

    private int remainingTimeout(long deadline) throws SocketTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new SocketTimeoutException("Mitsubishi MC response deadline exceeded");
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, (nanos + 999_999L) / 1_000_000L));
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
