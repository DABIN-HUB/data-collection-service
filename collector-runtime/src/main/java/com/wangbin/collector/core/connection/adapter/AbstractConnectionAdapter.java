package com.wangbin.collector.core.connection.adapter;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import com.wangbin.collector.common.utils.JsonUtil;
import com.wangbin.collector.core.connection.model.ConnectionMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 连接适配器基础实现，统一处理重连、心跳和统计。
 */
@Slf4j
public abstract class AbstractConnectionAdapter<C> implements ConnectionAdapter<C> {

    protected final DeviceInfo deviceInfo;
    protected final DeviceConnection config;

    protected volatile ConnectionStatus status = ConnectionStatus.DISCONNECTED;
    protected ConnectionMetrics metrics;
    protected String connectionId;
    protected long lastActivityTime;
    protected Map<String, Object> connectionParams = new ConcurrentHashMap<>();
    protected Map<String, Object> statistics = new ConcurrentHashMap<>();

    protected AtomicLong bytesSent = new AtomicLong(0);
    protected AtomicLong bytesReceived = new AtomicLong(0);
    protected AtomicLong messagesSent = new AtomicLong(0);
    protected AtomicLong messagesReceived = new AtomicLong(0);
    protected AtomicLong errors = new AtomicLong(0);
    protected AtomicLong heartbeats = new AtomicLong(0);

    private int reconnectAttempts = 0;
    private long currentReconnectDelay;
    private boolean reconnecting = false;

    /**
     * 创建当前组件实例。
     */
    protected AbstractConnectionAdapter(DeviceInfo deviceInfo, DeviceConnection connectionConfig) {
        this.deviceInfo = deviceInfo;
        DeviceConnection source = connectionConfig;
        if (source == null) {
            source = new DeviceConnection();
        }
        if (source.getHost() == null && deviceInfo != null) {
            source.setHost(deviceInfo.getIpAddress());
        }
        if (source.getPort() == null && deviceInfo != null) {
            source.setPort(deviceInfo.getPort());
        }
        if (source.getConnectionType() == null && deviceInfo != null) {
            source.setConnectionType(deviceInfo.getConnectionType());
        }
        this.config = source;
        this.connectionId = generateConnectionId();
        this.metrics = new ConnectionMetrics();
        this.lastActivityTime = System.currentTimeMillis();
        this.currentReconnectDelay = config.getInitialReconnectDelay();
    }

    /**
     * 处理连接生命周期。
     */
    @Override
    public void connect() throws Exception {
        log.info("正在连接:{}", connectionId);
        if (status == ConnectionStatus.CONNECTED) {
            log.warn("连接 已存在 已建立:{}", connectionId);
            return;
        }
        if (status == ConnectionStatus.CONNECTING) {
            log.warn("连接 is 进行中:{}", connectionId);
            return;
        }
        try {
            status = ConnectionStatus.CONNECTING;
            metrics.setStatus(status);
            doConnect();
            status = ConnectionStatus.CONNECTED;
            lastActivityTime = System.currentTimeMillis();
            metrics.setConnectTime(lastActivityTime);
            metrics.setStatus(status);
            metrics.setLastError(null);
            log.info("连接 已建立:{}", connectionId);
        } catch (Exception e) {
            status = ConnectionStatus.ERROR;
            metrics.setLastError(e.getMessage());
            errors.incrementAndGet();
            log.error("连接 失败:{}", connectionId, e);
            throw e;
        }
    }

    /**
     * 处理连接生命周期。
     */
    @Override
    public void disconnect() throws Exception {
        log.info("正在断开连接：{}", connectionId);
        if (status == ConnectionStatus.DISCONNECTED) {
            log.warn("连接 已存在 已关闭:{}", connectionId);
            return;
        }
        if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.RECONNECTING) {
            log.warn("连接 still 进行中, 跳过 断开:{}", connectionId);
            return;
        }
        try {
            status = ConnectionStatus.RECONNECTING;
            metrics.setStatus(status);
            doDisconnect();
            status = ConnectionStatus.DISCONNECTED;
            metrics.setStatus(status);
            metrics.setLastError(null);
            reconnectAttempts = 0;
            currentReconnectDelay = config.getInitialReconnectDelay();
            log.info("连接 已关闭:{}", connectionId);
        } catch (Exception e) {
            status = ConnectionStatus.ERROR;
            metrics.setLastError(e.getMessage());
            errors.incrementAndGet();
            log.error("Disconnect 失败:{}", connectionId, e);
            throw e;
        }
    }

    /**
     * 处理连接生命周期。
     */
    @Override
    public void reconnect() throws Exception {
        log.info("正在重连连接：{}", connectionId);
        if (reconnecting) {
            log.warn("Reconnect 已存在 进行中:{}", connectionId);
            return;
        }
        if (config.getMaxReconnectAttempts() > 0 && reconnectAttempts >= config.getMaxReconnectAttempts()) {
            log.error("Reach 最大值 重连 attempts {}:{}", config.getMaxReconnectAttempts(), connectionId);
            status = ConnectionStatus.DISCONNECTED;
            metrics.setStatus(status);
            throw new Exception("Reach max reconnect attempts");
        }
        try {
            reconnecting = true;
            boolean wasConnected = isConnected();
            if (wasConnected) {
                disconnect();
            }
            status = ConnectionStatus.RECONNECTING;
            metrics.setStatus(status);
            long delay = calculateReconnectDelay();
            log.info("{} 毫秒后重连连接：{}", delay, connectionId);
            Thread.sleep(delay);
            doConnect();
            reconnectAttempts = 0;
            currentReconnectDelay = config.getInitialReconnectDelay();
            status = ConnectionStatus.CONNECTED;
            lastActivityTime = System.currentTimeMillis();
            metrics.setConnectTime(lastActivityTime);
            metrics.setStatus(status);
            metrics.setLastError(null);
            log.info("Reconnect 成功:{}", connectionId);
        } catch (Exception e) {
            reconnectAttempts++;
            updateReconnectDelay();
            status = ConnectionStatus.ERROR;
            metrics.setLastError(e.getMessage());
            errors.incrementAndGet();
            log.error("Reconnect 失败 ({}):{}", reconnectAttempts, connectionId, e);
            throw e;
        } finally {
            reconnecting = false;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private long calculateReconnectDelay() {
        if (reconnectAttempts == 0) {
            return currentReconnectDelay;
        }
        double jitter = 0.9 + Math.random() * 0.2;
        return (long) (currentReconnectDelay * jitter);
    }

    /**
     * 更新或刷新业务状态。
     */
    private void updateReconnectDelay() {
        long newDelay = (long) (currentReconnectDelay * config.getReconnectBackoffMultiplier());
        currentReconnectDelay = Math.min(newDelay, config.getMaxReconnectDelay());
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void send(byte[] data) throws Exception {
        checkConnection();
        try {
            doSend(data);
            bytesSent.addAndGet(data.length);
            messagesSent.incrementAndGet();
            lastActivityTime = System.currentTimeMillis();
            log.debug("发送 {} 字节到连接 {}", data.length, connectionId);
        } catch (Exception e) {
            errors.incrementAndGet();
            metrics.setLastError(e.getMessage());
            log.error("发送失败：{}", connectionId, e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void send(String data) throws Exception {
        send(data.getBytes(config.getCharset()));
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void send(Object data) throws Exception {
        send(JsonUtil.toJsonString(data));
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public byte[] receive() throws Exception {
        checkConnection();
        try {
            byte[] data = doReceive();
            if (data != null) {
                bytesReceived.addAndGet(data.length);
                messagesReceived.incrementAndGet();
                lastActivityTime = System.currentTimeMillis();
            }
            return data;
        } catch (Exception e) {
            errors.incrementAndGet();
            metrics.setLastError(e.getMessage());
            log.error("Receive 失败:{}", connectionId, e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public String receiveAsString() throws Exception {
        byte[] data = receive();
        return data != null ? new String(data, config.getCharset()) : null;
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public byte[] receive(long timeout) throws Exception {
        checkConnection();
        try {
            byte[] data = doReceive(timeout);
            if (data != null) {
                bytesReceived.addAndGet(data.length);
                messagesReceived.incrementAndGet();
                lastActivityTime = System.currentTimeMillis();
            }
            return data;
        } catch (Exception e) {
            errors.incrementAndGet();
            metrics.setLastError(e.getMessage());
            log.error("Receive 失败:{}", connectionId, e);
            throw e;
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void heartbeat() throws Exception {
        log.debug("发送连接心跳：{}", connectionId);
        if (!isConnected()) {
            log.warn("连接 not 已建立, 跳过 heartbeat:{}", connectionId);
            return;
        }
        try {
            doHeartbeat();
            heartbeats.incrementAndGet();
            lastActivityTime = System.currentTimeMillis();
            metrics.setLastHeartbeatTime(lastActivityTime);
            metrics.setLastActivityTime(lastActivityTime);
        } catch (Exception e) {
            log.error("Heartbeat 失败:{}", connectionId, e);
            errors.incrementAndGet();
            metrics.setLastError(e.getMessage());
            metrics.setLastErrorTime(System.currentTimeMillis());
            if (config.isAutoReconnect()) {
                try {
                    reconnect();
                } catch (Exception re) {
                    status = ConnectionStatus.ERROR;
                    metrics.setStatus(status);
                    log.error("心跳失败后的重连也失败：{}", connectionId, re);
                }
            } else {
                status = ConnectionStatus.ERROR;
                metrics.setStatus(status);
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public boolean healthCheck() {
        if (status != ConnectionStatus.CONNECTED) {
            log.warn("连接 状态 异常:{}", connectionId);
            return false;
        }
        long idleTime = System.currentTimeMillis() - lastActivityTime;
        if (config.getHeartbeatTimeout() > 0 && idleTime > config.getHeartbeatTimeout()) {
            log.warn("连接 空闲 超时 detected:{}", connectionId);
            return false;
        }
        try {
            doHeartbeat();
            return true;
        } catch (Exception e) {
            log.error("Health check 失败:{}", connectionId, e);
            return false;
        }
    }

    /**
     * 校验业务条件和参数边界。
     */
    protected void validateConnection() throws Exception {
        if (!isConnected()) {
            if (config.isAutoReconnect()) {
                log.info("连接 已关闭, trigger 重连:{}", connectionId);
                reconnect();
            } else {
                throw new Exception("Connection closed");
            }
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    @Override
    public void authenticate() throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Connection not established");
        }
        try {
            log.info("正在认证:{}", connectionId);
            status = ConnectionStatus.AUTHENTICATING;
            doAuthenticate();
            status = ConnectionStatus.AUTHENTICATED;
            metrics.setAuthTime(System.currentTimeMillis());
            metrics.setStatus(status);
            log.info("认证 成功:{}", connectionId);
        } catch (Exception e) {
            status = ConnectionStatus.ERROR;
            metrics.setLastError(e.getMessage());
            errors.incrementAndGet();
            log.error("认证 失败:{}", connectionId, e);
            throw e;
        }
    }

    @Override
    public ConnectionStatus getStatus() {
        return status;
    }

    @Override
    public DeviceConnection getConnectionConfig() {
        return config;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return deviceInfo;
    }

    @Override
    public ConnectionMetrics getMetrics() {
        updateMetrics();
        return metrics;
    }

    @Override
    public boolean isConnected() {
        return status.isConnected();
    }

    @Override
    public boolean isAuthenticated() {
        return status == ConnectionStatus.AUTHENTICATED;
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public String getDeviceId() {
        return deviceInfo != null ? deviceInfo.getDeviceId() : null;
    }

    @Override
    public long getLastActivityTime() {
        return lastActivityTime;
    }

    /**
     * 更新或刷新业务状态。
     */
    @Override
    public void updateActivityTime() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    @Override
    public Map<String, Object> getConnectionParams() {
        return connectionParams;
    }

    @Override
    public void setConnectionParam(String key, Object value) {
        connectionParams.put(key, value);
    }

    @Override
    public synchronized Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("connectionId", connectionId);
        stats.put(CommonMapKeys.STATUS, status.name());
        stats.put("bytesSent", bytesSent.get());
        stats.put("bytesReceived", bytesReceived.get());
        stats.put("messagesSent", messagesSent.get());
        stats.put("messagesReceived", messagesReceived.get());
        stats.put("errors", errors.get());
        stats.put("heartbeats", heartbeats.get());
        stats.put(CommonMapKeys.LAST_ACTIVITY_TIME, lastActivityTime);
        stats.put("connectionDuration", status == ConnectionStatus.CONNECTED ? System.currentTimeMillis() - metrics.getConnectTime() : 0);
        stats.put("reconnectAttempts", reconnectAttempts);
        stats.put("currentReconnectDelay", currentReconnectDelay);
        return stats;
    }

    /**
     * 记录或统计业务状态。
     */
    @Override
    public void resetStatistics() {
        bytesSent.set(0);
        bytesReceived.set(0);
        messagesSent.set(0);
        messagesReceived.set(0);
        errors.set(0);
        heartbeats.set(0);
        statistics.clear();
    }

    /**
     * 执行当前业务逻辑。
     */
    protected String generateConnectionId() {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String random = String.valueOf((int) (Math.random() * 10000));
        return "CONN_" + (deviceInfo != null ? deviceInfo.getDeviceId() : "UNKNOWN") + "_" + timestamp + "_" + random;
    }

    /**
     * 校验业务条件和参数边界。
     */
    protected void checkConnection() {
        if (!isConnected()) {
            throw new IllegalStateException("Connection not established or closed");
        }
    }

    /**
     * 更新或刷新业务状态。
     */
    protected void updateMetrics() {
        metrics.setBytesSent(bytesSent.get());
        metrics.setBytesReceived(bytesReceived.get());
        metrics.setMessagesSent(messagesSent.get());
        metrics.setMessagesReceived(messagesReceived.get());
        metrics.setErrors(errors.get());
        metrics.setHeartbeats(heartbeats.get());
        metrics.setLastActivityTime(lastActivityTime);
        metrics.setConnectionDuration(System.currentTimeMillis() - metrics.getConnectTime());
        long totalMessages = messagesSent.get() + messagesReceived.get();
        if (totalMessages > 0) {
            double successRate = (totalMessages - errors.get()) * 100.0 / totalMessages;
            metrics.setSuccessRate(successRate);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    protected String resolveHost() {
        if (config.getHost() != null && !config.getHost().isEmpty()) {
            return config.getHost();
        }
        return deviceInfo != null ? deviceInfo.getIpAddress() : null;
    }

    /**
     * 解析或转换业务数据。
     */
    protected Integer resolvePort() {
        if (config.getPort() != null && config.getPort() > 0) {
            return config.getPort();
        }
        return deviceInfo != null ? deviceInfo.getPort() : null;
    }

    /**
     * 执行当前业务逻辑。
     */
    protected abstract void doConnect() throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    protected abstract void doHeartbeat() throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    protected abstract void doAuthenticate() throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    protected void doSend(byte[] data) {
        throw new UnsupportedOperationException("Raw send not supported");
    }

    /**
     * 执行当前业务逻辑。
     */
    protected byte[] doReceive() {
        throw new UnsupportedOperationException("Raw receive not supported");
    }

    /**
     * 执行当前业务逻辑。
     */
    protected byte[] doReceive(long timeout) {
        throw new UnsupportedOperationException("Raw receive not supported");
    }
}
