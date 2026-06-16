package com.wangbin.collector.core.cache.aspect;

public interface TelemetryPostProcessStage {

    String name();

    boolean enabled(TelemetryPostProcessContext context);

    void process(TelemetryPostProcessContext context);
}
