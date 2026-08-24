package com.wangbin.collector.core.cache.aspect;

import java.util.concurrent.RejectedExecutionException;

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

    /**
     * 处理阶段执行器拒绝后的补偿入口，默认保持原有只记录拒绝的行为。
     */
    default boolean onRejected(TelemetryPostProcessContext context, RejectedExecutionException exception) {
        return false;
    }
}
