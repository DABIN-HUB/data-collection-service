package com.wangbin.collector.core.connection.adapter;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.enums.ConnectionStatus;
import com.wangbin.collector.core.connection.model.ConnectionMetrics;

import java.util.Map;

/**
 * 连接适配器接口
 * @param <C> 客户端类型
 */
public interface ConnectionAdapter<C> {

    /**
     * 处理连接生命周期。
     */
    void connect() throws Exception;

    /**
     * 处理连接生命周期。
     */
    void disconnect() throws Exception;

    /**
     * 处理连接生命周期。
     */
    void reconnect() throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    void send(byte[] data) throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    void send(String data) throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    void send(Object data) throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    byte[] receive() throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    String receiveAsString() throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    byte[] receive(long timeout) throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    void heartbeat() throws Exception;

    /**
     * 执行当前业务逻辑。
     */
    void authenticate() throws Exception;

    ConnectionStatus getStatus();

    DeviceConnection getConnectionConfig();

    DeviceInfo getDeviceInfo();

    ConnectionMetrics getMetrics();

    boolean isConnected();

    boolean isAuthenticated();

    String getConnectionId();

    String getDeviceId();

    long getLastActivityTime();

    /**
     * 更新或刷新业务状态。
     */
    void updateActivityTime();

    C getClient();

    Map<String, Object> getConnectionParams();

    void setConnectionParam(String key, Object value);

    Map<String, Object> getStatistics();

    /**
     * 记录或统计业务状态。
     */
    void resetStatistics();

    /**
     * 执行当前业务逻辑。
     */
    boolean healthCheck();
}
