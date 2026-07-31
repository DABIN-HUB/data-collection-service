package com.wangbin.collector.core.cache.aspect;

import lombok.extern.slf4j.Slf4j;
import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 执行遥测后处理阶段，并隔离单阶段失败。
 */
@Slf4j
@Component
public class TelemetryPostProcessPipeline {

    private final List<TelemetryPostProcessStage> stageCandidates;
    private final Map<TelemetryStageType, Executor> stageExecutors;

    /**
     * 创建当前组件实例。
     */
    public TelemetryPostProcessPipeline(
            List<TelemetryPostProcessStage> stageCandidates,
            @Qualifier(TelemetryExecutorNames.CACHE_STAGE) Executor cacheExecutor,
            @Qualifier(TelemetryExecutorNames.STREAM_STAGE) Executor streamExecutor,
            @Qualifier(TelemetryExecutorNames.HISTORY_STAGE) Executor historyExecutor,
            @Qualifier(TelemetryExecutorNames.REPORT_STAGE) Executor reportExecutor) {
        this.stageCandidates = stageCandidates;
        EnumMap<TelemetryStageType, Executor> executors = new EnumMap<>(TelemetryStageType.class);
        executors.put(TelemetryStageType.CACHE, cacheExecutor);
        executors.put(TelemetryStageType.STREAM, streamExecutor);
        executors.put(TelemetryStageType.HISTORY, historyExecutor);
        executors.put(TelemetryStageType.REPORT, reportExecutor);
        this.stageExecutors = Map.copyOf(executors);
    }


    /**
     * 处理当前业务流程。
     */
    public void process(TelemetryPostProcessContext context) {
        if (context == null || context.deviceId() == null || context.point() == null || context.processResult() == null) {
            return;
        }

        List<TelemetryPostProcessStage> orderedStages = new ArrayList<>(stageCandidates);
        AnnotationAwareOrderComparator.sort(orderedStages);
        for (TelemetryPostProcessStage stage : orderedStages) {
            if (stage == null || !stage.enabled(context)) {
                continue;
            }
            executeStage(stage, context);
        }
    }

    /**
     * 处理当前业务流程。
     */
    private void executeStage(TelemetryPostProcessStage stage, TelemetryPostProcessContext context) {
        Executor executor = stageExecutors.get(stage.type());
        if (executor == null) {
            log.error("遥测后处理阶段未配置执行器，阶段={}", stage.name());
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    stage.process(context);
                } catch (Exception exception) {
                    log.error("遥测后处理阶段执行失败，阶段={}，设备={}，点位={}",
                            stage.name(), context.deviceId(), context.point().getPointId(), exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            log.warn("遥测后处理阶段任务被拒绝，阶段={}，设备={}，点位={}",
                    stage.name(), context.deviceId(), context.point().getPointId());
        }
    }
}
