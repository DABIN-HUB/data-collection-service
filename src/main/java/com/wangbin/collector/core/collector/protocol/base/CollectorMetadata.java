package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.exception.CollectorException;

import java.util.Map;

public interface CollectorMetadata {

    Map<String, Object> getDeviceStatus() throws CollectorException;

    String getCollectorType();

    String getProtocolType();
}
