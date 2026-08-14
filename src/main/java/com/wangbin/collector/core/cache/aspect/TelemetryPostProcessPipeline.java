package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.logging.RateLimitedLogReporter;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.LongAdder;

/**
 * 执行遥测后处理阶段，并隔离单个阶段的执行失败。
 */
@Slf4j
@Component
public class TelemetryPostProcessPipeline {

    private static final int LATENCY_SAMPLE_LIMIT = 20_000;

    private final List<TelemetryPostProcessStage> stageCandidates;
    private final Map<TelemetryStageType, Executor> stageExecutors;
    private final TelemetryLatencyReservoir processLatencyNanos = new TelemetryLatencyReservoir(LATENCY_SAMPLE_LIMIT);
    private final TelemetryLatencyReservoir stageSubmissionLatencyNanos =
            new TelemetryLatencyReservoir(LATENCY_SAMPLE_LIMIT);
    private final RateLimitedLogReporter rejectedLogReporter = new RateLimitedLogReporter(log);
    private final LongAdder processedItems = new LongAdder();
    private final LongAdder stageSubmissions = new LongAdder();
    private final LongAdder stageRejectedEvents = new LongAdder();
    private final LongAdder stageRejectedCompensatedEvents = new LongAdder();
    private final LongAdder stageRejectedUncompensatedEvents = new LongAdder();
    private final LongAdder stageRejectedShutdownEvents = new LongAdder();
    private volatile List<TelemetryPostProcessStage> orderedStageSnapshot = List.of();

    /**
     * 创建遥测后处理流水线。
     */
    public TelemetryPostProcessPipeline(
            List<TelemetryPostProcessStage> stageCandidates,
            @Qualifier(TelemetryExecutorNames.CACHE_STAGE) Executor cacheExecutor,
            @Qualifier(TelemetryExecutorNames.STREAM_STAGE) Executor streamExecutor,
            @Qualifier(TelemetryExecutorNames.HISTORY_STAGE) Executor historyExecutor,
            @Qualifier(TelemetryExecutorNames.REPORT_STAGE) Executor reportExecutor) {
        this.stageCandidates = stageCandidates == null ? List.of() : stageCandidates;
        EnumMap<TelemetryStageType, Executor> executors = new EnumMap<>(TelemetryStageType.class);
        executors.put(TelemetryStageType.CACHE, cacheExecutor);
        executors.put(TelemetryStageType.STREAM, streamExecutor);
        executors.put(TelemetryStageType.HISTORY, historyExecutor);
        executors.put(TelemetryStageType.REPORT, reportExecutor);
        this.stageExecutors = Map.copyOf(executors);
        rebuildStageSnapshot();
    }

    /**
     * 提交所有启用的后处理阶段。
     */
    public void process(TelemetryPostProcessContext context) {
        if (context == null || context.deviceId() == null || context.point() == null || context.processResult() == null) {
            return;
        }

        long startedAt = System.nanoTime();
        try {
            processedItems.increment();
            for (TelemetryPostProcessStage stage : orderedStageSnapshot) {
                if (stage == null || !stage.enabled(context)) {
                    continue;
                }
                executeStage(stage, context);
            }
        } finally {
            processLatencyNanos.add(System.nanoTime() - startedAt);
        }
    }

    private void executeStage(TelemetryPostProcessStage stage, TelemetryPostProcessContext context) {
        Executor executor = stageExecutors.get(stage.type());
        if (executor == null) {
            log.error("遥测后处理阶段未配置执行器，阶段={}", stage.name());
            return;
        }
        long startedAt = System.nanoTime();
        try {
            executor.execute(() -> {
                try {
                    stage.process(context);
                } catch (Exception exception) {
                    log.error("遥测后处理阶段执行失败，阶段={}，设备={}，点位={}",
                            stage.name(), context.deviceId(), context.point().getPointId(), exception);
                }
            });
            stageSubmissions.increment();
            stageSubmissionLatencyNanos.add(System.nanoTime() - startedAt);
        } catch (RejectedExecutionException exception) {
            stageSubmissionLatencyNanos.add(System.nanoTime() - startedAt);
            handleRejectedStage(stage, context, executor, exception);
        }
    }

    private void handleRejectedStage(TelemetryPostProcessStage stage,
                                     TelemetryPostProcessContext context,
                                     Executor executor,
                                     RejectedExecutionException exception) {
        stageRejectedEvents.increment();
        if (isShuttingDown(executor)) {
            stageRejectedShutdownEvents.increment();
            rejectedLogReporter.warn("stage-shutdown-" + stage.name(),
                    "遥测后处理阶段任务被拒绝，执行器正在关闭，阶段={}，设备={}，点位={}，queue={}，active={}/{}，原因={}",
                    stage.name(), context.deviceId(), context.point().getPointId(),
                    queueSize(executor), activeCount(executor), maxPoolSize(executor), exception.getMessage());
            return;
        }
        try {
            if (stage.onRejected(context, exception)) {
                stageRejectedCompensatedEvents.increment();
                rejectedLogReporter.warn("stage-compensated-" + stage.name(),
                        "遥测后处理阶段任务被拒绝，已进入阶段补偿路径，阶段={}，设备={}，点位={}，queue={}，active={}/{}，原因={}",
                        stage.name(), context.deviceId(), context.point().getPointId(),
                        queueSize(executor), activeCount(executor), maxPoolSize(executor), exception.getMessage());
                return;
            }
        } catch (Exception fallbackException) {
            log.error("遥测后处理阶段拒绝补偿失败，阶段={}，设备={}，点位={}",
                    stage.name(), context.deviceId(), context.point().getPointId(), fallbackException);
            return;
        }
        stageRejectedUncompensatedEvents.increment();
        rejectedLogReporter.warn("stage-uncompensated-" + stage.name(),
                "遥测后处理阶段任务被拒绝，阶段无补偿路径，阶段={}，设备={}，点位={}，queue={}，active={}/{}，原因={}",
                stage.name(), context.deviceId(), context.point().getPointId(),
                queueSize(executor), activeCount(executor), maxPoolSize(executor), exception.getMessage());
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

    /**
     * 在阶段注册列表变化后原子重建有序快照，正常处理路径只读取不可变快照。
     */
    void rebuildStageSnapshot() {
        List<TelemetryPostProcessStage> orderedStages = new ArrayList<>(stageCandidates.size());
        for (TelemetryPostProcessStage stage : stageCandidates) {
            if (stage != null) {
                orderedStages.add(stage);
            }
        }
        AnnotationAwareOrderComparator.sort(orderedStages);
        orderedStageSnapshot = List.copyOf(orderedStages);
    }

    List<TelemetryPostProcessStage> orderedStageSnapshot() {
        return orderedStageSnapshot;
    }

    /**
     * 返回流水线热路径内部观测快照。
     */
    public TelemetryPipelineMetrics metrics() {
        RateLimitedLogReporter.Snapshot logSnapshot = rejectedLogReporter.snapshot();
        TelemetryLatencyReservoir.Snapshot processLatencySnapshot = processLatencyNanos.snapshot();
        TelemetryLatencyReservoir.Snapshot stageSubmissionLatencySnapshot = stageSubmissionLatencyNanos.snapshot();
        return new TelemetryPipelineMetrics(
                processedItems.sum(),
                stageSubmissions.sum(),
                stageRejectedEvents.sum(),
                stageRejectedCompensatedEvents.sum(),
                stageRejectedUncompensatedEvents.sum(),
                stageRejectedShutdownEvents.sum(),
                processLatencySnapshot.percentileMillis(0.50D),
                processLatencySnapshot.percentileMillis(0.95D),
                processLatencySnapshot.percentileMillis(0.99D),
                processLatencySnapshot.sampleCount(),
                processLatencySnapshot.totalRecorded(),
                processLatencySnapshot.overwrittenSamples(),
                stageSubmissionLatencySnapshot.percentileMillis(0.50D),
                stageSubmissionLatencySnapshot.percentileMillis(0.95D),
                stageSubmissionLatencySnapshot.percentileMillis(0.99D),
                stageSubmissionLatencySnapshot.sampleCount(),
                stageSubmissionLatencySnapshot.totalRecorded(),
                stageSubmissionLatencySnapshot.overwrittenSamples(),
                processLatencySnapshot.internalErrors() + stageSubmissionLatencySnapshot.internalErrors(),
                logSnapshot.emittedEvents(),
                logSnapshot.suppressedEvents());
    }

    /**
     * 重置热路径观测采样，不影响阶段顺序和业务计数外部来源。
     */
    public void resetMetrics() {
        processedItems.reset();
        stageSubmissions.reset();
        stageRejectedEvents.reset();
        stageRejectedCompensatedEvents.reset();
        stageRejectedUncompensatedEvents.reset();
        stageRejectedShutdownEvents.reset();
        processLatencyNanos.reset();
        stageSubmissionLatencyNanos.reset();
        rejectedLogReporter.reset();
    }

    private int queueSize(Executor executor) {
        ThreadPoolExecutor pool = threadPool(executor);
        return pool == null ? -1 : pool.getQueue().size();
    }

    private int activeCount(Executor executor) {
        ThreadPoolExecutor pool = threadPool(executor);
        return pool == null ? -1 : pool.getActiveCount();
    }

    private int maxPoolSize(Executor executor) {
        ThreadPoolExecutor pool = threadPool(executor);
        return pool == null ? -1 : pool.getMaximumPoolSize();
    }

    private ThreadPoolExecutor threadPool(Executor executor) {
        if (executor instanceof ThreadPoolExecutor threadPoolExecutor) {
            return threadPoolExecutor;
        }
        if (executor instanceof ThreadPoolTaskExecutor taskExecutor) {
            try {
                return taskExecutor.getThreadPoolExecutor();
            } catch (IllegalStateException exception) {
                return null;
            }
        }
        return null;
    }
}
