package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MitsubishiMcConnectionAdapterTest {
    @Test
    void slowDripCannotExtendTheWholeExchangeDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
            Future<?> peer = executor.submit(() -> {
                try (Socket client = server.accept()) {
                    client.getInputStream().read();
                    for (int i = 0; i < 12; i++) {
                        client.getOutputStream().write(i == 0 ? 0xD0 : 0);
                        client.getOutputStream().flush();
                        Thread.sleep(100);
                    }
                } catch (Exception ignored) {
                    // Closing the timed-out client socket terminates the peer's drip.
                }
            });
            MitsubishiMcConnectionAdapter adapter = connect(server.getLocalPort());
            long start = System.nanoTime();
            try {
                assertThrows(Exception.class, () -> adapter.exchange(new byte[]{0x50}, 250));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 850);
                assertFalse(adapter.isConnected());
            } finally {
                adapter.disconnect();
            }
            peer.get(3, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void invalidDeclaredLengthImmediatelyInvalidatesSession() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
            Future<?> peer = executor.submit(() -> {
                try (Socket client = server.accept()) {
                    client.getInputStream().read();
                    client.getOutputStream().write(new byte[]{(byte) 0xD0, 0, 0, (byte) 0xFF, (byte) 0xFF, 3, 0, 1, 0});
                    client.getOutputStream().flush();
                } catch (Exception ignored) {
                    // The client closes its stream when the peer frame is invalid.
                }
            });
            MitsubishiMcConnectionAdapter adapter = connect(server.getLocalPort());
            try {
                assertThrows(Exception.class, () -> adapter.exchange(new byte[]{0x50}, 1000));
                assertFalse(adapter.isConnected());
            } finally {
                adapter.disconnect();
            }
            peer.get(3, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void fragmentedHeaderAndBodyFinishWithinOneDeadline() throws Exception {
        byte[] frame = new byte[]{(byte) 0xD0, 0, 0, (byte) 0xFF, (byte) 0xFF, 3, 0, 4, 0, 0, 0, 42, 0};
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> peer = executor.submit(() -> {
                    try (Socket client = server.accept()) {
                        client.getInputStream().read();
                        int[] cuts = {0, 2, 5, 9, 10, 11, 12, 13};
                        for (int i = 1; i < cuts.length; i++) {
                            client.getOutputStream().write(frame, cuts[i - 1], cuts[i] - cuts[i - 1]);
                            client.getOutputStream().flush();
                            Thread.sleep(12);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                MitsubishiMcConnectionAdapter adapter = connect(server.getLocalPort());
                try {
                    assertArrayEquals(frame, adapter.exchange(new byte[]{0x50}, 800));
                    assertTrue(adapter.isConnected());
                } finally {
                    adapter.disconnect();
                }
                peer.get(3, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private MitsubishiMcConnectionAdapter connect(int port) throws Exception {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("mc-adapter-probe");
        DeviceConnection connection = new DeviceConnection();
        connection.setHost("127.0.0.1");
        connection.setPort(port);
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        MitsubishiMcConnectionAdapter adapter = new MitsubishiMcConnectionAdapter(device, connection);
        adapter.connect();
        return adapter;
    }
}
