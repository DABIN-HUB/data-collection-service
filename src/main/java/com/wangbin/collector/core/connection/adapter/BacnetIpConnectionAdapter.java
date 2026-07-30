package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.bacnet.client.BacnetIpUdpClient;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetBvlcCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetIAmDecoder;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetReadPropertyCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.codec.BacnetWhoIsCodec;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetRemoteDevice;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyMultipleRequest;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * BACnet/IP UDP connection adapter with polling, segmented APDU support,
 * COV notification handling and optional BBMD/Foreign Device lifecycle.
 */
@Slf4j
public class BacnetIpConnectionAdapter extends AbstractConnectionAdapter<BacnetIpUdpClient> implements BacnetConnectionAdapter {

    private DatagramSocket socket;
    private BacnetIpUdpClient client;
    private BacnetRemoteDevice remoteDevice;
    private volatile Consumer<BacnetCovNotification> covNotificationListener;
    private volatile Runnable reconnectListener;

    private final ScheduledExecutorService protocolScheduler;
    private final AtomicLong foreignDeviceRegistrationCount = new AtomicLong(0);
    private final AtomicLong foreignDeviceRenewCount = new AtomicLong(0);
    private final AtomicLong foreignDeviceRenewFailureCount = new AtomicLong(0);

    private volatile InetSocketAddress bbmdAddress;
    private volatile Integer foreignDeviceTtlSeconds;
    private volatile long foreignDeviceLeaseExpiresAt;
    private volatile ScheduledFuture<?> foreignDeviceRenewTask;

    public BacnetIpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        this(deviceInfo, config, null);
    }

    public BacnetIpConnectionAdapter(DeviceInfo deviceInfo,
                                     DeviceConnection config,
                                     ScheduledExecutorService protocolScheduler) {
        super(deviceInfo, config);
        this.protocolScheduler = protocolScheduler;
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
        Integer configuredBindPort = config.getIntConfig("localBindPort", 0);
        int localBindPort = configuredBindPort != null ? configuredBindPort : 0;
        socket = new DatagramSocket(new InetSocketAddress(localBindAddress, localBindPort));
        socket.setReuseAddress(true);
        socket.setSoTimeout(resolveReadTimeout());

        InetSocketAddress configuredRemoteAddress = new InetSocketAddress(host, resolvedPort);
        bbmdAddress = resolveBbmdAddress();
        foreignDeviceTtlSeconds = resolveForeignDeviceTtlSeconds();
        registerForeignDeviceIfConfigured();

        Integer remoteDeviceInstance = config.getIntConfig("remoteDeviceInstance", null);
        if (remoteDeviceInstance == null || remoteDeviceInstance < 0) {
            throw new IllegalStateException("BACnet/IP requires remoteDeviceInstance");
        }
        boolean useWhoIsDiscovery = Boolean.TRUE.equals(config.getBoolConfig("useWhoIsDiscovery", false));
        if (useWhoIsDiscovery) {
            boolean useBbmdBroadcast = bbmdAddress != null;
            InetSocketAddress discoveryTarget = useBbmdBroadcast ? bbmdAddress : configuredRemoteAddress;
            remoteDevice = discoverRemoteDevice(discoveryTarget,
                    remoteDeviceInstance,
                    resolveApduTimeout(),
                    useBbmdBroadcast);
        }
        if (remoteDevice == null) {
            remoteDevice = BacnetRemoteDevice.builder()
                    .deviceInstance(remoteDeviceInstance)
                    .socketAddress(configuredRemoteAddress)
                    .discoveredByWhoIs(false)
                    .build();
        }

        InetSocketAddress activeRemoteAddress = remoteDevice.getSocketAddress() != null
                ? remoteDevice.getSocketAddress()
                : configuredRemoteAddress;
        client = new BacnetIpUdpClient(socket, activeRemoteAddress);
        if (covNotificationListener != null) {
            client.setCovNotificationHandler(covNotificationListener);
        }
        scheduleForeignDeviceRenewalIfPossible();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("host", host);
        params.put("port", resolvedPort);
        params.put("targetHost", activeRemoteAddress.getHostString());
        params.put("targetPort", activeRemoteAddress.getPort());
        params.put("remoteDeviceInstance", remoteDeviceInstance);
        params.put("localBindHost", localBindAddress.getHostAddress());
        params.put("localBindPort", socket.getLocalPort());
        params.put("useWhoIsDiscovery", useWhoIsDiscovery);
        params.put("discoveredByWhoIs", remoteDevice.isDiscoveredByWhoIs());
        params.put("apduTimeoutMs", resolveApduTimeout());
        params.put("segmentTimeoutMs", resolveSegmentTimeout());
        params.put("retries", resolveRequestRetries());
        if (bbmdAddress != null) {
            params.put("bbmdHost", bbmdAddress.getHostString());
            params.put("bbmdPort", bbmdAddress.getPort());
            params.put("foreignDeviceTtlSeconds", foreignDeviceTtlSeconds);
            params.put("foreignDeviceLeaseExpiresAt", foreignDeviceLeaseExpiresAt);
        }
        connectionParams.clear();
        connectionParams.putAll(params);
        statistics.put("protocol", "BACNET_IP");
        statistics.put("implemented", true);
        statistics.put("transport", "UDP");
        statistics.put("message", useWhoIsDiscovery && remoteDevice.isDiscoveredByWhoIs()
                ? "BACnet/IP UDP adapter connected and remote device discovered by Who-Is"
                : "BACnet/IP UDP adapter connected");
        log.info("BACnet/IP adapter connected, deviceId={}, configuredRemote={}:{}, targetRemote={}:{}, localPort={}, retries={}, apduTimeoutMs={}, bbmd={}"
                        + "", getDeviceId(),
                host,
                resolvedPort,
                activeRemoteAddress.getHostString(),
                activeRemoteAddress.getPort(),
                socket.getLocalPort(),
                resolveRequestRetries(),
                resolveApduTimeout(),
                bbmdAddress != null ? bbmdAddress : "disabled");
        notifyReconnectListener();
    }

    @Override
    protected void doDisconnect() throws Exception {
        cancelForeignDeviceRenewalTask();
        foreignDeviceLeaseExpiresAt = 0L;
        bbmdAddress = null;
        foreignDeviceTtlSeconds = null;

        BacnetIpUdpClient existingClient = client;
        client = null;
        try {
            if (existingClient != null) {
                existingClient.close();
            }
        } finally {
            try {
                if (socket != null) {
                    socket.close();
                }
            } finally {
                socket = null;
                remoteDevice = null;
            }
        }
        log.info("BACnet/IP adapter disconnected, deviceId={}", getDeviceId());
    }

    @Override
    protected void doHeartbeat() throws Exception {
        if (socket == null || socket.isClosed()) {
            throw new IllegalStateException("BACnet/IP UDP socket is not active");
        }
        ensureForeignDeviceLease();
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

    public void setCovNotificationListener(Consumer<BacnetCovNotification> listener) {
        this.covNotificationListener = listener;
        if (client != null) {
            client.setCovNotificationHandler(listener);
        }
    }

    @Override
    public void setReconnectListener(Runnable listener) {
        this.reconnectListener = listener;
    }

    public synchronized BacnetReadPropertyResponse readProperty(BacnetReadPropertyRequest request, long timeoutMs) throws Exception {
        ensureForeignDeviceLease();
        BacnetIpUdpClient activeClient = requireClient();
        BacnetReadPropertyResponse response = activeClient.readProperty(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveSegmentTimeout(),
                resolveRequestRetries());
        updateActivityTime();
        return response;
    }

    public synchronized BacnetReadPropertyMultipleResponse readPropertyMultiple(BacnetReadPropertyMultipleRequest request,
                                                                                long timeoutMs) throws Exception {
        ensureForeignDeviceLease();
        BacnetIpUdpClient activeClient = requireClient();
        BacnetReadPropertyMultipleResponse response = activeClient.readPropertyMultiple(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveSegmentTimeout(),
                resolveRequestRetries());
        updateActivityTime();
        return response;
    }

    public synchronized void writeProperty(BacnetWritePropertyRequest request, long timeoutMs) throws Exception {
        ensureForeignDeviceLease();
        BacnetIpUdpClient activeClient = requireClient();
        activeClient.writeProperty(request, timeoutMs > 0 ? timeoutMs : resolveApduTimeout(), resolveRequestRetries());
        updateActivityTime();
    }

    @Override
    public synchronized void writePropertyMultiple(BacnetWritePropertyMultipleRequest request, long timeoutMs) throws Exception {
        ensureForeignDeviceLease();
        BacnetIpUdpClient activeClient = requireClient();
        activeClient.writePropertyMultiple(request, timeoutMs > 0 ? timeoutMs : resolveApduTimeout(), resolveRequestRetries());
        updateActivityTime();
    }

    public synchronized void subscribeCov(BacnetSubscribeCovRequest request, long timeoutMs) throws Exception {
        ensureForeignDeviceLease();
        BacnetIpUdpClient activeClient = requireClient();
        activeClient.subscribeCov(request, timeoutMs > 0 ? timeoutMs : resolveApduTimeout(), resolveRequestRetries());
        updateActivityTime();
    }

    public synchronized void subscribeCovProperty(BacnetSubscribeCovPropertyRequest request, long timeoutMs) throws Exception {
        ensureForeignDeviceLease();
        BacnetIpUdpClient activeClient = requireClient();
        activeClient.subscribeCovProperty(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveRequestRetries());
        updateActivityTime();
    }

    @Override
    public synchronized void acknowledgeConfirmedCovNotification(int invokeId) throws Exception {
        ensureForeignDeviceLease();
        BacnetIpUdpClient activeClient = requireClient();
        activeClient.acknowledgeConfirmedCovNotification(invokeId);
        updateActivityTime();
    }

    @Override
    public String getTransportName() {
        return "UDP";
    }

    @Override
    public BacnetRemoteDevice getRemoteDevice() {
        return remoteDevice;
    }

    public long getRequestRetryCount() {
        return client != null ? client.getRequestRetryCount() : 0L;
    }

    public long getRequestTimeoutCount() {
        return client != null ? client.getRequestTimeoutCount() : 0L;
    }

    public long getInvokeIdMismatchCount() {
        return client != null ? client.getInvokeIdMismatchCount() : 0L;
    }

    public long getCovNotificationCount() {
        return client != null ? client.getCovNotificationCount() : 0L;
    }

    public long getSegmentedResponseCount() {
        return client != null ? client.getSegmentedResponseCount() : 0L;
    }

    public boolean isForeignDeviceRegistrationActive() {
        return bbmdAddress != null && foreignDeviceLeaseExpiresAt > System.currentTimeMillis();
    }

    public long getForeignDeviceRegistrationCount() {
        return foreignDeviceRegistrationCount.get();
    }

    public long getForeignDeviceRenewCount() {
        return foreignDeviceRenewCount.get();
    }

    public long getForeignDeviceRenewFailureCount() {
        return foreignDeviceRenewFailureCount.get();
    }

    public long getForeignDeviceLeaseExpiresAt() {
        return foreignDeviceLeaseExpiresAt;
    }

    private BacnetRemoteDevice discoverRemoteDevice(InetSocketAddress targetAddress,
                                                    int remoteDeviceInstance,
                                                    int timeoutMs,
                                                    boolean useBbmdBroadcast) throws Exception {
        byte[] whoIs = BacnetWhoIsCodec.encode(remoteDeviceInstance, remoteDeviceInstance);
        if (useBbmdBroadcast) {
            whoIs = BacnetBvlcCodec.wrapWithFunction(whoIs, BacnetReadPropertyCodec.BVLC_DISTRIBUTE_BROADCAST_TO_NETWORK);
        }
        int previousTimeout = socket.getSoTimeout();
        try {
            DatagramPacket packet = new DatagramPacket(whoIs, whoIs.length, targetAddress);
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
                                remoteDeviceInstance, candidate.getSocketAddress());
                        return candidate;
                    }
                } catch (Exception ignored) {
                    // Ignore non-I-Am datagrams during discovery window.
                }
            }
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
        throw new IllegalStateException("BACnet/IP Who-Is discovery did not receive matching I-Am for remoteDeviceInstance="
                + remoteDeviceInstance);
    }

    private void registerForeignDeviceIfConfigured() throws Exception {
        if (bbmdAddress == null || foreignDeviceTtlSeconds == null || foreignDeviceTtlSeconds <= 0) {
            return;
        }
        performForeignDeviceRegistration(true);
    }

    private synchronized void ensureForeignDeviceLease() throws Exception {
        if (!needsForeignDeviceRenewal(System.currentTimeMillis())) {
            return;
        }
        try {
            performForeignDeviceRegistration(false);
        } catch (Exception ex) {
            foreignDeviceRenewFailureCount.incrementAndGet();
            throw ex;
        }
    }

    private void notifyReconnectListener() {
        Runnable listener = reconnectListener;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (Exception ex) {
            log.warn("BACnet/IP reconnect listener failed, deviceId={}", getDeviceId(), ex);
        }
    }

    private synchronized void performForeignDeviceRegistration(boolean initialRegistration) throws Exception {
        if (bbmdAddress == null || foreignDeviceTtlSeconds == null || foreignDeviceTtlSeconds <= 0) {
            return;
        }
        int timeoutMs = resolveApduTimeout();
        if (client != null) {
            client.registerForeignDevice(bbmdAddress, foreignDeviceTtlSeconds, timeoutMs, resolveRequestRetries());
        } else {
            rawRegisterForeignDevice(bbmdAddress, foreignDeviceTtlSeconds, timeoutMs);
        }
        foreignDeviceLeaseExpiresAt = System.currentTimeMillis() + (foreignDeviceTtlSeconds * 1000L);
        if (initialRegistration) {
            foreignDeviceRegistrationCount.incrementAndGet();
        } else {
            foreignDeviceRenewCount.incrementAndGet();
        }
        connectionParams.put("foreignDeviceLeaseExpiresAt", foreignDeviceLeaseExpiresAt);
    }

    private void rawRegisterForeignDevice(InetSocketAddress targetAddress,
                                          int ttlSeconds,
                                          int timeoutMs) throws Exception {
        byte[] request = BacnetBvlcCodec.encodeRegisterForeignDevice(ttlSeconds);
        int previousTimeout = socket.getSoTimeout();
        try {
            DatagramPacket packet = new DatagramPacket(request, request.length, targetAddress);
            socket.send(packet);
            socket.setSoTimeout(timeoutMs);
            DatagramPacket response = new DatagramPacket(new byte[64], 64);
            socket.receive(response);
            byte[] responseBytes = new byte[response.getLength()];
            System.arraycopy(response.getData(), response.getOffset(), responseBytes, 0, response.getLength());
            BacnetBvlcCodec.verifyResult(responseBytes, BacnetBvlcCodec.BVLC_RESULT_CODE_SUCCESSFUL_COMPLETION);
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
    }

    private void scheduleForeignDeviceRenewalIfPossible() {
        cancelForeignDeviceRenewalTask();
        if (protocolScheduler == null || bbmdAddress == null || foreignDeviceTtlSeconds == null || foreignDeviceTtlSeconds <= 0) {
            return;
        }
        long intervalMs = resolveForeignDeviceRenewIntervalMs();
        foreignDeviceRenewTask = protocolScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!needsForeignDeviceRenewal(System.currentTimeMillis())) {
                    return;
                }
                performForeignDeviceRegistration(false);
            } catch (Exception ex) {
                foreignDeviceRenewFailureCount.incrementAndGet();
                log.warn("BACnet/IP Foreign Device renew failed, deviceId={}, bbmd={}",
                        getDeviceId(), bbmdAddress, ex);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void cancelForeignDeviceRenewalTask() {
        ScheduledFuture<?> task = foreignDeviceRenewTask;
        foreignDeviceRenewTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private boolean needsForeignDeviceRenewal(long now) {
        if (bbmdAddress == null || foreignDeviceTtlSeconds == null || foreignDeviceTtlSeconds <= 0) {
            return false;
        }
        if (foreignDeviceLeaseExpiresAt <= 0) {
            return true;
        }
        return now >= (foreignDeviceLeaseExpiresAt - resolveForeignDeviceRenewLeadMs());
    }

    private long resolveForeignDeviceRenewIntervalMs() {
        long ttlMs = foreignDeviceTtlSeconds * 1000L;
        return Math.max(500L, ttlMs - resolveForeignDeviceRenewLeadMs());
    }

    private long resolveForeignDeviceRenewLeadMs() {
        long ttlMs = foreignDeviceTtlSeconds * 1000L;
        return Math.max(250L, Math.min(5000L, ttlMs / 4));
    }

    private InetSocketAddress resolveBbmdAddress() {
        String host = config.getStringConfig("bbmdHost", null);
        Integer port = config.getIntConfig("bbmdPort", null);
        if (host == null || host.isBlank() || port == null || port <= 0) {
            return null;
        }
        return new InetSocketAddress(host, port);
    }

    private Integer resolveForeignDeviceTtlSeconds() {
        Integer ttlSeconds = config.getIntConfig("foreignDeviceTtlSeconds", null);
        return ttlSeconds != null && ttlSeconds > 0 ? ttlSeconds : null;
    }

    private BacnetIpUdpClient requireClient() {
        if (client == null) {
            throw new IllegalStateException("BACnet/IP client is not initialized");
        }
        return client;
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

    private int resolveApduTimeout() {
        Integer apduTimeout = config.getIntConfig("apduTimeout", null);
        if (apduTimeout == null || apduTimeout <= 0) {
            apduTimeout = config.getIntConfig("apduTimeoutMs", null);
        }
        if (apduTimeout != null && apduTimeout > 0) {
            return apduTimeout;
        }
        return resolveReadTimeout();
    }

    private int resolveSegmentTimeout() {
        Integer segmentTimeout = config.getIntConfig("segmentTimeout", null);
        if (segmentTimeout == null || segmentTimeout <= 0) {
            segmentTimeout = config.getIntConfig("segmentTimeoutMs", null);
        }
        if (segmentTimeout != null && segmentTimeout > 0) {
            return segmentTimeout;
        }
        return resolveApduTimeout();
    }

    private int resolveRequestRetries() {
        Integer retries = config.getIntConfig("retries", null);
        if (retries == null) {
            retries = config.getRetries();
        }
        if (retries == null || retries < 0) {
            return 0;
        }
        return retries;
    }
}
