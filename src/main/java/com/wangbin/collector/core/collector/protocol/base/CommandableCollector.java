package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.exception.CollectorException;

import java.util.Map;

public interface CommandableCollector {

    Object executeCommand(String command, Map<String, Object> params) throws CollectorException;
}
