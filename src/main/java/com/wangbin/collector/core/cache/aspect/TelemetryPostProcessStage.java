package com.wangbin.collector.core.cache.aspect;

public interface TelemetryPostProcessStage {

    TelemetryStageType type();

    String name();

    boolean enabled(TelemetryPostProcessContext context);

    void process(TelemetryPostProcessContext context);
}
