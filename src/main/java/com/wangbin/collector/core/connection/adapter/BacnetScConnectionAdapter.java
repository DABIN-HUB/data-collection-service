package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.bacnet.client.BacnetScClient;
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

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

@Slf4j
public class BacnetScConnectionAdapter extends WebSocketConnectionAdapter implements BacnetConnectionAdapter {

    private BacnetScClient bacnetClient;
    private BacnetRemoteDevice remoteDevice;
    private volatile Consumer<BacnetCovNotification> covNotificationListener;
    private volatile Runnable reconnectListener;

    public BacnetScConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        this(deviceInfo, config, null, null);
    }

    public BacnetScConnectionAdapter(DeviceInfo deviceInfo,
                                     DeviceConnection config,
                                     Executor httpExecutor,
                                     ScheduledExecutorService heartbeatScheduler) {
        super(deviceInfo, enrichConfig(config), httpExecutor, heartbeatScheduler);
    }

    @Override
    protected void doConnect() throws Exception {
        super.doConnect();
        bacnetClient = new BacnetScClient(this);
        if (covNotificationListener != null) {
            bacnetClient.setCovNotificationHandler(covNotificationListener);
        }
        remoteDevice = BacnetRemoteDevice.builder()
                .deviceInstance(resolveRemoteDeviceInstance())
                .socketAddress(resolveRemoteSocketAddress())
                .discoveredByWhoIs(false)
                .build();
        Map<String, Object> params = new LinkedHashMap<>(getConnectionParams());
        params.put("remoteDeviceInstance", resolveRemoteDeviceInstance());
        params.put("transport", "WSS");
        connectionParams.clear();
        connectionParams.putAll(params);
        statistics.put("protocol", "BACNET_SC");
        statistics.put("implemented", true);
        statistics.put("transport", "WSS");
        statistics.put("message", "BACnet/SC experimental secure WebSocket tunnel connected");
        notifyReconnectListener();
    }

    @Override
    protected void doDisconnect() throws Exception {
        BacnetScClient existingClient = bacnetClient;
        bacnetClient = null;
        if (existingClient != null) {
            existingClient.close();
        }
        remoteDevice = null;
        super.doDisconnect();
    }

    @Override
    public void setCovNotificationListener(Consumer<BacnetCovNotification> listener) {
        this.covNotificationListener = listener;
        if (bacnetClient != null) {
            bacnetClient.setCovNotificationHandler(listener);
        }
    }

    @Override
    public void setReconnectListener(Runnable listener) {
        this.reconnectListener = listener;
    }

    @Override
    public synchronized BacnetReadPropertyResponse readProperty(BacnetReadPropertyRequest request, long timeoutMs) throws Exception {
        BacnetReadPropertyResponse response = requireClient().readProperty(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveSegmentTimeout(),
                resolveRequestRetries());
        updateActivityTime();
        return response;
    }

    @Override
    public synchronized BacnetReadPropertyMultipleResponse readPropertyMultiple(BacnetReadPropertyMultipleRequest request,
                                                                                long timeoutMs) throws Exception {
        BacnetReadPropertyMultipleResponse response = requireClient().readPropertyMultiple(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveSegmentTimeout(),
                resolveRequestRetries());
        updateActivityTime();
        return response;
    }

    @Override
    public synchronized void writeProperty(BacnetWritePropertyRequest request, long timeoutMs) throws Exception {
        requireClient().writeProperty(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveRequestRetries());
        updateActivityTime();
    }

    @Override
    public synchronized void writePropertyMultiple(BacnetWritePropertyMultipleRequest request, long timeoutMs) throws Exception {
        requireClient().writePropertyMultiple(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveRequestRetries());
        updateActivityTime();
    }

    @Override
    public synchronized void subscribeCov(BacnetSubscribeCovRequest request, long timeoutMs) throws Exception {
        requireClient().subscribeCov(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveRequestRetries());
        updateActivityTime();
    }

    @Override
    public synchronized void subscribeCovProperty(BacnetSubscribeCovPropertyRequest request, long timeoutMs) throws Exception {
        requireClient().subscribeCovProperty(request,
                timeoutMs > 0 ? timeoutMs : resolveApduTimeout(),
                resolveRequestRetries());
        updateActivityTime();
    }

    @Override
    public synchronized void acknowledgeConfirmedCovNotification(int invokeId) throws Exception {
        requireClient().acknowledgeConfirmedCovNotification(invokeId);
        updateActivityTime();
    }

    @Override
    public BacnetRemoteDevice getRemoteDevice() {
        return remoteDevice;
    }

    @Override
    public String getTransportName() {
        return "BACnet/SC";
    }

    @Override
    public long getRequestRetryCount() { return bacnetClient != null ? bacnetClient.getRequestRetryCount() : 0L; }
    @Override
    public long getRequestTimeoutCount() { return bacnetClient != null ? bacnetClient.getRequestTimeoutCount() : 0L; }
    @Override
    public long getInvokeIdMismatchCount() { return bacnetClient != null ? bacnetClient.getInvokeIdMismatchCount() : 0L; }
    @Override
    public long getCovNotificationCount() { return bacnetClient != null ? bacnetClient.getCovNotificationCount() : 0L; }
    @Override
    public long getSegmentedResponseCount() { return bacnetClient != null ? bacnetClient.getSegmentedResponseCount() : 0L; }

    private BacnetScClient requireClient() {
        if (bacnetClient == null) {
            throw new IllegalStateException("BACnet/SC client is not initialized");
        }
        return bacnetClient;
    }

    private void notifyReconnectListener() {
        Runnable listener = reconnectListener;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (Exception ex) {
            log.warn("BACnet/SC reconnect listener failed, deviceId={}", getDeviceId(), ex);
        }
    }

    private int resolveRemoteDeviceInstance() {
        Integer remoteDeviceInstance = getConnectionConfig().getIntConfig("remoteDeviceInstance", null);
        if (remoteDeviceInstance == null || remoteDeviceInstance < 0) {
            throw new IllegalStateException("BACnet/SC requires remoteDeviceInstance");
        }
        return remoteDeviceInstance;
    }

    private int resolveApduTimeout() {
        Integer apduTimeout = getConnectionConfig().getIntConfig("apduTimeout", null);
        if (apduTimeout == null || apduTimeout <= 0) {
            apduTimeout = getConnectionConfig().getIntConfig("apduTimeoutMs", null);
        }
        if (apduTimeout != null && apduTimeout > 0) {
            return apduTimeout;
        }
        return getConnectionConfig().getReadTimeout() != null && getConnectionConfig().getReadTimeout() > 0
                ? getConnectionConfig().getReadTimeout()
                : 5000;
    }

    private int resolveSegmentTimeout() {
        Integer segmentTimeout = getConnectionConfig().getIntConfig("segmentTimeout", null);
        if (segmentTimeout == null || segmentTimeout <= 0) {
            segmentTimeout = getConnectionConfig().getIntConfig("segmentTimeoutMs", null);
        }
        return segmentTimeout != null && segmentTimeout > 0 ? segmentTimeout : resolveApduTimeout();
    }

    private int resolveRequestRetries() {
        Integer retries = getConnectionConfig().getIntConfig("retries", null);
        if (retries == null) {
            retries = getConnectionConfig().getRetries();
        }
        return retries != null && retries >= 0 ? retries : 0;
    }

    private InetSocketAddress resolveRemoteSocketAddress() {
        String host = getConnectionConfig().getHost();
        if ((host == null || host.isBlank()) && getDeviceInfo() != null) {
            host = getDeviceInfo().getIpAddress();
        }
        Integer port = getConnectionConfig().getPort();
        if ((port == null || port <= 0) && getDeviceInfo() != null) {
            port = getDeviceInfo().getPort();
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        return new InetSocketAddress(host, port != null && port > 0 ? port : 443);
    }

    private static DeviceConnection enrichConfig(DeviceConnection original) {
        DeviceConnection config = original != null ? original : new DeviceConnection();
        if (config.getExtJson() == null) {
            config.setExtJson(new LinkedHashMap<>());
        }
        config.getExtJson().putIfAbsent("binaryMode", true);
        config.getExtJson().putIfAbsent("heartbeatUsePing", true);
        config.getExtJson().putIfAbsent("subprotocol", "bacnet-sc");
        config.getExtJson().putIfAbsent("path", "/bacnet/sc");
        config.setSslEnabled(true);
        if (config.getPort() == null || config.getPort() <= 0) {
            config.setPort(443);
        }
        return config;
    }
}
