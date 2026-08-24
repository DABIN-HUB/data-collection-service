package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetMstpFrame;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetMstpFrameCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetMstpFrameType;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetTransportFrameSupport;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.support.InMemoryBacnetSerialChannel;
import com.wangbin.collector.core.collector.protocol.bacnet.transport.BacnetSerialChannel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacnetMstpConnectionAdapterTest {

    @Test
    void shouldReadPropertyOverMstpAndPassToken() throws Exception {
        InMemoryBacnetSerialChannel.ChannelPair pair = InMemoryBacnetSerialChannel.createPair();
        pair.left().open();
        pair.right().open();

        DeviceInfo deviceInfo = new DeviceInfo();
        deviceInfo.setDeviceId("dev-bacnet-mstp-test");
        deviceInfo.setProtocolType("BACNET_MSTP");
        deviceInfo.setConnectionType("BACNET_MSTP");

        DeviceConnection connection = new DeviceConnection();
        connection.setConnectionType("BACNET_MSTP");
        connection.setReadTimeout(1000);
        connection.setTimeout(1000);
        connection.setExtJson(new LinkedHashMap<>());
        connection.getExtJson().put("serialPort", "MEMORY");
        connection.getExtJson().put("localMacAddress", 5);
        connection.getExtJson().put("remoteMacAddress", 8);
        connection.getExtJson().put("remoteDeviceInstance", 1001);
        connection.getExtJson().put("nextStationMac", 8);
        connection.getExtJson().put("remoteIsMaster", true);
        connection.getExtJson().put("tokenClaimTimeoutMs", 2000);
        connection.getExtJson().put("pollForMasterTimeoutMs", 100);
        connection.getExtJson().put("apduTimeout", 1000);
        connection.getExtJson().put("segmentTimeout", 500);
        connection.getExtJson().put("retries", 0);

        TestableBacnetMstpConnectionAdapter adapter = new TestableBacnetMstpConnectionAdapter(deviceInfo, connection, pair.left());
        try (FakeMstpRemoteDevice remoteDevice = new FakeMstpRemoteDevice(pair.right(), 8, 5)) {
            remoteDevice.start();
            adapter.connect();

            BacnetReadPropertyRequest request = BacnetReadPropertyRequest.builder()
                    .objectType(BacnetObjectType.ANALOG_INPUT)
                    .objectInstance(1)
                    .propertyIdentifier(BacnetPropertyIdentifier.PRESENT_VALUE)
                    .invokeId(7)
                    .remoteDeviceInstance(1001)
                    .build();

            BacnetReadPropertyResponse response = adapter.readProperty(request, 1000);

            assertEquals(12.5d, ((Number) response.getValue()).doubleValue(), 0.0001d);
            assertTrue(adapter.getTokenReceiveCount() > 0);
            assertTrue(adapter.getTokenPassCount() > 0);
            assertTrue(remoteDevice.awaitTokenReturned());
        } finally {
            adapter.disconnect();
        }
    }

    private static final class TestableBacnetMstpConnectionAdapter extends BacnetMstpConnectionAdapter {

        private final BacnetSerialChannel serialChannel;

        private TestableBacnetMstpConnectionAdapter(DeviceInfo deviceInfo,
                                                    DeviceConnection config,
                                                    BacnetSerialChannel serialChannel) {
            super(deviceInfo, config);
            this.serialChannel = serialChannel;
        }

        @Override
        protected BacnetSerialChannel createSerialChannel() {
            return serialChannel;
        }
    }

    private static final class FakeMstpRemoteDevice implements AutoCloseable {

        private final InMemoryBacnetSerialChannel serialChannel;
        private final int localMac;
        private final int clientMac;
        private final CountDownLatch tokenReturned = new CountDownLatch(1);
        private volatile boolean running = true;
        private Thread thread;

        private FakeMstpRemoteDevice(InMemoryBacnetSerialChannel serialChannel, int localMac, int clientMac) {
            this.serialChannel = serialChannel;
            this.localMac = localMac;
            this.clientMac = clientMac;
        }

        private void start() {
            thread = new Thread(this::runLoop, "fake-bacnet-mstp-remote");
            thread.setDaemon(true);
            thread.start();
        }

        private boolean awaitTokenReturned() throws InterruptedException {
            return tokenReturned.await(2, TimeUnit.SECONDS);
        }

        private void runLoop() {
            try {
                Thread.sleep(100);
                sendFrame(BacnetMstpFrameType.TOKEN, clientMac, new byte[0]);
                while (running) {
                    BacnetMstpFrame frame = BacnetMstpFrameCodec.read(serialChannel, 250);
                    if (frame == null) {
                        continue;
                    }
                    switch (frame.frameType()) {
                        case TOKEN -> {
                            if (frame.destinationAddress() == localMac) {
                                tokenReturned.countDown();
                            }
                        }
                        case POLL_FOR_MASTER -> {
                            if (frame.destinationAddress() == localMac) {
                                sendFrame(BacnetMstpFrameType.REPLY_TO_POLL_FOR_MASTER, frame.sourceAddress(), new byte[0]);
                            }
                        }
                        case BACNET_DATA_EXPECTING_REPLY -> {
                            if (frame.destinationAddress() == localMac) {
                                int invokeId = Byte.toUnsignedInt(frame.data()[4]);
                                sendFrame(BacnetMstpFrameType.BACNET_DATA_NOT_EXPECTING_REPLY,
                                        frame.sourceAddress(),
                                        buildReadPropertyAck(invokeId));
                            }
                        }
                        default -> {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void sendFrame(BacnetMstpFrameType frameType, int destinationAddress, byte[] payload) throws Exception {
            byte[] encoded = BacnetMstpFrameCodec.encode(new BacnetMstpFrame(frameType, destinationAddress, localMac, payload));
            serialChannel.write(encoded);
        }

        private byte[] buildReadPropertyAck(int invokeId) {
            ByteArrayOutputStream apdu = new ByteArrayOutputStream();
            apdu.write(BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK << 4);
            apdu.write(invokeId & 0xFF);
            apdu.write(BacnetReadPropertyCodec.SERVICE_CHOICE_READ_PROPERTY);
            writeContextObjectIdentifier(apdu, 0, BacnetObjectType.ANALOG_INPUT.getId(), 1);
            writeContextUnsigned(apdu, 1, BacnetPropertyIdentifier.PRESENT_VALUE.getId());
            apdu.write(0x3E);
            apdu.writeBytes(encodeReal(12.5f));
            apdu.write(0x3F);

            ByteArrayOutputStream npdu = new ByteArrayOutputStream();
            npdu.write(BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION);
            npdu.write(0x00);
            npdu.writeBytes(apdu.toByteArray());
            return npdu.toByteArray();
        }

        private void writeContextObjectIdentifier(ByteArrayOutputStream out, int contextId, int objectTypeId, int instance) {
            out.write((contextId << 4) | 0x0C);
            int raw = ((objectTypeId & 0x03FF) << 22) | (instance & 0x3FFFFF);
            writeUnsigned(out, raw, 4);
        }

        private void writeContextUnsigned(ByteArrayOutputStream out, int contextId, int value) {
            int length = value <= 0xFF ? 1 : 2;
            out.write((contextId << 4) | 0x08 | length);
            writeUnsigned(out, value, length);
        }

        private void writeUnsigned(ByteArrayOutputStream out, long value, int bytes) {
            for (int i = bytes - 1; i >= 0; i--) {
                out.write((int) ((value >> (i * 8)) & 0xFF));
            }
        }

        private byte[] encodeReal(float value) {
            ByteBuffer payload = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
            payload.put((byte) ((4 << 4) | 4));
            payload.putFloat(value);
            return payload.array();
        }

        @Override
        public void close() throws Exception {
            running = false;
            if (thread != null) {
                thread.join(2000);
            }
        }
    }
}