package com.wangbin.collector.monitor.metrics;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 閲囬泦鍣ㄥ眰闈㈢殑鑱氬悎鎸囨爣锛屾弿杩板崟涓澶囨垨浠诲姟鐨勮繍琛屽仴搴风姸鍐点€?
 */
@Data
@Builder
public class CollectorMetrics {

    private final String deviceId;
    private final String protocol;

    /**
     * 鏈€杩戜竴涓粺璁″懆鏈熷唴澶勭悊鐨勬暟鎹偣鏁伴噺銆?
     */
    private final long processedPoints;

    /**
     * 姣忕澶勭悊鐨勬暟鎹偣閫熺巼銆?
     */
    private final double pointsPerSecond;

    /**
     * 閲囬泦鎴愬姛鐜囷紙0-100锛夈€?
     */
    private final double successRate;

    /**
     * 骞冲潎閲囬泦寤惰繜锛屽崟浣嶆绉掋€?
     */
    private final double averageLatencyMs;

    /**
     * 鍗忚鎴栭┍鍔ㄧ壒鏈夌殑闄勫姞鎸囨爣銆?
     */
    @Builder.Default
    private final Map<String, Object> protocolMetrics = Collections.emptyMap();

    /**
     * 缁熻鐢熸垚鏃堕棿銆?
     */
    @Builder.Default
    private final long timestamp = Instant.now().toEpochMilli();

    public static CollectorMetrics idle(String deviceId, String protocol) {
        return CollectorMetrics.builder()
                .deviceId(deviceId)
                .protocol(protocol)
                .processedPoints(0)
                .pointsPerSecond(0.0)
                .successRate(0.0)
                .averageLatencyMs(0.0)
                .protocolMetrics(Collections.emptyMap())
                .build();
    }
}