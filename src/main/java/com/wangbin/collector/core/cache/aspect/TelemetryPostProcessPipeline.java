package com.wangbin.collector.core.cache.aspect;

import lombok.extern.slf4j.Slf4j;
import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Executes post-processing stages with per-stage failure isolation.
 */
@Slf4j
@Component
public class TelemetryPostProcessPipeline {

    private final List<TelemetryPostProcessStage> stageCandidates;
    private final Map<TelemetryStageType, Executor> stageExecutors;

    @Autowired
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

    TelemetryPostProcessPipeline(List<TelemetryPostProcessStage> stageCandidates) {
        this.stageCandidates = stageCandidates;
        EnumMap<TelemetryStageType, Executor> executors = new EnumMap<>(TelemetryStageType.class);
        for (TelemetryStageType stageType : TelemetryStageType.values()) {
            executors.put(stageType, Runnable::run);
        }
        this.stageExecutors = Map.copyOf(executors);
    }

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

    private void executeStage(TelemetryPostProcessStage stage, TelemetryPostProcessContext context) {
        Executor executor = stageExecutors.get(stage.type());
        if (executor == null) {
            log.error("遥测后处理阶段未配置执行器，stage={}", stage.name());
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    stage.process(context);
                } catch (Exception exception) {
                    log.error("遥测后处理阶段执行失败，stage={}，device={}，point={}",
                            stage.name(), context.deviceId(), context.point().getPointId(), exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            log.warn("遥测后处理阶段任务被拒绝，stage={}，device={}，point={}",
                    stage.name(), context.deviceId(), context.point().getPointId());
        }
    }
}
