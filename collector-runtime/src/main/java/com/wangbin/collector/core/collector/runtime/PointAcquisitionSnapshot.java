package com.wangbin.collector.core.collector.runtime;

/** 当前运行代次内单个稳定 pointId 的采集事实；质量未知时 qualityCode 为 null。 */
public record PointAcquisitionSnapshot(String pointId,
                                       Mode acquisitionMode,
                                       Outcome outcome,
                                       Integer qualityCode,
                                       long lastAttemptAt,
                                       long lastValueAt,
                                       long lastFailureAt,
                                       int consecutiveFailures,
                                       String failureReason,
                                       String errorMessage,
                                       boolean stale,
                                       boolean participating) {
    public PointAcquisitionSnapshot(String pointId, Mode acquisitionMode, Outcome outcome, Integer qualityCode,
                                     long lastAttemptAt, long lastValueAt, long lastFailureAt,
                                     int consecutiveFailures, String failureReason, String errorMessage, boolean stale) {
        this(pointId, acquisitionMode, outcome, qualityCode, lastAttemptAt, lastValueAt, lastFailureAt,
                consecutiveFailures, failureReason, errorMessage, stale, true);
    }
    public enum Mode { UNKNOWN, POLLING, EVENT, HYBRID }
    public enum Outcome { WAITING, OBSERVED, FAILED, STALE }
}
