package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.core.processor.ProcessResult;

import java.util.Map;

/**
 * 暴露单次调用产生的处理结果快照，避免遥测切面直接绑定 BaseCollector。
 */
public interface InvocationProcessResultSource {
    Map<String, ProcessResult> takeInvocationProcessResults();
}
