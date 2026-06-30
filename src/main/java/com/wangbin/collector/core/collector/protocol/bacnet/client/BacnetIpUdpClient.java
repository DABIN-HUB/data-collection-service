package com.wangbin.collector.core.collector.protocol.bacnet.client;

import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyMultipleCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyMultipleResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyResponseDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetRemoteDevice;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

@Slf4j
public class BacnetIpUdpClient {

    private static final int MAX_FRAME_SIZE = 2048;

    private final DatagramSocket socket;
    private final InetSocketAddress remoteAddress;

    public BacnetIpUdpClient(DatagramSocket socket, InetSocketAddress remoteAddress) {
        this.socket = socket;
        this.remoteAddress = remoteAddress;
    }

    public BacnetReadPropertyResponse readProperty(BacnetReadPropertyRequest request, long timeoutMs) throws Exception {
        byte[] frame = BacnetReadPropertyCodec.encode(request);
        DatagramPacket packet = new DatagramPacket(frame, frame.length, remoteAddress);
        socket.send(packet);
        socket.setSoTimeout(resolveTimeout(timeoutMs));
        byte[] response = receive();
        return BacnetReadPropertyResponseDecoder.decode(response, request.getInvokeId());
    }

    public BacnetReadPropertyMultipleResponse readPropertyMultiple(BacnetReadPropertyMultipleRequest request,
                                                                   long timeoutMs) throws Exception {
        byte[] frame = BacnetReadPropertyMultipleCodec.encode(request);
        DatagramPacket packet = new DatagramPacket(frame, frame.length, remoteAddress);
        socket.send(packet);
        socket.setSoTimeout(resolveTimeout(timeoutMs));
        byte[] response = receive();
        return BacnetReadPropertyMultipleResponseDecoder.decode(response, request.getInvokeId());
    }

    public BacnetRemoteDevice probeRemoteDevice(int remoteDeviceInstance, int timeoutMs) {
        return BacnetRemoteDevice.builder()
                .deviceInstance(remoteDeviceInstance)
                .socketAddress(remoteAddress)
                .build();
    }

    private byte[] receive() throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[MAX_FRAME_SIZE], MAX_FRAME_SIZE);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException ex) {
            throw new SocketTimeoutException("BACnet/IP receive timed out after " + socket.getSoTimeout() + "ms");
        }
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
        if (log.isDebugEnabled()) {
            log.debug("BACnet/IP UDP response received, bytes={}, from={}", data.length, packet.getSocketAddress());
        }
        return data;
    }

    private int resolveTimeout(long timeoutMs) {
        if (timeoutMs <= 0) {
            return 5000;
        }
        if (timeoutMs > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) timeoutMs;
    }
}
