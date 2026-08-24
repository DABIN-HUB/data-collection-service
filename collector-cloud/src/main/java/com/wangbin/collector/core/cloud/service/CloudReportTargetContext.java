package com.wangbin.collector.core.cloud.service;

import com.wangbin.collector.core.cloud.config.CloudAckOptions;
import com.wangbin.collector.core.cloud.config.CloudBatchFlushPolicy;
import com.wangbin.collector.core.cloud.config.CloudPayloadOptions;
import com.wangbin.collector.core.cloud.protocol.CloudProtocolAdapter;

/**
 * 单个网关上报目标的运行时上下文。
 */
public record CloudReportTargetContext(
        String targetId,
        String cloudProvider,
        CloudProtocolAdapter protocolAdapter,
        CloudPayloadOptions payloadOptions,
        CloudBatchFlushPolicy batchPolicy,
        CloudAckOptions ackOptions) {

    public CloudReportTargetContext {
        if (protocolAdapter == null) {
            throw new IllegalArgumentException("protocolAdapter must not be null");
        }
        cloudProvider = cloudProvider == null || cloudProvider.isBlank()
                ? protocolAdapter.provider()
                : cloudProvider.trim();
        payloadOptions = payloadOptions == null ? CloudPayloadOptions.defaults() : payloadOptions;
        batchPolicy = batchPolicy == null ? CloudBatchFlushPolicy.defaults() : batchPolicy;
        ackOptions = ackOptions == null ? CloudAckOptions.defaults() : ackOptions;
    }
}
