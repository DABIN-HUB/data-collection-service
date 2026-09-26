package com.wangbin.collector.core.collector.protocol.fins.transport;

import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** 单请求在途的 FINS 传输；连接恢复仅由上层连接管理器负责。 */
public interface FinsTransport extends AutoCloseable {
    void send(byte[] request) throws Exception;

    /** 允许传输在发送阶段也使用本次请求的剩余期限；UDP 保持一次 datagram 发送语义。 */
    default void send(byte[] request, long timeoutMs) throws Exception {
        send(request);
    }

    byte[] receive(long timeoutMs) throws Exception;

    boolean isOpen();

    FinsSessionNodes nodes();

    /** TCP 无 UDP 客户端句柄，保持旧适配器的客户端类型契约。 */
    default DatagramSocket udpSocket() {
        return null;
    }

    @Override
    void close();

    /** 使用单调时钟计算整个接收过程的剩余时间，避免逐片重新计时。 */
    static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("FINS response deadline exceeded");
        }
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
    }

    static long deadline(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("FINS timeout must be positive");
        }
        long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long now = System.nanoTime();
        return now + Math.min(nanos, Long.MAX_VALUE - Math.max(0, now));
    }

    static void validateRequest(byte[] request, int maxFrameSize) {
        if (request == null || request.length < 12 || request.length > maxFrameSize) {
            throw new IllegalArgumentException("FINS request length is invalid");
        }
    }

    /** 仅核对请求/应答路由与关联字段；业务帧结构和 EndCode 仍由 FinsFrameCodec 解析。 */
    static boolean matchesReply(byte[] request, byte[] reply) {
        if (request == null || reply == null || reply.length < 14) {
            return false;
        }
        for (int offset = 0; offset < 3; offset++) {
            if (reply[3 + offset] != request[6 + offset]
                    || reply[6 + offset] != request[3 + offset]) {
                return false;
            }
        }
        return reply[9] == request[9] && reply[10] == request[10] && reply[11] == request[11];
    }
}
