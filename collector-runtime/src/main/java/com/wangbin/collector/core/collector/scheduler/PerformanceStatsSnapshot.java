package com.wangbin.collector.core.collector.scheduler;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 调度器性能快照。
 */
@Data
@Builder
public class PerformanceStatsSnapshot {

    private final int timeSliceCount;
    private final int timeSliceIntervalMs;

    @Builder.Default
    private final Map<Integer, Long> timeSliceExecutionTimes = Collections.emptyMap();

    @Builder.Default
    private final Map<Integer, Long> overloadedSlices = Collections.emptyMap();

    @Builder.Default
    private final Map<String, Long> slowestDevices = Collections.emptyMap();

    @Builder.Default
    private final Map<String, Map<String, Object>> deviceStats = Collections.emptyMap();

    private final double processCpuLoad;
    private final long batchDispatchRejectedCount;
    private final long collectRejectedCount;
    private final long processRejectedCount;
    private final long reconnectAttemptCount;
    private final long reconnectSuccessCount;
    private final long reconnectFailureCount;
    private final int reconnectingDevices;

    @Builder.Default
    private final long generatedAt = Instant.now().toEpochMilli();
}
