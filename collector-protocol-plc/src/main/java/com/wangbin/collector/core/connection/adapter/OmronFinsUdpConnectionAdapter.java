package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 定义当前模块的业务组件。
 */
@Slf4j
public class OmronFinsUdpConnectionAdapter extends AbstractConnectionAdapter<DatagramSocket> {

    private DatagramSocket socket;
    private InetSocketAddress remoteAddress;
    private int readBufferSize;
    private Socket tcpSocket;
    private DataInputStream tcpInput;
    private DataOutputStream tcpOutput;
    private volatile boolean tcpMode;

    /**
     * 创建当前组件实例。
     */
    public OmronFinsUdpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
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
            throw new IllegalStateException("Invalid OMRON FINS host");
        }
        int resolvedPort = port != null && port > 0 ? port : 9600;
        int timeout = resolveReadTimeout();
        this.readBufferSize = Math.max(config.getBufferSize() != null ? config.getBufferSize() : 4096, 1024);
        this.remoteAddress = new InetSocketAddress(host, resolvedPort);
        setConnectionParam("host", host);
        setConnectionParam("port", resolvedPort);
        String transport = resolveTransport();
        if ("TCP".equals(transport)) {
            connectTcp(timeout);
            log.info("OMRON FINS TCP 连接 已创建:{}:{}", host, resolvedPort);
            return;
        }
        this.socket = new DatagramSocket();
        this.socket.connect(remoteAddress);
        this.socket.setSoTimeout(timeout);
        this.socket.setReuseAddress(true);
        log.info("OMRON FINS UDP 连接 已创建:{}:{}，传输模式={}", host, resolvedPort, transport);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        closeTcp();
        if (socket != null) {
            socket.close();
            socket = null;
        }
        remoteAddress = null;
        tcpMode = false;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (tcpMode) {
            if (tcpSocket == null || tcpSocket.isClosed() || !tcpSocket.isConnected()) {
                throw new IllegalStateException("OMRON FINS TCP socket is not active");
            }
            return;
        }
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            throw new IllegalStateException("OMRON FINS UDP socket is not active");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // FINS/UDP has no extra 认证 phase in this 采集器.
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSend(byte[] data) {
        if (socket == null || remoteAddress == null) {
            throw new IllegalStateException("OMRON FINS UDP socket is not initialized");
        }
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, remoteAddress);
            socket.send(packet);
        } catch (Exception e) {
            throw new IllegalStateException("OMRON FINS UDP send failed", e);
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
            throw new IllegalStateException("OMRON FINS UDP receive failed", e);
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
            throw new IllegalStateException("OMRON FINS UDP receive failed", e);
        }
    }

    @Override
    public DatagramSocket getClient() {
        return socket;
    }

    @Override
    public boolean isConnected() {
        if (tcpMode) {
            return super.isConnected() && tcpSocket != null && tcpSocket.isConnected() && !tcpSocket.isClosed();
        }
        return super.isConnected() && socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * 执行当前业务逻辑。
     */
    public synchronized byte[] exchange(byte[] request, long timeoutMs) throws Exception {
        if (tcpMode) {
            return exchangeTcp(request, timeoutMs);
        }
        try {
            send(request);
            return receive(timeoutMs);
        } catch (Exception exception) {
            if (!isSocketTimeout(exception) || !"AUTO".equals(resolveTransport())) {
                throw exception;
            }
            log.info("OMRON FINS UDP 未收到响应，切换 FINS/TCP: {}", remoteAddress);
            closeUdp();
            connectTcp(timeoutMs > 0 ? (int) timeoutMs : resolveReadTimeout());
            return exchangeTcp(request, timeoutMs);
        }
    }

    private boolean isSocketTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void connectTcp(int timeoutMs) throws Exception {
        Socket createdSocket = new Socket();
        createdSocket.connect(remoteAddress, timeoutMs);
        createdSocket.setSoTimeout(timeoutMs);
        DataInputStream input = new DataInputStream(createdSocket.getInputStream());
        DataOutputStream output = new DataOutputStream(createdSocket.getOutputStream());
        output.write(StandardCharsets.US_ASCII.encode("FINS").array());
        output.writeInt(12);
        output.writeInt(0);
        output.writeInt(0);
        output.writeInt(config.getIntConfig("localNode", 0));
        output.flush();
        byte[] response = readTcpBody(input, timeoutMs);
        if (response.length < 16 || readInt(response, 0) != 1 || readInt(response, 4) != 0) {
            throw new IllegalStateException("OMRON FINS TCP 节点协商失败");
        }
        tcpSocket = createdSocket;
        tcpInput = input;
        tcpOutput = output;
        tcpMode = true;
        setConnectionParam("transport", "TCP");
    }

    private byte[] exchangeTcp(byte[] request, long timeoutMs) throws Exception {
        if (!tcpMode || tcpOutput == null || tcpInput == null) {
            throw new IllegalStateException("OMRON FINS TCP connection is not initialized");
        }
        int timeout = timeoutMs > 0 ? (int) timeoutMs : resolveReadTimeout();
        tcpSocket.setSoTimeout(timeout);
        tcpOutput.write(StandardCharsets.US_ASCII.encode("FINS").array());
        tcpOutput.writeInt(8 + request.length);
        tcpOutput.writeInt(2);
        tcpOutput.writeInt(0);
        tcpOutput.write(request);
        tcpOutput.flush();
        byte[] response = readTcpBody(tcpInput, timeout);
        if (response.length < 8 || readInt(response, 0) != 2 || readInt(response, 4) != 0) {
            throw new IllegalStateException("OMRON FINS TCP 请求失败");
        }
        return Arrays.copyOfRange(response, 8, response.length);
    }

    private byte[] readTcpBody(DataInputStream input, int timeoutMs) throws Exception {
        int previousTimeout = tcpSocket != null ? tcpSocket.getSoTimeout() : 0;
        if (tcpSocket != null && timeoutMs > 0) {
            tcpSocket.setSoTimeout(timeoutMs);
        }
        try {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (!Arrays.equals(magic, StandardCharsets.US_ASCII.encode("FINS").array())) {
                throw new IllegalStateException("OMRON FINS TCP 响应头无效");
            }
            int length = input.readInt();
            if (length < 0 || length > readBufferSize) {
                throw new IllegalStateException("OMRON FINS TCP 响应长度无效: " + length);
            }
            byte[] body = new byte[length];
            input.readFully(body);
            return body;
        } catch (EOFException exception) {
            throw new IllegalStateException("OMRON FINS TCP 连接已关闭", exception);
        } finally {
            if (tcpSocket != null && previousTimeout > 0) {
                tcpSocket.setSoTimeout(previousTimeout);
            }
        }
    }

    private int readInt(byte[] value, int offset) {
        return ((value[offset] & 0xFF) << 24)
                | ((value[offset + 1] & 0xFF) << 16)
                | ((value[offset + 2] & 0xFF) << 8)
                | (value[offset + 3] & 0xFF);
    }

    private void closeTcp() {
        try {
            if (tcpSocket != null) {
                tcpSocket.close();
            }
        } catch (Exception ignored) {
            // 关闭阶段忽略底层连接异常。
        } finally {
            tcpSocket = null;
            tcpInput = null;
            tcpOutput = null;
        }
    }

    private void closeUdp() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    private String resolveTransport() {
        String configured = config.getStringConfig("transport", "AUTO");
        if (configured == null || configured.isBlank()) {
            return "AUTO";
        }
        String normalized = configured.trim().toUpperCase();
        return "TCP".equals(normalized) || "UDP".equals(normalized) ? normalized : "AUTO";
    }

    /**
     * 解析或转换业务数据。
     */
    private byte[] readFrame(int timeoutMs) throws Exception {
        if (socket == null) {
            throw new IllegalStateException("OMRON FINS UDP socket is not initialized");
        }
        socket.setSoTimeout(timeoutMs);
        byte[] buffer = new byte[readBufferSize];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException e) {
            throw e;
        }
        return Arrays.copyOf(packet.getData(), packet.getLength());
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

    /**
     * 解析或转换业务数据。
     */
    @Override
    protected String resolveHost() {
        return config.getHost() != null && !config.getHost().isBlank()
                ? config.getHost().trim()
                : deviceInfo != null ? deviceInfo.getIpAddress() : null;
    }

    /**
     * 解析或转换业务数据。
     */
    @Override
    protected Integer resolvePort() {
        if (config.getPort() != null && config.getPort() > 0) {
            return config.getPort();
        }
        return deviceInfo != null ? deviceInfo.getPort() : null;
    }
}