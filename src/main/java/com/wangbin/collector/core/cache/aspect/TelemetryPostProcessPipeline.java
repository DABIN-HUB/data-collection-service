package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 执行遥测后处理阶段，并隔离单个阶段的执行失败。
 */
@Slf4j
@Component
public class TelemetryPostProcessPipeline {

    private final List<TelemetryPostProcessStage> stageCandidates;
    private final Map<TelemetryStageType, Executor> stageExecutors;

    /**
     * 创建遥测后处理流水线。
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
     * 提交所有启用的后处理阶段。
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
            handleRejectedStage(stage, context, executor, exception);
        }
    }

    private void handleRejectedStage(TelemetryPostProcessStage stage,
                                     TelemetryPostProcessContext context,
                                     Executor executor,
                                     RejectedExecutionException exception) {
        if (isShuttingDown(executor)) {
            log.warn("遥测后处理阶段任务被拒绝，执行器正在关闭，阶段={}，设备={}，点位={}",
                    stage.name(), context.deviceId(), context.point().getPointId());
            return;
        }
        try {
            if (stage.onRejected(context, exception)) {
                log.warn("遥测后处理阶段任务被拒绝，已进入阶段补偿路径，阶段={}，设备={}，点位={}",
                        stage.name(), context.deviceId(), context.point().getPointId());
                return;
            }
        } catch (Exception fallbackException) {
            log.error("遥测后处理阶段拒绝补偿失败，阶段={}，设备={}，点位={}",
                    stage.name(), context.deviceId(), context.point().getPointId(), fallbackException);
            return;
        }
        log.warn("遥测后处理阶段任务被拒绝，阶段={}，设备={}，点位={}",
                stage.name(), context.deviceId(), context.point().getPointId());
    }

    private boolean isShuttingDown(Executor executor) {
        if (executor instanceof ThreadPoolTaskExecutor taskExecutor) {
            try {
                return taskExecutor.getThreadPoolExecutor().isShutdown();
            } catch (IllegalStateException exception) {
                return false;
            }
        }
        return executor instanceof ExecutorService executorService && executorService.isShutdown();
    }
}
