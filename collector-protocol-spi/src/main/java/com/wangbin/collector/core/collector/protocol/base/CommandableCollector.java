package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.exception.CollectorException;

import java.util.Map;

/**
 * 定义当前模块的业务契约。
 */
public interface CommandableCollector {

    /**
     * 处理当前业务流程。
     */
    Object executeCommand(String command, Map<String, Object> params) throws CollectorException;
}
