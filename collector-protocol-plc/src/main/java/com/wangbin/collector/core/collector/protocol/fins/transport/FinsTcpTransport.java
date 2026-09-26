package com.wangbin.collector.core.collector.protocol.fins.transport;

import java.io.EOFException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

/** FINS/TCP 会话：建连仅协商一次节点，按长度界定完整帧，失败后关闭而不内部重连。 */
public final class FinsTcpTransport implements FinsTransport {
    private static final byte[] MAGIC = {'F', 'I', 'N', 'S'};
    private static final int NODE_REQUEST = 0;
    private static final int NODE_RESPONSE = 1;
    private static final int FRAME_SEND = 2;
    private static final int FRAME_ERROR = 3;

    private final int maxFrameSize;
    private final int requestTimeoutMs;
    private SocketChannel channel;
    private Selector selector;
    private FinsSessionNodes nodes;
    private byte[] pendingRequest;

    public FinsTcpTransport(InetSocketAddress remote, int connectTimeoutMs, int maxFrameSize,
                            int localNode, int requestTimeoutMs) throws Exception {
        if (remote == null || remote.isUnresolved() || connectTimeoutMs <= 0 || requestTimeoutMs <= 0
                || maxFrameSize < 34 || localNode < 0 || localNode > 254) {
            throw new IllegalArgumentException("FINS TCP endpoint, timeout, frame size or local node invalid");
        }
        this.maxFrameSize = maxFrameSize;
        this.requestTimeoutMs = requestTimeoutMs;
        try {
            long deadline = FinsTransport.deadline(connectTimeoutMs);
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_CONNECT);
            if (!channel.connect(remote)) {
                while (!channel.finishConnect()) {
                    waitReady(SelectionKey.OP_CONNECT, deadline);
                }
            }
            channel.socket().setTcpNoDelay(true);
            writeFrame(NODE_REQUEST, ByteBuffer.allocate(4).putInt(localNode).array(), deadline);
            byte[] body = readFrame(deadline);
            if (body.length != 16 || command(body) != NODE_RESPONSE) {
                throw new FinsTransportException(FinsTransportException.Code.TCP_HANDSHAKE_ERROR,
                        "node response command or length invalid", null);
            }
            checkError(body);
            long source = uint32(body, 8);
            long target = uint32(body, 12);
            if (source < 1 || source > 254 || target < 1 || target > 254) {
                throw new FinsTransportException(FinsTransportException.Code.TCP_HANDSHAKE_ERROR,
                        "negotiated node invalid", null);
            }
            nodes = new FinsSessionNodes((int) source, (int) target);
        } catch (Exception exception) {
            close();
            throw exception;
        }
    }

    @Override
    public synchronized void send(byte[] request) throws Exception {
        send(request, requestTimeoutMs);
    }

    @Override
    public synchronized void send(byte[] request, long timeoutMs) throws Exception {
        FinsTransport.validateRequest(request, maxFrameSize - 16);
        if (!isOpen() || pendingRequest != null) {
            throw new IllegalStateException("FINS TCP connection closed or request already in flight");
        }
        try {
            // FINS/TCP 在协商后应使用实际分配的节点，而非配置中的占位节点。
            if ((request[7] & 0xFF) != nodes.sourceNode()
                    || (request[4] & 0xFF) != nodes.destinationNode()) {
                throw new IllegalArgumentException("FINS TCP request nodes differ from negotiated session");
            }
            writeFrame(FRAME_SEND, request, FinsTransport.deadline(timeoutMs));
            pendingRequest = Arrays.copyOf(request, 12);
        } catch (Exception exception) {
            close();
            throw exception;
        }
    }

    @Override
    public synchronized byte[] receive(long timeoutMs) throws Exception {
        if (!isOpen() || pendingRequest == null) {
            throw new IllegalStateException("FINS TCP has no pending request");
        }
        try {
            byte[] body = readFrame(FinsTransport.deadline(timeoutMs));
            int command = command(body);
            checkError(body);
            if (command == FRAME_ERROR) {
                throw new FinsTransportException(FinsTransportException.Code.TCP_FRAME_ERROR,
                        "frame error notification", null);
            }
            if (command != FRAME_SEND || body.length < 22) {
                throw new FinsTransportException(FinsTransportException.Code.TCP_FRAME_ERROR,
                        "data response command or length invalid", null);
            }
            byte[] reply = Arrays.copyOfRange(body, 8, body.length);
            if (!FinsTransport.matchesReply(pendingRequest, reply)) {
                throw new FinsTransportException(FinsTransportException.Code.TCP_FRAME_ERROR,
                        "data response does not match request route, SID or command", null);
            }
            return reply;
        } catch (Exception exception) {
            // 超时、EOF 和畸形帧均可能导致流错位，不能复用此连接。
            close();
            throw exception;
        } finally {
            pendingRequest = null;
        }
    }

    private void writeFrame(int command, byte[] payload, long deadline) throws Exception {
        if (payload.length + 16L > maxFrameSize) {
            throw new IllegalArgumentException("FINS TCP frame exceeds maxFrameSize");
        }
        ByteBuffer frame = ByteBuffer.allocate(16 + payload.length);
        frame.put(MAGIC).putInt(8 + payload.length).putInt(command).putInt(0).put(payload).flip();
        while (frame.hasRemaining()) {
            if (channel.write(frame) == 0) {
                waitReady(SelectionKey.OP_WRITE, deadline);
            }
            FinsTransport.remainingMillis(deadline);
        }
    }

    private byte[] readFrame(long deadline) throws Exception {
        byte[] header = readFully(8, deadline);
        if (!Arrays.equals(Arrays.copyOf(header, 4), MAGIC)) {
            throw new FinsTransportException(FinsTransportException.Code.TCP_FRAME_ERROR,
                    "response magic invalid", null);
        }
        long length = uint32(header, 4);
        if (length < 8) {
            throw new FinsTransportException(FinsTransportException.Code.TCP_FRAME_ERROR,
                    "response length below transport header: " + length, null);
        }
        if (length > maxFrameSize - 8L) {
            throw new FinsTransportException(FinsTransportException.Code.FRAME_TOO_LARGE,
                    "response length exceeds maxFrameSize: " + length, null);
        }
        return readFully((int) length, deadline);
    }

    private byte[] readFully(int length, long deadline) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer);
            if (count < 0) {
                throw new EOFException("FINS TCP peer closed during frame");
            }
            if (count == 0) {
                waitReady(SelectionKey.OP_READ, deadline);
            }
            FinsTransport.remainingMillis(deadline);
        }
        return buffer.array();
    }

    private void waitReady(int operation, long deadline) throws Exception {
        SelectionKey key = channel.keyFor(selector);
        key.interestOps(operation);
        while (true) {
            int count = selector.select(FinsTransport.remainingMillis(deadline));
            if (count > 0 && key.isValid() && (key.readyOps() & operation) != 0) {
                selector.selectedKeys().clear();
                return;
            }
            selector.selectedKeys().clear();
        }
    }

    private static int command(byte[] body) {
        return (int) uint32(body, 0);
    }

    private static long uint32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF) << 24)
                | ((long) (bytes[offset + 1] & 0xFF) << 16)
                | ((long) (bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFFL);
    }

    private static void checkError(byte[] body) {
        long error = uint32(body, 4);
        if (error != 0) {
            throw new FinsTransportException(FinsTransportException.Code.FINS_TCP_ERROR_CODE,
                    "header error: 0x" + Long.toHexString(error), null);
        }
    }

    @Override
    public boolean isOpen() {
        return channel != null && channel.isConnected() && channel.isOpen();
    }

    @Override
    public FinsSessionNodes nodes() {
        return nodes;
    }

    @Override
    public synchronized void close() {
        pendingRequest = null;
        nodes = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception ignored) {
                // 关闭阶段保留首次协议错误，不覆盖原始异常。
            }
            channel = null;
        }
        if (selector != null) {
            try {
                selector.close();
            } catch (Exception ignored) {
                // 同上。
            }
            selector = null;
        }
    }
}
