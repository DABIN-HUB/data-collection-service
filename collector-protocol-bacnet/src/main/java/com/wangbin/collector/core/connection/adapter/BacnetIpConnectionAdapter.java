package com.wangbin.collector.core.connection.adapter;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.ConnectionStatus;
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
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * BACnet/IP UDP 连接 适配器 with polling, segmented APDU support,
 * COV 通知 handling and optional BBMD/Foreign 设备 lifecycle.
 */
@Slf4j
public class BacnetIpConnectionAdapter extends AbstractConnectionAdapter<BacnetIpUdpClient> implements BacnetConnectionAdapter {

    // 生命周期锁只保护身份和状态；每次连接的独立交换锁负责串行化协议请求。
    private final Object lifecycleLock = new Object();
    private volatile ConnectionSession session;
    private final ThreadLocal<ConnectionSession> callbackSession = new ThreadLocal<>();
    private boolean resourcesClosed;
    private int reconnectAttempts;
    private long currentReconnectDelay;
    private volatile Consumer<BacnetCovNotification> covNotificationListener;
    private volatile Runnable reconnectListener;

    private final ScheduledExecutorService protocolScheduler;
    private final AtomicLong foreignDeviceRegistrationCount = new AtomicLong(0);
    private final AtomicLong foreignDeviceRenewCount = new AtomicLong(0);
    private final AtomicLong foreignDeviceRenewFailureCount = new AtomicLong(0);

    /** 本次连接独占的资源，旧操作只能持有并释放自己的实例。 */
    private static final class ConnectionSession {
        private final Object exchangeLock = new Object();
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private volatile DatagramSocket socket;
        private volatile BacnetIpUdpClient client;
        private volatile BacnetRemoteDevice remoteDevice;
        private volatile InetSocketAddress bbmdAddress;
        private volatile Integer foreignDeviceTtlSeconds;
        private volatile long foreignDeviceLeaseExpiresAt;
        private ScheduledFuture<?> foreignDeviceRenewTask;
    }

    /**
     * 创建当前组件实例。
     */
    public BacnetIpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        this(deviceInfo, config, null);
    }

    /**
     * 创建当前组件实例。
     */
    public BacnetIpConnectionAdapter(DeviceInfo deviceInfo,
                                     DeviceConnection config,
                                     ScheduledExecutorService protocolScheduler) {
        super(deviceInfo, config);
        this.protocolScheduler = protocolScheduler;
        this.currentReconnectDelay = this.config.getInitialReconnectDelay();
    }

    /** 基类会跳过建连中的断开并无条件提交旧操作结果，此处按连接身份提交。 */
    @Override
    public void connect() throws Exception {
        connectSession(false, null);
    }

    @Override
    public void reconnect() throws Exception {
        connectSession(true, null);
    }

    private void connectSession(boolean reconnect, ConnectionSession expectedSession) throws Exception {
        ConnectionSession activeSession;
        long delay;
        synchronized (lifecycleLock) {
            rejectStaleCallback();
            if (expectedSession != null && session != expectedSession) {
                return;
            }
            if (resourcesClosed) {
                throw new IllegalStateException("BACnet/IP adapter resources have been closed");
            }
            if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.RECONNECTING) {
                throw new IllegalStateException("BACnet/IP connection is already in progress");
            }
            if (!reconnect && isConnected()) {
                return;
            }
            if (reconnect && config.getMaxReconnectAttempts() > 0
                    && reconnectAttempts >= config.getMaxReconnectAttempts()) {
                closeSession(session);
                session = null;
                connectionParams.clear();
                statistics.remove(CommonMapKeys.MESSAGE);
                status = ConnectionStatus.DISCONNECTED;
                metrics.setStatus(status);
                throw new IllegalStateException("Reach max reconnect attempts");
            }
            closeSession(session);
            connectionParams.clear();
            activeSession = new ConnectionSession();
            session = activeSession;
            status = reconnect ? ConnectionStatus.RECONNECTING : ConnectionStatus.CONNECTING;
            metrics.setStatus(status);
            delay = reconnect ? (reconnectAttempts == 0 ? currentReconnectDelay
                    : (long) (currentReconnectDelay * (0.9 + Math.random() * 0.2))) : 0L;
        }
        try {
            // 断开也取消重连退避，避免休眠结束后复活已释放的连接。
            if (delay > 0 && activeSession.cancelled.await(delay, TimeUnit.MILLISECONDS)) {
                throw cancelledOperation(null);
            }
            initializeConnection(activeSession);
            synchronized (lifecycleLock) {
                requireCurrentSession(activeSession);
                status = ConnectionStatus.CONNECTED;
                lastActivityTime = System.currentTimeMillis();
                metrics.setConnectTime(lastActivityTime);
                metrics.setStatus(status);
                metrics.setLastError(null);
                reconnectAttempts = 0;
                currentReconnectDelay = config.getInitialReconnectDelay();
                scheduleForeignDeviceRenewalIfPossible(activeSession);
                log.info("BACnet/IP 连接已建立, 设备={}, 目标地址={}", getDeviceId(), activeSession.remoteDevice.getSocketAddress());
            }
        } catch (Exception | Error ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            synchronized (lifecycleLock) {
                closeSession(activeSession);
                if (session != activeSession) {
                    if (ex instanceof Error error) {
                        throw error;
                    }
                    throw cancelledOperation(ex);
                }
                session = null;
                connectionParams.clear();
                statistics.remove(CommonMapKeys.MESSAGE);
                status = ConnectionStatus.ERROR;
                metrics.setStatus(status);
                metrics.setLastError(ex.getMessage());
                errors.incrementAndGet();
                if (reconnect) {
                    reconnectAttempts++;
                    currentReconnectDelay = Math.min((long) (currentReconnectDelay
                            * config.getReconnectBackoffMultiplier()), config.getMaxReconnectDelay());
                }
            }
            throw ex;
        }
        notifyReconnectListener(activeSession);
    }

    @Override
    public void disconnect() {
        doDisconnect();
    }

    /** 最终释放后禁止后台心跳或重连任务重新使用已退役的适配器。 */
    @Override
    public void closeResources() {
        synchronized (lifecycleLock) {
            rejectStaleCallback();
            resourcesClosed = true;
            doDisconnect();
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doConnect() throws Exception {
        connect();
    }

    private void initializeConnection(ConnectionSession activeSession) throws Exception {
        requireCurrentSession(activeSession);
        String host = resolveHost();
        Integer port = resolvePort();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Invalid BACnet/IP connection host");
        }
        int resolvedPort = port != null && port > 0 ? port : 47808;
        InetAddress localBindAddress = InetAddress.getByName(config.getStringConfig("localBindHost", "0.0.0.0"));
        Integer configuredBindPort = config.getIntConfig("localBindPort", 0);
        int localBindPort = configuredBindPort != null ? configuredBindPort : 0;
        InetSocketAddress configuredRemoteAddress = new InetSocketAddress(host, resolvedPort);
        Integer remoteDeviceInstance = config.getIntConfig("remoteDeviceInstance", null);
        if (remoteDeviceInstance == null || remoteDeviceInstance < 0) {
            throw new IllegalStateException("BACnet/IP requires remoteDeviceInstance");
        }
        boolean useWhoIsDiscovery = Boolean.TRUE.equals(config.getBoolConfig("useWhoIsDiscovery", false));
        activeSession.bbmdAddress = resolveBbmdAddress();
        activeSession.foreignDeviceTtlSeconds = resolveForeignDeviceTtlSeconds();
        DatagramSocket activeSocket = new DatagramSocket(null);
        synchronized (lifecycleLock) {
            try {
                requireCurrentSession(activeSession);
                activeSession.socket = activeSocket;
            } catch (Exception | Error ex) {
                activeSocket.close();
                throw ex;
            }
        }
        activeSocket.bind(new InetSocketAddress(localBindAddress, localBindPort));
        activeSocket.setReuseAddress(true);
        activeSocket.setSoTimeout(resolveReadTimeout());
        BacnetRemoteDevice discoveredDevice = null;
        performForeignDeviceRegistration(activeSession, true);
        if (useWhoIsDiscovery) {
            boolean useBbmdBroadcast = activeSession.bbmdAddress != null;
            InetSocketAddress discoveryTarget = useBbmdBroadcast ? activeSession.bbmdAddress : configuredRemoteAddress;
            discoveredDevice = discoverRemoteDevice(activeSession, activeSocket, discoveryTarget,
                    remoteDeviceInstance,
                    resolveApduTimeout(),
                    useBbmdBroadcast);
        }
        if (discoveredDevice == null) {
            discoveredDevice = BacnetRemoteDevice.builder()
                    .deviceInstance(remoteDeviceInstance)
                    .socketAddress(configuredRemoteAddress)
                    .discoveredByWhoIs(false)
                    .build();
        }

        InetSocketAddress activeRemoteAddress = discoveredDevice.getSocketAddress() != null
                ? discoveredDevice.getSocketAddress()
                : configuredRemoteAddress;
        BacnetIpUdpClient activeClient = new BacnetIpUdpClient(activeSocket, activeRemoteAddress);
        synchronized (lifecycleLock) {
            try {
                requireCurrentSession(activeSession);
                activeSession.client = activeClient;
                activeClient.setCovNotificationHandler(notification -> dispatchCovNotification(activeSession, notification));
            } catch (Exception | Error ex) {
                activeClient.close();
                throw ex;
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put(CommonMapKeys.HOST, host);
        params.put(CommonMapKeys.PORT, resolvedPort);
        params.put("targetHost", activeRemoteAddress.getHostString());
        params.put("targetPort", activeRemoteAddress.getPort());
        params.put("remoteDeviceInstance", remoteDeviceInstance);
        params.put("localBindHost", localBindAddress.getHostAddress());
        params.put("localBindPort", activeSocket.getLocalPort());
        params.put("useWhoIsDiscovery", useWhoIsDiscovery);
        params.put("discoveredByWhoIs", discoveredDevice.isDiscoveredByWhoIs());
        params.put("apduTimeoutMs", resolveApduTimeout());
        params.put("segmentTimeoutMs", resolveSegmentTimeout());
        params.put("retries", resolveRequestRetries());
        if (activeSession.bbmdAddress != null) {
            params.put("bbmdHost", activeSession.bbmdAddress.getHostString());
            params.put("bbmdPort", activeSession.bbmdAddress.getPort());
            if (activeSession.foreignDeviceTtlSeconds != null) {
                params.put("foreignDeviceTtlSeconds", activeSession.foreignDeviceTtlSeconds);
            }
            params.put("foreignDeviceLeaseExpiresAt", activeSession.foreignDeviceLeaseExpiresAt);
        }
        synchronized (lifecycleLock) {
            requireCurrentSession(activeSession);
            activeSession.remoteDevice = discoveredDevice;
            connectionParams.clear();
            connectionParams.putAll(params);
            statistics.put(CommonMapKeys.PROTOCOL, "BACNET_IP");
            statistics.put("implemented", true);
            statistics.put(CommonMapKeys.TRANSPORT, "UDP");
            statistics.put(CommonMapKeys.MESSAGE, useWhoIsDiscovery && discoveredDevice.isDiscoveredByWhoIs()
                    ? "BACnet/IP UDP adapter connected and remote device discovered by Who-Is"
                    : "BACnet/IP UDP adapter connected");
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doDisconnect() {
        synchronized (lifecycleLock) {
            rejectStaleCallback();
            ConnectionSession existingSession = session;
            session = null;
            closeSession(existingSession);
            connectionParams.clear();
            statistics.remove(CommonMapKeys.MESSAGE);
            status = ConnectionStatus.DISCONNECTED;
            metrics.setStatus(status);
            metrics.setLastError(null);
            reconnectAttempts = 0;
            currentReconnectDelay = config.getInitialReconnectDelay();
        }
        log.info("BACnet/IP 适配器 已断开, 设备={}", getDeviceId());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doHeartbeat() throws Exception {
        ensureForeignDeviceLease(requireSession());
    }

    /** 心跳失败只能触发所属连接的重连，不能作废已经替换的新连接。 */
    @Override
    public void heartbeat() throws Exception {
        ConnectionSession activeSession;
        synchronized (lifecycleLock) {
            if (!isConnected()) {
                return;
            }
            activeSession = session;
        }
        try {
            ensureForeignDeviceLease(activeSession);
            synchronized (lifecycleLock) {
                requireCurrentSession(activeSession);
                heartbeats.incrementAndGet();
                updateActivityTime();
                metrics.setLastHeartbeatTime(lastActivityTime);
                metrics.setLastActivityTime(lastActivityTime);
            }
        } catch (Exception ex) {
            synchronized (lifecycleLock) {
                if (session != activeSession) {
                    return;
                }
                errors.incrementAndGet();
                metrics.setLastError(ex.getMessage());
                metrics.setLastErrorTime(System.currentTimeMillis());
                if (!config.isAutoReconnect()) {
                    status = ConnectionStatus.ERROR;
                    metrics.setStatus(status);
                    return;
                }
            }
            try {
                connectSession(true, activeSession);
            } catch (Exception reconnectError) {
                log.warn("BACnet/IP 心跳后重连失败, 设备={}", getDeviceId(), reconnectError);
            }
        }
    }

    @Override
    public boolean healthCheck() {
        ConnectionSession activeSession;
        synchronized (lifecycleLock) {
            if (status != ConnectionStatus.CONNECTED || !isConnected()
                    || (config.getHeartbeatTimeout() > 0
                    && System.currentTimeMillis() - lastActivityTime > config.getHeartbeatTimeout())) {
                return false;
            }
            activeSession = session;
        }
        try {
            ensureForeignDeviceLease(activeSession);
            synchronized (lifecycleLock) {
                return session == activeSession && isConnected();
            }
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public void authenticate() {
        synchronized (lifecycleLock) {
            rejectStaleCallback();
            if (!isConnected()) {
                throw new IllegalStateException("BACnet/IP connection is not established");
            }
            doAuthenticate();
            status = ConnectionStatus.AUTHENTICATED;
            metrics.setAuthTime(System.currentTimeMillis());
            metrics.setStatus(status);
        }
    }

    @Override
    public Map<String, Object> getStatistics() {
        synchronized (lifecycleLock) {
            Map<String, Object> result = super.getStatistics();
            result.put("reconnectAttempts", reconnectAttempts);
            result.put("currentReconnectDelay", currentReconnectDelay);
            return result;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    protected void doAuthenticate() {
        // BACnet/IP 轮询无需单独的认证交换。
    }

    @Override
    public BacnetIpUdpClient getClient() {
        ConnectionSession activeSession = session;
        return activeSession != null ? activeSession.client : null;
    }

    @Override
    public boolean isConnected() {
        synchronized (lifecycleLock) {
            ConnectionSession activeSession = session;
            return super.isConnected() && activeSession != null && activeSession.socket != null
                    && !activeSession.socket.isClosed() && activeSession.client != null;
        }
    }

    public void setCovNotificationListener(Consumer<BacnetCovNotification> listener) {
        this.covNotificationListener = listener;
    }

    @Override
    public void setReconnectListener(Runnable listener) {
        this.reconnectListener = listener;
    }

    /**
     * 查询并返回业务数据。
     */
    public BacnetReadPropertyResponse readProperty(BacnetReadPropertyRequest request, long timeoutMs) throws Exception {
        return executeWithClient(activeClient -> activeClient.readProperty(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveSegmentTimeout(),
                resolveRequestRetries()));
    }

    /**
     * 查询并返回业务数据。
     */
    public BacnetReadPropertyMultipleResponse readPropertyMultiple(BacnetReadPropertyMultipleRequest request,
                                                                    long timeoutMs) throws Exception {
        return executeWithClient(activeClient -> activeClient.readPropertyMultiple(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveSegmentTimeout(),
                resolveRequestRetries()));
    }

    /**
     * 写入或持久化业务数据。
     */
    public void writeProperty(BacnetWritePropertyRequest request, long timeoutMs) throws Exception {
        executeWithClient(activeClient -> {
            activeClient.writeProperty(request, timeoutMs > 0 ? timeoutMs : resolveApduTimeout(), resolveRequestRetries());
            return null;
        });
    }

    /**
     * 写入或持久化业务数据。
     */
    @Override
    public void writePropertyMultiple(BacnetWritePropertyMultipleRequest request, long timeoutMs) throws Exception {
        executeWithClient(activeClient -> {
            activeClient.writePropertyMultiple(request, timeoutMs > 0 ? timeoutMs : resolveApduTimeout(), resolveRequestRetries());
            return null;
        });
    }

    /**
     * 维护注册或订阅关系。
     */
    public void subscribeCov(BacnetSubscribeCovRequest request, long timeoutMs) throws Exception {
        subscribeCov(request, timeoutMs, null);
    }

    /** 完成交换锁等待及 FDR 后，才记录本次订阅的实际发起时间。 */
    public void subscribeCov(BacnetSubscribeCovRequest request, long timeoutMs, LongConsumer sentAt) throws Exception {
        executeWithClient(activeClient -> {
            if (sentAt != null) sentAt.accept(System.currentTimeMillis());
            activeClient.subscribeCov(request, timeoutMs > 0 ? timeoutMs : resolveApduTimeout(), resolveRequestRetries());
            return null;
        });
    }

    /**
     * 维护注册或订阅关系。
     */
    public void subscribeCovProperty(BacnetSubscribeCovPropertyRequest request, long timeoutMs) throws Exception {
        subscribeCovProperty(request, timeoutMs, null);
    }

    public void subscribeCovProperty(BacnetSubscribeCovPropertyRequest request, long timeoutMs,
                                     LongConsumer sentAt) throws Exception {
        executeWithClient(activeClient -> {
            if (sentAt != null) sentAt.accept(System.currentTimeMillis());
            activeClient.subscribeCovProperty(request,
                    timeoutMs > 0 ? timeoutMs : resolveApduTimeout(), resolveRequestRetries());
            return null;
        });
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void acknowledgeConfirmedCovNotification(int invokeId) throws Exception {
        executeWithClient(activeClient -> {
            activeClient.acknowledgeConfirmedCovNotification(invokeId);
            return null;
        });
    }

    @Override
    public String getTransportName() {
        return "UDP";
    }

    @Override
    public BacnetRemoteDevice getRemoteDevice() {
        ConnectionSession activeSession = session;
        return activeSession != null ? activeSession.remoteDevice : null;
    }

    public long getRequestRetryCount() {
        BacnetIpUdpClient client = getClient();
        return client != null ? client.getRequestRetryCount() : 0L;
    }

    public long getRequestTimeoutCount() {
        BacnetIpUdpClient client = getClient();
        return client != null ? client.getRequestTimeoutCount() : 0L;
    }

    public long getInvokeIdMismatchCount() {
        BacnetIpUdpClient client = getClient();
        return client != null ? client.getInvokeIdMismatchCount() : 0L;
    }

    public long getCovNotificationCount() {
        BacnetIpUdpClient client = getClient();
        return client != null ? client.getCovNotificationCount() : 0L;
    }

    public long getSegmentedResponseCount() {
        BacnetIpUdpClient client = getClient();
        return client != null ? client.getSegmentedResponseCount() : 0L;
    }

    public boolean isForeignDeviceRegistrationActive() {
        synchronized (lifecycleLock) {
            ConnectionSession activeSession = session;
            return activeSession != null && activeSession.bbmdAddress != null && isConnected()
                    && activeSession.foreignDeviceLeaseExpiresAt > System.currentTimeMillis();
        }
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
        ConnectionSession activeSession = session;
        return activeSession != null ? activeSession.foreignDeviceLeaseExpiresAt : 0L;
    }

    /**
     * 执行当前业务逻辑。
     */
    private BacnetRemoteDevice discoverRemoteDevice(ConnectionSession activeSession,
                                                    DatagramSocket activeSocket,
                                                    InetSocketAddress targetAddress,
                                                    int remoteDeviceInstance,
                                                    int timeoutMs,
                                                    boolean useBbmdBroadcast) throws Exception {
        byte[] whoIs = BacnetWhoIsCodec.encode(remoteDeviceInstance, remoteDeviceInstance);
        if (useBbmdBroadcast) {
            whoIs = BacnetBvlcCodec.wrapWithFunction(whoIs, BacnetReadPropertyCodec.BVLC_DISTRIBUTE_BROADCAST_TO_NETWORK);
        }
        requireCurrentSession(activeSession);
        int previousTimeout = activeSocket.getSoTimeout();
        Exception failure = null;
        try {
            DatagramPacket packet = new DatagramPacket(whoIs, whoIs.length, targetAddress);
            activeSocket.send(packet);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (setRemainingTimeout(activeSession, activeSocket, deadline)) {
                DatagramPacket response = new DatagramPacket(new byte[2048], 2048);
                try {
                    activeSocket.receive(response);
                } catch (SocketTimeoutException ex) {
                    break;
                }
                byte[] data = new byte[response.getLength()];
                System.arraycopy(response.getData(), response.getOffset(), data, 0, response.getLength());
                InetSocketAddress from = new InetSocketAddress(response.getAddress(), response.getPort());
                try {
                    BacnetRemoteDevice candidate = BacnetIAmDecoder.decode(data, from);
                    if (candidate.getDeviceInstance() == remoteDeviceInstance) {
                        log.info("BACnet/IP Who-Is discovery resolved 远端 设备, 实例={}, 地址={}",
                                remoteDeviceInstance, candidate.getSocketAddress());
                        requireCurrentSession(activeSession);
                        return candidate;
                    }
                } catch (CancellationException ex) {
                    throw ex;
                } catch (Exception ignored) {
                    // 发现窗口内忽略非 I-Am 数据报。
                }
            }
            throw new IllegalStateException("BACnet/IP Who-Is discovery did not receive matching I-Am for remoteDeviceInstance="
                    + remoteDeviceInstance);
        } catch (Exception ex) {
            failure = ex;
            throw ex;
        } finally {
            restoreSocketTimeout(activeSocket, previousTimeout, failure);
        }
    }

    /**
     * 维护注册或订阅关系。
     */
    private void ensureForeignDeviceLease(ConnectionSession activeSession) throws Exception {
        synchronized (activeSession.exchangeLock) {
            requireCurrentSession(activeSession);
            requireClient(activeSession);
            if (!needsForeignDeviceRenewal(activeSession, System.currentTimeMillis())) {
                return;
            }
            try {
                performForeignDeviceRegistration(activeSession, false);
            } catch (Exception ex) {
                synchronized (lifecycleLock) {
                    if (session != activeSession) {
                        throw cancelledOperation(ex);
                    }
                    foreignDeviceRenewFailureCount.incrementAndGet();
                }
                throw ex;
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void notifyReconnectListener(ConnectionSession activeSession) {
        Runnable listener = reconnectListener;
        if (listener == null || session != activeSession) {
            return;
        }
        ConnectionSession previousSession = callbackSession.get();
        try {
            callbackSession.set(activeSession);
            requireCurrentSession(activeSession);
            listener.run();
        } catch (Exception ex) {
            log.warn("BACnet/IP 重连 listener 失败, 设备={}", getDeviceId(), ex);
        } finally {
            restoreCallbackSession(previousSession);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void performForeignDeviceRegistration(ConnectionSession activeSession, boolean initialRegistration) throws Exception {
        synchronized (activeSession.exchangeLock) {
            requireCurrentSession(activeSession);
            InetSocketAddress activeBbmdAddress = activeSession.bbmdAddress;
            Integer activeTtlSeconds = activeSession.foreignDeviceTtlSeconds;
            if (activeBbmdAddress == null || activeTtlSeconds == null || activeTtlSeconds <= 0) {
                return;
            }
            int timeoutMs = resolveApduTimeout();
            BacnetIpUdpClient activeClient = activeSession.client;
            DatagramSocket activeSocket = activeSession.socket;
            if (activeClient != null) {
                activeClient.registerForeignDevice(activeBbmdAddress, activeTtlSeconds, timeoutMs, resolveRequestRetries());
            } else {
                rawRegisterForeignDevice(activeSession, activeSocket, activeBbmdAddress, activeTtlSeconds, timeoutMs);
            }
            synchronized (lifecycleLock) {
                requireCurrentSession(activeSession);
                activeSession.foreignDeviceLeaseExpiresAt = System.currentTimeMillis() + (activeTtlSeconds * 1000L);
                if (initialRegistration) {
                    foreignDeviceRegistrationCount.incrementAndGet();
                } else {
                    foreignDeviceRenewCount.incrementAndGet();
                }
                connectionParams.put("foreignDeviceLeaseExpiresAt", activeSession.foreignDeviceLeaseExpiresAt);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private void rawRegisterForeignDevice(ConnectionSession activeSession,
                                          DatagramSocket activeSocket,
                                          InetSocketAddress targetAddress,
                                          int ttlSeconds,
                                          int timeoutMs) throws Exception {
        byte[] request = BacnetBvlcCodec.encodeRegisterForeignDevice(ttlSeconds);
        requireCurrentSession(activeSession);
        int previousTimeout = activeSocket.getSoTimeout();
        Exception failure = null;
        try {
            DatagramPacket packet = new DatagramPacket(request, request.length, targetAddress);
            activeSocket.send(packet);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (setRemainingTimeout(activeSession, activeSocket, deadline)) {
                DatagramPacket response = new DatagramPacket(new byte[64], 64);
                activeSocket.receive(response);
                if (!targetAddress.equals(new InetSocketAddress(response.getAddress(), response.getPort()))) {
                    continue;
                }
                byte[] responseBytes = new byte[response.getLength()];
                System.arraycopy(response.getData(), response.getOffset(), responseBytes, 0, response.getLength());
                BacnetBvlcCodec.verifyResult(responseBytes, BacnetBvlcCodec.BVLC_RESULT_CODE_SUCCESSFUL_COMPLETION);
                requireCurrentSession(activeSession);
                return;
            }
            throw new SocketTimeoutException("BACnet/IP Foreign Device registration timed out");
        } catch (Exception ex) {
            failure = ex;
            throw ex;
        } finally {
            restoreSocketTimeout(activeSocket, previousTimeout, failure);
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void scheduleForeignDeviceRenewalIfPossible(ConnectionSession activeSession) {
        if (protocolScheduler == null || activeSession.bbmdAddress == null
                || activeSession.foreignDeviceTtlSeconds == null || activeSession.foreignDeviceTtlSeconds <= 0) {
            return;
        }
        long intervalMs = resolveForeignDeviceRenewIntervalMs(activeSession);
        activeSession.foreignDeviceRenewTask = protocolScheduler.scheduleWithFixedDelay(() -> {
            try {
                // cancel(false) 不终止已进入的任务，执行和提交都必须核对原连接身份。
                if (session != activeSession) {
                    return;
                }
                ensureForeignDeviceLease(activeSession);
            } catch (Exception ex) {
                if (session != activeSession) {
                    return;
                }
                log.warn("BACnet/IP Foreign 设备 续期 失败, 设备={}, BBMD={}",
                        getDeviceId(), activeSession.bbmdAddress, ex);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 执行当前业务逻辑。
     */
    private void cancelForeignDeviceRenewalTask(ConnectionSession activeSession) {
        ScheduledFuture<?> task = activeSession.foreignDeviceRenewTask;
        activeSession.foreignDeviceRenewTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean needsForeignDeviceRenewal(ConnectionSession activeSession, long now) {
        if (activeSession.bbmdAddress == null || activeSession.foreignDeviceTtlSeconds == null
                || activeSession.foreignDeviceTtlSeconds <= 0) {
            return false;
        }
        if (activeSession.foreignDeviceLeaseExpiresAt <= 0) {
            return true;
        }
        return now >= (activeSession.foreignDeviceLeaseExpiresAt - resolveForeignDeviceRenewLeadMs(activeSession));
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveForeignDeviceRenewIntervalMs(ConnectionSession activeSession) {
        long ttlMs = activeSession.foreignDeviceTtlSeconds * 1000L;
        return Math.max(500L, ttlMs - resolveForeignDeviceRenewLeadMs(activeSession));
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveForeignDeviceRenewLeadMs(ConnectionSession activeSession) {
        long ttlMs = activeSession.foreignDeviceTtlSeconds * 1000L;
        return Math.max(250L, Math.min(5000L, ttlMs / 4));
    }

    /**
     * 解析或转换业务数据。
     */
    private InetSocketAddress resolveBbmdAddress() {
        String host = config.getStringConfig("bbmdHost", null);
        Integer port = config.getIntConfig("bbmdPort", null);
        if (host == null || host.isBlank() || port == null || port <= 0) {
            return null;
        }
        return new InetSocketAddress(host, port);
    }

    /**
     * 解析或转换业务数据。
     */
    private Integer resolveForeignDeviceTtlSeconds() {
        Integer ttlSeconds = config.getIntConfig("foreignDeviceTtlSeconds", null);
        return ttlSeconds != null && ttlSeconds > 0 ? ttlSeconds : null;
    }

    /**
     * 校验业务条件和参数边界。
     */
    private BacnetIpUdpClient requireClient(ConnectionSession activeSession) {
        BacnetIpUdpClient activeClient = activeSession.client;
        DatagramSocket activeSocket = activeSession.socket;
        if (activeClient == null || activeSocket == null || activeSocket.isClosed()) {
            throw new IllegalStateException("BACnet/IP client is not initialized");
        }
        return activeClient;
    }

    private ConnectionSession requireSession() {
        synchronized (lifecycleLock) {
            ConnectionSession activeSession = session;
            rejectStaleCallback();
            if (activeSession == null || !isConnected()) {
                throw new IllegalStateException("BACnet/IP connection is not established");
            }
            return activeSession;
        }
    }

    private void requireCurrentSession(ConnectionSession activeSession) {
        synchronized (lifecycleLock) {
            if (session != activeSession || activeSession.cancelled.getCount() == 0) {
                throw cancelledOperation(null);
            }
        }
    }

    private void rejectStaleCallback() {
        ConnectionSession boundSession = callbackSession.get();
        if (boundSession != null && boundSession != session) {
            throw cancelledOperation(null);
        }
    }

    /** 已关闭的旧 Socket 不再恢复超时，也不让清理异常覆盖原始通信异常。 */
    private void restoreSocketTimeout(DatagramSocket activeSocket, int previousTimeout, Exception failure) throws SocketException {
        if (activeSocket.isClosed()) {
            return;
        }
        try {
            activeSocket.setSoTimeout(previousTimeout);
        } catch (SocketException ex) {
            if (failure != null) {
                failure.addSuppressed(ex);
            } else if (!activeSocket.isClosed()) {
                throw ex;
            }
        }
    }

    private boolean setRemainingTimeout(ConnectionSession activeSession, DatagramSocket activeSocket, long deadline) throws SocketException {
        requireCurrentSession(activeSession);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        activeSocket.setSoTimeout((int) Math.min(Integer.MAX_VALUE,
                Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))));
        return true;
    }

    /** 此方法只关闭本次连接的本地资源，不获取交换锁，也不等待网络响应。 */
    private void closeSession(ConnectionSession existingSession) {
        if (existingSession == null) {
            return;
        }
        existingSession.cancelled.countDown();
        cancelForeignDeviceRenewalTask(existingSession);
        BacnetIpUdpClient existingClient = existingSession.client;
        try {
            if (existingClient != null) {
                existingClient.setCovNotificationHandler(null);
                existingClient.close();
            }
        } finally {
            DatagramSocket existingSocket = existingSession.socket;
            if (existingSocket != null) {
                existingSocket.close();
            }
            existingSession.foreignDeviceLeaseExpiresAt = 0L;
        }
    }

    /** 取消不伪装成新连接的传输失败，原异常保留为附加诊断。 */
    private CancellationException cancelledOperation(Throwable failure) {
        CancellationException cancelled = new CancellationException("BACnet/IP connection operation cancelled");
        if (failure != null) {
            cancelled.addSuppressed(failure);
        }
        return cancelled;
    }

    /**
     * 已进入的回调仅可在原会话仍有效时提交非网络副作用。
     * action 不得发起协议交换；身份检查与提交共享现有生命周期锁。
     */
    public boolean runIfCurrentCallbackSession(Runnable action) {
        return runIfCurrentSession(captureSessionIdentity(), action);
    }

    /** 异步回调及租约任务显式携带原会话，不能在线程切换后借用当前会话。 */
    public Object captureSessionIdentity() {
        synchronized (lifecycleLock) {
            ConnectionSession boundSession = callbackSession.get();
            return boundSession != null ? boundSession : session;
        }
    }

    public boolean runIfCurrentSession(Object expectedSession, Runnable action) {
        synchronized (lifecycleLock) {
            if (!(expectedSession instanceof ConnectionSession boundSession)
                    || boundSession != session || resourcesClosed
                    || boundSession.cancelled.getCount() == 0 || !isConnected()) return false;
            action.run();
            return true;
        }
    }

    /** 取得原会话交换锁后再次核对订阅所有权；取消先完成时排队旧续订不得再上网。 */
    public void exchangeIfCurrentSession(Object expectedSession, BooleanSupplier owner,
                                         SessionExchange exchange) throws Exception {
        if (!(expectedSession instanceof ConnectionSession captured)) throw cancelledOperation(null);
        synchronized (captured.exchangeLock) {
            requireCurrentSession(captured);
            if (!owner.getAsBoolean()) throw cancelledOperation(null);
            ConnectionSession previous = callbackSession.get();
            try {
                callbackSession.set(captured);
                exchange.run();
            } finally {
                restoreCallbackSession(previous);
            }
        }
    }

    @FunctionalInterface
    public interface SessionExchange {
        void run() throws Exception;
    }

    private void dispatchCovNotification(ConnectionSession activeSession, BacnetCovNotification notification) {
        Consumer<BacnetCovNotification> listener = covNotificationListener;
        if (session != activeSession || listener == null) {
            return;
        }
        ConnectionSession previousSession = callbackSession.get();
        try {
            callbackSession.set(activeSession);
            requireCurrentSession(activeSession);
            listener.accept(notification);
        } finally {
            restoreCallbackSession(previousSession);
        }
    }

    /** 回调中发起的协议请求仍绑定原连接，不能在回调期间切换到新连接。 */
    private void restoreCallbackSession(ConnectionSession previousSession) {
        if (previousSession == null) {
            callbackSession.remove();
        } else {
            callbackSession.set(previousSession);
        }
    }

    private <T> T executeWithClient(ClientOperation<T> operation) throws Exception {
        ConnectionSession activeSession = requireSession();
        synchronized (activeSession.exchangeLock) {
            try {
                requireCurrentSession(activeSession);
                ensureForeignDeviceLease(activeSession);
                T result = operation.execute(requireClient(activeSession));
                synchronized (lifecycleLock) {
                    requireCurrentSession(activeSession);
                    updateActivityTime();
                }
                return result;
            } catch (Exception ex) {
                if (session != activeSession) {
                    throw cancelledOperation(ex);
                }
                throw ex;
            }
        }
    }

    @FunctionalInterface
    private interface ClientOperation<T> {
        T execute(BacnetIpUdpClient activeClient) throws Exception;
    }

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 解析或转换业务数据。
     */
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
