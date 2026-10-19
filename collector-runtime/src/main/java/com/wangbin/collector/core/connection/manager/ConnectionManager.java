package com.wangbin.collector.core.connection.manager;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import com.wangbin.collector.common.exception.CollectorException;
import com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.core.connection.adapter.ConnectionAdapter;
import com.wangbin.collector.core.connection.factory.ConnectionFactory;
import com.wangbin.collector.core.connection.model.ConnectionMetrics;
import com.wangbin.collector.core.port.ExceptionReporter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 连接管理器
 */
@Slf4j
@Component
public class ConnectionManager {

    private final ConnectionFactory connectionFactory;
    private final ConfigManager configManager;
    @Nullable
    private final ExceptionReporter exceptionReporter;

    // 登记身份同时拥有适配器、分组和运行代次，旧任务不再重新解析设备别名。
    private final Map<String, Registration> connections = new ConcurrentHashMap<>();

    // 连接分组：groupId -> Set<deviceId>
    private final Map<String, Set<String>> groupConnections = new ConcurrentHashMap<>();

    // 连接状态监听器
    private final List<ConnectionStateListener> stateListeners = new CopyOnWriteArrayList<>();

    // 连接事件处理器
    private final List<ConnectionEventHandler> eventHandlers = new CopyOnWriteArrayList<>();

    @Nullable
    private final ExecutorService heartbeatExecutor;

    private final Map<String, Registration> heartbeatInProgress = new ConcurrentHashMap<>();
    private final Set<Registration> retiredConnections = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private CollectionTaskGuard collectionTaskGuard;

    private static final class Registration {
        private final ConnectionAdapter adapter;
        private final String groupId;
        private final long generation;
        private final ReentrantLock ioLock = new ReentrantLock();
        private volatile boolean retired;
        private boolean resourcesClosed;
        private boolean removalNotified;

        private Registration(ConnectionAdapter adapter, String groupId, long generation) {
            this.adapter = adapter;
            this.groupId = groupId;
            this.generation = generation;
        }
    }

    @Autowired(required = false)
    public void setCollectionTaskGuard(CollectionTaskGuard collectionTaskGuard) {
        this.collectionTaskGuard = collectionTaskGuard;
    }

    /**
     * 创建连接管理器。
     */
    public ConnectionManager(ConnectionFactory connectionFactory,
                             ConfigManager configManager,
                             @Nullable ExceptionReporter exceptionReporter,
                             @Qualifier("heartbeatExecutor") @Nullable ExecutorService heartbeatExecutor) {
        this.connectionFactory = connectionFactory;
        this.configManager = configManager;
        this.exceptionReporter = exceptionReporter;
        this.heartbeatExecutor = heartbeatExecutor;
    }

    /**
     * 处理组件生命周期。
     */
    @PostConstruct
    public void init() {
        log.info("连接管理器初始化完成");
    }

    /**
     * 处理组件生命周期。
     */
    @PreDestroy
    public void destroy() {
        log.info("开始关闭所有连接...");
        closeAllConnections();
        heartbeatInProgress.clear();
        log.info("所有连接已关闭");
    }

    /**
     * 创建连接
     */
    public ConnectionAdapter createConnection(DeviceInfo deviceInfo) {
        return createConnection(deviceInfo, null);
    }

    /**
     * 创建并返回业务对象。
     */
    public ConnectionAdapter createConnection(DeviceInfo deviceInfo, DeviceConnection overrideConfig) {
        return createConnection(deviceInfo, overrideConfig, 0L);
    }

    /** 同一有效代次仍可复用已连接适配器，不允许新代次继承旧连接所有权。 */
    public ConnectionAdapter createConnection(DeviceInfo deviceInfo, DeviceConnection overrideConfig, long generation) {
        if (deviceInfo == null || deviceInfo.getDeviceId() == null || deviceInfo.getDeviceId().isBlank()) {
            throw new CollectorException("设备信息无效", null, null);
        }
        String deviceId = deviceInfo.getDeviceId();
        Registration candidate = null;
        try {
            DeviceConnection config = overrideConfig != null ? overrideConfig : resolveConnectionConfig(deviceId);
            if (config == null) throw new CollectorException("连接配置不存在", deviceId, null);
            while (true) {
                Registration existing = connections.get(deviceId);
                if (existing != null) {
                    synchronized (existing) {
                        if (connections.get(deviceId) != existing || existing.retired) continue;
                        if (existing.adapter.isConnected() && isCurrent(existing)
                                && (generation == 0L || existing.generation == generation)) {
                            if (candidate != null) closeRegistration(candidate, false);
                            return existing.adapter;
                        }
                    }
                }
                if (candidate == null) {
                    candidate = new Registration(connectionFactory.createConnection(deviceInfo, config),
                            deviceInfo.getGroupId(), generation);
                }
                // 锁序为登记提交门 -> 索引锁；工厂、网络操作和物理关闭均在索引锁外。
                Object commitGate = existing != null ? existing : candidate;
                synchronized (commitGate) {
                    synchronized (connections) {
                        if (closed || !isGenerationCurrent(deviceId, generation)) {
                            throw new CollectorException("连接管理器已关闭或运行代次已失效", deviceId, null);
                        }
                        if (connections.get(deviceId) != existing) continue;
                        validateGroupCapacity(deviceInfo, config);
                        if (existing != null) detachRegistration(existing);
                        connections.put(deviceId, candidate);
                        addToGroup(deviceInfo);
                    }
                }
                if (existing != null) closeRegistration(existing, true);
                Registration created = candidate;
                publishIfCurrent(created, () -> notifyConnectionCreated(created.adapter));
                return created.adapter;
            }
        } catch (Exception e) {
            if (candidate != null && connections.get(deviceId) != candidate) closeRegistration(candidate, false);
            recordException(deviceId, e);
            if (e instanceof CollectorException collectorException) throw collectorException;
            throw new CollectorException("创建连接失败", deviceId, null, e);
        }
    }

    /**
     * 建立连接
     */
    public void connect(String deviceId) {
        Registration registration = requireRegistration(deviceId);
        requireOperation(deviceId, operateIfCurrent(registration, registration.adapter::connect, ConnectionStatus.CONNECTED));
    }

    /** 所有者建连只操作捕获身份，身份失效时不访问当前新连接。 */
    public boolean connectIfOwned(String deviceId, ConnectionAdapter expectedAdapter) {
        Registration registration = registrationIfOwned(deviceId, expectedAdapter);
        return registration != null
                && operateIfCurrent(registration, registration.adapter::connect, ConnectionStatus.CONNECTED);
    }

    /**
     * 断开连接
     */
    public void disconnect(String deviceId) {
        Registration registration = requireRegistration(deviceId);
        requireOperation(deviceId, operateIfCurrent(registration, registration.adapter::disconnect, ConnectionStatus.DISCONNECTED));
    }

    /**
     * 重新连接
     */
    public void reconnect(String deviceId) {
        Registration registration = requireRegistration(deviceId);
        requireOperation(deviceId, operateIfCurrent(registration, registration.adapter::reconnect, ConnectionStatus.CONNECTED));
    }

    /** 心跳和异步所有者不能借设备别名重连被替换的新适配器。 */
    public boolean reconnectIfOwned(String deviceId, ConnectionAdapter expectedAdapter) {
        Registration registration = registrationIfOwned(deviceId, expectedAdapter);
        return registration != null
                && operateIfCurrent(registration, registration.adapter::reconnect, ConnectionStatus.CONNECTED);
    }

    /**
     * 发送数据
     */
    public void send(String deviceId, byte[] data) {
        Registration registration = requireRegistration(deviceId);
        requireOperation(deviceId, operateIfCurrent(registration, () -> registration.adapter.send(data), null));
    }

    /**
     * 接收数据
     */
    public byte[] receive(String deviceId) {
        Registration registration = requireRegistration(deviceId);
        byte[][] result = new byte[1][];
        requireOperation(deviceId, operateIfCurrent(registration, () -> result[0] = registration.adapter.receive(), null));
        return result[0];
    }

    /**
     * 获取连接
     */
    public ConnectionAdapter getConnection(String deviceId) {
        Registration registration = connections.get(deviceId);
        return registration != null ? registration.adapter : null;
    }

    /**
     * 获取所有连接
     */
    public List<ConnectionAdapter> getAllConnections() {
        return connections.values().stream().map(registration -> registration.adapter).collect(Collectors.toList());
    }

    /**
     * 获取活跃连接
     */
    public List<ConnectionAdapter> getActiveConnections() {
        return getAllConnections().stream().filter(ConnectionAdapter::isConnected).collect(Collectors.toList());
    }

    /**
     * 获取分组连接
     */
    public List<ConnectionAdapter> getGroupConnections(String groupId) {
        synchronized (connections) {
            Set<String> members = groupConnections.get(groupId);
            if (members == null) return Collections.emptyList();
            return members.stream().map(connections::get).filter(Objects::nonNull)
                    .filter(registration -> Objects.equals(registration.groupId, groupId))
                    .map(registration -> registration.adapter).collect(Collectors.toList());
        }
    }

    /**
     * 获取连接指标
     */
    public ConnectionMetrics getConnectionMetrics(String deviceId) {
        ConnectionAdapter connection = getConnection(deviceId);
        return connection != null ? connection.getMetrics() : null;
    }

    /**
     * 获取所有连接指标
     */
    public Map<String, ConnectionMetrics> getAllConnectionMetrics() {
        Map<String, ConnectionMetrics> metrics = new HashMap<>();
        connections.forEach((deviceId, registration) -> {
            metrics.put(deviceId, registration.adapter.getMetrics());
        });
        return metrics;
    }

    /**
     * 移除连接
     */
    public void removeConnection(String deviceId) {
        Registration current = connections.get(deviceId);
        if (current != null) removeRegistration(current);
    }

    /** 原子比较登记身份并摘除，旧采集器不能清理同设备的新适配器。 */
    public boolean removeConnectionIfOwned(String deviceId, ConnectionAdapter expectedAdapter) {
        Registration registration = registrationIfOwned(deviceId, expectedAdapter);
        if (registration != null && removeRegistration(registration)) return true;
        for (Registration retired : retiredConnections) {
            if (retired.adapter == expectedAdapter && Objects.equals(deviceId, retired.adapter.getDeviceId())) {
                requireClosed(retired, closeRegistration(retired, false));
            }
        }
        log.debug("忽略失效连接所有者的索引清理: 设备={}", deviceId);
        return false;
    }

    private boolean removeRegistration(Registration registration) {
        synchronized (registration) {
            synchronized (connections) {
                if (connections.get(registration.adapter.getDeviceId()) != registration || registration.retired) return false;
                detachRegistration(registration);
            }
        }
        requireClosed(registration, closeRegistration(registration, true));
        return true;
    }

    /** 索引摘除与物理关闭分离，关闭等待只占该登记的I/O锁。 */
    private void detachRegistration(Registration registration) {
        registration.retired = true;
        String deviceId = registration.adapter.getDeviceId();
        connections.remove(deviceId, registration);
        heartbeatInProgress.remove(deviceId, registration);
        if (registration.groupId != null) {
            Set<String> members = groupConnections.get(registration.groupId);
            if (members != null && members.remove(deviceId) && members.isEmpty()) {
                groupConnections.remove(registration.groupId, members);
            }
        }
        retiredConnections.add(registration);
    }

    private boolean closeRegistration(Registration registration, boolean notifyRemoved) {
        boolean sendRemoved = false;
        registration.ioLock.lock();
        try {
            if (!registration.resourcesClosed) {
                registration.adapter.closeResources();
                registration.resourcesClosed = true;
                retiredConnections.remove(registration);
            }
        } catch (Exception e) {
            retiredConnections.add(registration);
            log.error("释放退役连接失败: 设备={}", registration.adapter.getDeviceId(), e);
        } finally {
            if (notifyRemoved && !registration.removalNotified) {
                registration.removalNotified = true;
                sendRemoved = true;
            }
            registration.ioLock.unlock();
        }
        if (sendRemoved) notifyConnectionRemoved(registration.adapter);
        return registration.resourcesClosed;
    }

    private void requireClosed(Registration registration, boolean released) {
        if (!released) throw new CollectorException("退役连接资源尚未释放，可按原身份重试", registration.adapter.getDeviceId(), null);
    }

    /**
     * 关闭所有连接
     */
    public void closeAllConnections() {
        List<Registration> snapshot;
        synchronized (connections) {
            closed = true;
            snapshot = new ArrayList<>(connections.values());
        }
        for (Registration registration : snapshot) {
            try {
                removeRegistration(registration);
            } catch (Exception e) {
                log.warn("连接停机清理未完成，继续关闭其他身份: 设备={}", registration.adapter.getDeviceId(), e);
            }
        }
        // 失败的资源关闭保留身份，重复停机只重试这些资源，不盲清新登记。
        for (Registration registration : new ArrayList<>(retiredConnections)) closeRegistration(registration, false);
        if (!retiredConnections.isEmpty()) throw new CollectorException("部分退役连接资源尚未释放", null, null);
    }

    /**
     * 关闭分组连接
     */
    public void closeGroupConnections(String groupId) {
        for (ConnectionAdapter connection : getGroupConnections(groupId)) {
            removeConnectionIfOwned(connection.getDeviceId(), connection);
        }
    }

    /**
     * 心跳检查
     */
    @Scheduled(fixedDelay = 30000) // 每30秒检查一次
    public void startHeartbeatMonitor() {
        for (Registration registration : connections.values()) submitHeartbeat(registration.adapter);
    }

    /**
     * 处理当前业务流程。
     */
    private void submitHeartbeat(ConnectionAdapter connection) {
        if (connection == null) return;
        String deviceId = connection.getDeviceId();
        Registration registration = registrationIfOwned(deviceId, connection);
        if (registration == null || !isCurrent(registration)) return;
        if (heartbeatInProgress.putIfAbsent(deviceId, registration) != null) return;
        Runnable task = () -> {
            try {
                handleHeartbeat(registration);
            } finally {
                heartbeatInProgress.remove(deviceId, registration);
            }
        };
        if (heartbeatExecutor == null) {
            task.run();
            return;
        }
        try {
            heartbeatExecutor.submit(task);
        } catch (RejectedExecutionException e) {
            heartbeatInProgress.remove(deviceId, registration);
            log.warn("心跳任务被拒绝:设备={}, 队列长度={}", deviceId, heartbeatQueueSize(), e);
        }
    }

    /**
     * 执行当前业务逻辑。
     */
    private int heartbeatQueueSize() {
        if (heartbeatExecutor instanceof ThreadPoolExecutor executor) {
            return executor.getQueue().size();
        }
        return -1;
    }

    /**
     * 处理当前业务流程。
     */
    private void handleHeartbeat(Registration registration) {
        ConnectionAdapter connection = registration.adapter;
        try {
            if (!isCurrent(registration)) return;
            DeviceConnection config = connection.getConnectionConfig();
            if (!connection.isConnected()) {
                if (config != null && config.isAutoReconnect()) reconnectIfOwned(connection.getDeviceId(), connection);
                return;
            }
            ConnectionMetrics metrics = connection.getMetrics();
            if (metrics != null && metrics.isTimeout(resolveHeartbeatTimeout(connection))) {
                if (!publishIfCurrent(registration, () -> notifyHeartbeatTimeout(connection))) return;
                if (config != null && config.isAutoReconnect()) reconnectIfOwned(connection.getDeviceId(), connection);
            } else {
                operateIfCurrent(registration, connection::heartbeat, null);
            }
        } catch (Exception e) {
            // I/O异常已由身份提交门处理；失效任务不能发布当前设备错误。
            log.debug("心跳操作未完成: 设备={}", connection.getDeviceId(), e);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private long resolveHeartbeatTimeout(ConnectionAdapter connection) {
        DeviceConnection config = connection.getConnectionConfig();
        long interval = Optional.ofNullable(config != null ? config.getHeartbeatInterval() : null)
                .map(Integer::longValue)
                .filter(value -> value > 0)
                .orElse(30_000L);
        return Optional.ofNullable(config != null ? config.getHeartbeatTimeout() : null)
                .map(Integer::longValue)
                .filter(value -> value > 0)
                .orElse(interval * 3);
    }

    /**
     * 添加连接状态监听器
     */
    public void addStateListener(ConnectionStateListener listener) {
        stateListeners.add(listener);
    }

    /**
     * 移除连接状态监听器
     */
    public void removeStateListener(ConnectionStateListener listener) {
        stateListeners.remove(listener);
    }

    /**
     * 添加连接事件处理器
     */
    public void addEventHandler(ConnectionEventHandler handler) {
        eventHandlers.add(handler);
    }

    /**
     * 移除连接事件处理器
     */
    public void removeEventHandler(ConnectionEventHandler handler) {
        eventHandlers.remove(handler);
    }

    /**
     * 添加到分组
     */
    private void addToGroup(DeviceInfo deviceInfo) {
        if (deviceInfo == null) {
            return;
        }
        String groupId = deviceInfo.getGroupId();
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        groupConnections
                .computeIfAbsent(groupId, key -> ConcurrentHashMap.newKeySet())
                .add(deviceInfo.getDeviceId());
    }

    /**
     * 校验业务条件和参数边界。
     */
    private void validateGroupCapacity(DeviceInfo deviceInfo, DeviceConnection config) {
        if (deviceInfo == null) {
            return;
        }
        String groupId = deviceInfo.getGroupId();
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        int maxConnections = Optional.ofNullable(config != null ? config.getMaxGroupConnections() : null).orElse(0);
        if (maxConnections <= 0) {
            return;
        }
        Set<String> members = groupConnections.get(groupId);
        if (members != null && members.size() - (members.contains(deviceInfo.getDeviceId()) ? 1 : 0) >= maxConnections) {
            throw new CollectorException("分组连接数已达到限制", deviceInfo.getDeviceId(), null);
        }
    }

    /**
     * 解析或转换业务数据。
     */
    private DeviceConnection resolveConnectionConfig(String deviceId) {
        if (configManager == null || deviceId == null) {
            return null;
        }
        DeviceContext context = configManager.getDeviceContext(deviceId);
        return context != null ? context.copyConnectionConfig() : null;
    }

    /**
     * 从分组中移除
     */
    private Registration registrationIfOwned(String deviceId, ConnectionAdapter expectedAdapter) {
        if (deviceId == null || expectedAdapter == null) return null;
        Registration registration = connections.get(deviceId);
        return registration != null && registration.adapter == expectedAdapter ? registration : null;
    }

    private Registration requireRegistration(String deviceId) {
        Registration registration = connections.get(deviceId);
        if (registration == null) throw new CollectorException("连接不存在", deviceId, null);
        return registration;
    }

    private boolean isGenerationCurrent(String deviceId, long generation) {
        return generation == 0L || collectionTaskGuard == null || collectionTaskGuard.isCurrent(deviceId, generation);
    }

    private boolean isCurrent(Registration registration) {
        return !closed && !registration.retired
                && connections.get(registration.adapter.getDeviceId()) == registration
                && isGenerationCurrent(registration.adapter.getDeviceId(), registration.generation);
    }

    private boolean publishIfCurrent(Registration registration, Runnable publish) {
        synchronized (registration) {
            if (!isCurrent(registration)) return false;
            if (registration.generation > 0L && collectionTaskGuard != null) {
                if (!collectionTaskGuard.commitIfCurrent(registration.adapter.getDeviceId(),
                        registration.generation, () -> { })) return false;
            }
        }
        // 监听器始终收到捕获的适配器；禁止锁内回调形成登记锁与采集器生命周期锁反转。
        if (!isCurrent(registration)) return false;
        publish.run();
        return true;
    }

    /** 登记独立I/O锁串行化该适配器；退役先摘表，再等旧操作退出后关闭。 */
    private boolean operateIfCurrent(Registration registration, ConnectionOperation operation, ConnectionStatus status) {
        Exception failure = null;
        registration.ioLock.lock();
        try {
            if (!isCurrent(registration)) return false;
            operation.run();
        } catch (Exception e) {
            failure = e;
        } finally {
            registration.ioLock.unlock();
        }
        if (failure != null) {
            Exception cause = failure;
            publishIfCurrent(registration, () -> {
                notifyConnectionError(registration.adapter, cause);
                recordException(registration.adapter.getDeviceId(), cause);
            });
            throw new CollectorException("连接操作失败", registration.adapter.getDeviceId(), null, cause);
        }
        return publishIfCurrent(registration, () -> {
            if (status != null) notifyConnectionStateChanged(registration.adapter, status);
        });
    }

    private void requireOperation(String deviceId, boolean completed) {
        if (!completed) throw new CollectorException("连接所有权已失效", deviceId, null);
    }

    @FunctionalInterface
    private interface ConnectionOperation {
        void run() throws Exception;
    }

    /**
     * 通知连接创建
     */
    private void notifyConnectionCreated(ConnectionAdapter connection) {
        for (ConnectionEventHandler handler : eventHandlers) {
            try {
                handler.onConnectionCreated(connection);
            } catch (Exception e) {
                log.error("连接创建事件处理失败", e);
            }
        }
    }

    /**
     * 通知连接状态变化
     */
    private void notifyConnectionStateChanged(ConnectionAdapter connection, ConnectionStatus newStatus) {
        for (ConnectionStateListener listener : stateListeners) {
            try {
                listener.onStateChanged(connection, newStatus);
            } catch (Exception e) {
                log.error("连接状态变化监听失败", e);
            }
        }

        for (ConnectionEventHandler handler : eventHandlers) {
            try {
                handler.onConnectionStateChanged(connection, newStatus);
            } catch (Exception e) {
                log.error("连接状态变化事件处理失败", e);
            }
        }
    }

    /**
     * 通知连接错误
     */
    private void notifyConnectionError(ConnectionAdapter connection, Exception error) {
        for (ConnectionEventHandler handler : eventHandlers) {
            try {
                handler.onConnectionError(connection, error);
            } catch (Exception e) {
                log.error("连接错误事件处理失败", e);
            }
        }
    }

    /**
     * 通知心跳超时
     */
    private void notifyHeartbeatTimeout(ConnectionAdapter connection) {
        for (ConnectionEventHandler handler : eventHandlers) {
            try {
                handler.onHeartbeatTimeout(connection);
            } catch (Exception e) {
                log.error("心跳超时事件处理失败", e);
            }
        }
    }

    /**
     * 通知连接移除
     */
    private void notifyConnectionRemoved(ConnectionAdapter connection) {
        for (ConnectionEventHandler handler : eventHandlers) {
            try {
                handler.onConnectionRemoved(connection);
            } catch (Exception e) {
                log.error("连接移除事件处理失败", e);
            }
        }
    }

    /**
     * 记录或统计业务状态。
     */
    private void recordException(String deviceId, Exception e) {
        if (exceptionReporter != null) {
            exceptionReporter.record(e, deviceId, null);
        }
    }

    /**
     * 连接状态监听器接口
     */
    public interface ConnectionStateListener {
        /**
         * 执行当前业务逻辑。
         */
        void onStateChanged(ConnectionAdapter connection, ConnectionStatus newStatus);
    }

    /**
     * 连接事件处理器接口
     */
    public interface ConnectionEventHandler {
        /**
         * 执行当前业务逻辑。
         */
        void onConnectionCreated(ConnectionAdapter connection);
        /**
         * 执行当前业务逻辑。
         */
        void onConnectionStateChanged(ConnectionAdapter connection, ConnectionStatus newStatus);
        /**
         * 执行当前业务逻辑。
         */
        void onConnectionError(ConnectionAdapter connection, Exception error);
        /**
         * 执行当前业务逻辑。
         */
        void onHeartbeatTimeout(ConnectionAdapter connection);
        /**
         * 执行当前业务逻辑。
         */
        void onConnectionRemoved(ConnectionAdapter connection);
    }
}
