package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * 自定义 UDP 请求响应连接，一个数据报对应一个完整帧。
 */
@Slf4j
public class CustomUdpConnectionAdapter extends AbstractConnectionAdapter<DatagramSocket>
        implements CustomExchangeAdapter {

    private final Object exchangeLock = new Object();
    private DatagramSocket socket;
    private InetSocketAddress remoteAddress;

    /**
     * 创建当前组件实例。
     */
    public CustomUdpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
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
            throw new IllegalArgumentException("自定义UDP连接必须配置有效的主机和端口");
        }
        remoteAddress = new InetSocketAddress(host, port);
        socket = new DatagramSocket();
        socket.connect(remoteAddress);
        socket.setSoTimeout(resolveReadTimeout());
        setConnectionParam("host", host);
        setConnectionParam("port", port);
        log.info("自定义UDP连接已建立: {}:{}", host, port);
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
        remoteAddress = null;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() {
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            throw new IllegalStateException("自定义UDP连接未激活");
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
            if (socket == null || remoteAddress == null) {
                throw new IllegalStateException("自定义UDP套接字未初始化");
            }
            socket.send(new DatagramPacket(data, data.length, remoteAddress));
        } catch (Exception exception) {
            throw new IllegalStateException("自定义UDP发送失败", exception);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected byte[] doReceive() {
        return receiveDatagram(resolveReadTimeout());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected byte[] doReceive(long timeout) {
        return receiveDatagram(timeout > 0 ? (int) timeout : resolveReadTimeout());
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
    public DatagramSocket getClient() {
        return socket;
    }

    @Override
    public boolean isConnected() {
        return super.isConnected() && socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * 执行当前业务逻辑。
     */
    private byte[] receiveDatagram(int timeoutMs) {
        try {
            if (socket == null) {
                throw new IllegalStateException("自定义UDP套接字未初始化");
            }
            socket.setSoTimeout(timeoutMs);
            int bufferSize = config.getBufferSize() != null && config.getBufferSize() > 0
                    ? config.getBufferSize()
                    : 8_192;
            byte[] buffer = new byte[bufferSize];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            return Arrays.copyOf(packet.getData(), packet.getLength());
        } catch (Exception exception) {
            throw new IllegalStateException("自定义UDP接收失败", exception);
        }
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
