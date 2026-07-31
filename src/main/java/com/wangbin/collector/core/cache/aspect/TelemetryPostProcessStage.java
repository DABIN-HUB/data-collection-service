package com.wangbin.collector.core.cache.aspect;

/**
 * 定义当前模块的业务契约。
 */
public interface TelemetryPostProcessStage {

    /**
     * 执行当前业务逻辑。
     */
    TelemetryStageType type();

    /**
     * 执行当前业务逻辑。
     */
    String name();

    /**
     * 执行当前业务逻辑。
     */
    boolean enabled(TelemetryPostProcessContext context);

    /**
     * 处理当前业务流程。
     */
    void process(TelemetryPostProcessContext context);
}
