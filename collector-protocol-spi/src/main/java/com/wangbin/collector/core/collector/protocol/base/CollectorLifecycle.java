package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;

import java.util.Map;

/**
 * 定义当前模块的业务契约。
 */
public interface CollectorLifecycle {

    /**
     * 处理组件生命周期。
     */
    void init(DeviceInfo deviceInfo) throws CollectorException;

    /**
     * 处理连接生命周期。
     */
    void connect() throws CollectorException;

    /** 绑定采集器所属运行代次，旧采集器的迟到推送不得污染新窗口。 */
    default void setRuntimeGeneration(long generation) {
        // 兼容未提供运行代次绑定能力的旧采集器。
    }

    /**
     * 处理连接生命周期。
     */
    void disconnect() throws CollectorException;

    boolean isConnected();

    String getConnectionStatus();

    String getLastError();

    Map<String, Object> getStatistics();

    /**
     * 记录或统计业务状态。
     */
    void resetStatistics();

    /**
     * 处理组件生命周期。
     */
    void destroy();
}
