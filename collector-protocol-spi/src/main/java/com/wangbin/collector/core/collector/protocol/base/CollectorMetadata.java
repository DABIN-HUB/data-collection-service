package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.exception.CollectorException;

import java.util.Map;

/**
 * 定义当前模块的业务契约。
 */
public interface CollectorMetadata {

    Map<String, Object> getDeviceStatus() throws CollectorException;

    String getCollectorType();

    String getProtocolType();
}
