package com.wangbin.collector.core.cache.aspect;

import com.wangbin.collector.common.logging.RateLimitedLogReporter;
import com.wangbin.collector.core.cache.config.TelemetryExecutorNames;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.port.DeviceDataActivityReporter;
import org.springframework.beans.factory.annotation.Autowired;
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

    private ConfigManager configManager;
    private AcquisitionRuntimeTracker acquisitionRuntimeTracker;
    private DeviceDataActivityReporter activityReporter;
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

    /** 生产装配必须核对当前点位身份，并在核心完成后通知活动统计。 */
    @Autowired
    public TelemetryPostProcessPipeline(List<TelemetryPostProcessStage> stages,
            @Qualifier(TelemetryExecutorNames.CACHE_STAGE) Executor cacheExecutor,
            @Qualifier(TelemetryExecutorNames.STREAM_STAGE) Executor streamExecutor,
            @Qualifier(TelemetryExecutorNames.HISTORY_STAGE) Executor historyExecutor,
            @Qualifier(TelemetryExecutorNames.REPORT_STAGE) Executor reportExecutor,
            AcquisitionRuntimeTracker tracker, DeviceDataActivityReporter reporter,
            ConfigManager configManager) {
        this(stages, cacheExecutor, streamExecutor, historyExecutor, reportExecutor);
        acquisitionRuntimeTracker = tracker;
        activityReporter = reporter;
        this.configManager = configManager;
    }

    /** 测试兼容构造器，不作为生产装配入口。 */
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
        processCore(context);
    }

    /** 只等待核心实时处理，不等待历史、云上报或 ACK。 */
    public CompletableFuture<Boolean> processCore(TelemetryPostProcessContext context) {
        if (context == null || context.deviceId() == null || context.point() == null || context.processResult() == null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> core = null;
        long startedAt = System.nanoTime();
        try {
            processedItems.increment();
            for (TelemetryPostProcessStage stage : orderedStageSnapshot) {
                if (stage == null || !stage.enabled(context)) continue;
                if (context.historicalOnly() && (stage.type() == TelemetryStageType.CACHE
                        || stage.type() == TelemetryStageType.STREAM)) continue;
                CompletableFuture<Boolean> completion = executeStage(stage, context);
                if (stage.type() == TelemetryStageType.CACHE) core = completion;
            }
        } finally {
            processLatencyNanos.add(System.nanoTime() - startedAt);
        }
        return core != null ? core : CompletableFuture.completedFuture(false);
    }

    public CompletableFuture<Void> processRecovery(TelemetryPostProcessContext context) {
        if (!context.historicalOnly()) throw new IllegalArgumentException("补偿只能使用历史上下文");
        List<CompletableFuture<Boolean>> completions = new ArrayList<>();
        for (TelemetryPostProcessStage stage : orderedStageSnapshot) {
            if (stage != null && (stage.type() == TelemetryStageType.HISTORY || stage.type() == TelemetryStageType.REPORT)
                    && stage.enabled(context)) completions.add(executeStage(stage, context));
        }
        return CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new));
    }

    private boolean commitStage(TelemetryPostProcessStage stage, TelemetryPostProcessContext context,
                                Runnable action) {
        if (context.historicalOnly()) {
            if (stage.type() != TelemetryStageType.HISTORY && stage.type() != TelemetryStageType.REPORT) return false;
            action.run();
            return true;
        }
        if (!context.live()) return false;
        if (stage.type() != TelemetryStageType.CACHE) {
            // 下游只在执行入口取得归属许可；网络/历史/上报不能持有设备提交门阻塞 STOP 或背压补偿。
            // 已进入的下游工作允许完成，但不会写实时缓存或授予本代成功事实。
            if (!commitLiveContext(stage, context, () -> {}) || !context.live()) return false;
            action.run();
            return true;
        }
        // 核心实时缓存与采集事实必须在同一设备/配置/回执门内完成，保持 STOP 和精准失效线性化。
        return commitLiveContext(stage, context, action);
    }

    private boolean commitLiveContext(TelemetryPostProcessStage stage, TelemetryPostProcessContext context,
                                      Runnable action) {
        return context.guard().commitIfCurrent(context.deviceId(), context.generation(), () -> {
            Runnable commit = () -> commitLiveStage(stage, context, action);
            if (context.receipt() != null) {
                Runnable guarded = commit;
                commit = () -> {
                    if (!context.receipt().commitIfOpen(guarded)) {
                        throw new CancellationException("本轮核心处理已取消");
                    }
                };
            }
            if (configManager == null) {
                commit.run();
                return;
            }
            Object sourceVersion = context.processResult().getMetadata(ProcessResultMetadataKeys.CONFIG_VERSION);
            // 版本核对和最终副作用在同一配置读锁中；配置提交不能穿过核对与缓存写入的间隙。
            if (!(sourceVersion instanceof Number version) || !configManager.runIfConfigurationCurrent(
                    context.deviceId(), version.longValue(), commit)) {
                throw new CancellationException("源配置版本已失效或缺失");
            }
        });
    }

    private void commitLiveStage(TelemetryPostProcessStage stage, TelemetryPostProcessContext context,
                                 Runnable action) {
        if (!context.live() || (acquisitionRuntimeTracker != null
                && !acquisitionRuntimeTracker.matchesPoint(context.deviceId(), context.generation(), context.point()))) {
            throw new CancellationException("代次或点位身份已失效");
        }
        if (configManager != null) {
            com.wangbin.collector.common.domain.entity.DataPoint current = configManager.getDataPointByPointId(
                    context.deviceId(), context.point().getPointId());
            if (current == null || !java.util.Objects.equals(current.getDeviceId(), context.deviceId())
                    || !java.util.Objects.equals(current.getPointCode(), context.point().getPointCode())
                    || !java.util.Objects.equals(current.getAddress(), context.point().getAddress())
                    || !java.util.Objects.equals(current.getDataType(), context.point().getDataType())) {
                throw new CancellationException("点位配置身份已失效");
            }
        }
        if (stage.type() == TelemetryStageType.CACHE && acquisitionRuntimeTracker != null
                && context.sampleAt() > 0L && !acquisitionRuntimeTracker.acceptsSample(
                context.deviceId(), context.generation(), context.point(), context.sampleAt())) {
            throw new CancellationException("旧样本不能覆盖更新事实");
        }
        action.run();
    }

    private CompletableFuture<Boolean> executeStage(TelemetryPostProcessStage stage, TelemetryPostProcessContext context) {
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        Executor executor = stageExecutors.get(stage.type());
        if (executor == null) {
            completion.completeExceptionally(new IllegalStateException("遥测阶段缺少执行器：" + stage.name()));
            return completion;
        }
        long startedAt = System.nanoTime();
        try {
            executor.execute(() -> {
                try {
                    boolean committed = commitStage(stage, context, () -> {
                        stage.process(context);
                        if (stage.type() == TelemetryStageType.CACHE && context.validValue()
                                && activityReporter != null && !"POLLING".equals(context.source())) {
                            activityReporter.recordSuccessfulData(context.deviceId(), context.generation(), context.sampleAt());
                        }
                    });
                    completion.complete(committed && context.validValue());
                } catch (Exception exception) {
                    if (exception instanceof CancellationException) {
                        if (context.receipt() != null && "旧样本不能覆盖更新事实".equals(exception.getMessage())) {
                            context.receipt().complete(context.point().getPointId(), false, "STALE_SAMPLE", context.sampleAt());
                        }
                        log.debug("跳过失效遥测阶段，阶段={}，设备={}，点位={}，原因={}",
                                stage.name(), context.deviceId(), context.point().getPointId(), exception.getMessage());
                    } else {
                        recordCoreFailure(stage, context);
                        log.error("遥测后处理阶段执行失败，阶段={}，设备={}，点位={}",
                                stage.name(), context.deviceId(), context.point().getPointId(), exception);
                    }
                    completion.completeExceptionally(exception);
                }
            });
            stageSubmissions.increment();
            stageSubmissionLatencyNanos.add(System.nanoTime() - startedAt);
        } catch (RejectedExecutionException exception) {
            stageSubmissionLatencyNanos.add(System.nanoTime() - startedAt);
            recordCoreFailure(stage, context);
            completion.completeExceptionally(exception);
            // 补偿只保留可靠数据，不把任务提交或后续重放当作本轮核心成功。
            if (stage.type() != TelemetryStageType.CACHE) {
                try {
                    commitStage(stage, context, () -> handleRejectedStage(stage, context, executor, exception));
                } catch (CancellationException cancelled) {
                    log.debug("跳过已失效遥测的阶段补偿，设备={}，阶段={}", context.deviceId(), stage.name());
                }
            } else {
                stageRejectedEvents.increment();
                stageRejectedUncompensatedEvents.increment();
            }
        }
        return completion;
    }

    private void recordCoreFailure(TelemetryPostProcessStage stage, TelemetryPostProcessContext context) {
        if (stage.type() != TelemetryStageType.CACHE || acquisitionRuntimeTracker == null || !context.live()) return;
        try {
            commitStage(stage, context, () -> {
                acquisitionRuntimeTracker.recordPointFailure(context.deviceId(), context.generation(),
                        context.point().getPointId(), "PROCESS_ERROR", context.sampleAt());
            });
        } catch (CancellationException cancelled) {
            log.debug("旧配置或旧样本的核心失败不覆盖当前事实，设备={}，点位={}",
                    context.deviceId(), context.point().getPointId());
        } catch (Exception exception) {
            log.error("记录核心处理失败事实时发生错误，设备={}，点位={}",
                    context.deviceId(), context.point().getPointId(), exception);
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
