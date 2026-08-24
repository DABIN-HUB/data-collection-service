package com.wangbin.collector.core.collector.protocol.bacnet.support;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class FakeBbmdServer implements AutoCloseable {

    private static final int APDU_TYPE_UNCONFIRMED_REQUEST = 0x01;
    private static final int SERVICE_CHOICE_WHO_IS = 0x08;

    private final DatagramSocket socket;
    private final InetSocketAddress forwardedDeviceAddress;
    private final int remoteDeviceInstance;
    private final int maxApduLengthAccepted;
    private final int vendorId;
    private final AtomicLong foreignDeviceRegistrationCount = new AtomicLong(0);
    private final AtomicLong distributeBroadcastCount = new AtomicLong(0);
    private final AtomicReference<RuntimeException> asyncFailure = new AtomicReference<>();
    private final CountDownLatch started = new CountDownLatch(1);

    private volatile boolean running = true;
    private volatile int lastForeignDeviceTtlSeconds;
    private Thread serverThread;

    public FakeBbmdServer(InetSocketAddress forwardedDeviceAddress,
                          int remoteDeviceInstance,
                          int maxApduLengthAccepted,
                          int vendorId) throws Exception {
        this.socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        this.socket.setSoTimeout(250);
        this.forwardedDeviceAddress = forwardedDeviceAddress;
        this.remoteDeviceInstance = remoteDeviceInstance;
        this.maxApduLengthAccepted = maxApduLengthAccepted;
        this.vendorId = vendorId;
        this.serverThread = new Thread(this::runLoop, "fake-bbmd-server");
        this.serverThread.setDaemon(true);
        this.serverThread.start();
        awaitStart();
    }

    public int port() {
        return socket.getLocalPort();
    }

    public long getForeignDeviceRegistrationCount() {
        return foreignDeviceRegistrationCount.get();
    }

    public long getDistributeBroadcastCount() {
        return distributeBroadcastCount.get();
    }

    public int getLastForeignDeviceTtlSeconds() {
        return lastForeignDeviceTtlSeconds;
    }

    private void awaitStart() throws Exception {
        if (!started.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Fake BBMD server failed to start");
        }
    }

    private void runLoop() {
        started.countDown();
        byte[] buffer = new byte[2048];
        while (running) {
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(request);
                byte[] payload = new byte[request.getLength()];
                System.arraycopy(request.getData(), request.getOffset(), payload, 0, request.getLength());
                byte[] response = handleRequest(payload);
                if (response != null) {
                    DatagramPacket packet = new DatagramPacket(response, response.length, request.getSocketAddress());
                    socket.send(packet);
                }
            } catch (SocketTimeoutException ignored) {
                // 轮询循环。
            } catch (Exception ex) {
                if (running) {
                    asyncFailure.compareAndSet(null, new RuntimeException("Fake BBMD server failed", ex));
                }
            }
        }
    }

    private byte[] handleRequest(byte[] frame) {
        verifyNoAsyncFailure();
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int bvlcType = Byte.toUnsignedInt(buffer.get());
        if (bvlcType != BacnetReadPropertyCodec.BVLC_TYPE_IP) {
            throw new IllegalArgumentException("Unexpected BVLC type: " + bvlcType);
        }
        int function = Byte.toUnsignedInt(buffer.get());
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length != frame.length) {
            throw new IllegalArgumentException("BVLC length mismatch");
        }
        return switch (function) {
            case BacnetReadPropertyCodec.BVLC_REGISTER_FOREIGN_DEVICE -> handleRegisterForeignDevice(buffer);
            case BacnetReadPropertyCodec.BVLC_DISTRIBUTE_BROADCAST_TO_NETWORK -> handleDistributeBroadcast(frame);
            default -> null;
        };
    }

    private byte[] handleRegisterForeignDevice(ByteBuffer buffer) {
        foreignDeviceRegistrationCount.incrementAndGet();
        lastForeignDeviceTtlSeconds = Short.toUnsignedInt(buffer.getShort());
        return buildBvlcResult(0x0000);
    }

    private byte[] handleDistributeBroadcast(byte[] frame) {
        if (!isWhoIs(frame)) {
            return null;
        }
        distributeBroadcastCount.incrementAndGet();
        return buildForwardedIAm();
    }

    private boolean isWhoIs(byte[] frame) {
        if (frame == null || frame.length < 8) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        buffer.get();
        int function = Byte.toUnsignedInt(buffer.get());
        if (function != BacnetReadPropertyCodec.BVLC_DISTRIBUTE_BROADCAST_TO_NETWORK) {
            return false;
        }
        buffer.getShort();
        if (Byte.toUnsignedInt(buffer.get()) != BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION) {
            return false;
        }
        buffer.get();
        int apduHeader = Byte.toUnsignedInt(buffer.get());
        int pduType = (apduHeader >> 4) & 0x0F;
        if (pduType != APDU_TYPE_UNCONFIRMED_REQUEST) {
            return false;
        }
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        return serviceChoice == SERVICE_CHOICE_WHO_IS;
    }

    private byte[] buildBvlcResult(int resultCode) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(BacnetReadPropertyCodec.BVLC_TYPE_IP);
        out.write(BacnetReadPropertyCodec.BVLC_RESULT);
        out.write(0);
        out.write(6);
        out.write((resultCode >> 8) & 0xFF);
        out.write(resultCode & 0xFF);
        return out.toByteArray();
    }

    private byte[] buildForwardedIAm() {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(APDU_TYPE_UNCONFIRMED_REQUEST << 4);
        apdu.write(0x00);
        apdu.writeBytes(encodeApplicationObjectIdentifier(BacnetObjectType.DEVICE, remoteDeviceInstance));
        apdu.writeBytes(encodeUnsignedApplication(maxApduLengthAccepted, 2));
        apdu.writeBytes(encodeEnumeratedApplication(3));
        apdu.writeBytes(encodeUnsignedApplication(vendorId, 2));

        ByteArrayOutputStream npdu = new ByteArrayOutputStream();
        npdu.write(BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION);
        npdu.write(0x00);
        npdu.writeBytes(apdu.toByteArray());
        byte[] npduBytes = npdu.toByteArray();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(BacnetReadPropertyCodec.BVLC_TYPE_IP);
        frame.write(BacnetReadPropertyCodec.BVLC_FORWARDED_NPDU);
        int totalLength = npduBytes.length + 10;
        frame.write((totalLength >> 8) & 0xFF);
        frame.write(totalLength & 0xFF);
        frame.writeBytes(forwardedDeviceAddress.getAddress().getAddress());
        frame.write((forwardedDeviceAddress.getPort() >> 8) & 0xFF);
        frame.write(forwardedDeviceAddress.getPort() & 0xFF);
        frame.writeBytes(npduBytes);
        return frame.toByteArray();
    }

    private byte[] encodeUnsignedApplication(int value, int applicationTypeId) {
        byte[] raw = encodeUnsigned(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((applicationTypeId << 4) | (raw.length & 0x07));
        out.writeBytes(raw);
        return out.toByteArray();
    }

    private byte[] encodeEnumeratedApplication(int value) {
        byte[] raw = encodeUnsigned(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((9 << 4) | (raw.length & 0x07));
        out.writeBytes(raw);
        return out.toByteArray();
    }

    private byte[] encodeApplicationObjectIdentifier(BacnetObjectType objectType, int instance) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((12 << 4) | 4);
        out.writeBytes(encodeObjectIdentifier(objectType, instance));
        return out.toByteArray();
    }

    private byte[] encodeObjectIdentifier(BacnetObjectType objectType, int instance) {
        int raw = ((objectType.getId() & 0x03FF) << 22) | (instance & 0x3FFFFF);
        return new byte[]{
                (byte) ((raw >> 24) & 0xFF),
                (byte) ((raw >> 16) & 0xFF),
                (byte) ((raw >> 8) & 0xFF),
                (byte) (raw & 0xFF)
        };
    }

    private byte[] encodeUnsigned(int value) {
        if (value <= 0xFF) {
            return new byte[]{(byte) value};
        }
        if (value <= 0xFFFF) {
            return new byte[]{(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
        }
        if (value <= 0xFFFFFF) {
            return new byte[]{(byte) ((value >> 16) & 0xFF), (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
        }
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private void verifyNoAsyncFailure() {
        RuntimeException failure = asyncFailure.get();
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        running = false;
        try {
            socket.close();
        } finally {
            if (serverThread != null) {
                serverThread.join(2000);
            }
        }
    }
}