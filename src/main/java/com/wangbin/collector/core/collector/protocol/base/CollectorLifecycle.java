package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.exception.CollectorException;

import java.util.Map;

public interface CollectorLifecycle {

    void init(DeviceInfo deviceInfo) throws CollectorException;

    void connect() throws CollectorException;

    void disconnect() throws CollectorException;

    boolean isConnected();

    String getConnectionStatus();

    String getLastError();

    Map<String, Object> getStatistics();

    void resetStatistics();

    void destroy();
}
