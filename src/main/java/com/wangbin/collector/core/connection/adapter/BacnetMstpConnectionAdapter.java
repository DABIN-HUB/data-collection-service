package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.bacnet.client.BacnetMstpClient;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetCovNotification;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetRemoteDevice;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;
import com.wangbin.collector.core.collector.protocol.bacnet.transport.BacnetMstpTokenManager;
import com.wangbin.collector.core.collector.protocol.bacnet.transport.BacnetSerialChannel;
import com.wangbin.collector.core.collector.protocol.bacnet.transport.JSerialCommBacnetSerialChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
public class BacnetMstpConnectionAdapter extends AbstractConnectionAdapter<BacnetMstpClient>
        implements BacnetConnectionAdapter {

    private BacnetSerialChannel serialChannel;
    private BacnetMstpTokenManager tokenManager;
    private BacnetMstpClient client;
    private BacnetRemoteDevice remoteDevice;
    private volatile Consumer<BacnetCovNotification> covNotificationListener;

    public BacnetMstpConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection config) {
        super(deviceInfo, config);
    }

    @Override
    protected void doConnect() throws Exception {
        serialChannel = createSerialChannel();
        serialChannel.open();
        int remoteMacAddress = resolveRemoteMacAddress();
        tokenManager = new BacnetMstpTokenManager(serialChannel, config,
                Boolean.TRUE.equals(config.getBoolConfig("remoteIsMaster", true)) ? remoteMacAddress : null);
        tokenManager.start();
        client = new BacnetMstpClient(tokenManager, remoteMacAddress);
        if (covNotificationListener != null) {
            client.setCovNotificationHandler(covNotificationListener);
        }
        remoteDevice = BacnetRemoteDevice.builder()
                .deviceInstance(resolveRemoteDeviceInstance())
                .discoveredByWhoIs(false)
                .build();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("serialPort", resolveSerialPort());
        params.put("baudRate", resolveBaudRate());
        params.put("dataBits", resolveDataBits());
        params.put("stopBits", resolveStopBits());
        params.put("parity", resolveParity());
        params.put("localMacAddress", resolveLocalMacAddress());
        params.put("remoteMacAddress", remoteMacAddress);
        params.put("remoteDeviceInstance", resolveRemoteDeviceInstance());
        params.put("maxMaster", resolveMaxMaster());
        params.put("maxInfoFrames", resolveMaxInfoFrames());
        params.put("tokenClaimTimeoutMs", resolveTokenClaimTimeoutMs());
        params.put("replyTimeoutMs", resolveReplyTimeoutMs());
        connectionParams.clear();
        connectionParams.putAll(params);
        statistics.put("protocol", "BACNET_MSTP");
        statistics.put("implemented", true);
        statistics.put("transport", "MS/TP");
        statistics.put("message", "BACnet MS/TP serial adapter connected");
        log.info("BACnet MS/TP adapter connected, deviceId={}, serialPort={}, localMac={}, remoteMac={}, baudRate={}",
                getDeviceId(), resolveSerialPort(), resolveLocalMacAddress(), remoteMacAddress, resolveBaudRate());
    }

    @Override
    protected void doDisconnect() throws Exception {
        BacnetMstpClient existingClient = client;
        client = null;
        if (existingClient != null) {
            existingClient.close();
        }
        BacnetMstpTokenManager existingTokenManager = tokenManager;
        tokenManager = null;
        if (existingTokenManager != null) {
            existingTokenManager.close();
        }
        BacnetSerialChannel existingChannel = serialChannel;
        serialChannel = null;
        if (existingChannel != null) {
            existingChannel.close();
        }
        remoteDevice = null;
        log.info("BACnet MS/TP adapter disconnected, deviceId={}", getDeviceId());
    }

    @Override
    protected void doHeartbeat() {
        if (serialChannel == null || !serialChannel.isOpen() || client == null) {
            throw new IllegalStateException("BACnet MS/TP serial channel is not active");
        }
    }

    @Override
    protected void doAuthenticate() {
    }

    @Override
    public BacnetMstpClient getClient() {
        return client;
    }

    @Override
    public boolean isConnected() {
        return super.isConnected() && serialChannel != null && serialChannel.isOpen() && client != null;
    }

    @Override
    public void setCovNotificationListener(Consumer<BacnetCovNotification> listener) {
        this.covNotificationListener = listener;
        if (client != null) {
            client.setCovNotificationHandler(listener);
        }
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
    public BacnetRemoteDevice getRemoteDevice() {
        return remoteDevice;
    }

    @Override
    public String getTransportName() {
        return "MS/TP";
    }

    @Override
    public long getRequestRetryCount() { return client != null ? client.getRequestRetryCount() : 0L; }
    @Override
    public long getRequestTimeoutCount() { return client != null ? client.getRequestTimeoutCount() : 0L; }
    @Override
    public long getInvokeIdMismatchCount() { return client != null ? client.getInvokeIdMismatchCount() : 0L; }
    @Override
    public long getCovNotificationCount() { return client != null ? client.getCovNotificationCount() : 0L; }
    @Override
    public long getSegmentedResponseCount() { return client != null ? client.getSegmentedResponseCount() : 0L; }
    @Override
    public long getTokenReceiveCount() { return tokenManager != null ? tokenManager.getTokenReceiveCount() : 0L; }
    @Override
    public long getTokenPassCount() { return tokenManager != null ? tokenManager.getTokenPassCount() : 0L; }
    @Override
    public long getPollForMasterCount() { return tokenManager != null ? tokenManager.getPollForMasterCount() : 0L; }
    @Override
    public long getReplyToPollCount() { return tokenManager != null ? tokenManager.getReplyToPollCount() : 0L; }
    @Override
    public long getFrameErrorCount() { return tokenManager != null ? tokenManager.getFrameErrorCount() : 0L; }
    @Override
    public long getCrcErrorCount() { return tokenManager != null ? tokenManager.getCrcErrorCount() : 0L; }

    protected BacnetSerialChannel createSerialChannel() {
        return new JSerialCommBacnetSerialChannel(resolveSerialPort(),
                resolveBaudRate(),
                resolveDataBits(),
                resolveStopBits(),
                resolveParity(),
                resolveReadTimeout(),
                resolveWriteTimeout());
    }

    protected String resolveSerialPort() {
        String serialPort = config.getStringConfig("serialPort", null);
        if (serialPort != null && !serialPort.isBlank()) {
            return serialPort;
        }
        String host = resolveHost();
        return host != null && !host.isBlank() ? host : "COM1";
    }

    protected int resolveBaudRate() {
        Integer baudRate = config.getIntConfig("baudRate", null);
        return baudRate != null && baudRate > 0 ? baudRate : 38400;
    }

    protected int resolveDataBits() {
        Integer dataBits = config.getIntConfig("dataBits", null);
        return dataBits != null && dataBits > 0 ? dataBits : 8;
    }

    protected int resolveStopBits() {
        Integer stopBits = config.getIntConfig("stopBits", null);
        return stopBits != null && stopBits > 0 ? stopBits : 1;
    }

    protected String resolveParity() {
        return config.getStringConfig("parity", "none");
    }

    protected int resolveLocalMacAddress() {
        Integer localMac = config.getIntConfig("localMacAddress", null);
        if (localMac == null) {
            localMac = config.getIntConfig("macAddress", null);
        }
        if (localMac == null || localMac < 0 || localMac > 0xFE) {
            throw new IllegalStateException("BACnet MS/TP requires localMacAddress/macAddress between 0 and 254");
        }
        return localMac;
    }

    protected int resolveRemoteMacAddress() {
        Integer remoteMac = config.getIntConfig("remoteMacAddress", null);
        if (remoteMac == null) {
            remoteMac = config.getIntConfig("targetMacAddress", null);
        }
        if (remoteMac == null || remoteMac < 0 || remoteMac > 0xFE) {
            throw new IllegalStateException("BACnet MS/TP requires remoteMacAddress between 0 and 254");
        }
        return remoteMac;
    }

    protected int resolveRemoteDeviceInstance() {
        Integer remoteDeviceInstance = config.getIntConfig("remoteDeviceInstance", null);
        if (remoteDeviceInstance == null || remoteDeviceInstance < 0) {
            throw new IllegalStateException("BACnet MS/TP requires remoteDeviceInstance");
        }
        return remoteDeviceInstance;
    }

    protected int resolveReadTimeout() {
        Integer readTimeout = config.getReadTimeout();
        if (readTimeout != null && readTimeout > 0) {
            return readTimeout;
        }
        Integer timeout = config.getTimeout();
        return timeout != null && timeout > 0 ? timeout : 5000;
    }

    protected int resolveWriteTimeout() {
        Integer writeTimeout = config.getWriteTimeout();
        if (writeTimeout != null && writeTimeout > 0) {
            return writeTimeout;
        }
        return resolveReadTimeout();
    }

    protected int resolveApduTimeout() {
        Integer apduTimeout = config.getIntConfig("apduTimeout", null);
        if (apduTimeout == null || apduTimeout <= 0) {
            apduTimeout = config.getIntConfig("apduTimeoutMs", null);
        }
        return apduTimeout != null && apduTimeout > 0 ? apduTimeout : resolveReadTimeout();
    }

    protected int resolveSegmentTimeout() {
        Integer segmentTimeout = config.getIntConfig("segmentTimeout", null);
        if (segmentTimeout == null || segmentTimeout <= 0) {
            segmentTimeout = config.getIntConfig("segmentTimeoutMs", null);
        }
        return segmentTimeout != null && segmentTimeout > 0 ? segmentTimeout : resolveApduTimeout();
    }

    protected int resolveRequestRetries() {
        Integer retries = config.getIntConfig("retries", null);
        if (retries == null) {
            retries = config.getRetries();
        }
        return retries != null && retries >= 0 ? retries : 0;
    }

    protected int resolveMaxMaster() {
        Integer maxMaster = config.getIntConfig("maxMaster", null);
        return maxMaster != null && maxMaster >= 0 ? maxMaster : 127;
    }

    protected int resolveMaxInfoFrames() {
        Integer maxInfoFrames = config.getIntConfig("maxInfoFrames", null);
        return maxInfoFrames != null && maxInfoFrames > 0 ? maxInfoFrames : 1;
    }

    protected int resolveTokenClaimTimeoutMs() {
        Integer timeout = config.getIntConfig("tokenClaimTimeoutMs", null);
        return timeout != null && timeout > 0 ? timeout : 1000;
    }

    protected int resolveReplyTimeoutMs() {
        Integer timeout = config.getIntConfig("replyTimeoutMs", null);
        return timeout != null && timeout > 0 ? timeout : resolveApduTimeout();
    }

    private BacnetMstpClient requireClient() {
        if (client == null) {
            throw new IllegalStateException("BACnet MS/TP client is not initialized");
        }
        return client;
    }
}