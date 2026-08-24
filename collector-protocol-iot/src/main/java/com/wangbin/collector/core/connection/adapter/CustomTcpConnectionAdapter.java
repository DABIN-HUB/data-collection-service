package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.custom.codec.CustomFrameCodec;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 自定义 TCP 请求响应连接。
 */
@Slf4j
public class CustomTcpConnectionAdapter extends AbstractConnectionAdapter<Socket>
        implements CustomExchangeAdapter {

    private final Object exchangeLock = new Object();
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    /**
     * 创建当前组件实例。
     */
    public CustomTcpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        String host = resolveHost();
        Integer port = resolvePort();
        if (host == null || host.isBlank() || port == null || port <= 0) {
            throw new IllegalArgumentException("自定义TCP连接必须配置有效的主机和端口");
        }
        Socket createdSocket = new Socket();
        createdSocket.setKeepAlive(Boolean.TRUE.equals(config.getKeepAlive()));
        createdSocket.setTcpNoDelay(true);
        createdSocket.connect(new InetSocketAddress(host, port), resolveConnectTimeout());
        createdSocket.setSoTimeout(resolveReadTimeout());
        socket = createdSocket;
        inputStream = createdSocket.getInputStream();
        outputStream = createdSocket.getOutputStream();
        setConnectionParam("host", host);
        setConnectionParam("port", port);
        log.info("自定义TCP连接已建立: {}:{}", host, port);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() throws Exception {
        Socket current = socket;
        socket = null;
        inputStream = null;
        outputStream = null;
        if (current != null) {
            current.close();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            throw new IllegalStateException("自定义TCP连接未激活");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // 私有协议认证必须通过显式请求模板完成，连接层不执行隐式认证。
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doSend(byte[] data) {
        try {
            if (outputStream == null) {
                throw new IllegalStateException("自定义TCP输出流未初始化");
            }
            outputStream.write(CustomFrameCodec.encode(data, config));
            outputStream.flush();
        } catch (Exception exception) {
            throw new IllegalStateException("自定义TCP发送失败", exception);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected byte[] doReceive() {
        return receiveFrame(resolveReadTimeout());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected byte[] doReceive(long timeout) {
        return receiveFrame(timeout > 0 ? (int) timeout : resolveReadTimeout());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public byte[] exchange(byte[] request, long timeoutMs) throws Exception {
        synchronized (exchangeLock) {
            send(request);
            return receive(timeoutMs);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void sendOnly(byte[] request) throws Exception {
        synchronized (exchangeLock) {
            send(request);
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
    private byte[] receiveFrame(int timeoutMs) {
        try {
            if (socket == null || inputStream == null) {
                throw new IllegalStateException("自定义TCP输入流未初始化");
            }
            socket.setSoTimeout(timeoutMs);
            return CustomFrameCodec.decode(inputStream, config);
        } catch (Exception exception) {
            throw new IllegalStateException("自定义TCP接收失败", exception);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveConnectTimeout() {
        Integer timeout = config.getConnectTimeout();
        return timeout != null && timeout > 0 ? timeout : 5_000;
    }

    /**
     * 解析或转换业务数据。
     */
    private int resolveReadTimeout() {
        Integer timeout = config.getReadTimeout();
        if (timeout != null && timeout > 0) {
            return timeout;
        }
        timeout = config.getTimeout();
        return timeout != null && timeout > 0 ? timeout : 5_000;
    }
}
