package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

@Slf4j
public class OmronFinsUdpConnectionAdapter extends AbstractConnectionAdapter<DatagramSocket> {

    private DatagramSocket socket;
    private InetSocketAddress remoteAddress;
    private int readBufferSize;

    public OmronFinsUdpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

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
        this.socket = new DatagramSocket();
        this.socket.connect(remoteAddress);
        this.socket.setSoTimeout(timeout);
        this.socket.setReuseAddress(true);
        setConnectionParam("host", host);
        setConnectionParam("port", resolvedPort);
        log.info("OMRON FINS UDP connection created: {}:{}", host, resolvedPort);
    }

    @Override
    protected void doDisconnect() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
        remoteAddress = null;
    }

    @Override
    protected void doHeartbeat() {
        if (socket == null || socket.isClosed() || !socket.isConnected()) {
            throw new IllegalStateException("OMRON FINS UDP socket is not active");
        }
    }

    @Override
    protected void doAuthenticate() {
        // FINS/UDP has no extra authentication phase in this collector.
    }

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

    @Override
    protected byte[] doReceive() {
        try {
            return readFrame(resolveReadTimeout());
        } catch (Exception e) {
            throw new IllegalStateException("OMRON FINS UDP receive failed", e);
        }
    }

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
        return super.isConnected() && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized byte[] exchange(byte[] request, long timeoutMs) throws Exception {
        send(request);
        return receive(timeoutMs);
    }

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

    @Override
    protected String resolveHost() {
        return config.getHost() != null && !config.getHost().isBlank()
                ? config.getHost().trim()
                : deviceInfo != null ? deviceInfo.getIpAddress() : null;
    }

    @Override
    protected Integer resolvePort() {
        if (config.getPort() != null && config.getPort() > 0) {
            return config.getPort();
        }
        return deviceInfo != null ? deviceInfo.getPort() : null;
    }
}