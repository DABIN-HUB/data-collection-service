package com.wangbin.collector.core.cloud.config;

import com.wangbin.collector.core.report.config.ReportProperties;

/**
 * 网关级批量属性包刷新策略。
 */
public record CloudBatchFlushPolicy(
        boolean enabled,
        int maxDevicesPerPack,
        int maxPropertiesPerPack,
        int maxPayloadBytes,
        long maxDelayMs,
        boolean highPriorityBypass) {

    private static final int DEFAULT_MAX_DEVICES = 50;
    private static final int DEFAULT_MAX_PROPERTIES = 500;
    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 128 * 1024;
    private static final long DEFAULT_MAX_DELAY_MS = 1000L;

    public static CloudBatchFlushPolicy defaults() {
        return new CloudBatchFlushPolicy(
                true,
                DEFAULT_MAX_DEVICES,
                DEFAULT_MAX_PROPERTIES,
                DEFAULT_MAX_PAYLOAD_BYTES,
                DEFAULT_MAX_DELAY_MS,
                true);
    }

    public static CloudBatchFlushPolicy disabled() {
        return new CloudBatchFlushPolicy(
                false,
                DEFAULT_MAX_DEVICES,
                DEFAULT_MAX_PROPERTIES,
                DEFAULT_MAX_PAYLOAD_BYTES,
                DEFAULT_MAX_DELAY_MS,
                true);
    }

    public static CloudBatchFlushPolicy from(ReportProperties.Cloud.Batch batch) {
        if (batch == null) {
            return defaults();
        }
        return new CloudBatchFlushPolicy(
                batch.isEnabled(),
                positiveOrDefault(batch.getMaxDevicesPerPack(), DEFAULT_MAX_DEVICES),
                positiveOrDefault(batch.getMaxPropertiesPerPack(), DEFAULT_MAX_PROPERTIES),
                positiveOrDefault(batch.getMaxPayloadBytes(), DEFAULT_MAX_PAYLOAD_BYTES),
                positiveOrDefault(batch.getMaxDelayMs(), DEFAULT_MAX_DELAY_MS),
                batch.isHighPriorityBypass());
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static long positiveOrDefault(long value, long defaultValue) {
        return value > 0 ? value : defaultValue;
    }
}
