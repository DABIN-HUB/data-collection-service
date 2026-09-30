package com.wangbin.collector.core.collector.protocol.fins.transport;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/** FINS/UDP：单请求在途，仅接收目标端口与 SID/命令匹配的应答。 */
public final class FinsUdpTransport implements FinsTransport {
    private final InetSocketAddress remote;
    private final int receiveBufferSize;
    private final FinsSessionNodes nodes;
    private DatagramSocket socket;
    private byte[] pendingRequest;

    public FinsUdpTransport(InetSocketAddress remote, int receiveBufferSize,
                            FinsSessionNodes nodes) throws Exception {
        if (remote == null || remote.isUnresolved()) {
            throw new IllegalArgumentException("FINS UDP destination is unresolved");
        }
        this.remote = remote;
        this.receiveBufferSize = receiveBufferSize;
        this.nodes = nodes;
        DatagramSocket created = new DatagramSocket();
        try {
            created.connect(remote);
            socket = created;
        } catch (Exception exception) {
            created.close();
            throw exception;
        }
    }

    @Override
    public synchronized void send(byte[] request) throws Exception {
        FinsTransport.validateRequest(request, receiveBufferSize);
        if (!isOpen() || pendingRequest != null) {
            throw new IllegalStateException("FINS UDP connection closed or request already in flight");
        }
        // 保存请求头副本，防止调用方发送后修改 SID 或命令字段。
        byte[] key = Arrays.copyOf(request, 12);
        socket.send(new DatagramPacket(request, request.length, remote));
        pendingRequest = key;
    }

    @Override
    public synchronized byte[] receive(long timeoutMs) throws Exception {
        if (!isOpen() || pendingRequest == null) {
            throw new IllegalStateException("FINS UDP has no pending request");
        }
        try {
            long deadline = FinsTransport.deadline(timeoutMs);
            // 多留一个字节以识别超过上限的报文，不能将 UDP 截断结果误判成完整帧。
            byte[] buffer = new byte[receiveBufferSize + 1];
            while (true) {
                socket.setSoTimeout(FinsTransport.remainingMillis(deadline));
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                // 已连接 socket 的系统级过滤之外再次确认来源，拒绝旁路或端口错配。
                if (!remote.getAddress().equals(packet.getAddress()) || remote.getPort() != packet.getPort()) {
                    continue;
                }
                int length = packet.getLength();
                if (length < 14 || !FinsTransport.matchesReply(pendingRequest, buffer)) {
                    continue;
                }
                if (length > receiveBufferSize) {
                    throw new FinsTransportException(FinsTransportException.Code.FRAME_TOO_LARGE,
                            "UDP reply exceeds bufferSize", null);
                }
                return Arrays.copyOf(buffer, length);
            }
        } catch (SocketTimeoutException exception) {
            // PLC 活性无法由 UDP 本地 socket 判断；超时后交给 ConnectionManager 重建同一 UDP 会话。
            close();
            throw exception;
        } finally {
            pendingRequest = null;
        }
    }

    @Override
    public boolean isOpen() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    @Override
    public FinsSessionNodes nodes() {
        return nodes;
    }

    @Override
    public DatagramSocket udpSocket() {
        return socket;
    }

    @Override
    public synchronized void close() {
        pendingRequest = null;
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }
}
