package com.wangbin.collector.core.collector.protocol.opc.da;

/**
 * 定义当前模块的不可变数据记录。
 */
public record OpcDaConfig(
        String serverProgId,
        String host,
        String endpoint,
        String username,
        String password,
        String domain,
        int requestTimeout,
        int updateRate,
        String bridgeMode,
        String bridgeBaseUrl,
        String bridgeToken,
        int bridgeRetryCount,
        long bridgeRetryBackoffMs) {
}
