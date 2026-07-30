package com.wangbin.collector.core.cloud.config;

import com.wangbin.collector.core.report.config.ReportProperties;

/**
 * 云平台业务 ACK 状态机配置。
 */
public record CloudAckOptions(
        CloudAckMode mode,
        long timeoutMs,
        int maxPending,
        long timeoutScanMs,
        CloudAckCommitMode commitMode) {

    private static final long DEFAULT_TIMEOUT_MS = 5000L;
    private static final int DEFAULT_MAX_PENDING = 10000;
    private static final long DEFAULT_TIMEOUT_SCAN_MS = 500L;

    public static CloudAckOptions defaults() {
        return new CloudAckOptions(
                CloudAckMode.ASYNC,
                DEFAULT_TIMEOUT_MS,
                DEFAULT_MAX_PENDING,
                DEFAULT_TIMEOUT_SCAN_MS,
                CloudAckCommitMode.PUBLISH_SUCCESS);
    }

    public static CloudAckOptions from(ReportProperties.Cloud.Ack ack) {
        if (ack == null) {
            return defaults();
        }
        long timeoutMs = ack.getTimeoutMs() > 0 ? ack.getTimeoutMs() : DEFAULT_TIMEOUT_MS;
        int maxPending = ack.getMaxPending() > 0 ? ack.getMaxPending() : DEFAULT_MAX_PENDING;
        long timeoutScanMs = ack.getTimeoutScanMs() > 0 ? ack.getTimeoutScanMs() : DEFAULT_TIMEOUT_SCAN_MS;
        return new CloudAckOptions(
                CloudAckMode.from(ack.getMode()),
                timeoutMs,
                maxPending,
                timeoutScanMs,
                CloudAckCommitMode.from(ack.getCommitOn()));
    }

    public boolean enabled() {
        return mode != CloudAckMode.DISABLED;
    }
}
