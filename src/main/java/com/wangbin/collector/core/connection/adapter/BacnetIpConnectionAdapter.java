package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.bacnet.client.BacnetIpUdpClient;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetIAmDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetWhoIsCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetRemoteDevice;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal BACnet/IP UDP adapter for ReadProperty polling.
 */
@Slf4j
public class BacnetIpConnectionAdapter extends AbstractConnectionAdapter<BacnetIpUdpClient> {

    private DatagramSocket socket;
    private BacnetIpUdpClient client;
    private BacnetRemoteDevice remoteDevice;

    public BacnetIpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    @Override
    protected void doConnect() throws Exception {
        String host = resolveHost();
        Integer port = resolvePort();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Invalid BACnet/IP connection host");
        }
        int resolvedPort = port != null && port > 0 ? port : 47808;
        InetAddress localBindAddress = InetAddress.getByName(config.getStringConfig("localBindHost", "0.0.0.0"));
        int localBindPort = config.getIntConfig("localBindPort", 0) != null
                ? config.getIntConfig("localBindPort", 0)
                : 0;
        socket = new DatagramSocket(new InetSocketAddress(localBindAddress, localBindPort));
        socket.setReuseAddress(true);
        socket.setSoTimeout(resolveReadTimeout());

        InetSocketAddress remoteAddress = new InetSocketAddress(host, resolvedPort);
        client = new BacnetIpUdpClient(socket, remoteAddress);
        Integer remoteDeviceInstance = config.getIntConfig("remoteDeviceInstance", null);
        if (remoteDeviceInstance == null || remoteDeviceInstance < 0) {
            throw new IllegalStateException("BACnet/IP requires remoteDeviceInstance");
        }
        boolean useWhoIsDiscovery = Boolean.TRUE.equals(config.getBoolConfig("useWhoIsDiscovery", false));
        if (useWhoIsDiscovery) {
            remoteDevice = discoverRemoteDevice(remoteAddress, remoteDeviceInstance, resolveReadTimeout());
        }
        if (remoteDevice == null) {
            remoteDevice = client.probeRemoteDevice(remoteDeviceInstance, resolveReadTimeout());
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("host", host);
        params.put("port", resolvedPort);
        params.put("remoteDeviceInstance", remoteDeviceInstance);
        params.put("localBindHost", localBindAddress.getHostAddress());
        params.put("localBindPort", socket.getLocalPort());
        params.put("useWhoIsDiscovery", useWhoIsDiscovery);
        params.put("discoveredByWhoIs", remoteDevice.isDiscoveredByWhoIs());
        connectionParams.clear();
        connectionParams.putAll(params);
        statistics.put("protocol", "BACNET_IP");
        statistics.put("implemented", true);
        statistics.put("transport", "UDP");
        statistics.put("message", useWhoIsDiscovery && remoteDevice.isDiscoveredByWhoIs()
                ? "BACnet/IP UDP adapter connected and remote device discovered by Who-Is"
                : "BACnet/IP UDP adapter connected");
        log.info("BACnet/IP adapter connected, deviceId={}, remote={}:{}, localPort={}",
                getDeviceId(), host, resolvedPort, socket.getLocalPort());
    }

    @Override
    protected void doDisconnect() {
        try {
            if (socket != null) {
                socket.close();
            }
        } finally {
            socket = null;
            client = null;
            remoteDevice = null;
        }
        log.info("BACnet/IP adapter disconnected, deviceId={}", getDeviceId());
    }

    @Override
    protected void doHeartbeat() {
        if (socket == null || socket.isClosed()) {
            throw new IllegalStateException("BACnet/IP UDP socket is not active");
        }
    }

    @Override
    protected void doAuthenticate() {
        // BACnet/IP polling has no separate authentication phase here.
    }

    @Override
    public BacnetIpUdpClient getClient() {
        return client;
    }

    @Override
    public boolean isConnected() {
        return super.isConnected() && socket != null && !socket.isClosed() && client != null;
    }

    public synchronized BacnetReadPropertyResponse readProperty(BacnetReadPropertyRequest request, long timeoutMs) throws Exception {
        if (client == null) {
            throw new IllegalStateException("BACnet/IP client is not initialized");
        }
        BacnetReadPropertyResponse response = client.readProperty(request, timeoutMs > 0 ? timeoutMs : resolveReadTimeout());
        updateActivityTime();
        return response;
    }

    public synchronized BacnetReadPropertyMultipleResponse readPropertyMultiple(BacnetReadPropertyMultipleRequest request,
                                                                                long timeoutMs) throws Exception {
        if (client == null) {
            throw new IllegalStateException("BACnet/IP client is not initialized");
        }
        BacnetReadPropertyMultipleResponse response = client.readPropertyMultiple(request,
                timeoutMs > 0 ? timeoutMs : resolveReadTimeout());
        updateActivityTime();
        return response;
    }

    public BacnetRemoteDevice getRemoteDevice() {
        return remoteDevice;
    }

    private BacnetRemoteDevice discoverRemoteDevice(InetSocketAddress remoteAddress,
                                                    int remoteDeviceInstance,
                                                    int timeoutMs) throws Exception {
        byte[] whoIs = BacnetWhoIsCodec.encode(remoteDeviceInstance, remoteDeviceInstance);
        DatagramPacket packet = new DatagramPacket(whoIs, whoIs.length, remoteAddress);
        socket.send(packet);
        socket.setSoTimeout(timeoutMs);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <= deadline) {
            DatagramPacket response = new DatagramPacket(new byte[2048], 2048);
            try {
                socket.receive(response);
            } catch (SocketTimeoutException ex) {
                break;
            }
            byte[] data = new byte[response.getLength()];
            System.arraycopy(response.getData(), response.getOffset(), data, 0, response.getLength());
            InetSocketAddress from = new InetSocketAddress(response.getAddress(), response.getPort());
            try {
                BacnetRemoteDevice candidate = BacnetIAmDecoder.decode(data, from);
                if (candidate.getDeviceInstance() == remoteDeviceInstance) {
                    log.info("BACnet/IP Who-Is discovery resolved remote device, instance={}, address={}",
                            remoteDeviceInstance, from);
                    return candidate;
                }
            } catch (Exception ignored) {
                // Ignore non-I-Am datagrams during discovery window.
            }
        }
        throw new IllegalStateException("BACnet/IP Who-Is discovery did not receive matching I-Am for remoteDeviceInstance="
                + remoteDeviceInstance);
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
}
