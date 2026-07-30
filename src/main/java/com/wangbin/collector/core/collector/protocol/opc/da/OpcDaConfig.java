package com.wangbin.collector.core.collector.protocol.opc.da;

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
