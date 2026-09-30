package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.FinsTransportMode;
import com.wangbin.collector.core.collector.protocol.fins.transport.FinsTcpTransport;
import com.wangbin.collector.core.collector.protocol.fins.transport.FinsTransportException;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用本地套接字校验 FINS 传输的线级边界，而非模拟传输实现。 */
class OmronFinsTransportTest {
    private static final byte[] MAGIC = "FINS".getBytes(StandardCharsets.US_ASCII);

    @Test
    void udpSkipsWrongSidAndAcceptsMatchingReply() throws Exception {
        try (UdpPeer peer = new UdpPeer((socket, packet, request) -> {
            send(socket, packet, response(request, 98));
            send(socket, packet, response(request, request[9] & 0xff));
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "UDP", 350);
            try {
                adapter.connect();
                byte[] request = request(7);
                assertArrayEquals(response(request, 7), adapter.exchange(request, 350));
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void udpSkipsWrongRouteEvenWhenSidAndCommandMatch() throws Exception {
        try (UdpPeer peer = new UdpPeer((socket, packet, request) -> {
            byte[] wrongRoute = response(request, request[9] & 0xff);
            wrongRoute[4]++;
            send(socket, packet, wrongRoute);
            send(socket, packet, response(request, request[9] & 0xff));
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "UDP", 350);
            try {
                adapter.connect();
                assertArrayEquals(response(request(29), 29), adapter.exchange(request(29), 350));
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void udpStalePacketsCannotExtendAbsoluteDeadline() throws Exception {
        AtomicInteger staleSent = new AtomicInteger();
        try (UdpPeer peer = new UdpPeer((socket, packet, request) -> {
            for (int index = 0; index < 24; index++) {
                send(socket, packet, response(request, ((request[9] & 0xff) + 1) & 0xff));
                staleSent.incrementAndGet();
                Thread.sleep(12);
            }
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "UDP", 120);
            try {
                adapter.connect();
                long started = System.nanoTime();
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> adapter.exchange(request(7), 120));
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertInstanceOf(SocketTimeoutException.class, failure.getCause());
                assertTrue(staleSent.get() >= 2, "应在超时前收到多个过期 SID 报文");
                assertTrue(elapsedMs < 260, "过期报文不能逐次重置 120ms 超时: " + elapsedMs);
                assertFalse(adapter.isConnected());
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void udpSkipsWrongCommandAndTruncatedReply() throws Exception {
        try (UdpPeer peer = new UdpPeer((socket, packet, request) -> {
            byte[] wrongCommand = response(request, request[9] & 0xff);
            wrongCommand[11] = 2;
            send(socket, packet, wrongCommand);
            send(socket, packet, Arrays.copyOf(response(request, request[9] & 0xff), 13));
            send(socket, packet, response(request, request[9] & 0xff));
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "UDP", 350);
            try {
                adapter.connect();
                assertArrayEquals(response(request(6), 6), adapter.exchange(request(6), 350));
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void udpDiscardsLateReplyFromPreviousTimedOutExchange() throws Exception {
        AtomicReference<DatagramPacket> previous = new AtomicReference<>();
        try (UdpPeer peer = new UdpPeer((socket, packet, request) -> {
            if (previous.get() == null) {
                previous.set(new DatagramPacket(response(request, request[9] & 0xff), 14,
                        packet.getSocketAddress()));
            } else {
                socket.send(previous.get());
                send(socket, packet, response(request, request[9] & 0xff));
            }
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "UDP", 100);
            try {
                adapter.connect();
                assertThrows(Exception.class, () -> adapter.exchange(request(1), 100));
                assertFalse(adapter.isConnected());
                adapter.disconnect();
                adapter.connect();
                assertArrayEquals(response(request(2), 2), adapter.exchange(request(2), 350));
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void udpTimesOutWithoutResponseAndRemainsUdp() throws Exception {
        try (UdpPeer peer = new UdpPeer((socket, packet, request) -> {})) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "UDP", 100);
            try {
                adapter.connect();
                assertThrows(Exception.class, () -> adapter.exchange(request(3), 100));
                assertFalse(adapter.isConnected());
                assertEquals(FinsTransportMode.UDP, adapter.getEffectiveTransport());
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void udpRejectsReplyFromOtherSource() throws Exception {
        try (DatagramSocket impostor = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             UdpPeer peer = new UdpPeer((socket, packet, request) -> {
                 byte[] forged = response(request, request[9] & 0xff);
                 impostor.send(new DatagramPacket(forged, forged.length, packet.getSocketAddress()));
             })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "UDP", 120);
            try {
                adapter.connect();
                assertThrows(Exception.class, () -> adapter.exchange(request(4), 120));
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void udpCloseIsRepeatableAndDoesNotAcceptFurtherExchange() throws Exception {
        try (UdpPeer peer = new UdpPeer((socket, packet, request) -> {})) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "UDP", 120);
            adapter.connect();
            DatagramSocket client = adapter.getClient();
            assertNotNull(client);
            adapter.disconnect();
            adapter.disconnect();
            assertTrue(client.isClosed());
            assertFalse(adapter.isConnected());
            assertThrows(Exception.class, () -> adapter.exchange(request(5), 120));
        }
    }

    @Test
    void tcpNegotiatesNodesAndTransportsExactlyOneFramedFinsRequest() throws Exception {
        try (TcpPeer peer = new TcpPeer(socket -> {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] hello = readEnvelope(input);
            assertEquals(12, hello.length);
            assertEquals(0, integer(hello, 0));
            assertEquals(0, integer(hello, 4));
            assertEquals(0, integer(hello, 8));
            output.write(envelope(1, 0, new byte[]{0, 0, 0, 37, 0, 0, 0, 52}));
            output.flush();
            byte[] data = readEnvelope(input);
            assertEquals(2, integer(data, 0));
            assertEquals(0, integer(data, 4));
            byte[] command = Arrays.copyOfRange(data, 8, data.length);
            assertEquals(52, command[4] & 0xff);
            assertEquals(37, command[7] & 0xff);
            output.write(envelope(2, 0, response(command, command[9] & 0xff)));
            output.flush();
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 500);
            try {
                adapter.connect();
                assertEquals(37, adapter.getEffectiveNodes().sourceNode());
                assertEquals(52, adapter.getEffectiveNodes().destinationNode());
                byte[] negotiated = requestWithNodes(8, adapter.getEffectiveNodes().destinationNode(),
                        adapter.getEffectiveNodes().sourceNode());
                assertArrayEquals(response(negotiated, 8), adapter.exchange(negotiated, 500));
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void tcpReadsFragmentedHeaderAndPayload() throws Exception {
        try (TcpPeer peer = new TcpPeer(socket -> {
            InputStream input = socket.getInputStream();
            readEnvelope(new DataInputStream(input));
            byte[] hello = envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1});
            for (byte value : hello) {
                socket.getOutputStream().write(value);
                socket.getOutputStream().flush();
            }
            byte[] data = readEnvelope(new DataInputStream(input));
            byte[] fins = Arrays.copyOfRange(data, 8, data.length);
            byte[] reply = envelope(2, 0, response(fins, fins[9] & 0xff));
            for (byte value : reply) {
                socket.getOutputStream().write(value);
                socket.getOutputStream().flush();
            }
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 700);
            try {
                adapter.connect();
                assertArrayEquals(response(request(9), 9), adapter.exchange(request(9), 700));
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void tcpRejectsWrongRouteEvenWhenSidAndCommandMatch() throws Exception {
        try (TcpPeer peer = new TcpPeer(socket -> {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            readEnvelope(input);
            output.write(envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1}));
            output.flush();
            byte[] body = readEnvelope(input);
            byte[] request = Arrays.copyOfRange(body, 8, body.length);
            byte[] wrongRoute = response(request, request[9] & 0xff);
            wrongRoute[7]++;
            output.write(envelope(2, 0, wrongRoute));
            output.flush();
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 350);
            try {
                adapter.connect();
                FinsTransportException failure = assertThrows(FinsTransportException.class,
                        () -> adapter.exchange(request(30), 350));
                assertEquals(FinsTransportException.Code.TCP_FRAME_ERROR, failure.getCode());
                assertFalse(adapter.isConnected());
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void tcpResponseReadTimeoutClosesConnectionAndPeerSocket() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        try (TcpPeer peer = new TcpPeer(socket -> {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            readEnvelope(input);
            socket.getOutputStream().write(envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1}));
            socket.getOutputStream().flush();
            readEnvelope(input);
            // 保持连接但不发送响应，区分读超时与 EOF。
            if (input.read() == -1) {
                closed.countDown();
            }
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 200);
            try {
                adapter.connect();
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> adapter.exchange(request(20), 80));
                assertInstanceOf(SocketTimeoutException.class, failure.getCause());
                assertFalse(adapter.isConnected());
                assertTrue(closed.await(500, TimeUnit.MILLISECONDS), "读超时后应关闭 TCP socket");
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void tcpSendBackpressureHonorsDeadlineAndClosesSession() throws Exception {
        final int maxFrameSize = 1_048_576;
        CountDownLatch releasePeer = new CountDownLatch(1);
        CountDownLatch peerClosed = new CountDownLatch(1);
        try (TcpPeer peer = new TcpPeer(1, 1024, socket -> {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            readEnvelope(input);
            socket.getOutputStream().write(envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1}));
            socket.getOutputStream().flush();
            // 等待客户端发送超时后才读取，确保背压期间服务端不消耗 TCP 接收缓冲区。
            if (releasePeer.await(3, TimeUnit.SECONDS)) {
                while (input.read() != -1) {
                    // 消耗已入内核缓冲区的数据，确认超时后的连接最终发出 EOF。
                }
                peerClosed.countDown();
            }
        });
             FinsTcpTransport transport = new FinsTcpTransport(
                     new InetSocketAddress("127.0.0.1", peer.port()), 800, maxFrameSize, 0, 800)) {
            SocketChannel channel = tcpChannel(transport);
            channel.setOption(StandardSocketOptions.SO_SNDBUF, 1024);
            assertTrue(channel.getOption(StandardSocketOptions.SO_SNDBUF) <= 8192,
                    "需要可控的小发送缓冲区，实际值: " + channel.getOption(StandardSocketOptions.SO_SNDBUF));
            byte[] largeRequest = Arrays.copyOf(requestWithNodes(31, 1, 10), maxFrameSize - 16);
            FutureTask<Throwable> sending = new FutureTask<>(() -> {
                try {
                    transport.send(largeRequest, 800);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            Thread sender = new Thread(sending, "fins-test-backpressure-sender");
            long started = System.nanoTime();
            try {
                sender.start();
                boolean sawNoProgress = false;
                long observationDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
                Selector selector = tcpSelector(transport);
                while (!sending.isDone() && System.nanoTime() < observationDeadline) {
                    // 生产实现仅在 SocketChannel.write 返回 0 后注册 OP_WRITE。
                    SelectionKey key = channel.keyFor(selector);
                    if (key != null && key.isValid() && (key.interestOps() & SelectionKey.OP_WRITE) != 0) {
                        sawNoProgress = true;
                        break;
                    }
                    Thread.sleep(2);
                }
                Throwable failure = sending.get(2, TimeUnit.SECONDS);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(sawNoProgress, "未观察到 write 返回 0 后注册 OP_WRITE；不能将读超时当成写背压");
                assertInstanceOf(SocketTimeoutException.class, failure);
                assertTrue(elapsedMs < 1600, "发送必须受 800ms 绝对期限约束: " + elapsedMs);
                assertFalse(transport.isOpen());
                assertNull(transport.nodes());
                assertThrows(IllegalStateException.class, () -> transport.send(largeRequest, 100));
                assertThrows(IllegalStateException.class, () -> transport.receive(100));
            } finally {
                transport.close();
                releasePeer.countDown();
                sender.join(2000);
            }
            assertTrue(peerClosed.await(1500, TimeUnit.MILLISECONDS), "发送超时后服务端应读到 EOF");
        }
    }

    @Test
    void tcpHandshakeFragmentsCannotExtendConnectDeadline() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);
        try (TcpPeer peer = new TcpPeer(socket -> {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            readEnvelope(input);
            byte[] handshake = envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1});
            for (int index = 0; index < 4; index++) {
                socket.getOutputStream().write(handshake[index]);
                socket.getOutputStream().flush();
                sent.incrementAndGet();
                Thread.sleep(65);
            }
            if (input.read() == -1) {
                closed.countDown();
            }
        })) {
            long started = System.nanoTime();
            assertThrows(SocketTimeoutException.class,
                    () -> new FinsTcpTransport(new InetSocketAddress("127.0.0.1", peer.port()),
                            260, 256, 0, 260));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(sent.get() >= 3, "必须在连接期限内实际收到多个握手分片");
            assertTrue(elapsedMs < 390, "握手分片不得续期 260ms 的建连期限: " + elapsedMs);
            assertTrue(closed.await(1000, TimeUnit.MILLISECONDS), "握手超时后应关闭 TCP socket");
        }
    }

    @Test
    void tcpResponseBodyFragmentsCannotExtendReadDeadline() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);
        try (TcpPeer peer = new TcpPeer(socket -> {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            readEnvelope(input);
            socket.getOutputStream().write(envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1}));
            socket.getOutputStream().flush();
            byte[] body = readEnvelope(input);
            byte[] command = Arrays.copyOfRange(body, 8, body.length);
            byte[] reply = envelope(2, 0, response(command, command[9] & 0xff));
            socket.getOutputStream().write(reply, 0, 8);
            socket.getOutputStream().flush();
            for (int index = 8; index < 12; index++) {
                socket.getOutputStream().write(reply[index]);
                socket.getOutputStream().flush();
                sent.incrementAndGet();
                Thread.sleep(65);
            }
            if (input.read() == -1) {
                closed.countDown();
            }
        });
             FinsTcpTransport transport = new FinsTcpTransport(
                     new InetSocketAddress("127.0.0.1", peer.port()), 800, 256, 0, 800)) {
            transport.send(requestWithNodes(34, 1, 10), 500);
            long started = System.nanoTime();
            assertThrows(SocketTimeoutException.class, () -> transport.receive(260));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(sent.get() >= 3, "必须在读取期限内实际收到多个响应体分片");
            assertTrue(elapsedMs < 390, "响应体分片不得续期 260ms 的读取期限: " + elapsedMs);
            assertFalse(transport.isOpen());
            assertNull(transport.nodes());
            assertThrows(IllegalStateException.class, () -> transport.receive(100));
            assertTrue(closed.await(1000, TimeUnit.MILLISECONDS), "读取超时后应关闭 TCP socket");
        }
    }

    @Test
    void tcpRejectsBadDataMagicAndClosesConnection() throws Exception {
        try (TcpPeer peer = new TcpPeer(socket -> {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            readEnvelope(input);
            socket.getOutputStream().write(envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1}));
            socket.getOutputStream().flush();
            readEnvelope(input);
            byte[] reply = envelope(2, 0, response(request(21), 21));
            System.arraycopy("FAIL".getBytes(StandardCharsets.US_ASCII), 0, reply, 0, 4);
            socket.getOutputStream().write(reply);
            socket.getOutputStream().flush();
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 300);
            try {
                adapter.connect();
                FinsTransportException failure = assertThrows(FinsTransportException.class,
                        () -> adapter.exchange(request(21), 300));
                assertEquals(FinsTransportException.Code.TCP_FRAME_ERROR, failure.getCode());
                assertTrue(failure.getMessage().contains("magic"));
                assertFalse(adapter.isConnected());
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void tcpReadsCoalescedFramesByLengthWithoutConsumingNextFrame() throws Exception {
        try (TcpPeer peer = new TcpPeer(socket -> {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            readEnvelope(input);
            output.write(envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1}));
            output.flush();
            byte[] first = readEnvelope(input);
            byte[] firstRequest = Arrays.copyOfRange(first, 8, first.length);
            assertEquals(22, firstRequest[9] & 0xff);
            // 单请求在途：第二帧提前到达，仅验证长度读取不吞掉流中后续完整帧。
            byte[] secondRequest = request(23);
            output.write(envelope(2, 0, response(firstRequest, 22)));
            output.write(envelope(2, 0, response(secondRequest, 23)));
            output.flush();
            byte[] second = readEnvelope(input);
            assertArrayEquals(secondRequest, Arrays.copyOfRange(second, 8, second.length));
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 400);
            try {
                adapter.connect();
                assertArrayEquals(response(request(22), 22), adapter.exchange(request(22), 400));
                assertArrayEquals(response(request(23), 23), adapter.exchange(request(23), 400));
                assertTrue(adapter.isConnected());
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void tcpRejectsEofMidFrame() throws Exception {
        try (TcpPeer peer = new TcpPeer(socket -> {
            readEnvelope(new DataInputStream(socket.getInputStream()));
            socket.getOutputStream().write(Arrays.copyOf(envelope(1, 0, new byte[8]), 11));
            socket.getOutputStream().flush();
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 250);
            assertThrows(Exception.class, adapter::connect);
            assertFalse(adapter.isConnected());
            adapter.disconnect();
        }
    }

    @Test
    void tcpRejectsInvalidMagicAndOversizedLength() throws Exception {
        for (boolean badMagic : new boolean[]{true, false}) {
            try (TcpPeer peer = new TcpPeer(socket -> {
                readEnvelope(new DataInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.write(badMagic ? "FAIL".getBytes(StandardCharsets.US_ASCII) : MAGIC);
                out.writeInt(badMagic ? 16 : 1_048_577);
                out.flush();
            })) {
                OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 300);
                assertThrows(Exception.class, adapter::connect);
                adapter.disconnect();
            }
        }
    }

    @Test
    void tcpRejectsInvalidHandshakeCommandAndError() throws Exception {
        for (int[] header : new int[][]{{2, 0}, {1, 3}}) {
            try (TcpPeer peer = new TcpPeer(socket -> {
                readEnvelope(new DataInputStream(socket.getInputStream()));
                socket.getOutputStream().write(envelope(header[0], header[1], new byte[8]));
                socket.getOutputStream().flush();
            })) {
                OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 300);
                assertThrows(Exception.class, adapter::connect);
                adapter.disconnect();
            }
        }
    }

    @Test
    void tcpRejectsInvalidNegotiatedNodes() throws Exception {
        for (byte[] nodes : new byte[][]{{0, 0, 0, 0, 0, 0, 0, 1},
                {0, 0, 0, 10, 0, 0, 0, 0}}) {
            try (TcpPeer peer = new TcpPeer(socket -> {
                readEnvelope(new DataInputStream(socket.getInputStream()));
                socket.getOutputStream().write(envelope(1, 0, nodes));
                socket.getOutputStream().flush();
            })) {
                OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 300);
                assertThrows(Exception.class, adapter::connect);
                assertFalse(adapter.isConnected());
                adapter.disconnect();
            }
        }
    }

    @Test
    void tcpRejectsInvalidDataCommandAndEofMidBody() throws Exception {
        for (boolean invalidCommand : new boolean[]{true, false}) {
            try (TcpPeer peer = new TcpPeer(socket -> {
                readEnvelope(new DataInputStream(socket.getInputStream()));
                socket.getOutputStream().write(envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1}));
                socket.getOutputStream().flush();
                readEnvelope(new DataInputStream(socket.getInputStream()));
                if (invalidCommand) {
                    socket.getOutputStream().write(envelope(3, 0, request(11)));
                } else {
                    socket.getOutputStream().write(Arrays.copyOf(envelope(2, 0, request(11)), 14));
                }
                socket.getOutputStream().flush();
            })) {
                OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 300);
                try {
                    adapter.connect();
                    assertThrows(Exception.class, () -> adapter.exchange(request(11), 300));
                } finally {
                    adapter.disconnect();
                }
            }
        }
    }

    @Test
    void tcpRejectsDataErrorAndInvalidDataLength() throws Exception {
        for (boolean errorCode : new boolean[]{true, false}) {
            try (TcpPeer peer = new TcpPeer(socket -> {
                readEnvelope(new DataInputStream(socket.getInputStream()));
                socket.getOutputStream().write(envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1}));
                socket.getOutputStream().flush();
                readEnvelope(new DataInputStream(socket.getInputStream()));
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.write(MAGIC);
                output.writeInt(errorCode ? 8 : 1_048_577);
                if (errorCode) {
                    output.writeInt(2);
                    output.writeInt(1);
                }
                output.flush();
            })) {
                OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 300);
                try {
                    adapter.connect();
                    assertThrows(Exception.class, () -> adapter.exchange(request(14), 300));
                } finally {
                    adapter.disconnect();
                }
            }
        }
    }

    @Test
    void tcpCloseIsRepeatableAndRejectsFurtherExchange() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        try (TcpPeer peer = new TcpPeer(socket -> {
            readEnvelope(new DataInputStream(socket.getInputStream()));
            socket.getOutputStream().write(envelope(1, 0, new byte[]{0, 0, 0, 10, 0, 0, 0, 1}));
            socket.getOutputStream().flush();
            if (socket.getInputStream().read() == -1) {
                closed.countDown();
            }
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 350);
            adapter.connect();
            adapter.disconnect();
            adapter.disconnect();
            assertFalse(adapter.isConnected());
            assertTrue(closed.await(500, TimeUnit.MILLISECONDS));
            assertThrows(Exception.class, () -> adapter.exchange(request(15), 100));
        }
    }

    @Test
    void tcpCloseResourcesReleasesSessionAndReconnectNegotiatesFreshNodes() throws Exception {
        AtomicInteger sessions = new AtomicInteger();
        CountDownLatch firstClosed = new CountDownLatch(1);
        try (TcpPeer peer = new TcpPeer(2, socket -> {
            int session = sessions.incrementAndGet();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            readEnvelope(input);
            output.write(envelope(1, 0, new byte[]{0, 0, 0, (byte) (10 + session), 0, 0, 0, 1}));
            output.flush();
            if (session == 1) {
                if (input.read() == -1) {
                    firstClosed.countDown();
                }
            } else {
                byte[] data = readEnvelope(input);
                byte[] command = Arrays.copyOfRange(data, 8, data.length);
                assertEquals(12, command[7] & 0xff);
                output.write(envelope(2, 0, response(command, command[9] & 0xff)));
                output.flush();
            }
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 400);
            try {
                adapter.connect();
                assertEquals(11, adapter.getEffectiveNodes().sourceNode());
                adapter.closeResources();
                adapter.closeResources();
                assertFalse(adapter.isConnected());
                assertNull(adapter.getEffectiveNodes());
                assertTrue(firstClosed.await(500, TimeUnit.MILLISECONDS));
                adapter.reconnect();
                assertEquals(12, adapter.getEffectiveNodes().sourceNode());
                byte[] next = requestWithNodes(24, 1, 12);
                assertArrayEquals(response(next, 24), adapter.exchange(next, 400));
                assertEquals(2, sessions.get());
            } finally {
                adapter.disconnect();
            }
        }
    }

    @Test
    void tcpHandshakeFailureReleasesSocket() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);
        try (TcpPeer peer = new TcpPeer(socket -> {
            accepted.incrementAndGet();
            readEnvelope(new DataInputStream(socket.getInputStream()));
            socket.getOutputStream().write(envelope(1, 1, new byte[8]));
            socket.getOutputStream().flush();
            socket.setSoTimeout(500);
            try {
                if (socket.getInputStream().read() == -1) {
                    closed.countDown();
                }
            } catch (SocketTimeoutException ignored) {
                // 超过等待时间仍未关闭，由断言报告资源泄漏。
            }
        })) {
            OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "TCP", 300);
            assertThrows(Exception.class, adapter::connect);
            assertTrue(closed.await(600, TimeUnit.MILLISECONDS));
            adapter.disconnect();
            assertEquals(1, accepted.get());
        }
    }

    @Test
    void autoTimeoutDoesNotSwitchToTcp() throws Exception {
        // Windows may allocate an ephemeral TCP port already owned by an unrelated UDP socket.
        for (int attempt = 0; attempt < 8; attempt++) {
            try (ServerSocket tcp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                UdpPeer peer;
                try {
                    peer = new UdpPeer(tcp.getLocalPort(), (socket, packet, request) -> {});
                } catch (java.net.BindException collision) {
                    if (attempt == 7) {
                        throw collision;
                    }
                    continue;
                }
                try (peer) {
                    tcp.setSoTimeout(350);
                    OmronFinsUdpConnectionAdapter adapter = adapter(peer.port(), "AUTO", 100);
                    try {
                        adapter.connect();
                        assertThrows(Exception.class, () -> adapter.exchange(request(12), 100));
                        assertFalse(adapter.isConnected());
                        assertEquals(FinsTransportMode.UDP, adapter.getEffectiveTransport());
                        assertThrows(SocketTimeoutException.class, tcp::accept);
                    } finally {
                        adapter.disconnect();
                    }
                }
                return;
            }
        }
    }

    private static OmronFinsUdpConnectionAdapter adapter(int port, String transport, int timeout) {
        DeviceInfo info = new DeviceInfo();
        info.setDeviceId("fins-wire-test");
        info.setIpAddress("127.0.0.1");
        info.setPort(port);
        info.setConnectionType("OMRON_FINS");
        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("OMRON_FINS");
        connection.setHost("127.0.0.1");
        connection.setPort(port);
        connection.setReadTimeout(timeout);
        connection.setConnectTimeout(timeout);
        connection.setBufferSize(256);
        connection.setInitialReconnectDelay(1);
        connection.setExtJson(Map.of("transport", transport, "localNode", "TCP".equals(transport) ? 0 : 10,
                "plcNode", 1, "maxFrameSize", 256));
        return new OmronFinsUdpConnectionAdapter(info, connection);
    }

    private static byte[] request(int sid) {
        return requestWithNodes(sid, 1, 10);
    }

    private static byte[] requestWithNodes(int sid, int destination, int source) {
        byte[] request = new byte[18];
        request[0] = (byte) 0x80;
        request[2] = 2;
        request[4] = (byte) destination;
        request[7] = (byte) source;
        request[9] = (byte) sid;
        request[10] = 1;
        request[11] = 1;
        request[12] = (byte) 0x82;
        request[14] = 100;
        request[17] = 1;
        return request;
    }

    private static byte[] response(byte[] request, int sid) {
        byte[] reply = Arrays.copyOf(request, 14);
        reply[0] = (byte) 0xc0;
        reply[4] = request[7];
        reply[7] = request[4];
        reply[9] = (byte) sid;
        reply[12] = 0;
        reply[13] = 0;
        return reply;
    }

    private static void send(DatagramSocket socket, DatagramPacket packet, byte[] data) throws Exception {
        socket.send(new DatagramPacket(data, data.length, packet.getSocketAddress()));
    }

    private static SocketChannel tcpChannel(FinsTcpTransport transport) throws Exception {
        Field field = FinsTcpTransport.class.getDeclaredField("channel");
        field.setAccessible(true);
        return (SocketChannel) field.get(transport);
    }

    private static Selector tcpSelector(FinsTcpTransport transport) throws Exception {
        Field field = FinsTcpTransport.class.getDeclaredField("selector");
        field.setAccessible(true);
        return (Selector) field.get(transport);
    }

    private static int integer(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static byte[] envelope(int command, int error, byte[] payload) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeInt(8 + payload.length);
        out.writeInt(command);
        out.writeInt(error);
        out.write(payload);
        return bytes.toByteArray();
    }

    private static byte[] readEnvelope(DataInputStream input) throws Exception {
        byte[] magic = new byte[4];
        input.readFully(magic);
        assertArrayEquals(MAGIC, magic);
        int length = input.readInt();
        assertTrue(length >= 8 && length < 4096);
        byte[] body = new byte[length];
        input.readFully(body);
        return body;
    }

    private interface UdpHandler {
        void handle(DatagramSocket socket, DatagramPacket packet, byte[] request) throws Exception;
    }

    private static final class UdpPeer implements AutoCloseable {
        private final DatagramSocket socket;
        private final Thread worker;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        UdpPeer(UdpHandler handler) throws Exception {
            this(0, handler);
        }

        UdpPeer(int port, UdpHandler handler) throws Exception {
            socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", port));
            worker = new Thread(() -> {
                while (running.get()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
                        socket.receive(packet);
                        handler.handle(socket, packet, Arrays.copyOf(packet.getData(), packet.getLength()));
                    } catch (Exception exception) {
                        if (running.get()) {
                            failure.set(exception);
                        }
                        return;
                    }
                }
            }, "fins-test-udp-peer");
            worker.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            socket.close();
            worker.join(1000);
            if (failure.get() != null) {
                throw new AssertionError("模拟 UDP 对端失败", failure.get());
            }
        }
    }

    private interface TcpHandler {
        void handle(Socket socket) throws Exception;
    }

    private static final class TcpPeer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean running = true;
        private volatile Socket client;

        TcpPeer(TcpHandler handler) throws Exception {
            this(1, handler);
        }

        TcpPeer(int sessions, TcpHandler handler) throws Exception {
            this(sessions, 0, handler);
        }

        TcpPeer(int sessions, int receiveBufferSize, TcpHandler handler) throws Exception {
            server = new ServerSocket();
            if (receiveBufferSize > 0) {
                server.setReceiveBufferSize(receiveBufferSize);
            }
            server.bind(new InetSocketAddress("127.0.0.1", 0), 1);
            worker = new Thread(() -> {
                try {
                    for (int index = 0; index < sessions; index++) {
                        try (Socket socket = server.accept()) {
                            client = socket;
                            socket.setSoTimeout(1000);
                            handler.handle(socket);
                        }
                    }
                } catch (Exception | AssertionError exception) {
                    if (running) {
                        failure.set(exception);
                    }
                }
            }, "fins-test-tcp-peer");
            worker.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            running = false;
            server.close();
            if (client != null) {
                client.close();
            }
            worker.join(1200);
            if (failure.get() != null) {
                throw new AssertionError("模拟 TCP 对端失败", failure.get());
            }
        }
    }
}
