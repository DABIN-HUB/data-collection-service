package com.wangbin.collector.soak;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.cloud.CloudDeviceType;
import com.wangbin.collector.common.domain.cloud.CloudTargetConfig;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.cache.aspect.CollectorDataPostProcessor;
import com.wangbin.collector.core.cache.aspect.CollectorDataPostProcessorMetrics;
import com.wangbin.collector.core.cache.aspect.TelemetryPipelineMetrics;
import com.wangbin.collector.core.cache.aspect.TelemetryPostProcessPipeline;
import com.wangbin.collector.core.cache.config.TelemetryExecutorProperties;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBuffer;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBufferMetrics;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBufferProperties;
import com.wangbin.collector.core.cache.service.TelemetryStreamMetrics;
import com.wangbin.collector.core.cache.service.TelemetryStreamService;
import com.wangbin.collector.core.collector.factory.CollectorFactory;
import com.wangbin.collector.core.collector.ingress.TelemetryIngressService;
import com.wangbin.collector.core.collector.protocol.base.BaseCollector;
import com.wangbin.collector.core.collector.scheduler.CollectionScheduler;
import com.wangbin.collector.core.collector.scheduler.PerformanceMonitor;
import com.wangbin.collector.core.collector.scheduler.PerformanceStatsSnapshot;
import com.wangbin.collector.core.collector.scheduler.SchedulerRuntimeState;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.outbox.CloudOutboxStatus;
import com.wangbin.collector.monitor.metrics.SystemResourceMonitorService;
import com.wangbin.collector.monitor.metrics.SystemResourceSnapshot;
import com.wangbin.collector.storage.buffer.HistoryBatchMetrics;
import com.wangbin.collector.storage.buffer.HistoryBatchProperties;
import com.wangbin.collector.storage.buffer.HistoryBatchWriter;
import com.wangbin.collector.storage.buffer.HistoryBufferProperties;
import com.wangbin.collector.storage.buffer.HistoryBufferMetrics;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.service.TimeSeriesService;
import com.wangbin.collector.storage.service.TdengineWriteMetrics;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 真实 Redis、TDengine 与本地云端通道的长稳容量基线入口。
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "telemetry.tdengine.enabled=true",
        "collector.report.enabled=true",
        "collector.report.mode=mqtt",
        "collector.report.mqtt.enabled=true",
        "collector.report.cloud.ack.mode=async",
        "collector.report.cloud.ack.commit-on=ack-success",
        "collector.report.cloud.batch.max-delay-ms=500",
        "collector.report.interval-ms=1000",
        "collector.config.loader=file",
        "collector.adaptive-collection.enabled=false"
})
class RealEnvironmentSoakIT {

    private static final DateTimeFormatter RUN_ID_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final String DEFAULT_SOURCE = "REAL_SOAK";
    private static final long BYTES_PER_MIB = 1024L * 1024L;
    private static final int MIN_PHASE_WHEEL_TICK_MS = 50;
    static final double DEFAULT_LOAD_DEVIATION_TOLERANCE_PERCENT = 5.0d;

    @Autowired
    private TelemetryIngressService telemetryIngressService;

    @Autowired
    private CollectorDataPostProcessor collectorDataPostProcessor;

    @Autowired
    private TelemetryPostProcessPipeline telemetryPostProcessPipeline;

    @Autowired
    private CollectionScheduler collectionScheduler;

    @Autowired
    private SchedulerRuntimeState schedulerRuntimeState;

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private AutowireCapableBeanFactory beanFactory;

    @Autowired
    private SystemResourceMonitorService systemResourceMonitorService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ReportProperties reportProperties;

    @Autowired
    private HistoryBatchProperties historyBatchProperties;

    @Autowired
    private HistoryBufferProperties historyBufferProperties;

    @Autowired
    private TdengineProperties tdengineProperties;

    @Autowired
    private TelemetryIngressBufferProperties telemetryIngressBufferProperties;

    @Autowired
    private TelemetryStreamProperties telemetryStreamProperties;

    @Autowired
    private CollectorProperties collectorProperties;

    @Autowired
    private TelemetryExecutorProperties telemetryExecutorProperties;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ObjectProvider<HistoryWriteBuffer> historyWriteBufferProvider;

    @Autowired
    private ObjectProvider<HistoryBatchWriter> historyBatchWriterProvider;

    @Autowired
    private ObjectProvider<TelemetryIngressBuffer> telemetryIngressBufferProvider;

    @Autowired
    private ObjectProvider<TelemetryStreamService> telemetryStreamServiceProvider;

    @Autowired
    private ObjectProvider<TimeSeriesService> timeSeriesServiceProvider;

    @Autowired
    private ObjectProvider<DataSource> dataSourceProvider;

    @MockBean
    private CollectorFactory collectorFactory;

    @Test
    void runRealEnvironmentSoak() throws Exception {
        SoakOptions options = SoakOptions.from(environment);
        Path outputDir = options.outputDir();
        Files.createDirectories(outputDir);
        SoakCounters counters = new SoakCounters();
        SoakRunIsolationSupport.SoakRunLock runLock = null;
        RedisNamespaceBinding redisBinding = null;
        SoakLifecycle lifecycle = new SoakLifecycle(options.startedAt());
        List<DevicePoints> devicePoints = List.of();
        MqttAckBridge ackBridge = null;
        boolean lockAcquired = false;
        try {
            runLock = SoakRunIsolationSupport.acquireRunLock(soakResultsRoot(outputDir), options.runId());
            lockAcquired = true;
            lifecycle.runLockOwner.set(runLock.owner());
            lifecycle.runLockPath.set(runLock.path().toString());
            redisBinding = applyRedisNamespace(options);
            devicePoints = registerDevices(options);
            if (options.runtimeIngressMode()) {
                configureRuntimeCollectorFactory(counters);
            }
            List<Long> roundDurations = new ArrayList<>();
            List<MetricSample> samples = new ArrayList<>();
            try (BufferedWriter metricsWriter = Files.newBufferedWriter(
                    outputDir.resolve("metrics.csv"), StandardCharsets.UTF_8)) {
                writeRunInfo(outputDir, options, devicePoints, lifecycle);
                writeMetricsHeader(metricsWriter);
                if (options.ackBridgeEnabled()) {
                    ackBridge = MqttAckBridge.start(options, objectMapper);
                }
                MetricSample runStartSample;
                MetricSample loadEndSample;
                if (options.runtimeIngressMode()) {
                    RuntimeMeasurementWindow window = runRuntimeWarmupAndMeasurement(
                            outputDir, options, devicePoints, counters, roundDurations, samples, metricsWriter,
                            ackBridge, lifecycle);
                    runStartSample = window.measurementStartSample();
                    loadEndSample = window.measurementEndSample();
                } else {
                    lifecycle.measurementValid.set(true);
                    lifecycle.measurementStartedAt.set(System.currentTimeMillis());
                    runStartSample = collectSample(options, counters, roundDurations, ackBridge, false);
                    samples.add(runStartSample);
                    writeMetric(metricsWriter, runStartSample);
                    runLoad(options, devicePoints, counters, roundDurations, samples, metricsWriter);
                    loadEndSample = collectSample(options, counters, roundDurations, ackBridge, false);
                    samples.add(loadEndSample);
                    writeMetric(metricsWriter, loadEndSample);
                    lifecycle.measurementCompletedAt.set(loadEndSample.timestamp());
                }
                lifecycle.drainStartedAt.set(System.currentTimeMillis());
                waitForDrain(Duration.ofSeconds(options.drainWaitSeconds()));
                lifecycle.drainCompletedAt.set(System.currentTimeMillis());
                MetricSample finalSample = collectSample(options, counters, roundDurations, ackBridge, true);
                samples.add(finalSample);
                writeMetric(metricsWriter, finalSample);
                writeSummary(outputDir, options, devicePoints, counters, roundDurations,
                        samples, runStartSample, loadEndSample, ackBridge, lifecycle);
                writeRunInfo(outputDir, options, devicePoints, lifecycle);
                assertTrue(counters.rejected.get() <= options.maxAllowedRejected(),
                        "Soak rejected task count exceeded threshold: " + counters.rejected.get());
            }
        } catch (SoakRunIsolationSupport.SoakRunLockException exception) {
            lifecycle.measurementValid.set(false);
            lifecycle.invalidReason.set(exception.getMessage());
            writeInvalidSummary(outputDir, options, lifecycle);
            writeRunInfo(outputDir, options, devicePoints, lifecycle);
            throw exception;
        } finally {
            if (ackBridge != null) {
                ackBridge.close();
            }
            if (lockAcquired) {
                cleanupDevices(devicePoints);
                cleanupRunNamespace(options);
            }
            restoreRedisNamespace(redisBinding);
            if (runLock != null) {
                runLock.close();
            }
        }
    }

    private Path soakResultsRoot(Path outputDir) {
        Path parent = outputDir.toAbsolutePath().getParent();
        return parent != null ? parent : Path.of("target", "soak-results").toAbsolutePath();
    }

    private RedisNamespaceBinding applyRedisNamespace(SoakOptions options) {
        RedisNamespaceBinding binding = new RedisNamespaceBinding(
                historyBufferProperties.getPendingKey(),
                historyBufferProperties.getProcessingKey(),
                historyBufferProperties.getDeadLetterKey(),
                telemetryIngressBufferProperties.getPendingKey(),
                telemetryIngressBufferProperties.getProcessingKey(),
                telemetryIngressBufferProperties.getDeadLetterKey(),
                telemetryStreamProperties.getKey());
        SoakRunIsolationSupport.RedisNamespace namespace = options.redisNamespace();
        historyBufferProperties.setPendingKey(namespace.historyPendingKey());
        historyBufferProperties.setProcessingKey(namespace.historyProcessingKey());
        historyBufferProperties.setDeadLetterKey(namespace.historyDeadLetterKey());
        telemetryIngressBufferProperties.setPendingKey(namespace.entryPendingKey());
        telemetryIngressBufferProperties.setProcessingKey(namespace.entryProcessingKey());
        telemetryIngressBufferProperties.setDeadLetterKey(namespace.entryDeadLetterKey());
        telemetryStreamProperties.setKey(namespace.streamKey());
        return binding;
    }

    private void restoreRedisNamespace(RedisNamespaceBinding binding) {
        if (binding == null) {
            return;
        }
        historyBufferProperties.setPendingKey(binding.historyPendingKey());
        historyBufferProperties.setProcessingKey(binding.historyProcessingKey());
        historyBufferProperties.setDeadLetterKey(binding.historyDeadLetterKey());
        telemetryIngressBufferProperties.setPendingKey(binding.entryPendingKey());
        telemetryIngressBufferProperties.setProcessingKey(binding.entryProcessingKey());
        telemetryIngressBufferProperties.setDeadLetterKey(binding.entryDeadLetterKey());
        telemetryStreamProperties.setKey(binding.streamKey());
    }

    private void cleanupRunNamespace(SoakOptions options) {
        SoakRunIsolationSupport.RedisNamespace namespace = options.redisNamespace();
        if (!namespace.ownsAll(namespace.keys())) {
            return;
        }
        try {
            redisTemplate.delete(namespace.keys());
        } catch (RuntimeException ignored) {
            // 测试 namespace 清理失败不覆盖主结果；summary 已记录清理前的最终状态。
        }
    }

    private void runLoad(SoakOptions options,
                         List<DevicePoints> devicePoints,
                         SoakCounters counters,
                        List<Long> roundDurations,
                        List<MetricSample> samples,
                        BufferedWriter metricsWriter) throws Exception {
        if (options.runtimeIngressMode()) {
            return;
        }
        long start = System.currentTimeMillis();
        counters.loadStartedAt.set(start);
        long end = start + TimeUnit.SECONDS.toMillis(options.durationSeconds());
        long nextSample = start;
        int roundIndex = 0;
        try {
            while (System.currentTimeMillis() < end) {
                long roundStart = System.nanoTime();
                long eventTs = System.currentTimeMillis();
                int emitted = 0;
                for (DevicePoints device : devicePoints) {
                    if (options.batchIngressMode()) {
                        appendBatch(device.deviceId(), device.points(), eventTs, roundIndex, counters);
                        emitted += device.points().size();
                        paceWithinRound(options, roundStart, emitted);
                    } else {
                        for (DataPoint point : device.points()) {
                            appendPoint(device.deviceId(), point, eventTs, roundIndex, counters);
                            emitted++;
                            paceWithinRound(options, roundStart, emitted);
                        }
                    }
                }
                long roundDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - roundStart);
                roundDurations.add(roundDurationMs);
                counters.rounds.incrementAndGet();
                long now = System.currentTimeMillis();
                if (now >= nextSample) {
                    MetricSample sample = collectSample(options, counters, roundDurations, null, false);
                    samples.add(sample);
                    writeMetric(metricsWriter, sample);
                    metricsWriter.flush();
                    nextSample = now + TimeUnit.SECONDS.toMillis(options.sampleIntervalSeconds());
                }
                long sleepMs = options.collectionIntervalMs() - roundDurationMs;
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
                roundIndex++;
            }
        } finally {
            counters.loadFinishedAt.set(System.currentTimeMillis());
        }
    }

    private RuntimeMeasurementWindow runRuntimeWarmupAndMeasurement(Path outputDir,
                                                                    SoakOptions options,
                                                                    List<DevicePoints> devicePoints,
                                                                    SoakCounters counters,
                                                                    List<Long> roundDurations,
                                                                    List<MetricSample> samples,
                                                                    BufferedWriter metricsWriter,
                                                                    MqttAckBridge ackBridge,
                                                                    SoakLifecycle lifecycle) throws Exception {
        validateFixedCapacityContract(options);
        configureRuntimeCollectorFactory(counters);
        lifecycle.setupStartedAt.set(System.currentTimeMillis());
        for (DevicePoints device : devicePoints) {
            assertTrue(startRuntimeDevice(device.deviceId()),
                    "runtime soak device failed to start: " + device.deviceId());
        }
        lifecycle.setupCompletedAt.set(System.currentTimeMillis());
        counters.activateRuntimeCollector();
        lifecycle.warmupStartedAt.set(System.currentTimeMillis());
        MetricSample measurementStart = null;
        try {
            observeRuntimeWindow(options, counters, roundDurations, samples, metricsWriter,
                    ackBridge, options.warmupSeconds(), false);
            lifecycle.warmupCompletedAt.set(System.currentTimeMillis());
            counters.deactivateRuntimeCollector();
            lifecycle.settleStartedAt.set(System.currentTimeMillis());
            SoakRunIsolationSupport.QuiescenceSnapshot warmupQuiescence =
                    waitForWarmupQuiescence(Duration.ofSeconds(Math.max(1L, options.settleTimeoutSeconds())));
            lifecycle.settleCompletedAt.set(System.currentTimeMillis());
            lifecycle.warmupBacklogClean.set(warmupQuiescence.quiescent());
            lifecycle.warmupQuiescence.set(warmupQuiescence);
            lifecycle.settleSeconds.set(Math.max(0L,
                    TimeUnit.MILLISECONDS.toSeconds(lifecycle.settleCompletedAt.get() - lifecycle.settleStartedAt.get())));
            if (!warmupQuiescence.quiescent()) {
                lifecycle.measurementValid.set(false);
                lifecycle.invalidReason.set("INVALID_WARMUP");
                writeInvalidSummary(outputDir, options, lifecycle);
                writeRunInfo(outputDir, options, devicePoints, lifecycle);
                throw new SoakRunIsolationSupport.InvalidWarmupException(warmupQuiescence);
            }

            collectionScheduler.resetPhaseWheelStats();
            telemetryPostProcessPipeline.resetMetrics();
            collectorDataPostProcessor.resetMetrics();
            TimeSeriesService timeSeriesService = timeSeriesServiceProvider.getIfAvailable();
            if (timeSeriesService != null) {
                timeSeriesService.resetWriteMetrics();
            }
            counters.activateRuntimeCollector();
            counters.startMeasurement();
            lifecycle.measurementValid.set(true);
            lifecycle.measurementStartedAt.set(counters.loadStartedAt.get());
            measurementStart = collectSample(options, counters, roundDurations, ackBridge, false);
            samples.add(measurementStart);
            writeMetric(metricsWriter, measurementStart);
            observeRuntimeWindow(options, counters, roundDurations, samples, metricsWriter,
                    ackBridge, options.durationSeconds(), true);
        } finally {
            counters.finishMeasurement();
            counters.deactivateRuntimeCollector();
            stopRuntimeDevices(devicePoints);
        }
        lifecycle.measurementCompletedAt.set(counters.loadFinishedAt.get());
        MetricSample measurementEnd = collectSample(options, counters, roundDurations, ackBridge, false);
        samples.add(measurementEnd);
        writeMetric(metricsWriter, measurementEnd);
        if (measurementStart == null) {
            measurementStart = measurementEnd;
        }
        return new RuntimeMeasurementWindow(measurementStart, measurementEnd);
    }

    private void validateFixedCapacityContract(SoakOptions options) {
        if (!options.fixedCapacityMode()) {
            return;
        }
        String invalidReason = fixedCapacityInvalidReason(collectorProperties.getAdaptiveCollection().isEnabled());
        if (invalidReason != null) {
            throw new IllegalStateException(invalidReason);
        }
    }

    private void observeRuntimeWindow(SoakOptions options,
                                      SoakCounters counters,
                                      List<Long> roundDurations,
                                      List<MetricSample> samples,
                                      BufferedWriter metricsWriter,
                                      MqttAckBridge ackBridge,
                                      long durationSeconds,
                                      boolean recordSamples) throws Exception {
        long start = System.currentTimeMillis();
        long end = start + TimeUnit.SECONDS.toMillis(Math.max(0L, durationSeconds));
        long nextSample = start + TimeUnit.SECONDS.toMillis(options.sampleIntervalSeconds());
        while (System.currentTimeMillis() < end) {
            long now = System.currentTimeMillis();
            if (recordSamples && now >= nextSample) {
                MetricSample sample = collectSample(options, counters, roundDurations, ackBridge, false);
                samples.add(sample);
                writeMetric(metricsWriter, sample);
                metricsWriter.flush();
                nextSample = now + TimeUnit.SECONDS.toMillis(options.sampleIntervalSeconds());
            }
            Thread.sleep(200L);
        }
    }

    private SoakRunIsolationSupport.QuiescenceSnapshot waitForWarmupQuiescence(Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        SoakRunIsolationSupport.QuiescenceSnapshot latest = currentQuiescenceSnapshot();
        while (System.nanoTime() < deadline) {
            HistoryBatchWriter batchWriter = historyBatchWriterProvider.getIfAvailable();
            if (batchWriter != null) {
                batchWriter.flushDueBuckets();
            }
            latest = currentQuiescenceSnapshot();
            if (latest.quiescent()) {
                return latest;
            }
            Thread.sleep(500L);
        }
        return latest;
    }

    private SoakRunIsolationSupport.QuiescenceSnapshot currentQuiescenceSnapshot() {
        Map<String, Long> observed = new LinkedHashMap<>();
        HistoryBufferMetrics history = historyWriteBufferProvider.getIfAvailable() != null
                ? historyWriteBufferProvider.getIfAvailable().metrics()
                : new HistoryBufferMetrics(0L, 0L, 0L, 0, 0);
        TelemetryIngressBufferMetrics entry = telemetryIngressBufferProvider.getIfAvailable() != null
                ? telemetryIngressBufferProvider.getIfAvailable().metrics()
                : TelemetryIngressBufferMetrics.empty();
        HistoryBatchWriter batchWriter = historyBatchWriterProvider.getIfAvailable();
        HistoryBatchMetrics batch = batchWriter != null ? batchWriter.metrics() : emptyHistoryBatchMetrics();
        observed.put("entry.redisPending", entry.redisPending());
        observed.put("entry.redisProcessing", entry.redisProcessing());
        observed.put("entry.localPending", (long) entry.localPending());
        observed.put("history.redisPending", history.redisPending());
        observed.put("history.redisProcessing", history.redisProcessing());
        observed.put("history.localPending", (long) history.localPending());
        observed.put("historyBatch.currentBufferedRows", (long) batch.currentBufferedRows());
        observed.put("historyBatch.inFlightFlushes", (long) batch.inFlightFlushes());
        observed.put("historyBatch.flushExecutorQueueCurrent", (long) batch.flushExecutorQueueCurrent());
        observed.put("historyBatch.flushExecutorActiveCurrent", (long) batch.flushExecutorActiveCurrent());
        observed.put("scheduler.inFlightPointClaims", schedulerLongMethod("getInFlightPointScheduleSizeForTest"));
        SystemResourceSnapshot resources = systemResourceMonitorService.getResources();
        addExecutorQuiescence(observed, resources, "cacheAsyncExecutor");
        addExecutorQuiescence(observed, resources, "telemetryCacheStageExecutor");
        addExecutorQuiescence(observed, resources, "telemetryStreamStageExecutor");
        addExecutorQuiescence(observed, resources, "telemetryHistoryStageExecutor");
        addExecutorQuiescence(observed, resources, "batchDispatcherExecutor");
        addExecutorQuiescence(observed, resources, "asyncCollectorExecutor");
        addExecutorQuiescence(observed, resources, "dataProcessorExecutor");
        return SoakRunIsolationSupport.quiescence(observed);
    }

    private void addExecutorQuiescence(Map<String, Long> observed,
                                       SystemResourceSnapshot resources,
                                       String executorName) {
        if (resources == null || resources.getThreadPools() == null) {
            return;
        }
        var pool = resources.getThreadPools().get(executorName);
        if (pool == null) {
            return;
        }
        observed.put(executorName + ".queue", (long) pool.getQueueSize());
        observed.put(executorName + ".active", (long) pool.getActiveCount());
    }

    private void configureRuntimeCollectorFactory(SoakCounters counters) {
        when(collectorFactory.createCollector(any(DeviceInfo.class))).thenAnswer(invocation -> {
            DeviceInfo deviceInfo = invocation.getArgument(0);
            RuntimeSoakCollector collector = beanFactory.createBean(RuntimeSoakCollector.class);
            collector.attachCounters(counters);
            collector.init(deviceInfo);
            return collector;
        });
    }

    private boolean startRuntimeDevice(String deviceId) throws InterruptedException {
        if (collectionScheduler.startDevice(deviceId)) {
            return true;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (System.nanoTime() < deadline) {
            if (collectionScheduler.isDeviceRunning(deviceId)) {
                return true;
            }
            Thread.sleep(100L);
        }
        return collectionScheduler.isDeviceRunning(deviceId);
    }

    private void appendPoint(String deviceId,
                             DataPoint point,
                             long eventTs,
                             int roundIndex,
                             SoakCounters counters) {
        try {
            Object value = valueFor(point, roundIndex);
            telemetryIngressService.appendRaw(deviceId, point, value, 100, eventTs, DEFAULT_SOURCE);
            counters.submitted.incrementAndGet();
            counters.succeeded.incrementAndGet();
        } catch (RuntimeException exception) {
            counters.failed.incrementAndGet();
            String message = exception.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("reject")) {
                counters.rejected.incrementAndGet();
            }
        }
    }

    private void appendBatch(String deviceId,
                             List<DataPoint> points,
                             long eventTs,
                             int roundIndex,
                             SoakCounters counters) {
        if (points == null || points.isEmpty()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, ProcessResult> processResults = new LinkedHashMap<>();
        for (DataPoint point : points) {
            Object value = valueFor(point, roundIndex);
            values.put(point.getPointId(), value);
            processResults.put(point.getPointId(), processResult(point, value, eventTs));
        }
        try {
            collectorDataPostProcessor.saveBatchAsync(deviceId, points, values, processResults);
            counters.submitted.addAndGet(values.size());
            counters.succeeded.addAndGet(values.size());
        } catch (RuntimeException exception) {
            counters.failed.addAndGet(values.size());
            String message = exception.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("reject")) {
                counters.rejected.addAndGet(values.size());
            }
        }
    }

    private Object valueFor(DataPoint point, int roundIndex) {
        String dataType = point != null && point.getDataType() != null
                ? point.getDataType().toUpperCase(Locale.ROOT) : "DOUBLE";
        return switch (dataType) {
            case "LONG", "INT", "INTEGER" -> (long) roundIndex * 1_000L + ThreadLocalRandom.current().nextInt(1_000);
            case "BOOLEAN", "BOOL" -> (roundIndex & 1) == 0;
            case "STRING", "TEXT" -> "value-" + roundIndex + '-' + ThreadLocalRandom.current().nextInt(1_000);
            default -> roundIndex + ThreadLocalRandom.current().nextDouble(0.0d, 100.0d);
        };
    }

    private ProcessResult processResult(DataPoint point, Object value, long eventTs) {
        ProcessResult result = ProcessResult.success(value, value, "soak process result");
        result.addMetadata(ProcessResultMetadataKeys.RAW_VALUE, value);
        result.addMetadata(ProcessResultMetadataKeys.PROCESSED_VALUE, value);
        result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, eventTs);
        result.addMetadata(ProcessResultMetadataKeys.SOURCE, DEFAULT_SOURCE);
        return result;
    }

    private void paceWithinRound(SoakOptions options, long roundStartNanos, int emitted) throws InterruptedException {
        if (!options.spreadWithinInterval() || options.points() <= 0 || options.collectionIntervalMs() <= 0) {
            return;
        }
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(options.collectionIntervalMs());
        long targetNanos = roundStartNanos + (intervalNanos * emitted / options.points());
        long waitNanos = targetNanos - System.nanoTime();
        if (waitNanos <= 0L) {
            return;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(waitNanos);
        int nanos = (int) (waitNanos - TimeUnit.MILLISECONDS.toNanos(millis));
        Thread.sleep(millis, nanos);
    }

    private List<DevicePoints> registerDevices(SoakOptions options) {
        List<DevicePoints> result = new ArrayList<>(options.devices());
        int remaining = options.points();
        String devicePrefix = options.tdengineDevicePrefix();
        for (int deviceIndex = 0; deviceIndex < options.devices(); deviceIndex++) {
            int devicesLeft = options.devices() - deviceIndex;
            int pointCount = Math.max(1, remaining / devicesLeft);
            String deviceId = devicePrefix + "-dev-" + String.format(Locale.ROOT, "%04d", deviceIndex);
            DeviceInfo device = device(deviceId, options.collectionIntervalMs());
            DeviceConnection connection = connection(deviceId);
            List<DataPoint> points = points(deviceId, pointCount, options);
            configManager.saveLocalDeviceConfig(device, connection, points, true);
            result.add(new DevicePoints(deviceId, points));
            remaining -= pointCount;
        }
        return result;
    }

    private String safeRunSegment(String value) {
        String normalized = value == null ? "run" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (normalized.isBlank()) {
            return "run";
        }
        return normalized.length() > 24 ? normalized.substring(0, 24) : normalized;
    }

    private DeviceInfo device(String deviceId, long collectionIntervalMs) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceId);
        device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP");
        device.setIpAddress("127.0.0.1");
        device.setPort(1502);
        device.setCollectionInterval((int) Math.max(1L, collectionIntervalMs));

        CloudTargetConfig cloudTarget = new CloudTargetConfig();
        cloudTarget.setEnabled(true);
        cloudTarget.setDeviceType(CloudDeviceType.SUB_DEVICE);
        cloudTarget.setProductKey("soak-product");
        cloudTarget.setDeviceName(deviceId);
        device.setCloudTarget(cloudTarget);
        return device;
    }

    private DeviceConnection connection(String deviceId) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost("127.0.0.1");
        connection.setPort(1502);
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        connection.setWriteTimeout(1000);
        connection.setTimeout(1000);
        connection.setExtJson(Map.of("slaveId", 1));
        return connection;
    }

    private List<DataPoint> points(String deviceId, int pointCount, SoakOptions options) {
        List<DataPoint> points = new ArrayList<>(pointCount);
        int globalBase = Math.abs(deviceId.hashCode());
        for (int index = 0; index < pointCount; index++) {
            String pointCode = "p_" + String.format(Locale.ROOT, "%06d", index);
            DataPoint point = new DataPoint();
            point.setId((long) globalBase + index + 1L);
            point.setPointId(deviceId + "-" + pointCode);
            point.setPointCode(pointCode);
            point.setPointName("soak-" + pointCode);
            point.setDeviceId(deviceId);
            point.setDeviceName(deviceId);
            point.setAddress(toHoldingRegisterReference(index + 1));
            point.setDataType(dataTypeFor(index));
            point.setReadWrite("R");
            point.setUnitId(1);
            point.setUnit("value");
            point.setStatus(1);
            point.setCacheEnabled(1);
            point.setCacheDuration(300);
            point.setBaseCollectionInterval(options.collectionIntervalMs());
            point.setCurrentCollectionInterval(0L);
            point.setAdditionalConfig(Map.of(
                    "historyEnabled", options.historyEnabled(),
                    "streamEnabled", options.streamEnabled(),
                    "reportEnabled", options.cloudEnabled(),
                    "eventEnabled", false,
                    "reportField", pointCode
            ));
            points.add(point);
        }
        return points;
    }

    private String dataTypeFor(int index) {
        return switch (index % 4) {
            case 0 -> "LONG";
            case 1 -> "DOUBLE";
            case 2 -> "BOOLEAN";
            default -> "STRING";
        };
    }

    private String toHoldingRegisterReference(int oneBasedRegister) {
        int normalized = ((oneBasedRegister - 1) % 9999) + 1;
        return "4" + String.format(Locale.ROOT, "%04d", normalized);
    }

    private MetricSample collectSample(SoakOptions options,
                                       SoakCounters counters,
                                       List<Long> roundDurations,
                                       MqttAckBridge ackBridge,
                                       boolean finalSample) {
        SystemResourceSnapshot resources = systemResourceMonitorService.getResources();
        HistoryBufferMetrics history = historyWriteBufferProvider.getIfAvailable() != null
                ? historyWriteBufferProvider.getIfAvailable().metrics()
                : new HistoryBufferMetrics(0L, 0L, 0L, 0, 0);
        HistoryBatchMetrics batch = historyBatchWriterProvider.getIfAvailable() != null
                ? historyBatchWriterProvider.getIfAvailable().metrics()
                : emptyHistoryBatchMetrics();
        TelemetryIngressBufferMetrics entry = telemetryIngressBufferProvider.getIfAvailable() != null
                ? telemetryIngressBufferProvider.getIfAvailable().metrics()
                : TelemetryIngressBufferMetrics.empty();
        TelemetryStreamMetrics stream = telemetryStreamServiceProvider.getIfAvailable() != null
                ? telemetryStreamServiceProvider.getIfAvailable().metrics()
                : TelemetryStreamMetrics.empty();
        RedisSnapshot redis = redisSnapshot(options);
        CloudSnapshot cloud = cloudSnapshot(ackBridge);
        GcSnapshot gc = gcSnapshot();
        SchedulerStateSnapshot scheduler = schedulerStateSnapshot();
        HikariSnapshot hikari = hikariSnapshot();
        TelemetryPipelineMetrics pipelineMetrics = telemetryPostProcessPipeline.metrics();
        CollectorDataPostProcessorMetrics postProcessorMetrics = collectorDataPostProcessor.metrics();
        TimeSeriesService timeSeriesService = timeSeriesServiceProvider.getIfAvailable();
        TdengineWriteMetrics tdengineWrite = timeSeriesService != null
                ? timeSeriesService.writeMetrics()
                : emptyTdengineWriteMetrics();
        long now = System.currentTimeMillis();
        return new MetricSample(
                now,
                now - options.startedAt(),
                finalSample,
                counters.submitted.get(),
                counters.succeeded.get(),
                counters.failed.get(),
                counters.rejected.get(),
                counters.rounds.get(),
                last(roundDurations),
                percentile(roundDurations, 0.50d),
                percentile(roundDurations, 0.95d),
                percentile(roundDurations, 0.99d),
                max(roundDurations),
                resources.getProcessCpuLoad(),
                resources.getSystemCpuLoad(),
                resources.getHeapUsed(),
                resources.getHeapCommitted(),
                resources.getHeapMax(),
                resources.getNonHeapUsed(),
                resources.getThreadCount(),
                gc.count(),
                gc.timeMs(),
                resources.getThreadPools(),
                new PipelineSnapshot(
                        pipelineMetrics.processedItems(),
                        pipelineMetrics.stageSubmissions(),
                        pipelineMetrics.stageRejectedEvents(),
                        pipelineMetrics.stageRejectedCompensatedEvents(),
                        pipelineMetrics.stageRejectedUncompensatedEvents(),
                        pipelineMetrics.stageRejectedShutdownEvents(),
                        pipelineMetrics.processLatencyP50Ms(),
                        pipelineMetrics.processLatencyP95Ms(),
                        pipelineMetrics.processLatencyP99Ms(),
                        pipelineMetrics.processLatencySampleCount(),
                        pipelineMetrics.processLatencyTotalRecorded(),
                        pipelineMetrics.processLatencyOverwrittenSamples(),
                        pipelineMetrics.stageSubmissionLatencyP50Ms(),
                        pipelineMetrics.stageSubmissionLatencyP95Ms(),
                        pipelineMetrics.stageSubmissionLatencyP99Ms(),
                        pipelineMetrics.stageSubmissionLatencySampleCount(),
                        pipelineMetrics.stageSubmissionLatencyTotalRecorded(),
                        pipelineMetrics.stageSubmissionLatencyOverwrittenSamples(),
                        pipelineMetrics.metricsInternalErrors(),
                        pipelineMetrics.logRateLimitedEvents(),
                        pipelineMetrics.logSuppressedEvents()),
                new PostProcessorSnapshot(
                        postProcessorMetrics.batchTaskCount(),
                        postProcessorMetrics.batchTaskItems(),
                        postProcessorMetrics.batchSizeP50(),
                        postProcessorMetrics.batchSizeP95(),
                        postProcessorMetrics.batchSizeMax(),
                        postProcessorMetrics.batchSizeSampleCount(),
                        postProcessorMetrics.batchSizeTotalRecorded(),
                        postProcessorMetrics.batchSizeOverwrittenSamples(),
                        postProcessorMetrics.batchTaskLatencyP50Ms(),
                        postProcessorMetrics.batchTaskLatencyP95Ms(),
                        postProcessorMetrics.batchTaskLatencyP99Ms(),
                        postProcessorMetrics.batchTaskLatencySampleCount(),
                        postProcessorMetrics.batchTaskLatencyTotalRecorded(),
                        postProcessorMetrics.batchTaskLatencyOverwrittenSamples(),
                        postProcessorMetrics.metricsInternalErrors(),
                        postProcessorMetrics.entryLogRateLimitedEvents(),
                        postProcessorMetrics.entryLogSuppressedEvents()),
                scheduler,
                hikari,
                redis,
                new EntryIngressSnapshot(entry.redisPending(), entry.redisProcessing(), entry.redisDeadLetter(),
                        entry.localPending(), entry.localCapacity(), entry.rejectedTasks(), entry.rejectedItems(),
                        entry.redisBufferedItems(), entry.localBufferedItems(), entry.droppedItems(),
                        entry.replayCompletedItems(), entry.pendingRemoveFailures(), entry.poisonDeadLetterItems(),
                        entry.staleSameRuntimeDroppedItems(), entry.crossRuntimeRecoveredItems(),
                        entry.legacyEnvelopeRecoveredItems()),
                new StreamSnapshot(stream.appendAttempts(), stream.skippedAppends(), stream.serializationFailures(),
                        stream.xaddSuccess(), stream.xaddFailure(), stream.appendLatencyP50Ms(),
                        stream.appendLatencyP95Ms(), stream.appendLatencyP99Ms(), stream.xaddLatencyP50Ms(),
                        stream.xaddLatencyP95Ms(), stream.xaddLatencyP99Ms()),
                new HistorySnapshot(history.redisPending(), history.redisProcessing(), history.redisDeadLetter(),
                        history.localPending(), history.localCapacity(),
                        history.writeFailureRedisBuffered(), history.rejectedRedisBuffered(),
                        history.writeFailureLocalBuffered(), history.rejectedLocalBuffered(),
                        history.writeFailureDropped(), history.rejectedDropped(),
                        history.writeFailureDisabled(), history.rejectedDisabled(),
                        history.replayClaimedRows(), history.replaySuccessfulRows(), history.replayFailedRows(),
                        history.replayBatchCount(), history.replayAverageBatchSize(), history.replayBatchSizeP95(),
                        history.replayBatchSizeMax(), history.replayRowsPerSecond(),
                        history.replayBatchWriteP50Ms(), history.replayBatchWriteP95Ms(),
                        history.replayBatchWriteP99Ms(), history.replayPausedForLivePressureCount(),
                        history.replayProcessingRows(), history.batchFallbackRedisRows(),
                        history.batchFallbackRedisOps(), history.batchFallbackLocalRows(),
                        history.batchFallbackDroppedRows(), history.batchFallbackLatencyP50Ms(),
                        history.batchFallbackLatencyP95Ms(), history.batchFallbackLatencyP99Ms(),
                        history.liveFlushQueueUtilization()),
                new HistoryBatchSnapshot(batch.acceptedRows(), batch.flushedBatches(), batch.flushedRows(),
                        batch.batchWriteSuccess(), batch.batchWriteFailure(), batch.fallbackRows(),
                        batch.currentBufferedRows(), batch.bufferedRowsPeak(), batch.averageBatchSize(),
                        batch.batchSizeP50(), batch.batchSizeP95(), batch.batchSizeMax(),
                        batch.flushLatencyP50Ms(), batch.flushLatencyP95Ms(), batch.flushLatencyP99Ms(),
                        batch.oldestBufferedAgeMs(), batch.shutdownFlushedRows(),
                        batch.fallbackRedisRows(), batch.fallbackLocalRows(), batch.fallbackDroppedRows(),
                        batch.fallbackDisabledRows(), batch.shutdownDeferredRows(),
                        batch.shutdownNonDurableRows(), batch.shutdownDroppedRows(), batch.shutdownDisabledRows(),
                        batch.flushExecutorSubmittedBatches(), batch.flushExecutorCompletedBatches(),
                        batch.flushExecutorRejectedBatches(), batch.flushExecutorQueueCurrent(),
                        batch.flushExecutorQueuePeak(), batch.flushExecutorActiveCurrent(),
                        batch.flushExecutorActivePeak(), batch.shutdownQueuedBatches(),
                        batch.bucketCount(), batch.admissionInFlight(), batch.inFlightFlushes(),
                        batch.sizeFlushBatches(), batch.timerFlushBatches(),
                        batch.sizeFlushRows(), batch.timerFlushRows(),
                        batch.sizeAverageBatchSize(), batch.sizeBatchSizeP50(),
                        batch.sizeBatchSizeP95(), batch.sizeBatchSizeMax(),
                        batch.timerAverageBatchSize(), batch.timerBatchSizeP50(),
                        batch.timerBatchSizeP95(), batch.timerBatchSizeMax(),
                        batch.tdengineBatchCallsPerSecond(), batch.flushExecutorServiceRatePerSecond(),
                        batch.flushExecutorQueueUtilization(), batch.tdengineWriteRequests(),
                        batch.tdengineWriteRows(), batch.tdengineWriteRequestsPerSecond(),
                        batch.tdengineRowsPerRequest(), batch.tdengineRowsPerRequestP95(),
                        batch.tdengineRowsPerRequestMax(), batch.tdengineTablesPerRequest(),
                        batch.tdengineTablesPerRequestP95(), batch.tdengineTablesPerRequestMax(),
                        batch.multiTableWriteRequests(), batch.multiTableWriteRows(),
                        batch.multiTableAggregatedBatches(), batch.activeWritesBySubTable(),
                        batch.maxConcurrentWritesSameSubTable(), batch.sameSubTableConcurrentWriteCount(),
                        batch.dbQueueWaitP50Ms(), batch.dbQueueWaitP95Ms(), batch.dbQueueWaitP99Ms(),
                        batch.dbExecuteLatencyP50Ms(), batch.dbExecuteLatencyP95Ms(),
                        batch.dbExecuteLatencyP99Ms(), batch.subTableWriteLatencyP95Ms()),
                tdengineWrite,
                cloud
        );
    }

    private TdengineWriteMetrics emptyTdengineWriteMetrics() {
        return new TdengineWriteMetrics(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0D, 0, 0, 0D, 0, 0,
                0D, 0D, 0D, 0D, 0D, 0D,
                0D, 0D, 0D, 0D, 0D, 0D,
                0D, 0D, 0D, 0, 0L, 0L, 0D, 0D);
    }

    private HistoryBatchMetrics emptyHistoryBatchMetrics() {
        return new HistoryBatchMetrics(
                0L, 0L, 0L, 0L, 0L, 0L, 0, 0, 0D,
                0, 0, 0, 0D, 0D, 0D, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, 0L, 0L, 0, 0, 0, 0, 0L,
                0, 0, 0, 0L, 0L, 0L, 0L, 0D,
                0, 0, 0, 0D, 0, 0, 0, 0D, 0D, 0D,
                0L, 0L, 0D, 0D, 0, 0, 0D, 0, 0, 0L, 0L, 0L,
                Map.of(), 0, 0L, 0D, 0D, 0D, 0D, 0D, 0D, Map.of());
    }

    private RedisSnapshot redisSnapshot(SoakOptions options) {
        try {
            Properties info = redisTemplate.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info());
            String version = info != null ? info.getProperty("redis_version", "unknown") : "unknown";
            long usedMemory = parseLong(info != null ? info.getProperty("used_memory") : null, -1L);
            long clients = parseLong(info != null ? info.getProperty("connected_clients") : null, -1L);
            long ops = parseLong(info != null ? info.getProperty("instantaneous_ops_per_sec") : null, -1L);
            Long streamLength = redisTemplate.opsForStream().size(options.streamKey());
            return new RedisSnapshot(true, version, usedMemory, clients, ops,
                    streamLength != null ? streamLength : -1L, null);
        } catch (RuntimeException exception) {
            return new RedisSnapshot(false, "unknown", -1L, -1L, -1L, -1L, exception.getClass().getSimpleName());
        }
    }

    private CloudSnapshot cloudSnapshot(MqttAckBridge ackBridge) {
        try {
            String keyPrefix = outboxPrefix();
            String dataKey = keyPrefix + "data";
            List<Object> values = redisTemplate.opsForHash().values(dataKey);
            Map<String, Long> statuses = new LinkedHashMap<>();
            for (CloudOutboxStatus status : CloudOutboxStatus.values()) {
                statuses.put(status.name(), 0L);
            }
            if (values != null) {
                for (Object value : values) {
                    String status = statusOf(String.valueOf(value));
                    statuses.computeIfPresent(status, (ignored, count) -> count + 1L);
                }
            }
            return new CloudSnapshot(
                    sum(statuses.values()),
                    statuses.getOrDefault(CloudOutboxStatus.PENDING.name(), 0L),
                    statuses.getOrDefault(CloudOutboxStatus.PUBLISHING.name(), 0L),
                    statuses.getOrDefault(CloudOutboxStatus.WAITING_ACK.name(), 0L),
                    statuses.getOrDefault(CloudOutboxStatus.WAITING_CONFIG.name(), 0L),
                    statuses.getOrDefault(CloudOutboxStatus.ISOLATED.name(), 0L),
                    ackBridge != null ? ackBridge.received.get() : 0L,
                    ackBridge != null ? ackBridge.sent.get() : 0L,
                    ackBridge != null ? ackBridge.failed.get() : 0L);
        } catch (RuntimeException exception) {
            return new CloudSnapshot(-1L, -1L, -1L, -1L, -1L, -1L,
                    ackBridge != null ? ackBridge.received.get() : 0L,
                    ackBridge != null ? ackBridge.sent.get() : 0L,
                    ackBridge != null ? ackBridge.failed.get() : 0L);
        }
    }

    private String statusOf(String json) {
        try {
            String status = objectMapper.readTree(json).path("status").asText(null);
            return status == null || status.isBlank() ? "UNKNOWN" : status;
        } catch (JsonProcessingException exception) {
            return "UNKNOWN";
        }
    }

    private String outboxPrefix() {
        String keyPrefix = reportProperties.getOutbox().getKeyPrefix();
        return keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
    }

    private GcSnapshot gcSnapshot() {
        long count = 0L;
        long timeMs = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long beanCount = bean.getCollectionCount();
            long beanTime = bean.getCollectionTime();
            if (beanCount > 0L) {
                count += beanCount;
            }
            if (beanTime > 0L) {
                timeMs += beanTime;
            }
        }
        return new GcSnapshot(count, timeMs);
    }

    private SchedulerStateSnapshot schedulerStateSnapshot() {
        PerformanceStatsSnapshot performance = collectionScheduler.getPerformanceSnapshot();
        PerformanceMonitor.PhaseWheelStatsSnapshot phaseWheel = collectionScheduler.getPhaseWheelStatsSnapshot();
        return new SchedulerStateSnapshot(
                performance.getTimeSliceCount(),
                performance.getTimeSliceIntervalMs(),
                Math.max(collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                        collectorProperties.getScheduler().getDueScanIntervalMs()),
                schedulerLongMethod("getTimeSliceRevision"),
                performance.getBatchDispatchRejectedCount(),
                performance.getCollectRejectedCount(),
                performance.getProcessRejectedCount(),
                performance.getReconnectAttemptCount(),
                performance.getReconnectSuccessCount(),
                performance.getReconnectFailureCount(),
                performance.getReconnectingDevices(),
                schedulerIntMethod("getCadenceStateSizeForTest"),
                schedulerIntMethod("getInFlightPointScheduleSizeForTest"),
                schedulerLongMethod("getTotalTaskCount"),
                schedulerLongMethod("getTotalEstimatedPointCount"),
                schedulerLongMethod("getMinimumCollectionIntervalMs"),
                schedulerIntMethod("getMaxTasksPerTimeSliceForTest"),
                schedulerIntMethod("getMaxPointsPerTimeSliceForTest"),
                performance.getTimeSliceExecutionTimes().size(),
                performance.getDeviceStats().size(),
                phaseWheel.tickCount(),
                phaseWheel.catchUpTickCount(),
                phaseWheel.consecutiveCatchUpCount(),
                phaseWheel.maxScansPer100Ms(),
                phaseWheel.tickGapP50Ms(),
                phaseWheel.tickGapP95Ms(),
                phaseWheel.tickGapP99Ms(),
                phaseWheel.tickGapMinMs(),
                phaseWheel.tickGapMaxMs(),
                phaseWheel.sliceExecutionP50Ms(),
                phaseWheel.sliceExecutionP95Ms(),
                phaseWheel.sliceExecutionP99Ms(),
                phaseWheel.sliceExecutionMaxMs());
    }

    private long schedulerLongMethod(String methodName) {
        Object value = ReflectionTestUtils.invokeMethod(schedulerRuntimeState, methodName);
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private int schedulerIntMethod(String methodName) {
        Object value = ReflectionTestUtils.invokeMethod(schedulerRuntimeState, methodName);
        return value instanceof Number number ? number.intValue() : -1;
    }

    private HikariSnapshot hikariSnapshot() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            return new HikariSnapshot(
                    hikari.getMaximumPoolSize(),
                    hikari.getMinimumIdle(),
                    pool != null ? pool.getActiveConnections() : -1,
                    pool != null ? pool.getIdleConnections() : -1,
                    pool != null ? pool.getThreadsAwaitingConnection() : -1,
                    pool != null ? pool.getTotalConnections() : -1,
                    null);
        }
        return new HikariSnapshot(-1, -1, -1, -1, -1, -1,
                dataSource != null ? dataSource.getClass().getName() : "unavailable");
    }

    private void writeRunInfo(Path outputDir,
                              SoakOptions options,
                              List<DevicePoints> devicePoints,
                              SoakLifecycle lifecycle) throws IOException {
        double theoreticalRate = theoreticalCollectorRate(options.points(), options.collectionIntervalMs());
        SchedulerStateSnapshot scheduler = schedulerStateSnapshot();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("runId", options.runId());
        info.put("redisNamespace", options.redisNamespace().asMap());
        info.put("tdengineDevicePrefix", options.tdengineDevicePrefix());
        info.put("scenario", options.scenario());
        info.put("points", options.points());
        info.put("devices", options.devices());
        info.put("pointsPerDevice", devicePoints.stream().map(DevicePoints::points).map(Collection::size).toList());
        info.put("durationSeconds", options.durationSeconds());
        info.put("measurementSeconds", options.durationSeconds());
        info.put("drainSeconds", options.drainWaitSeconds());
        info.put("capacityProfile", options.capacityProfile());
        info.put("fixedCapacityMode", options.fixedCapacityMode());
        info.put("adaptiveCollectionEnabled", collectorProperties.getAdaptiveCollection().isEnabled());
        info.put("collectionIntervalMs", options.collectionIntervalMs());
        info.put("configuredCollectionIntervalMs", options.collectionIntervalMs());
        info.put("effectiveCollectionIntervalMs", effectiveCollectionIntervalMs(options, devicePoints));
        info.put("totalPoints", options.points());
        info.put("theoreticalCollectorRate", theoreticalRate);
        info.put("loadDeviationTolerancePercent", options.loadDeviationTolerancePercent());
        info.put("schedulerDueScanIntervalMs", scheduler.dueScanIntervalMs());
        info.put("schedulerPhaseWheelTickMs", phaseWheelTickMs(scheduler));
        info.put("schedulerPhaseWheelRoundMs", phaseWheelRoundMs(scheduler));
        info.put("spreadWithinInterval", options.spreadWithinInterval());
        info.put("ingressMode", options.ingressMode());
        info.put("warmupSeconds", options.warmupSeconds());
        info.put("settleTimeoutSeconds", options.settleTimeoutSeconds());
        info.put("startedAt", Instant.ofEpochMilli(options.startedAt()).toString());
        info.put("warmupCompletedAt", SoakLifecycle.instantString(lifecycle.warmupCompletedAt.get()));
        info.put("settleStartedAt", SoakLifecycle.instantString(lifecycle.settleStartedAt.get()));
        info.put("settleCompletedAt", SoakLifecycle.instantString(lifecycle.settleCompletedAt.get()));
        info.put("settleSeconds", lifecycle.settleSeconds.get());
        info.put("measurementStartedAt", SoakLifecycle.instantString(lifecycle.measurementStartedAt.get()));
        info.put("measurementValid", lifecycle.measurementValid.get());
        info.put("invalidReason", lifecycle.invalidReason.get());
        info.put("warmupQuiescence", lifecycle.warmupQuiescence.get().asMap());
        info.put("lifecycle", lifecycle.asMap());
        info.put("branch", command(List.of("git", "rev-parse", "--abbrev-ref", "HEAD")));
        info.put("commit", command(List.of("git", "rev-parse", "HEAD")));
        String gitStatus = command(List.of("git", "status", "--short"));
        info.put("gitStatusSummary", gitStatus);
        info.put("gitDirty", gitStatus != null && !gitStatus.isBlank() && !"unknown".equals(gitStatus));
        info.put("gitDiffHash", sha256(command(List.of("git", "diff", "--binary", "--no-ext-diff"))));
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVm", System.getProperty("java.vm.name"));
        info.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments());
        info.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        info.put("maxMemoryBytes", Runtime.getRuntime().maxMemory());
        info.put("redis", redisSnapshot(options));
        info.put("tdengine", tdengineInfo());
        info.put("tdengineWriteMode", tdengineProperties.getWrite().getMode());
        info.put("tdengineWriteWebsocketConfigured",
                tdengineProperties.getWrite().getWebsocketUrl() != null
                        && !tdengineProperties.getWrite().getWebsocketUrl().isBlank());
        info.put("hikari", hikariSnapshot());
        info.put("cloudEnabled", options.cloudEnabled());
        info.put("reportServiceEnabled", reportProperties.isEnabled());
        info.put("historyBatchConfiguration", historyBatchConfiguration());
        info.put("schedulerConfiguration", schedulerConfiguration());
        info.put("telemetryExecutorConfiguration", telemetryExecutorConfiguration());
        info.put("mqttBrokerUrl", options.mqttBrokerUrl());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("run-info.json").toFile(), info);
    }

    private Map<String, Object> schedulerConfiguration() {
        CollectorProperties.SchedulerConfig scheduler = collectorProperties.getScheduler();
        SchedulerStateSnapshot snapshot = schedulerStateSnapshot();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("initialTimeSliceCount", scheduler.getInitialTimeSliceCount());
        config.put("maxTimeSliceCount", scheduler.getMaxTimeSliceCount());
        config.put("minTimeSliceIntervalMs", scheduler.getMinTimeSliceIntervalMs());
        config.put("defaultTimeSliceIntervalMs", scheduler.getDefaultTimeSliceIntervalMs());
        config.put("initialTimeSliceIntervalMs", scheduler.getInitialTimeSliceIntervalMs());
        config.put("dynamicAdjustIntervalMs", scheduler.getDynamicAdjustIntervalMs());
        config.put("targetTasksPerTimeSlice", scheduler.getTargetTasksPerTimeSlice());
        config.put("targetPointsPerTimeSlice", scheduler.getTargetPointsPerTimeSlice());
        config.put("dueScanIntervalMs", scheduler.getDueScanIntervalMs());
        config.put("runtimeDueScanIntervalMs", snapshot.dueScanIntervalMs());
        config.put("runtimePhaseWheelTickMs", phaseWheelTickMs(snapshot));
        config.put("runtimePhaseWheelRoundMs", phaseWheelRoundMs(snapshot));
        return config;
    }

    private Map<String, Object> historyBatchConfiguration() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", historyBatchProperties.isEnabled());
        config.put("batchSize", historyBatchProperties.getBatchSize());
        config.put("flushIntervalMs", historyBatchProperties.getFlushIntervalMs());
        config.put("flushScanIntervalMs", historyBatchProperties.getFlushScanIntervalMs());
        config.put("maxBufferedRows", historyBatchProperties.getMaxBufferedRows());
        config.put("shutdownFlushTimeoutMs", historyBatchProperties.getShutdownFlushTimeoutMs());
        Map<String, Object> flushExecutor = new LinkedHashMap<>();
        flushExecutor.put("coreSize", historyBatchProperties.getFlushExecutor().getCoreSize());
        flushExecutor.put("maxSize", historyBatchProperties.getFlushExecutor().getMaxSize());
        flushExecutor.put("queueCapacity", historyBatchProperties.getFlushExecutor().getQueueCapacity());
        config.put("flushExecutor", flushExecutor);
        return config;
    }

    private Map<String, Object> telemetryExecutorConfiguration() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("cacheAsyncExecutor", Map.of(
                "coreSize", 8,
                "maxSize", 16,
                "queueCapacity", 1000,
                "rejectedExecutionHandler", "ObservedRejectedExecutionHandler+AbortPolicy"));
        config.put("cacheStage", stageConfiguration(telemetryExecutorProperties.getCache()));
        config.put("streamStage", stageConfiguration(telemetryExecutorProperties.getStream()));
        config.put("historyStage", stageConfiguration(telemetryExecutorProperties.getHistory()));
        config.put("reportStage", stageConfiguration(telemetryExecutorProperties.getReport()));
        return config;
    }

    private Map<String, Object> stageConfiguration(TelemetryExecutorProperties.Stage stage) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("coreSize", stage.getCoreSize());
        config.put("maxSize", stage.getMaxSize());
        config.put("queueCapacity", stage.getQueueCapacity());
        config.put("rejectedExecutionHandler", "ObservedRejectedExecutionHandler+AbortPolicy");
        return config;
    }

    private Map<String, Object> tdengineInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            info.put("available", false);
            return info;
        }
        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            info.put("available", canConnect(dataSource));
            info.put("version", queryFirst(jdbcTemplate, List.of("SELECT SERVER_VERSION()", "SELECT server_version()")));
            String database = environment.getProperty("telemetry.tdengine.database", "wangbin_collector");
            info.put("database", database);
            info.put("precision", extractPrecision(jdbcTemplate, database));
        } catch (RuntimeException exception) {
            info.put("available", false);
            info.put("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        return info;
    }

    private boolean canConnect(DataSource dataSource) {
        try (Connection ignored = dataSource.getConnection()) {
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private String queryFirst(JdbcTemplate jdbcTemplate, List<String> sqlCandidates) {
        for (String sql : sqlCandidates) {
            try {
                Object value = jdbcTemplate.queryForObject(sql, Object.class);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (RuntimeException ignored) {
                // 兼容不同 TDengine JDBC 版本的版本函数。
            }
        }
        return "unknown";
    }

    private String extractPrecision(JdbcTemplate jdbcTemplate, String database) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SHOW CREATE DATABASE " + database);
            String text = rows.toString();
            int index = text.toUpperCase(Locale.ROOT).indexOf("PRECISION");
            if (index < 0) {
                return "unknown";
            }
            int end = Math.min(text.length(), index + 40);
            return text.substring(index, end);
        } catch (RuntimeException exception) {
            return "unknown: " + exception.getClass().getSimpleName();
        }
    }

    private void writeInvalidSummary(Path outputDir,
                                     SoakOptions options,
                                     SoakLifecycle lifecycle) throws IOException {
        double theoreticalRate = theoreticalCollectorRate(options.points(), options.collectionIntervalMs());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", options.runId());
        summary.put("redisNamespace", options.redisNamespace().asMap());
        summary.put("tdengineDevicePrefix", options.tdengineDevicePrefix());
        summary.put("scenario", options.scenario());
        summary.put("points", options.points());
        summary.put("devices", options.devices());
        summary.put("collectionIntervalMs", options.collectionIntervalMs());
        summary.put("configuredCollectionIntervalMs", options.collectionIntervalMs());
        summary.put("effectiveCollectionIntervalMs", options.collectionIntervalMs());
        summary.put("totalPoints", options.points());
        summary.put("capacityProfile", options.capacityProfile());
        summary.put("fixedCapacityMode", options.fixedCapacityMode());
        summary.put("adaptiveCollectionEnabled", collectorProperties.getAdaptiveCollection().isEnabled());
        summary.put("theoreticalPointsPerSecond", theoreticalRate);
        summary.put("theoreticalCollectorRate", theoreticalRate);
        summary.put("actualCollectorRate", 0D);
        summary.put("collectorRateDeviationPercent", 0D);
        summary.put("loadDeviationTolerancePercent", options.loadDeviationTolerancePercent());
        summary.put("loadProfileValid", false);
        summary.put("warmupSeconds", options.warmupSeconds());
        summary.put("settleTimeoutSeconds", options.settleTimeoutSeconds());
        summary.put("measurementSeconds", options.durationSeconds());
        summary.put("drainSeconds", options.drainWaitSeconds());
        summary.put("measurementValid", false);
        summary.put("invalidReason", lifecycle.invalidReason.get());
        summary.put("warmupBacklogClean", lifecycle.warmupBacklogClean.get());
        summary.put("settleSeconds", lifecycle.settleSeconds.get());
        summary.put("warmupQuiescence", lifecycle.warmupQuiescence.get().asMap());
        summary.put("lifecycle", lifecycle.asMap());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("summary.json").toFile(), summary);
    }

    private void writeSummary(Path outputDir,
                              SoakOptions options,
                              List<DevicePoints> devicePoints,
                              SoakCounters counters,
                              List<Long> roundDurations,
                              List<MetricSample> samples,
                              MetricSample runStartSample,
                              MetricSample loadEndSample,
                              MqttAckBridge ackBridge,
                              SoakLifecycle lifecycle) throws IOException {
        MetricSample finalSample = samples.get(samples.size() - 1);
        long elapsedMs = Math.max(1L, finalSample.elapsedMs());
        long loadElapsedMs = Math.max(1L, counters.loadFinishedAt.get() - counters.loadStartedAt.get());
        List<MetricSample> measurementSamples = samplesBetween(samples, runStartSample.timestamp(), loadEndSample.timestamp());
        List<Integer> readPointSizes = counters.runtimeReadPointSizesSnapshot();
        List<Long> runtimeCadenceIntervalsMs = counters.runtimeCadenceIntervalsMsSnapshot();
        double theoreticalRate = theoreticalCollectorRate(options.points(), options.collectionIntervalMs());
        double collectorRate = counters.runtimeReadPointsItems.get() * 1000.0d / loadElapsedMs;
        long theoreticalMeasurementRows = Math.round(theoreticalRate * options.durationSeconds());
        double collectorRateDeviationPercent = rateDeviationPercent(collectorRate, theoreticalRate);
        LoadProfileResult loadProfile = evaluateLoadProfile(collectorRate, theoreticalRate,
                options.loadDeviationTolerancePercent());
        long entryReplayDelta = delta(loadEndSample.entry().replayCompletedItems(),
                runStartSample.entry().replayCompletedItems());
        long pipelineAcceptedDelta = delta(loadEndSample.historyBatch().acceptedRows(),
                runStartSample.historyBatch().acceptedRows());
        long flushedBatchesDelta = delta(loadEndSample.historyBatch().flushedBatches(),
                runStartSample.historyBatch().flushedBatches());
        long tdengineFlushedDelta = delta(loadEndSample.historyBatch().flushedRows(),
                runStartSample.historyBatch().flushedRows());
        long livePipelineItems = Math.max(0L, pipelineAcceptedDelta - entryReplayDelta);
        long liveTdengineRows = Math.max(0L, tdengineFlushedDelta - Math.min(tdengineFlushedDelta, entryReplayDelta));
        double pipelineRate = livePipelineItems * 1000.0d / loadElapsedMs;
        double tdengineRate = liveTdengineRows * 1000.0d / loadElapsedMs;
        Map<String, Long> executorRejectedDelta = executorRejectedDelta(runStartSample, loadEndSample);
        long streamAppendAttemptsDelta = delta(loadEndSample.stream().appendAttempts(),
                runStartSample.stream().appendAttempts());
        long streamXaddSuccessDelta = delta(loadEndSample.stream().xaddSuccess(),
                runStartSample.stream().xaddSuccess());
        long streamXaddFailureDelta = delta(loadEndSample.stream().xaddFailure(),
                runStartSample.stream().xaddFailure());
        long entryRejectedItemsDelta = delta(loadEndSample.entry().rejectedItems(),
                runStartSample.entry().rejectedItems());
        long entryDroppedItemsDelta = delta(loadEndSample.entry().droppedItems(),
                runStartSample.entry().droppedItems());
        long historyDeferredDelta = historyDeferredDelta(runStartSample.history(), loadEndSample.history());
        long historyRejectedDroppedDelta = delta(loadEndSample.history().rejectedDropped(),
                runStartSample.history().rejectedDropped());
        long flushExecutorRejectedBatchesDelta = delta(loadEndSample.historyBatch().flushExecutorRejectedBatches(),
                runStartSample.historyBatch().flushExecutorRejectedBatches());
        long tdengineWriteRequestsDelta = delta(loadEndSample.historyBatch().tdengineWriteRequests(),
                runStartSample.historyBatch().tdengineWriteRequests());
        long tdengineWriteRowsDelta = delta(loadEndSample.historyBatch().tdengineWriteRows(),
                runStartSample.historyBatch().tdengineWriteRows());
        TdengineWriteMetrics writerStart = runStartSample.tdengineWrite();
        TdengineWriteMetrics writerEnd = loadEndSample.tdengineWrite();
        long writerRequestsDelta = delta(writerEnd.writeRequests(), writerStart.writeRequests());
        long writerRowsDelta = delta(writerEnd.writtenRows(), writerStart.writtenRows());
        long writerSingleRequestsDelta = delta(writerEnd.singleTableWriteRequests(),
                writerStart.singleTableWriteRequests());
        long writerMultiRequestsDelta = delta(writerEnd.multiTableWriteRequests(),
                writerStart.multiTableWriteRequests());
        long writerFailuresDelta = delta(writerEnd.writeFailures(), writerStart.writeFailures());
        long writerEnsureCallsDelta = delta(writerEnd.ensureSubTableCalls(), writerStart.ensureSubTableCalls());
        long writerEnsureHitsDelta = delta(writerEnd.ensureSubTableCacheHits(),
                writerStart.ensureSubTableCacheHits());
        long writerEnsureMissesDelta = delta(writerEnd.ensureSubTableCacheMisses(),
                writerStart.ensureSubTableCacheMisses());
        SchedulerStateSnapshot schedulerLoadDelta = schedulerDelta(runStartSample.scheduler(), loadEndSample.scheduler());
        if (!loadProfile.valid()) {
            lifecycle.measurementValid.set(false);
            lifecycle.invalidReason.set(loadProfile.invalidReason());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", options.runId());
        summary.put("redisNamespace", options.redisNamespace().asMap());
        summary.put("tdengineDevicePrefix", options.tdengineDevicePrefix());
        summary.put("scenario", options.scenario());
        summary.put("points", options.points());
        summary.put("devices", options.devices());
        summary.put("durationSeconds", options.durationSeconds());
        summary.put("warmupSeconds", options.warmupSeconds());
        summary.put("settleTimeoutSeconds", options.settleTimeoutSeconds());
        summary.put("measurementSeconds", options.durationSeconds());
        summary.put("drainSeconds", options.drainWaitSeconds());
        summary.put("capacityProfile", options.capacityProfile());
        summary.put("fixedCapacityMode", options.fixedCapacityMode());
        summary.put("tdengineWriteMode", tdengineProperties.getWrite().getMode());
        summary.put("tdengineWriteTransport", tdengineProperties.getWrite().getMode());
        summary.put("lifecycle", lifecycle.asMap());
        summary.put("warmupBacklogClean", lifecycle.warmupBacklogClean.get());
        summary.put("warmupQuiescence", lifecycle.warmupQuiescence.get().asMap());
        summary.put("settleSeconds", lifecycle.settleSeconds.get());
        summary.put("measurementValid", lifecycle.measurementValid.get());
        summary.put("invalidReason", lifecycle.invalidReason.get());
        summary.put("collectionIntervalMs", options.collectionIntervalMs());
        summary.put("configuredCollectionIntervalMs", options.collectionIntervalMs());
        summary.put("effectiveCollectionIntervalMs", effectiveCollectionIntervalMs(options, devicePoints));
        summary.put("totalPoints", options.points());
        summary.put("drainWaitSeconds", options.drainWaitSeconds());
        summary.put("ingressMode", options.ingressMode());
        summary.put("spreadWithinInterval", options.spreadWithinInterval());
        summary.put("actualElapsedMs", elapsedMs);
        summary.put("activeLoadElapsedMs", loadElapsedMs);
        summary.put("rounds", counters.rounds.get());
        summary.put("submitted", counters.submitted.get());
        summary.put("succeeded", counters.succeeded.get());
        summary.put("failed", counters.failed.get());
        summary.put("rejected", counters.rejected.get());
        summary.put("runtimeReadPointsCalls", counters.runtimeReadPointsCalls.get());
        summary.put("runtimeReadPointsItems", counters.runtimeReadPointsItems.get());
        summary.put("collectorMeasurementRows", counters.runtimeReadPointsItems.get());
        summary.put("theoreticalMeasurementRows", theoreticalMeasurementRows);
        summary.put("adaptiveCollectionEnabled", collectorProperties.getAdaptiveCollection().isEnabled());
        summary.put("pointsPerSecond", counters.submitted.get() * 1000.0d / loadElapsedMs);
        summary.put("theoreticalPointsPerSecond", theoreticalRate);
        summary.put("theoreticalCollectorRate", theoreticalRate);
        summary.put("actualCollectorPointsPerSecond", collectorRate);
        summary.put("actualCollectorRate", collectorRate);
        summary.put("collectorRateDeviationPercent", collectorRateDeviationPercent);
        summary.put("loadDeviationTolerancePercent", options.loadDeviationTolerancePercent());
        summary.put("loadProfileValid", loadProfile.valid());
        summary.put("loadProfileInvalidReason", loadProfile.invalidReason());
        summary.put("actualPipelinePointsPerSecond", pipelineRate);
        summary.put("actualTdengineRowsPerSecond", tdengineRate);
        summary.put("livePipelineItems", livePipelineItems);
        summary.put("liveTdengineRows", liveTdengineRows);
        summary.put("entryReplayCompletedItemsDelta", entryReplayDelta);
        summary.put("pipelineAcceptedRowsDelta", pipelineAcceptedDelta);
        summary.put("flushedBatchesDelta", flushedBatchesDelta);
        summary.put("tdengineFlushedRowsDelta", tdengineFlushedDelta);
        summary.put("batchAverageSizeDelta", flushedBatchesDelta > 0L
                ? tdengineFlushedDelta / (double) flushedBatchesDelta : 0D);
        summary.put("readPointsCalls", counters.runtimeReadPointsCalls.get());
        summary.put("pointsPerReadAvg", average(readPointSizes));
        summary.put("pointsPerReadP50", percentileInt(readPointSizes, 0.50d));
        summary.put("pointsPerReadP95", percentileInt(readPointSizes, 0.95d));
        summary.put("pointsPerReadMax", maxInt(readPointSizes));
        summary.put("runtimeCadenceP50Ms", percentile(runtimeCadenceIntervalsMs, 0.50d));
        summary.put("runtimeCadenceP95Ms", percentile(runtimeCadenceIntervalsMs, 0.95d));
        summary.put("runtimeCadenceP99Ms", percentile(runtimeCadenceIntervalsMs, 0.99d));
        summary.put("runtimeCadenceMinMs", runtimeCadenceIntervalsMs.stream().mapToLong(Long::longValue).min().orElse(0L));
        summary.put("runtimeCadenceMaxMs", max(runtimeCadenceIntervalsMs));
        summary.put("max100msBurstItems", counters.maxBurst100Ms());
        summary.put("max500msBurstItems", counters.maxBurst500Ms());
        summary.put("max1sBurstItems", counters.maxBurst1s());
        summary.put("schedulerMaxTasksPerSlice", schedulerLoadDelta.maxTasksPerSlice());
        summary.put("schedulerMaxPointsPerSlice", schedulerLoadDelta.maxPointsPerSlice());
        summary.put("schedulerTotalTaskCount", schedulerLoadDelta.totalTaskCount());
        summary.put("schedulerEstimatedPointCount", schedulerLoadDelta.estimatedPointCount());
        summary.put("schedulerMinimumCollectionIntervalMs", schedulerLoadDelta.minimumCollectionIntervalMs());
        summary.put("schedulerDueScanIntervalMs", schedulerLoadDelta.dueScanIntervalMs());
        summary.put("schedulerPhaseWheelTickMs", phaseWheelTickMs(schedulerLoadDelta));
        summary.put("schedulerPhaseWheelRoundMs", phaseWheelRoundMs(schedulerLoadDelta));
        summary.put("schedulerPhaseOffsetsMs", phaseOffsetsMs(schedulerLoadDelta));
        summary.put("schedulerMaxScansPer100Ms", maxScansPer100Ms(schedulerLoadDelta));
        summary.put("schedulerActualPhaseWheelTickCount", schedulerLoadDelta.phaseWheelTickCount());
        summary.put("schedulerActualCatchUpTicks", schedulerLoadDelta.phaseWheelCatchUpTickCount());
        summary.put("schedulerActualConsecutiveCatchUpTicks", schedulerLoadDelta.phaseWheelConsecutiveCatchUpCount());
        summary.put("schedulerActualMaxScansPer100Ms", schedulerLoadDelta.phaseWheelMaxScansPer100Ms());
        summary.put("schedulerTickGapP50Ms", schedulerLoadDelta.phaseWheelTickGapP50Ms());
        summary.put("schedulerTickGapP95Ms", schedulerLoadDelta.phaseWheelTickGapP95Ms());
        summary.put("schedulerTickGapP99Ms", schedulerLoadDelta.phaseWheelTickGapP99Ms());
        summary.put("schedulerTickGapMinMs", schedulerLoadDelta.phaseWheelTickGapMinMs());
        summary.put("schedulerTickGapMaxMs", schedulerLoadDelta.phaseWheelTickGapMaxMs());
        summary.put("schedulerSliceExecutionP50Ms", schedulerLoadDelta.sliceExecutionP50Ms());
        summary.put("schedulerSliceExecutionP95Ms", schedulerLoadDelta.sliceExecutionP95Ms());
        summary.put("schedulerSliceExecutionP99Ms", schedulerLoadDelta.sliceExecutionP99Ms());
        summary.put("schedulerSliceExecutionMaxMs", schedulerLoadDelta.sliceExecutionMaxMs());
        summary.put("saveBatchAsyncTaskRatePerSecond", counters.runtimeReadPointsCalls.get() * 1000.0d / loadElapsedMs);
        summary.put("saveBatchAsyncTaskRateNote", "runtime mode 中每次 readPoints 批量返回后由 AOP 触发一个 saveBatchAsync task");
        summary.put("roundP50Ms", percentile(roundDurations, 0.50d));
        summary.put("roundP95Ms", percentile(roundDurations, 0.95d));
        summary.put("roundP99Ms", percentile(roundDurations, 0.99d));
        summary.put("roundMaxMs", max(roundDurations));
        summary.put("roundMetricNote", "round*Ms 表示本测试发射周期，开启 pacing 时接近 collectionInterval，不代表 History 写入延迟");
        summary.put("processCpuLoadAvg", averageDouble(measurementSamples.stream().map(MetricSample::processCpuLoad).toList()));
        summary.put("processCpuLoadPeak", measurementSamples.stream().mapToDouble(MetricSample::processCpuLoad).max().orElse(-1D));
        summary.put("systemCpuLoadAvg", averageDouble(measurementSamples.stream().map(MetricSample::systemCpuLoad).toList()));
        summary.put("systemCpuLoadPeak", measurementSamples.stream().mapToDouble(MetricSample::systemCpuLoad).max().orElse(-1D));
        summary.put("heapPeakBytes", measurementSamples.stream().mapToLong(MetricSample::heapUsed).max().orElse(-1L));
        summary.put("heapEndBytes", finalSample.heapUsed());
        summary.put("threadPeak", measurementSamples.stream().mapToInt(MetricSample::threadCount).max().orElse(-1));
        summary.put("threadEnd", finalSample.threadCount());
        summary.put("gcCount", finalSample.gcCount());
        summary.put("gcTimeMs", finalSample.gcTimeMs());
        summary.put("redisMemoryStartBytes", measurementSamples.stream().findFirst().map(sample -> sample.redis().usedMemory()).orElse(-1L));
        summary.put("redisMemoryPeakBytes", measurementSamples.stream().mapToLong(sample -> sample.redis().usedMemory()).max().orElse(-1L));
        summary.put("redisMemoryEndBytes", finalSample.redis().usedMemory());
        summary.put("streamAppendAttempts", streamAppendAttemptsDelta);
        summary.put("streamXaddSuccess", streamXaddSuccessDelta);
        summary.put("streamXaddFailure", streamXaddFailureDelta);
        summary.put("streamAppendLatencyP50Ms", loadEndSample.stream().appendLatencyP50Ms());
        summary.put("streamAppendLatencyP95Ms", loadEndSample.stream().appendLatencyP95Ms());
        summary.put("streamAppendLatencyP99Ms", loadEndSample.stream().appendLatencyP99Ms());
        summary.put("streamXaddLatencyP50Ms", loadEndSample.stream().xaddLatencyP50Ms());
        summary.put("streamXaddLatencyP95Ms", loadEndSample.stream().xaddLatencyP95Ms());
        summary.put("streamXaddLatencyP99Ms", loadEndSample.stream().xaddLatencyP99Ms());
        summary.put("pipelineProcessedItems", loadEndSample.pipeline().processedItems());
        summary.put("pipelineStageSubmissions", loadEndSample.pipeline().stageSubmissions());
        summary.put("pipelineStageRejected", loadEndSample.pipeline().stageRejectedEvents());
        summary.put("pipelineStageRejectedCompensated", loadEndSample.pipeline().stageRejectedCompensatedEvents());
        summary.put("pipelineStageRejectedUncompensated", loadEndSample.pipeline().stageRejectedUncompensatedEvents());
        summary.put("pipelineLatencyP50Ms", loadEndSample.pipeline().processLatencyP50Ms());
        summary.put("pipelineLatencyP95Ms", loadEndSample.pipeline().processLatencyP95Ms());
        summary.put("pipelineLatencyP99Ms", loadEndSample.pipeline().processLatencyP99Ms());
        summary.put("pipelineLatencySampleCount", loadEndSample.pipeline().processLatencySampleCount());
        summary.put("pipelineLatencyTotalRecorded", loadEndSample.pipeline().processLatencyTotalRecorded());
        summary.put("pipelineLatencyOverwrittenSamples", loadEndSample.pipeline().processLatencyOverwrittenSamples());
        summary.put("stageSubmissionLatencyP50Ms", loadEndSample.pipeline().stageSubmissionLatencyP50Ms());
        summary.put("stageSubmissionLatencyP95Ms", loadEndSample.pipeline().stageSubmissionLatencyP95Ms());
        summary.put("stageSubmissionLatencyP99Ms", loadEndSample.pipeline().stageSubmissionLatencyP99Ms());
        summary.put("stageSubmissionLatencySampleCount", loadEndSample.pipeline().stageSubmissionLatencySampleCount());
        summary.put("stageSubmissionLatencyTotalRecorded", loadEndSample.pipeline().stageSubmissionLatencyTotalRecorded());
        summary.put("stageSubmissionLatencyOverwrittenSamples",
                loadEndSample.pipeline().stageSubmissionLatencyOverwrittenSamples());
        summary.put("pipelineMetricsInternalErrors", loadEndSample.pipeline().metricsInternalErrors());
        summary.put("pipelineLogRateLimitedEvents", loadEndSample.pipeline().logRateLimitedEvents());
        summary.put("pipelineLogSuppressedEvents", loadEndSample.pipeline().logSuppressedEvents());
        summary.put("entryBatchTaskCount", loadEndSample.postProcessor().batchTaskCount());
        summary.put("entryBatchTaskItems", loadEndSample.postProcessor().batchTaskItems());
        summary.put("entryBatchSizeP50", loadEndSample.postProcessor().batchSizeP50());
        summary.put("entryBatchSizeP95", loadEndSample.postProcessor().batchSizeP95());
        summary.put("entryBatchSizeMax", loadEndSample.postProcessor().batchSizeMax());
        summary.put("entryBatchSizeSampleCount", loadEndSample.postProcessor().batchSizeSampleCount());
        summary.put("entryBatchSizeTotalRecorded", loadEndSample.postProcessor().batchSizeTotalRecorded());
        summary.put("entryBatchSizeOverwrittenSamples", loadEndSample.postProcessor().batchSizeOverwrittenSamples());
        summary.put("entryBatchTaskLatencyP50Ms", loadEndSample.postProcessor().batchTaskLatencyP50Ms());
        summary.put("entryBatchTaskLatencyP95Ms", loadEndSample.postProcessor().batchTaskLatencyP95Ms());
        summary.put("entryBatchTaskLatencyP99Ms", loadEndSample.postProcessor().batchTaskLatencyP99Ms());
        summary.put("entryBatchTaskLatencySampleCount",
                loadEndSample.postProcessor().batchTaskLatencySampleCount());
        summary.put("entryBatchTaskLatencyTotalRecorded",
                loadEndSample.postProcessor().batchTaskLatencyTotalRecorded());
        summary.put("entryBatchTaskLatencyOverwrittenSamples",
                loadEndSample.postProcessor().batchTaskLatencyOverwrittenSamples());
        summary.put("entryMetricsInternalErrors", loadEndSample.postProcessor().metricsInternalErrors());
        summary.put("entryLogRateLimitedEvents", loadEndSample.postProcessor().entryLogRateLimitedEvents());
        summary.put("entryLogSuppressedEvents", loadEndSample.postProcessor().entryLogSuppressedEvents());
        summary.put("actualWarnLogs", loadEndSample.pipeline().logRateLimitedEvents()
                + loadEndSample.postProcessor().entryLogRateLimitedEvents());
        summary.put("suppressedWarnLogs", loadEndSample.pipeline().logSuppressedEvents()
                + loadEndSample.postProcessor().entryLogSuppressedEvents());
        summary.put("entryRejectedItemsDelta", entryRejectedItemsDelta);
        summary.put("entryDroppedItemsDelta", entryDroppedItemsDelta);
        summary.put("historyDeferredDelta", historyDeferredDelta);
        summary.put("historyRejectedDroppedDelta", historyRejectedDroppedDelta);
        summary.put("flushExecutorRejectedBatchesDelta", flushExecutorRejectedBatchesDelta);
        summary.put("historyBatchTdengineWriteRequestsDelta", tdengineWriteRequestsDelta);
        summary.put("historyBatchTdengineWriteRowsDelta", tdengineWriteRowsDelta);
        summary.put("historyBatchTdengineWriteRequestsPerSecondDelta", tdengineWriteRequestsDelta * 1000.0d / loadElapsedMs);
        summary.put("historyBatchTdengineWriteRowsPerSecondDelta", tdengineWriteRowsDelta * 1000.0d / loadElapsedMs);
        summary.put("tdengineWriterRequestsDelta", writerRequestsDelta);
        summary.put("tdengineWriterRowsDelta", writerRowsDelta);
        summary.put("tdengineWriterSingleTableRequestsDelta", writerSingleRequestsDelta);
        summary.put("tdengineWriterMultiTableRequestsDelta", writerMultiRequestsDelta);
        summary.put("tdengineWriterFailuresDelta", writerFailuresDelta);
        summary.put("tdengineWriterRequestsPerSecondDelta", writerRequestsDelta * 1000.0d / loadElapsedMs);
        summary.put("tdengineWriterRowsPerSecondDelta", writerRowsDelta * 1000.0d / loadElapsedMs);
        summary.put("tdengineWriterRowsPerRequest", writerRequestsDelta > 0L
                ? writerRowsDelta / (double) writerRequestsDelta : 0D);
        summary.put("tdengineWriterRowsPerRequestP95", writerEnd.rowsPerRequestP95());
        summary.put("tdengineWriterRowsPerRequestMax", writerEnd.rowsPerRequestMax());
        summary.put("tdengineWriterTablesPerRequest", writerEnd.averageTablesPerRequest());
        summary.put("tdengineWriterTablesPerRequestP95", writerEnd.tablesPerRequestP95());
        summary.put("tdengineWriterTablesPerRequestMax", writerEnd.tablesPerRequestMax());
        summary.put("tdengineWriterConnectionAcquireP50Ms", writerEnd.connectionAcquireP50Ms());
        summary.put("tdengineWriterConnectionAcquireP95Ms", writerEnd.connectionAcquireP95Ms());
        summary.put("tdengineWriterConnectionAcquireP99Ms", writerEnd.connectionAcquireP99Ms());
        summary.put("tdengineWriterSqlBuildP50Ms", writerEnd.sqlBuildP50Ms());
        summary.put("tdengineWriterSqlBuildP95Ms", writerEnd.sqlBuildP95Ms());
        summary.put("tdengineWriterSqlBuildP99Ms", writerEnd.sqlBuildP99Ms());
        summary.put("tdengineWriterDbExecuteP50Ms", writerEnd.dbExecuteP50Ms());
        summary.put("tdengineWriterDbExecuteP95Ms", writerEnd.dbExecuteP95Ms());
        summary.put("tdengineWriterDbExecuteP99Ms", writerEnd.dbExecuteP99Ms());
        summary.put("tdengineWriterTotalWriteP50Ms", writerEnd.totalWriteP50Ms());
        summary.put("tdengineWriterTotalWriteP95Ms", writerEnd.totalWriteP95Ms());
        summary.put("tdengineWriterTotalWriteP99Ms", writerEnd.totalWriteP99Ms());
        summary.put("tdengineWriterLatencySampleCount", writerEnd.sampleCount());
        summary.put("tdengineWriterLatencyTotalRecorded", writerEnd.totalRecordedSamples());
        summary.put("tdengineWriterLatencyOverwrittenSamples", writerEnd.overwrittenSamples());
        summary.put("tdengineWriterEnsureSubTableCallsDelta", writerEnsureCallsDelta);
        summary.put("tdengineWriterEnsureSubTableCacheHitsDelta", writerEnsureHitsDelta);
        summary.put("tdengineWriterEnsureSubTableCacheMissesDelta", writerEnsureMissesDelta);
        summary.put("historyBatchDbQueueWaitP50Ms", loadEndSample.historyBatch().dbQueueWaitP50Ms());
        summary.put("historyBatchDbQueueWaitP95Ms", loadEndSample.historyBatch().dbQueueWaitP95Ms());
        summary.put("historyBatchDbQueueWaitP99Ms", loadEndSample.historyBatch().dbQueueWaitP99Ms());
        summary.put("historyBatchDbExecuteLatencyP50Ms", loadEndSample.historyBatch().dbExecuteLatencyP50Ms());
        summary.put("historyBatchDbExecuteLatencyP95Ms", loadEndSample.historyBatch().dbExecuteLatencyP95Ms());
        summary.put("historyBatchDbExecuteLatencyP99Ms", loadEndSample.historyBatch().dbExecuteLatencyP99Ms());
        summary.put("historyBatchMaxConcurrentWritesSameSubTable",
                loadEndSample.historyBatch().maxConcurrentWritesSameSubTable());
        summary.put("historyBatchSameSubTableConcurrentWriteCount",
                loadEndSample.historyBatch().sameSubTableConcurrentWriteCount());
        summary.put("historyBatchSubTableWriteLatencyP95Ms",
                loadEndSample.historyBatch().subTableWriteLatencyP95Ms());
        summary.put("schedulerFinal", finalSample.scheduler());
        summary.put("schedulerLoadDelta", schedulerLoadDelta);
        summary.put("hikariFinal", finalSample.hikari());
        summary.put("hikariActivePeak", measurementSamples.stream().mapToInt(sample -> sample.hikari().activeConnections()).max().orElse(-1));
        summary.put("hikariWaitingPeak", measurementSamples.stream().mapToInt(sample -> sample.hikari().threadsAwaitingConnection()).max().orElse(-1));
        summary.put("historyPendingPeak", measurementSamples.stream().mapToLong(sample -> sample.history().redisPending()).max().orElse(-1L));
        summary.put("entryPendingPeak", measurementSamples.stream().mapToLong(sample -> sample.entry().redisPending()).max().orElse(-1L));
        summary.put("redisFinal", finalSample.redis());
        summary.put("telemetryEntryFinal", finalSample.entry());
        summary.put("streamFinal", finalSample.stream());
        summary.put("historyFinal", finalSample.history());
        summary.put("historyBatchFinal", finalSample.historyBatch());
        summary.put("cloudFinal", finalSample.cloud());
        summary.put("executorQueuePeaks", executorQueuePeaks(measurementSamples));
        summary.put("executorActivePeaks", executorActivePeaks(measurementSamples));
        summary.put("executorRejectedPeaks", executorRejectedPeaks(measurementSamples));
        summary.put("executorRejectedDelta", executorRejectedDelta);
        summary.put("executorCompletedFinal", executorCompletedFinal(finalSample));
        summary.put("historyLocalQueueSecondsAtMeasuredRate",
                estimateHistoryLocalSeconds(finalSample.history(), counters, loadElapsedMs));
        summary.put("cloudOutboxEstimate", estimateCloudOutbox(options, counters, loadElapsedMs));
        summary.put("ackBridgeReceived", ackBridge != null ? ackBridge.received.get() : 0L);
        summary.put("ackBridgeSent", ackBridge != null ? ackBridge.sent.get() : 0L);
        summary.put("ackBridgeFailed", ackBridge != null ? ackBridge.failed.get() : 0L);
        summary.put("deviceIds", devicePoints.stream().map(DevicePoints::deviceId).toList());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("summary.json").toFile(), summary);
        writeRuntimeCapacitySummary(outputDir, summary);
    }

    private Map<String, Long> castLongMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Long> result = new LinkedHashMap<>();
            map.forEach((key, number) -> {
                if (key != null && number instanceof Number typedNumber) {
                    result.put(String.valueOf(key), typedNumber.longValue());
                }
            });
            return result;
        }
        return Map.of();
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private long delta(long end, long start) {
        return Math.max(0L, end - start);
    }

    private List<MetricSample> samplesBetween(List<MetricSample> samples, long startInclusive, long endInclusive) {
        List<MetricSample> selected = samples.stream()
                .filter(sample -> sample.timestamp() >= startInclusive && sample.timestamp() <= endInclusive)
                .toList();
        return selected.isEmpty() ? samples : selected;
    }

    private long historyDeferredDelta(HistorySnapshot start, HistorySnapshot end) {
        return delta(end.rejectedRedisBuffered(), start.rejectedRedisBuffered())
                + delta(end.rejectedLocalBuffered(), start.rejectedLocalBuffered())
                + delta(end.writeFailureRedisBuffered(), start.writeFailureRedisBuffered())
                + delta(end.writeFailureLocalBuffered(), start.writeFailureLocalBuffered());
    }

    static double theoreticalCollectorRate(int points, long collectionIntervalMs) {
        return Math.max(0, points) * 1000.0d / Math.max(1L, collectionIntervalMs);
    }

    static double rateDeviationPercent(double actualRate, double theoreticalRate) {
        if (theoreticalRate <= 0D) {
            return actualRate <= 0D ? 0D : Double.POSITIVE_INFINITY;
        }
        return Math.abs(actualRate - theoreticalRate) * 100.0d / theoreticalRate;
    }

    static LoadProfileResult evaluateLoadProfile(double actualRate,
                                                 double theoreticalRate,
                                                 double tolerancePercent) {
        double deviation = rateDeviationPercent(actualRate, theoreticalRate);
        if (deviation > Math.max(0D, tolerancePercent)) {
            return new LoadProfileResult(false, "INVALID_LOAD_PROFILE");
        }
        return new LoadProfileResult(true, null);
    }

    static String fixedCapacityInvalidReason(boolean adaptiveCollectionEnabled) {
        return adaptiveCollectionEnabled
                ? "INVALID_LOAD_PROFILE: fixed capacity requires collector.adaptive-collection.enabled=false"
                : null;
    }

    static long measurementCounterDelta(long start, long end) {
        return Math.max(0L, end - start);
    }

    private long effectiveCollectionIntervalMs(SoakOptions options, List<DevicePoints> devicePoints) {
        List<Long> intervals = devicePoints.stream()
                .flatMap(device -> device.points().stream())
                .mapToLong(point -> point.getBaseCollectionInterval() != null && point.getBaseCollectionInterval() > 0
                        ? point.getBaseCollectionInterval() : options.collectionIntervalMs())
                .distinct()
                .boxed()
                .toList();
        return intervals.size() == 1 ? intervals.get(0) : -1L;
    }

    private Map<String, Long> executorRejectedDelta(MetricSample start, MetricSample end) {
        Map<String, Long> result = new LinkedHashMap<>();
        end.threadPools().forEach((name, snapshot) -> {
            long startRejected = start.threadPools().containsKey(name)
                    ? start.threadPools().get(name).getRejectedCount() : 0L;
            result.put(name, delta(snapshot.getRejectedCount(), startRejected));
        });
        return result;
    }

    private SchedulerStateSnapshot schedulerDelta(SchedulerStateSnapshot start, SchedulerStateSnapshot end) {
        return new SchedulerStateSnapshot(
                end.timeSliceCount(),
                end.timeSliceIntervalMs(),
                end.dueScanIntervalMs(),
                end.timeSliceRevision(),
                delta(end.batchDispatchRejectedCount(), start.batchDispatchRejectedCount()),
                delta(end.collectRejectedCount(), start.collectRejectedCount()),
                delta(end.processRejectedCount(), start.processRejectedCount()),
                delta(end.reconnectAttemptCount(), start.reconnectAttemptCount()),
                delta(end.reconnectSuccessCount(), start.reconnectSuccessCount()),
                delta(end.reconnectFailureCount(), start.reconnectFailureCount()),
                end.reconnectingDevices(),
                end.cadenceStateSize(),
                end.inFlightPointClaims(),
                end.totalTaskCount(),
                end.estimatedPointCount(),
                end.minimumCollectionIntervalMs(),
                end.maxTasksPerSlice(),
                end.maxPointsPerSlice(),
                end.timeSliceExecutionEntries(),
                end.deviceStatsEntries(),
                delta(end.phaseWheelTickCount(), start.phaseWheelTickCount()),
                delta(end.phaseWheelCatchUpTickCount(), start.phaseWheelCatchUpTickCount()),
                delta(end.phaseWheelConsecutiveCatchUpCount(), start.phaseWheelConsecutiveCatchUpCount()),
                end.phaseWheelMaxScansPer100Ms(),
                end.phaseWheelTickGapP50Ms(),
                end.phaseWheelTickGapP95Ms(),
                end.phaseWheelTickGapP99Ms(),
                end.phaseWheelTickGapMinMs(),
                end.phaseWheelTickGapMaxMs(),
                end.sliceExecutionP50Ms(),
                end.sliceExecutionP95Ms(),
                end.sliceExecutionP99Ms(),
                end.sliceExecutionMaxMs());
    }

    private long rejectedCounter(Map<String, Long> rejectedPeaks, String executorKeyword) {
        String normalized = executorKeyword.toLowerCase(Locale.ROOT);
        return rejectedPeaks.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).contains(normalized))
                .mapToLong(Map.Entry::getValue)
                .max()
                .orElse(0L);
    }

    private long queuePeak(Map<String, Long> queuePeaks, String executorKeyword) {
        String normalized = executorKeyword.toLowerCase(Locale.ROOT);
        return queuePeaks.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).contains(normalized))
                .mapToLong(Map.Entry::getValue)
                .max()
                .orElse(0L);
    }

    private long peakHistoryPending(Map<String, Object> summary) {
        Object value = summary.get("historyPendingPeak");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private int phaseWheelTickMs(SchedulerStateSnapshot scheduler) {
        int sliceCount = Math.max(1, scheduler.timeSliceCount());
        int dueScanInterval = Math.max(1, scheduler.dueScanIntervalMs());
        int distributedTick = (dueScanInterval + sliceCount - 1) / sliceCount;
        return Math.max(MIN_PHASE_WHEEL_TICK_MS, distributedTick);
    }

    private int phaseWheelRoundMs(SchedulerStateSnapshot scheduler) {
        return phaseWheelTickMs(scheduler) * Math.max(1, scheduler.timeSliceCount());
    }

    private List<Integer> phaseOffsetsMs(SchedulerStateSnapshot scheduler) {
        int tick = phaseWheelTickMs(scheduler);
        return IntStream.range(0, Math.max(1, scheduler.timeSliceCount()))
                .map(index -> index * tick)
                .boxed()
                .toList();
    }

    private long maxScansPer100Ms(SchedulerStateSnapshot scheduler) {
        int tick = phaseWheelTickMs(scheduler);
        return IntStream.range(0, Math.max(1, scheduler.timeSliceCount()))
                .map(index -> index * tick / 100)
                .boxed()
                .collect(java.util.stream.Collectors.groupingBy(
                        bucket -> bucket,
                        java.util.stream.Collectors.counting()))
                .values()
                .stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
    }

    private boolean isStable(SchedulerStateSnapshot scheduler,
                             EntryIngressSnapshot entry,
                             long entryRejectedItemsDelta,
                             long entryDroppedItemsDelta,
                             long streamXaddFailureDelta,
                             HistorySnapshot history,
                             long historyRejectedDroppedDelta,
                             HistoryBatchSnapshot batch,
                             Map<String, Long> rejectedDelta) {
        return scheduler.batchDispatchRejectedCount() == 0L
                && scheduler.collectRejectedCount() == 0L
                && scheduler.processRejectedCount() == 0L
                && entryRejectedItemsDelta == 0L
                && entry.redisPending() == 0L
                && entry.localPending() == 0
                && entryDroppedItemsDelta == 0L
                && rejectedCounter(rejectedDelta, "stream") == 0L
                && streamXaddFailureDelta == 0L
                && rejectedCounter(rejectedDelta, "history") == 0L
                && history.redisPending() == 0L
                && history.localPending() == 0
                && historyRejectedDroppedDelta == 0L
                && batch.currentBufferedRows() == 0
                && batch.inFlightFlushes() == 0;
    }

    private String firstBottleneck(SchedulerStateSnapshot scheduler,
                                   EntryIngressSnapshot entry,
                                   long entryRejectedItemsDelta,
                                   long entryDroppedItemsDelta,
                                   long streamXaddFailureDelta,
                                   HistorySnapshot history,
                                   Map<String, Long> rejectedDelta) {
        if (scheduler.processRejectedCount() > 0L) {
            return "process-executor";
        }
        if (scheduler.collectRejectedCount() > 0L) {
            return "collector-executor";
        }
        if (scheduler.batchDispatchRejectedCount() > 0L) {
            return "batch-dispatch-executor";
        }
        if (entryRejectedItemsDelta > 0L || entryDroppedItemsDelta > 0L || entry.redisPending() > 0L) {
            return "telemetry-entry";
        }
        if (rejectedCounter(rejectedDelta, "stream") > 0L) {
            return "stream-stage";
        }
        if (streamXaddFailureDelta > 0L) {
            return "redis-xadd";
        }
        if (rejectedCounter(rejectedDelta, "history") > 0L
                || history.redisPending() > 0L
                || history.localPending() > 0) {
            return "history-stage";
        }
        return "none";
    }

    private String csvValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private void writeRuntimeCapacitySummary(Path outputDir, Map<String, Object> summary) throws IOException {
        List<String> headers = List.of(
                "scenario", "points", "devices", "collectionIntervalMs", "theoreticalPointsPerSecond",
                "actualCollectorPointsPerSecond", "collectorRateDeviationPercent", "measurementValid",
                "invalidReason", "adaptiveCollectionEnabled", "actualPipelinePointsPerSecond",
                "actualTdengineRowsPerSecond",
                "durationSeconds", "readPointsCalls", "pointsPerReadAvg", "pointsPerReadP95",
                "max1sBurstItems", "dueScanIntervalMs", "phaseWheelTickMs", "phaseWheelRoundMs",
                "maxScansPer100Ms", "actualMaxScansPer100Ms", "phaseWheelCatchUpTicks",
                "phaseWheelTickGapP50Ms", "phaseWheelTickGapP95Ms", "phaseWheelTickGapP99Ms",
                "phaseWheelTickGapMinMs", "phaseWheelTickGapMaxMs", "sliceExecutionP95Ms",
                "maxTasksPerSlice", "maxPointsPerSlice",
                "pipelineLatencyP95Ms", "pipelineLatencySampleCount", "pipelineLatencyTotalRecorded",
                "pipelineLatencyOverwrittenSamples", "entryBatchTaskLatencyP95Ms",
                "entryBatchTaskLatencySampleCount", "entryBatchTaskLatencyTotalRecorded",
                "entryBatchTaskLatencyOverwrittenSamples", "actualWarnLogs", "suppressedWarnLogs",
                "batchDispatchRejected", "collectRejected", "processRejected",
                "entryRejectedItems", "streamRejected", "streamXaddFailure", "streamXaddLatencyP95Ms",
                "historyRejected", "historyDeferred",
                "historyPendingPeak", "historyPendingFinal", "historyQueuePeak", "batchAvg",
                "batchP95", "batchWriteP95", "flushExecutorQueuePeak", "flushExecutorRejectedBatches",
                "cpuAvg", "cpuPeak", "heapPeakBytes", "gcTimeMs",
                "threadPeak", "drainSeconds", "stable", "firstBottleneck");
        SchedulerStateSnapshot scheduler = (SchedulerStateSnapshot) summary.get("schedulerLoadDelta");
        EntryIngressSnapshot entry = (EntryIngressSnapshot) summary.get("telemetryEntryFinal");
        StreamSnapshot stream = (StreamSnapshot) summary.get("streamFinal");
        HistorySnapshot history = (HistorySnapshot) summary.get("historyFinal");
        HistoryBatchSnapshot batch = (HistoryBatchSnapshot) summary.get("historyBatchFinal");
        Map<String, Long> queuePeaks = castLongMap(summary.get("executorQueuePeaks"));
        Map<String, Long> rejectedDelta = castLongMap(summary.get("executorRejectedDelta"));
        long entryRejectedItemsDelta = longValue(summary.get("entryRejectedItemsDelta"));
        long entryDroppedItemsDelta = longValue(summary.get("entryDroppedItemsDelta"));
        long streamXaddFailureDelta = longValue(summary.get("streamXaddFailure"));
        long historyDeferredDelta = longValue(summary.get("historyDeferredDelta"));
        long historyRejectedDroppedDelta = longValue(summary.get("historyRejectedDroppedDelta"));
        long flushExecutorRejectedBatchesDelta = longValue(summary.get("flushExecutorRejectedBatchesDelta"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", summary.get("scenario"));
        row.put("points", summary.get("points"));
        row.put("devices", summary.get("devices"));
        row.put("collectionIntervalMs", summary.get("collectionIntervalMs"));
        row.put("theoreticalPointsPerSecond", summary.get("theoreticalPointsPerSecond"));
        row.put("actualCollectorPointsPerSecond", summary.get("actualCollectorPointsPerSecond"));
        row.put("collectorRateDeviationPercent", summary.get("collectorRateDeviationPercent"));
        row.put("measurementValid", summary.get("measurementValid"));
        row.put("invalidReason", summary.get("invalidReason"));
        row.put("adaptiveCollectionEnabled", summary.get("adaptiveCollectionEnabled"));
        row.put("actualPipelinePointsPerSecond", summary.get("actualPipelinePointsPerSecond"));
        row.put("actualTdengineRowsPerSecond", summary.get("actualTdengineRowsPerSecond"));
        row.put("durationSeconds", summary.get("durationSeconds"));
        row.put("readPointsCalls", summary.get("readPointsCalls"));
        row.put("pointsPerReadAvg", summary.get("pointsPerReadAvg"));
        row.put("pointsPerReadP95", summary.get("pointsPerReadP95"));
        row.put("max1sBurstItems", summary.get("max1sBurstItems"));
        row.put("dueScanIntervalMs", scheduler.dueScanIntervalMs());
        row.put("phaseWheelTickMs", summary.get("schedulerPhaseWheelTickMs"));
        row.put("phaseWheelRoundMs", summary.get("schedulerPhaseWheelRoundMs"));
        row.put("maxScansPer100Ms", summary.get("schedulerMaxScansPer100Ms"));
        row.put("actualMaxScansPer100Ms", summary.get("schedulerActualMaxScansPer100Ms"));
        row.put("phaseWheelCatchUpTicks", summary.get("schedulerActualCatchUpTicks"));
        row.put("phaseWheelTickGapP50Ms", summary.get("schedulerTickGapP50Ms"));
        row.put("phaseWheelTickGapP95Ms", summary.get("schedulerTickGapP95Ms"));
        row.put("phaseWheelTickGapP99Ms", summary.get("schedulerTickGapP99Ms"));
        row.put("phaseWheelTickGapMinMs", summary.get("schedulerTickGapMinMs"));
        row.put("phaseWheelTickGapMaxMs", summary.get("schedulerTickGapMaxMs"));
        row.put("sliceExecutionP95Ms", summary.get("schedulerSliceExecutionP95Ms"));
        row.put("maxTasksPerSlice", scheduler.maxTasksPerSlice());
        row.put("maxPointsPerSlice", scheduler.maxPointsPerSlice());
        row.put("pipelineLatencyP95Ms", summary.get("pipelineLatencyP95Ms"));
        row.put("pipelineLatencySampleCount", summary.get("pipelineLatencySampleCount"));
        row.put("pipelineLatencyTotalRecorded", summary.get("pipelineLatencyTotalRecorded"));
        row.put("pipelineLatencyOverwrittenSamples", summary.get("pipelineLatencyOverwrittenSamples"));
        row.put("entryBatchTaskLatencyP95Ms", summary.get("entryBatchTaskLatencyP95Ms"));
        row.put("entryBatchTaskLatencySampleCount", summary.get("entryBatchTaskLatencySampleCount"));
        row.put("entryBatchTaskLatencyTotalRecorded", summary.get("entryBatchTaskLatencyTotalRecorded"));
        row.put("entryBatchTaskLatencyOverwrittenSamples", summary.get("entryBatchTaskLatencyOverwrittenSamples"));
        row.put("actualWarnLogs", summary.get("actualWarnLogs"));
        row.put("suppressedWarnLogs", summary.get("suppressedWarnLogs"));
        row.put("batchDispatchRejected", scheduler.batchDispatchRejectedCount());
        row.put("collectRejected", scheduler.collectRejectedCount());
        row.put("processRejected", scheduler.processRejectedCount());
        row.put("entryRejectedItems", entryRejectedItemsDelta);
        row.put("streamRejected", rejectedCounter(rejectedDelta, "stream"));
        row.put("streamXaddFailure", streamXaddFailureDelta);
        row.put("streamXaddLatencyP95Ms", summary.get("streamXaddLatencyP95Ms"));
        row.put("historyRejected", rejectedCounter(rejectedDelta, "history"));
        row.put("historyDeferred", historyDeferredDelta);
        row.put("historyPendingPeak", peakHistoryPending(summary));
        row.put("historyPendingFinal", history.redisPending());
        row.put("historyQueuePeak", queuePeak(queuePeaks, "history"));
        row.put("batchAvg", summary.get("batchAverageSizeDelta"));
        row.put("batchP95", batch.batchSizeP95());
        row.put("batchWriteP95", batch.flushLatencyP95Ms());
        row.put("flushExecutorQueuePeak", batch.flushExecutorQueuePeak());
        row.put("flushExecutorRejectedBatches", flushExecutorRejectedBatchesDelta);
        row.put("cpuAvg", summary.get("processCpuLoadAvg"));
        row.put("cpuPeak", summary.get("processCpuLoadPeak"));
        row.put("heapPeakBytes", summary.get("heapPeakBytes"));
        row.put("gcTimeMs", summary.get("gcTimeMs"));
        row.put("threadPeak", summary.get("threadPeak"));
        row.put("drainSeconds", summary.get("drainWaitSeconds"));
        boolean measurementValid = Boolean.TRUE.equals(summary.get("measurementValid"));
        row.put("stable", measurementValid && isStable(scheduler, entry, entryRejectedItemsDelta, entryDroppedItemsDelta,
                streamXaddFailureDelta,
                history, historyRejectedDroppedDelta, batch, rejectedDelta));
        String firstBottleneck = measurementValid
                ? firstBottleneck(scheduler, entry, entryRejectedItemsDelta, entryDroppedItemsDelta,
                streamXaddFailureDelta, history, rejectedDelta)
                : "invalid-load-profile";
        row.put("firstBottleneck", firstBottleneck);
        try (BufferedWriter writer = Files.newBufferedWriter(outputDir.resolve("runtime-capacity-summary.csv"),
                StandardCharsets.UTF_8)) {
            writer.write(String.join(",", headers));
            writer.write('\n');
            writer.write(String.join(",", headers.stream()
                    .map(header -> csvValue(row.get(header)))
                    .toList()));
            writer.write('\n');
        }
    }

    private Map<String, Long> executorQueuePeaks(List<MetricSample> samples) {
        Map<String, Long> peaks = new LinkedHashMap<>();
        for (MetricSample sample : samples) {
            sample.threadPools().forEach((name, snapshot) -> peaks.merge(
                    name, (long) snapshot.getQueueSize(), Math::max));
        }
        return peaks;
    }

    private Map<String, Long> executorRejectedPeaks(List<MetricSample> samples) {
        Map<String, Long> peaks = new LinkedHashMap<>();
        for (MetricSample sample : samples) {
            sample.threadPools().forEach((name, snapshot) -> peaks.merge(
                    name, snapshot.getRejectedCount(), Math::max));
        }
        return peaks;
    }

    private Map<String, Long> executorActivePeaks(List<MetricSample> samples) {
        Map<String, Long> peaks = new LinkedHashMap<>();
        for (MetricSample sample : samples) {
            sample.threadPools().forEach((name, snapshot) -> peaks.merge(
                    name, (long) snapshot.getActiveCount(), Math::max));
        }
        return peaks;
    }

    private Map<String, Long> executorCompletedFinal(MetricSample finalSample) {
        Map<String, Long> completed = new LinkedHashMap<>();
        finalSample.threadPools().forEach((name, snapshot) ->
                completed.put(name, snapshot.getCompletedTaskCount()));
        return completed;
    }

    private double estimateHistoryLocalSeconds(HistorySnapshot history, SoakCounters counters, long elapsedMs) {
        if (history.localCapacity() <= 0 || counters.submitted.get() <= 0L) {
            return -1.0d;
        }
        double recordsPerSecond = counters.submitted.get() * 1000.0d / Math.max(1L, elapsedMs);
        return history.localCapacity() / Math.max(1.0d, recordsPerSecond);
    }

    private Map<String, Object> estimateCloudOutbox(SoakOptions options, SoakCounters counters, long elapsedMs) {
        double messagesPerSecond = counters.submitted.get() * 1000.0d / Math.max(1L, elapsedMs);
        long averageBytes = Math.max(512L, options.estimatedCloudMessageBytes());
        Map<String, Object> estimate = new LinkedHashMap<>();
        estimate.put("messagesPerSecondUpperBound", messagesPerSecond);
        estimate.put("bytesPerMessageAssumption", averageBytes);
        estimate.put("outage5MinMessages", Math.round(messagesPerSecond * 300.0d));
        estimate.put("outage30MinMessages", Math.round(messagesPerSecond * 1800.0d));
        estimate.put("outage1HourMessages", Math.round(messagesPerSecond * 3600.0d));
        estimate.put("outage5MinMemoryBytes", Math.round(messagesPerSecond * 300.0d * averageBytes));
        estimate.put("outage30MinMemoryBytes", Math.round(messagesPerSecond * 1800.0d * averageBytes));
        estimate.put("outage1HourMemoryBytes", Math.round(messagesPerSecond * 3600.0d * averageBytes));
        return estimate;
    }

    private void writeMetricsHeader(BufferedWriter writer) throws IOException {
        writer.write("timestamp,elapsedMs,finalSample,submitted,succeeded,failed,rejected,rounds,lastRoundMs,"
                + "roundP50Ms,roundP95Ms,roundP99Ms,roundMaxMs,processCpuLoad,systemCpuLoad,heapUsedMiB,"
                + "heapCommittedMiB,heapMaxMiB,nonHeapUsedMiB,threadCount,gcCount,gcTimeMs,redisConnected,"
                + "redisUsedMemory,redisOpsPerSec,redisStreamLength,historyRedisPending,historyRedisProcessing,"
                + "entryRedisPending,entryRedisProcessing,entryLocalPending,entryRejectedTasks,"
                + "entryRejectedItems,entryRedisBufferedItems,entryLocalBufferedItems,entryDroppedItems,"
                + "entryReplayCompletedItems,entryStaleSameRuntimeDroppedItems,entryCrossRuntimeRecoveredItems,"
                + "entryLegacyEnvelopeRecoveredItems,"
                + "streamAppendAttempts,streamSkippedAppends,streamSerializationFailures,streamXaddSuccess,"
                + "streamXaddFailure,streamAppendLatencyP50Ms,streamAppendLatencyP95Ms,streamAppendLatencyP99Ms,"
                + "streamXaddLatencyP50Ms,streamXaddLatencyP95Ms,streamXaddLatencyP99Ms,"
                + "pipelineProcessedItems,pipelineStageSubmissions,pipelineStageRejected,pipelineStageRejectedCompensated,"
                + "pipelineStageRejectedUncompensated,pipelineStageRejectedShutdown,pipelineLatencyP50Ms,"
                + "pipelineLatencyP95Ms,pipelineLatencyP99Ms,pipelineLatencySampleCount,"
                + "pipelineLatencyTotalRecorded,pipelineLatencyOverwrittenSamples,stageSubmissionLatencyP50Ms,"
                + "stageSubmissionLatencyP95Ms,stageSubmissionLatencyP99Ms,stageSubmissionLatencySampleCount,"
                + "stageSubmissionLatencyTotalRecorded,stageSubmissionLatencyOverwrittenSamples,"
                + "pipelineMetricsInternalErrors,pipelineLogRateLimited,pipelineLogSuppressed,"
                + "entryBatchTaskCount,entryBatchTaskItems,entryBatchSizeP50,"
                + "entryBatchSizeP95,entryBatchSizeMax,entryBatchSizeSampleCount,entryBatchSizeTotalRecorded,"
                + "entryBatchSizeOverwrittenSamples,entryBatchTaskLatencyP50Ms,entryBatchTaskLatencyP95Ms,"
                + "entryBatchTaskLatencyP99Ms,entryBatchTaskLatencySampleCount,"
                + "entryBatchTaskLatencyTotalRecorded,entryBatchTaskLatencyOverwrittenSamples,"
                + "entryMetricsInternalErrors,entryLogRateLimited,entryLogSuppressed,"
                + "historyLocalPending,historyRejectedRedisBuffered,historyRejectedLocalBuffered,"
                + "historyRejectedDropped,historyWriteFailureDisabled,historyRejectedDisabled,"
                + "historyReplayClaimedRows,historyReplaySuccessfulRows,historyReplayFailedRows,"
                + "historyReplayBatchCount,historyReplayAverageBatchSize,historyReplayBatchSizeP95,"
                + "historyReplayBatchSizeMax,historyReplayRowsPerSecond,historyReplayBatchWriteP50Ms,"
                + "historyReplayBatchWriteP95Ms,historyReplayBatchWriteP99Ms,historyReplayPausedForLivePressure,"
                + "historyReplayProcessingRows,historyBatchFallbackRedisRows,historyBatchFallbackRedisOps,"
                + "historyBatchFallbackLocalRows,historyBatchFallbackDroppedRows,historyBatchFallbackLatencyP50Ms,"
                + "historyBatchFallbackLatencyP95Ms,historyBatchFallbackLatencyP99Ms,historyLiveFlushQueueUtilization,"
                + "historyBatchAcceptedRows,historyBatchFlushedBatches,historyBatchFlushedRows,"
                + "historyBatchWriteSuccess,historyBatchWriteFailure,historyBatchFallbackRows,historyBatchCurrentBuffered,"
                + "historyBatchBufferedPeak,historyBatchAverageSize,historyBatchSizeP50,historyBatchSizeP95,"
                + "historyBatchSizeMax,historyBatchLatencyP50Ms,historyBatchLatencyP95Ms,historyBatchLatencyP99Ms,"
                + "historyBatchFallbackRedisRows,historyBatchFallbackLocalRows,historyBatchFallbackDroppedRows,"
                + "historyBatchFallbackDisabledRows,historyBatchShutdownDeferredRows,historyBatchShutdownNonDurableRows,"
                + "historyBatchShutdownDroppedRows,historyBatchShutdownDisabledRows,"
                + "historyBatchFlushExecutorSubmittedBatches,historyBatchFlushExecutorCompletedBatches,"
                + "historyBatchFlushExecutorRejectedBatches,historyBatchFlushExecutorQueueCurrent,"
                + "historyBatchFlushExecutorQueuePeak,historyBatchFlushExecutorActiveCurrent,"
                + "historyBatchFlushExecutorActivePeak,historyBatchShutdownQueuedBatches,historyBatchBucketCount,"
                + "historyBatchAdmissionInFlight,historyBatchInFlightFlushes,"
                + "historyBatchSizeFlushBatches,historyBatchTimerFlushBatches,"
                + "historyBatchSizeFlushRows,historyBatchTimerFlushRows,"
                + "historyBatchSizeAverageSize,historyBatchSizeBatchP50,historyBatchSizeBatchP95,"
                + "historyBatchSizeBatchMax,historyBatchTimerAverageSize,historyBatchTimerBatchP50,"
                + "historyBatchTimerBatchP95,historyBatchTimerBatchMax,"
                + "historyBatchTdengineBatchCallsPerSecond,historyBatchFlushExecutorServiceRatePerSecond,"
                + "historyBatchFlushExecutorQueueUtilization,historyBatchMaxConcurrentWritesSameSubTable,"
                + "historyBatchSameSubTableConcurrentWriteCount,historyBatchDbQueueWaitP50Ms,"
                + "historyBatchDbQueueWaitP95Ms,historyBatchDbQueueWaitP99Ms,"
                + "historyBatchDbExecuteLatencyP50Ms,historyBatchDbExecuteLatencyP95Ms,"
                + "historyBatchDbExecuteLatencyP99Ms,"
                + "cloudTotal,cloudPending,cloudPublishing,cloudWaitingAck,cloudIsolated,"
                + "ackReceived,ackSent,ackFailed,schedulerTimeSliceCount,schedulerTimeSliceIntervalMs,"
                + "schedulerDueScanIntervalMs,schedulerTimeSliceRevision,schedulerBatchDispatchRejected,schedulerCollectRejected,"
                + "schedulerProcessRejected,schedulerCadenceStateSize,schedulerInFlightPointClaims,"
                + "schedulerTotalTasks,schedulerEstimatedPoints,schedulerMinimumCollectionIntervalMs,"
                + "schedulerMaxTasksPerSlice,schedulerMaxPointsPerSlice,schedulerPhaseWheelTickCount,"
                + "schedulerPhaseWheelCatchUpTickCount,schedulerPhaseWheelConsecutiveCatchUpCount,"
                + "schedulerPhaseWheelMaxScansPer100Ms,schedulerPhaseWheelTickGapP50Ms,"
                + "schedulerPhaseWheelTickGapP95Ms,schedulerPhaseWheelTickGapP99Ms,"
                + "schedulerPhaseWheelTickGapMinMs,schedulerPhaseWheelTickGapMaxMs,"
                + "schedulerSliceExecutionP50Ms,schedulerSliceExecutionP95Ms,"
                + "schedulerSliceExecutionP99Ms,schedulerSliceExecutionMaxMs,"
                + "hikariActive,hikariIdle,hikariWaiting,hikariTotal,hikariMaxPool,threadPoolsJson\n");
    }

    private void writeMetric(BufferedWriter writer, MetricSample sample) throws IOException {
        writer.write(String.join(",",
                String.valueOf(sample.timestamp()),
                String.valueOf(sample.elapsedMs()),
                String.valueOf(sample.finalSample()),
                String.valueOf(sample.submitted()),
                String.valueOf(sample.succeeded()),
                String.valueOf(sample.failed()),
                String.valueOf(sample.rejected()),
                String.valueOf(sample.rounds()),
                String.valueOf(sample.lastRoundMs()),
                String.valueOf(sample.roundP50Ms()),
                String.valueOf(sample.roundP95Ms()),
                String.valueOf(sample.roundP99Ms()),
                String.valueOf(sample.roundMaxMs()),
                String.valueOf(sample.processCpuLoad()),
                String.valueOf(sample.systemCpuLoad()),
                String.valueOf(sample.heapUsed() / BYTES_PER_MIB),
                String.valueOf(sample.heapCommitted() / BYTES_PER_MIB),
                String.valueOf(sample.heapMax() / BYTES_PER_MIB),
                String.valueOf(sample.nonHeapUsed() / BYTES_PER_MIB),
                String.valueOf(sample.threadCount()),
                String.valueOf(sample.gcCount()),
                String.valueOf(sample.gcTimeMs()),
                String.valueOf(sample.redis().connected()),
                String.valueOf(sample.redis().usedMemory()),
                String.valueOf(sample.redis().opsPerSecond()),
                String.valueOf(sample.redis().streamLength()),
                String.valueOf(sample.history().redisPending()),
                String.valueOf(sample.history().redisProcessing()),
                String.valueOf(sample.entry().redisPending()),
                String.valueOf(sample.entry().redisProcessing()),
                String.valueOf(sample.entry().localPending()),
                String.valueOf(sample.entry().rejectedTasks()),
                String.valueOf(sample.entry().rejectedItems()),
                String.valueOf(sample.entry().redisBufferedItems()),
                String.valueOf(sample.entry().localBufferedItems()),
                String.valueOf(sample.entry().droppedItems()),
                String.valueOf(sample.entry().replayCompletedItems()),
                String.valueOf(sample.entry().staleSameRuntimeDroppedItems()),
                String.valueOf(sample.entry().crossRuntimeRecoveredItems()),
                String.valueOf(sample.entry().legacyEnvelopeRecoveredItems()),
                String.valueOf(sample.stream().appendAttempts()),
                String.valueOf(sample.stream().skippedAppends()),
                String.valueOf(sample.stream().serializationFailures()),
                String.valueOf(sample.stream().xaddSuccess()),
                String.valueOf(sample.stream().xaddFailure()),
                String.valueOf(sample.stream().appendLatencyP50Ms()),
                String.valueOf(sample.stream().appendLatencyP95Ms()),
                String.valueOf(sample.stream().appendLatencyP99Ms()),
                String.valueOf(sample.stream().xaddLatencyP50Ms()),
                String.valueOf(sample.stream().xaddLatencyP95Ms()),
                String.valueOf(sample.stream().xaddLatencyP99Ms()),
                String.valueOf(sample.pipeline().processedItems()),
                String.valueOf(sample.pipeline().stageSubmissions()),
                String.valueOf(sample.pipeline().stageRejectedEvents()),
                String.valueOf(sample.pipeline().stageRejectedCompensatedEvents()),
                String.valueOf(sample.pipeline().stageRejectedUncompensatedEvents()),
                String.valueOf(sample.pipeline().stageRejectedShutdownEvents()),
                String.valueOf(sample.pipeline().processLatencyP50Ms()),
                String.valueOf(sample.pipeline().processLatencyP95Ms()),
                String.valueOf(sample.pipeline().processLatencyP99Ms()),
                String.valueOf(sample.pipeline().processLatencySampleCount()),
                String.valueOf(sample.pipeline().processLatencyTotalRecorded()),
                String.valueOf(sample.pipeline().processLatencyOverwrittenSamples()),
                String.valueOf(sample.pipeline().stageSubmissionLatencyP50Ms()),
                String.valueOf(sample.pipeline().stageSubmissionLatencyP95Ms()),
                String.valueOf(sample.pipeline().stageSubmissionLatencyP99Ms()),
                String.valueOf(sample.pipeline().stageSubmissionLatencySampleCount()),
                String.valueOf(sample.pipeline().stageSubmissionLatencyTotalRecorded()),
                String.valueOf(sample.pipeline().stageSubmissionLatencyOverwrittenSamples()),
                String.valueOf(sample.pipeline().metricsInternalErrors()),
                String.valueOf(sample.pipeline().logRateLimitedEvents()),
                String.valueOf(sample.pipeline().logSuppressedEvents()),
                String.valueOf(sample.postProcessor().batchTaskCount()),
                String.valueOf(sample.postProcessor().batchTaskItems()),
                String.valueOf(sample.postProcessor().batchSizeP50()),
                String.valueOf(sample.postProcessor().batchSizeP95()),
                String.valueOf(sample.postProcessor().batchSizeMax()),
                String.valueOf(sample.postProcessor().batchSizeSampleCount()),
                String.valueOf(sample.postProcessor().batchSizeTotalRecorded()),
                String.valueOf(sample.postProcessor().batchSizeOverwrittenSamples()),
                String.valueOf(sample.postProcessor().batchTaskLatencyP50Ms()),
                String.valueOf(sample.postProcessor().batchTaskLatencyP95Ms()),
                String.valueOf(sample.postProcessor().batchTaskLatencyP99Ms()),
                String.valueOf(sample.postProcessor().batchTaskLatencySampleCount()),
                String.valueOf(sample.postProcessor().batchTaskLatencyTotalRecorded()),
                String.valueOf(sample.postProcessor().batchTaskLatencyOverwrittenSamples()),
                String.valueOf(sample.postProcessor().metricsInternalErrors()),
                String.valueOf(sample.postProcessor().entryLogRateLimitedEvents()),
                String.valueOf(sample.postProcessor().entryLogSuppressedEvents()),
                String.valueOf(sample.history().localPending()),
                String.valueOf(sample.history().rejectedRedisBuffered()),
                String.valueOf(sample.history().rejectedLocalBuffered()),
                String.valueOf(sample.history().rejectedDropped()),
                String.valueOf(sample.history().writeFailureDisabled()),
                String.valueOf(sample.history().rejectedDisabled()),
                String.valueOf(sample.history().replayClaimedRows()),
                String.valueOf(sample.history().replaySuccessfulRows()),
                String.valueOf(sample.history().replayFailedRows()),
                String.valueOf(sample.history().replayBatchCount()),
                String.valueOf(sample.history().replayAverageBatchSize()),
                String.valueOf(sample.history().replayBatchSizeP95()),
                String.valueOf(sample.history().replayBatchSizeMax()),
                String.valueOf(sample.history().replayRowsPerSecond()),
                String.valueOf(sample.history().replayBatchWriteP50Ms()),
                String.valueOf(sample.history().replayBatchWriteP95Ms()),
                String.valueOf(sample.history().replayBatchWriteP99Ms()),
                String.valueOf(sample.history().replayPausedForLivePressureCount()),
                String.valueOf(sample.history().replayProcessingRows()),
                String.valueOf(sample.history().batchFallbackRedisRows()),
                String.valueOf(sample.history().batchFallbackRedisOps()),
                String.valueOf(sample.history().batchFallbackLocalRows()),
                String.valueOf(sample.history().batchFallbackDroppedRows()),
                String.valueOf(sample.history().batchFallbackLatencyP50Ms()),
                String.valueOf(sample.history().batchFallbackLatencyP95Ms()),
                String.valueOf(sample.history().batchFallbackLatencyP99Ms()),
                String.valueOf(sample.history().liveFlushQueueUtilization()),
                String.valueOf(sample.historyBatch().acceptedRows()),
                String.valueOf(sample.historyBatch().flushedBatches()),
                String.valueOf(sample.historyBatch().flushedRows()),
                String.valueOf(sample.historyBatch().batchWriteSuccess()),
                String.valueOf(sample.historyBatch().batchWriteFailure()),
                String.valueOf(sample.historyBatch().fallbackRows()),
                String.valueOf(sample.historyBatch().currentBufferedRows()),
                String.valueOf(sample.historyBatch().bufferedRowsPeak()),
                String.valueOf(sample.historyBatch().averageBatchSize()),
                String.valueOf(sample.historyBatch().batchSizeP50()),
                String.valueOf(sample.historyBatch().batchSizeP95()),
                String.valueOf(sample.historyBatch().batchSizeMax()),
                String.valueOf(sample.historyBatch().flushLatencyP50Ms()),
                String.valueOf(sample.historyBatch().flushLatencyP95Ms()),
                String.valueOf(sample.historyBatch().flushLatencyP99Ms()),
                String.valueOf(sample.historyBatch().fallbackRedisRows()),
                String.valueOf(sample.historyBatch().fallbackLocalRows()),
                String.valueOf(sample.historyBatch().fallbackDroppedRows()),
                String.valueOf(sample.historyBatch().fallbackDisabledRows()),
                String.valueOf(sample.historyBatch().shutdownDeferredRows()),
                String.valueOf(sample.historyBatch().shutdownNonDurableRows()),
                String.valueOf(sample.historyBatch().shutdownDroppedRows()),
                String.valueOf(sample.historyBatch().shutdownDisabledRows()),
                String.valueOf(sample.historyBatch().flushExecutorSubmittedBatches()),
                String.valueOf(sample.historyBatch().flushExecutorCompletedBatches()),
                String.valueOf(sample.historyBatch().flushExecutorRejectedBatches()),
                String.valueOf(sample.historyBatch().flushExecutorQueueCurrent()),
                String.valueOf(sample.historyBatch().flushExecutorQueuePeak()),
                String.valueOf(sample.historyBatch().flushExecutorActiveCurrent()),
                String.valueOf(sample.historyBatch().flushExecutorActivePeak()),
                String.valueOf(sample.historyBatch().shutdownQueuedBatches()),
                String.valueOf(sample.historyBatch().bucketCount()),
                String.valueOf(sample.historyBatch().admissionInFlight()),
                String.valueOf(sample.historyBatch().inFlightFlushes()),
                String.valueOf(sample.historyBatch().sizeFlushBatches()),
                String.valueOf(sample.historyBatch().timerFlushBatches()),
                String.valueOf(sample.historyBatch().sizeFlushRows()),
                String.valueOf(sample.historyBatch().timerFlushRows()),
                String.valueOf(sample.historyBatch().sizeAverageBatchSize()),
                String.valueOf(sample.historyBatch().sizeBatchSizeP50()),
                String.valueOf(sample.historyBatch().sizeBatchSizeP95()),
                String.valueOf(sample.historyBatch().sizeBatchSizeMax()),
                String.valueOf(sample.historyBatch().timerAverageBatchSize()),
                String.valueOf(sample.historyBatch().timerBatchSizeP50()),
                String.valueOf(sample.historyBatch().timerBatchSizeP95()),
                String.valueOf(sample.historyBatch().timerBatchSizeMax()),
                String.valueOf(sample.historyBatch().tdengineBatchCallsPerSecond()),
                String.valueOf(sample.historyBatch().flushExecutorServiceRatePerSecond()),
                String.valueOf(sample.historyBatch().flushExecutorQueueUtilization()),
                String.valueOf(sample.historyBatch().maxConcurrentWritesSameSubTable()),
                String.valueOf(sample.historyBatch().sameSubTableConcurrentWriteCount()),
                String.valueOf(sample.historyBatch().dbQueueWaitP50Ms()),
                String.valueOf(sample.historyBatch().dbQueueWaitP95Ms()),
                String.valueOf(sample.historyBatch().dbQueueWaitP99Ms()),
                String.valueOf(sample.historyBatch().dbExecuteLatencyP50Ms()),
                String.valueOf(sample.historyBatch().dbExecuteLatencyP95Ms()),
                String.valueOf(sample.historyBatch().dbExecuteLatencyP99Ms()),
                String.valueOf(sample.cloud().total()),
                String.valueOf(sample.cloud().pending()),
                String.valueOf(sample.cloud().publishing()),
                String.valueOf(sample.cloud().waitingAck()),
                String.valueOf(sample.cloud().isolated()),
                String.valueOf(sample.cloud().ackReceived()),
                String.valueOf(sample.cloud().ackSent()),
                String.valueOf(sample.cloud().ackFailed()),
                String.valueOf(sample.scheduler().timeSliceCount()),
                String.valueOf(sample.scheduler().timeSliceIntervalMs()),
                String.valueOf(sample.scheduler().dueScanIntervalMs()),
                String.valueOf(sample.scheduler().timeSliceRevision()),
                String.valueOf(sample.scheduler().batchDispatchRejectedCount()),
                String.valueOf(sample.scheduler().collectRejectedCount()),
                String.valueOf(sample.scheduler().processRejectedCount()),
                String.valueOf(sample.scheduler().cadenceStateSize()),
                String.valueOf(sample.scheduler().inFlightPointClaims()),
                String.valueOf(sample.scheduler().totalTaskCount()),
                String.valueOf(sample.scheduler().estimatedPointCount()),
                String.valueOf(sample.scheduler().minimumCollectionIntervalMs()),
                String.valueOf(sample.scheduler().maxTasksPerSlice()),
                String.valueOf(sample.scheduler().maxPointsPerSlice()),
                String.valueOf(sample.scheduler().phaseWheelTickCount()),
                String.valueOf(sample.scheduler().phaseWheelCatchUpTickCount()),
                String.valueOf(sample.scheduler().phaseWheelConsecutiveCatchUpCount()),
                String.valueOf(sample.scheduler().phaseWheelMaxScansPer100Ms()),
                String.valueOf(sample.scheduler().phaseWheelTickGapP50Ms()),
                String.valueOf(sample.scheduler().phaseWheelTickGapP95Ms()),
                String.valueOf(sample.scheduler().phaseWheelTickGapP99Ms()),
                String.valueOf(sample.scheduler().phaseWheelTickGapMinMs()),
                String.valueOf(sample.scheduler().phaseWheelTickGapMaxMs()),
                String.valueOf(sample.scheduler().sliceExecutionP50Ms()),
                String.valueOf(sample.scheduler().sliceExecutionP95Ms()),
                String.valueOf(sample.scheduler().sliceExecutionP99Ms()),
                String.valueOf(sample.scheduler().sliceExecutionMaxMs()),
                String.valueOf(sample.hikari().activeConnections()),
                String.valueOf(sample.hikari().idleConnections()),
                String.valueOf(sample.hikari().threadsAwaitingConnection()),
                String.valueOf(sample.hikari().totalConnections()),
                String.valueOf(sample.hikari().maximumPoolSize()),
                csvJson(sample.threadPools())
        ));
        writer.write('\n');
    }

    private String csvJson(Object value) {
        try {
            return '"' + objectMapper.writeValueAsString(value).replace("\"", "\"\"") + '"';
        } catch (JsonProcessingException exception) {
            return "\"{}\"";
        }
    }

    private void waitForDrain(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            HistoryBatchWriter batchWriter = historyBatchWriterProvider.getIfAvailable();
            if (batchWriter != null) {
                batchWriter.flushDueBuckets();
            }
            SystemResourceSnapshot resources = systemResourceMonitorService.getResources();
            boolean queuesEmpty = resources.getThreadPools().values().stream()
                    .filter(Objects::nonNull)
                    .allMatch(pool -> pool.getQueueSize() <= 0 && pool.getActiveCount() <= 0);
            boolean historyBatchEmpty = batchWriter == null || batchWriter.metrics().currentBufferedRows() <= 0;
            if (queuesEmpty && historyBatchEmpty) {
                return;
            }
            Thread.sleep(500L);
        }
    }

    private void cleanupDevices(List<DevicePoints> devicePoints) {
        for (DevicePoints device : devicePoints) {
            try {
                collectionScheduler.stopDevice(device.deviceId());
                configManager.deleteLocalDeviceConfig(device.deviceId());
            } catch (RuntimeException ignored) {
                // Soak 清理失败不覆盖主测试结果，最终指标仍保留在结果目录。
            }
        }
    }

    private void stopRuntimeDevices(List<DevicePoints> devicePoints) {
        for (DevicePoints device : devicePoints) {
            try {
                collectionScheduler.stopDevice(device.deviceId());
            } catch (RuntimeException ignored) {
                // runtime soak 停止输入失败不覆盖后续 drain 指标，最终由 summary 暴露残留状态。
            }
        }
    }

    private String command(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(Path.of(".").toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                return "unknown";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private String sha256(String value) {
        if (value == null || "unknown".equals(value)) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format(Locale.ROOT, "%02x", b));
            }
            return builder.toString();
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static int percentileInt(List<Integer> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double average(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return 0D;
        }
        return values.stream().mapToLong(Integer::longValue).average().orElse(0D);
    }

    private static double averageDouble(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0D;
        }
        return values.stream()
                .filter(value -> value != null && value >= 0D)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0D);
    }

    private static int maxInt(List<Integer> values) {
        return values == null || values.isEmpty() ? 0 : values.stream().max(Comparator.naturalOrder()).orElse(0);
    }

    private static long max(List<Long> values) {
        return values == null || values.isEmpty() ? 0L : values.stream().max(Comparator.naturalOrder()).orElse(0L);
    }

    private static long last(List<Long> values) {
        return values == null || values.isEmpty() ? 0L : values.get(values.size() - 1);
    }

    private static long sum(Collection<Long> values) {
        return values == null ? 0L : values.stream().mapToLong(Long::longValue).sum();
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private record DevicePoints(String deviceId, List<DataPoint> points) {
    }

    private record RedisNamespaceBinding(String historyPendingKey,
                                         String historyProcessingKey,
                                         String historyDeadLetterKey,
                                         String entryPendingKey,
                                         String entryProcessingKey,
                                         String entryDeadLetterKey,
                                         String streamKey) {
    }

    private record RuntimeMeasurementWindow(MetricSample measurementStartSample,
                                            MetricSample measurementEndSample) {
    }

    record LoadProfileResult(boolean valid, String invalidReason) {
    }

    private static final class SoakLifecycle {
        private final AtomicLong createdAt = new AtomicLong();
        private final AtomicLong setupStartedAt = new AtomicLong();
        private final AtomicLong setupCompletedAt = new AtomicLong();
        private final AtomicLong warmupStartedAt = new AtomicLong();
        private final AtomicLong warmupCompletedAt = new AtomicLong();
        private final AtomicLong settleStartedAt = new AtomicLong();
        private final AtomicLong settleCompletedAt = new AtomicLong();
        private final AtomicLong settleSeconds = new AtomicLong();
        private final AtomicLong measurementStartedAt = new AtomicLong();
        private final AtomicLong measurementCompletedAt = new AtomicLong();
        private final AtomicLong drainStartedAt = new AtomicLong();
        private final AtomicLong drainCompletedAt = new AtomicLong();
        private final AtomicBoolean warmupBacklogClean = new AtomicBoolean(false);
        private final AtomicBoolean measurementValid = new AtomicBoolean(false);
        private final AtomicReference<String> invalidReason = new AtomicReference<>();
        private final AtomicReference<String> runLockOwner = new AtomicReference<>();
        private final AtomicReference<String> runLockPath = new AtomicReference<>();
        private final AtomicReference<SoakRunIsolationSupport.QuiescenceSnapshot> warmupQuiescence =
                new AtomicReference<>(SoakRunIsolationSupport.quiescence(Map.of()));

        private SoakLifecycle(long createdAt) {
            this.createdAt.set(createdAt);
        }

        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("createdAt", instantString(createdAt.get()));
            result.put("setupStartedAt", instantString(setupStartedAt.get()));
            result.put("setupCompletedAt", instantString(setupCompletedAt.get()));
            result.put("warmupStartedAt", instantString(warmupStartedAt.get()));
            result.put("warmupCompletedAt", instantString(warmupCompletedAt.get()));
            result.put("settleStartedAt", instantString(settleStartedAt.get()));
            result.put("settleCompletedAt", instantString(settleCompletedAt.get()));
            result.put("settleSeconds", settleSeconds.get());
            result.put("measurementStartedAt", instantString(measurementStartedAt.get()));
            result.put("measurementCompletedAt", instantString(measurementCompletedAt.get()));
            result.put("drainStartedAt", instantString(drainStartedAt.get()));
            result.put("drainCompletedAt", instantString(drainCompletedAt.get()));
            result.put("warmupBacklogClean", warmupBacklogClean.get());
            result.put("warmupQuiescence", warmupQuiescence.get().asMap());
            result.put("measurementValid", measurementValid.get());
            result.put("invalidReason", invalidReason.get());
            result.put("runLockOwner", runLockOwner.get());
            result.put("runLockPath", runLockPath.get());
            return result;
        }

        private static String instantString(long epochMillis) {
            return epochMillis > 0L ? Instant.ofEpochMilli(epochMillis).toString() : null;
        }
    }

    private record GcSnapshot(long count, long timeMs) {
    }

    private record RedisSnapshot(boolean connected,
                                 String version,
                                 long usedMemory,
                                 long connectedClients,
                                 long opsPerSecond,
                                 long streamLength,
                                 String error) {
    }

    private record EntryIngressSnapshot(long redisPending,
                                        long redisProcessing,
                                        long redisDeadLetter,
                                        int localPending,
                                        int localCapacity,
                                        long rejectedTasks,
                                        long rejectedItems,
                                        long redisBufferedItems,
                                        long localBufferedItems,
                                        long droppedItems,
                                         long replayCompletedItems,
                                         long pendingRemoveFailures,
                                         long poisonDeadLetterItems,
                                         long staleSameRuntimeDroppedItems,
                                         long crossRuntimeRecoveredItems,
                                         long legacyEnvelopeRecoveredItems) {
    }

    private record StreamSnapshot(long appendAttempts,
                                  long skippedAppends,
                                  long serializationFailures,
                                  long xaddSuccess,
                                  long xaddFailure,
                                  double appendLatencyP50Ms,
                                  double appendLatencyP95Ms,
                                  double appendLatencyP99Ms,
                                  double xaddLatencyP50Ms,
                                  double xaddLatencyP95Ms,
                                  double xaddLatencyP99Ms) {
    }

    private record HistorySnapshot(long redisPending,
                                   long redisProcessing,
                                   long redisDeadLetter,
                                   int localPending,
                                   int localCapacity,
                                   long writeFailureRedisBuffered,
                                   long rejectedRedisBuffered,
                                   long writeFailureLocalBuffered,
                                    long rejectedLocalBuffered,
                                    long writeFailureDropped,
                                    long rejectedDropped,
                                    long writeFailureDisabled,
                                    long rejectedDisabled,
                                    long replayClaimedRows,
                                    long replaySuccessfulRows,
                                    long replayFailedRows,
                                    long replayBatchCount,
                                    double replayAverageBatchSize,
                                    int replayBatchSizeP95,
                                    int replayBatchSizeMax,
                                    double replayRowsPerSecond,
                                    double replayBatchWriteP50Ms,
                                    double replayBatchWriteP95Ms,
                                    double replayBatchWriteP99Ms,
                                    long replayPausedForLivePressureCount,
                                    int replayProcessingRows,
                                    long batchFallbackRedisRows,
                                    long batchFallbackRedisOps,
                                    long batchFallbackLocalRows,
                                    long batchFallbackDroppedRows,
                                    double batchFallbackLatencyP50Ms,
                                    double batchFallbackLatencyP95Ms,
                                    double batchFallbackLatencyP99Ms,
                                    double liveFlushQueueUtilization) {
    }

    private record HistoryBatchSnapshot(long acceptedRows,
                                        long flushedBatches,
                                        long flushedRows,
                                        long batchWriteSuccess,
                                        long batchWriteFailure,
                                        long fallbackRows,
                                        int currentBufferedRows,
                                        int bufferedRowsPeak,
                                        double averageBatchSize,
                                        int batchSizeP50,
                                        int batchSizeP95,
                                        int batchSizeMax,
                                        double flushLatencyP50Ms,
                                        double flushLatencyP95Ms,
                                        double flushLatencyP99Ms,
                                        long oldestBufferedAgeMs,
                                        long shutdownFlushedRows,
                                        long fallbackRedisRows,
                                        long fallbackLocalRows,
                                        long fallbackDroppedRows,
                                        long fallbackDisabledRows,
                                        long shutdownDeferredRows,
                                        long shutdownNonDurableRows,
                                        long shutdownDroppedRows,
                                        long shutdownDisabledRows,
                                        long flushExecutorSubmittedBatches,
                                        long flushExecutorCompletedBatches,
                                        long flushExecutorRejectedBatches,
                                        int flushExecutorQueueCurrent,
                                        int flushExecutorQueuePeak,
                                        int flushExecutorActiveCurrent,
                                        int flushExecutorActivePeak,
                                        long shutdownQueuedBatches,
                                        int bucketCount,
                                        int admissionInFlight,
                                        int inFlightFlushes,
                                        long sizeFlushBatches,
                                        long timerFlushBatches,
                                        long sizeFlushRows,
                                        long timerFlushRows,
                                        double sizeAverageBatchSize,
                                        int sizeBatchSizeP50,
                                        int sizeBatchSizeP95,
                                        int sizeBatchSizeMax,
                                        double timerAverageBatchSize,
                                        int timerBatchSizeP50,
                                        int timerBatchSizeP95,
                                        int timerBatchSizeMax,
                                        double tdengineBatchCallsPerSecond,
                                        double flushExecutorServiceRatePerSecond,
                                        double flushExecutorQueueUtilization,
                                        long tdengineWriteRequests,
                                        long tdengineWriteRows,
                                        double tdengineWriteRequestsPerSecond,
                                        double tdengineRowsPerRequest,
                                        int tdengineRowsPerRequestP95,
                                        int tdengineRowsPerRequestMax,
                                        double tdengineTablesPerRequest,
                                        int tdengineTablesPerRequestP95,
                                        int tdengineTablesPerRequestMax,
                                        long multiTableWriteRequests,
                                        long multiTableWriteRows,
                                        long multiTableAggregatedBatches,
                                        Map<String, Integer> activeWritesBySubTable,
                                        int maxConcurrentWritesSameSubTable,
                                        long sameSubTableConcurrentWriteCount,
                                        double dbQueueWaitP50Ms,
                                        double dbQueueWaitP95Ms,
                                        double dbQueueWaitP99Ms,
                                        double dbExecuteLatencyP50Ms,
                                        double dbExecuteLatencyP95Ms,
                                        double dbExecuteLatencyP99Ms,
                                        Map<String, Double> subTableWriteLatencyP95Ms) {
    }

    private record CloudSnapshot(long total,
                                 long pending,
                                 long publishing,
                                 long waitingAck,
                                 long waitingConfig,
                                 long isolated,
                                 long ackReceived,
                                 long ackSent,
                                 long ackFailed) {
    }

    private record PipelineSnapshot(long processedItems,
                                    long stageSubmissions,
                                    long stageRejectedEvents,
                                    long stageRejectedCompensatedEvents,
                                    long stageRejectedUncompensatedEvents,
                                    long stageRejectedShutdownEvents,
                                    double processLatencyP50Ms,
                                    double processLatencyP95Ms,
                                    double processLatencyP99Ms,
                                    int processLatencySampleCount,
                                    long processLatencyTotalRecorded,
                                    long processLatencyOverwrittenSamples,
                                    double stageSubmissionLatencyP50Ms,
                                    double stageSubmissionLatencyP95Ms,
                                    double stageSubmissionLatencyP99Ms,
                                    int stageSubmissionLatencySampleCount,
                                    long stageSubmissionLatencyTotalRecorded,
                                    long stageSubmissionLatencyOverwrittenSamples,
                                    long metricsInternalErrors,
                                    long logRateLimitedEvents,
                                    long logSuppressedEvents) {
    }

    private record PostProcessorSnapshot(long batchTaskCount,
                                         long batchTaskItems,
                                         int batchSizeP50,
                                         int batchSizeP95,
                                         int batchSizeMax,
                                         int batchSizeSampleCount,
                                         long batchSizeTotalRecorded,
                                         long batchSizeOverwrittenSamples,
                                         double batchTaskLatencyP50Ms,
                                         double batchTaskLatencyP95Ms,
                                         double batchTaskLatencyP99Ms,
                                         int batchTaskLatencySampleCount,
                                         long batchTaskLatencyTotalRecorded,
                                         long batchTaskLatencyOverwrittenSamples,
                                         long metricsInternalErrors,
                                         long entryLogRateLimitedEvents,
                                         long entryLogSuppressedEvents) {
    }

    private record SchedulerStateSnapshot(int timeSliceCount,
                                          int timeSliceIntervalMs,
                                          int dueScanIntervalMs,
                                          long timeSliceRevision,
                                          long batchDispatchRejectedCount,
                                          long collectRejectedCount,
                                          long processRejectedCount,
                                          long reconnectAttemptCount,
                                          long reconnectSuccessCount,
                                          long reconnectFailureCount,
                                          int reconnectingDevices,
                                          int cadenceStateSize,
                                          int inFlightPointClaims,
                                          long totalTaskCount,
                                          long estimatedPointCount,
                                          long minimumCollectionIntervalMs,
                                          int maxTasksPerSlice,
                                          int maxPointsPerSlice,
                                          int timeSliceExecutionEntries,
                                          int deviceStatsEntries,
                                          long phaseWheelTickCount,
                                          long phaseWheelCatchUpTickCount,
                                          long phaseWheelConsecutiveCatchUpCount,
                                          long phaseWheelMaxScansPer100Ms,
                                          long phaseWheelTickGapP50Ms,
                                          long phaseWheelTickGapP95Ms,
                                          long phaseWheelTickGapP99Ms,
                                          long phaseWheelTickGapMinMs,
                                          long phaseWheelTickGapMaxMs,
                                          long sliceExecutionP50Ms,
                                          long sliceExecutionP95Ms,
                                          long sliceExecutionP99Ms,
                                          long sliceExecutionMaxMs) {
    }

    private record HikariSnapshot(int maximumPoolSize,
                                  int minimumIdle,
                                  int activeConnections,
                                  int idleConnections,
                                  int threadsAwaitingConnection,
                                  int totalConnections,
                                  String type) {
    }

    private record MetricSample(long timestamp,
                                long elapsedMs,
                                boolean finalSample,
                                long submitted,
                                long succeeded,
                                long failed,
                                long rejected,
                                long rounds,
                                long lastRoundMs,
                                long roundP50Ms,
                                long roundP95Ms,
                                long roundP99Ms,
                                long roundMaxMs,
                                double processCpuLoad,
                                double systemCpuLoad,
                                long heapUsed,
                                long heapCommitted,
                                long heapMax,
                                long nonHeapUsed,
                                int threadCount,
                                long gcCount,
                                long gcTimeMs,
                                Map<String, SystemResourceSnapshot.ThreadPoolSnapshot> threadPools,
                                PipelineSnapshot pipeline,
                                PostProcessorSnapshot postProcessor,
                                SchedulerStateSnapshot scheduler,
                                HikariSnapshot hikari,
                                RedisSnapshot redis,
                                EntryIngressSnapshot entry,
                                StreamSnapshot stream,
                                HistorySnapshot history,
                                HistoryBatchSnapshot historyBatch,
                                TdengineWriteMetrics tdengineWrite,
                                CloudSnapshot cloud) {
    }

    private static final class SoakCounters {
        private final AtomicLong submitted = new AtomicLong();
        private final AtomicLong succeeded = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong rounds = new AtomicLong();
        private final AtomicLong runtimeReadPointsCalls = new AtomicLong();
        private final AtomicLong runtimeReadPointsItems = new AtomicLong();
        private final AtomicLong loadStartedAt = new AtomicLong();
        private final AtomicLong loadFinishedAt = new AtomicLong();
        private final List<Integer> runtimeReadPointSizes = Collections.synchronizedList(new ArrayList<>());
        private final List<Long> runtimeCadenceIntervalsMs = Collections.synchronizedList(new ArrayList<>());
        private final ConcurrentMap<String, Long> lastRuntimeReadNanosByPoint = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, AtomicLong> burst100MsBuckets = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, AtomicLong> burst500MsBuckets = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, AtomicLong> burst1SBuckets = new ConcurrentHashMap<>();
        private final AtomicBoolean collectorActive = new AtomicBoolean(false);
        private final AtomicBoolean measurementActive = new AtomicBoolean(false);

        private SoakCounters() {
        }

        private void recordRuntimeRead(List<DataPoint> points) {
            if (points == null || points.isEmpty()) {
                return;
            }
            if (!measurementActive.get()) {
                return;
            }
            long nowNanos = System.nanoTime();
            int pointCount = points.size();
            runtimeReadPointsCalls.incrementAndGet();
            runtimeReadPointsItems.addAndGet(pointCount);
            submitted.addAndGet(pointCount);
            succeeded.addAndGet(pointCount);
            rounds.incrementAndGet();
            runtimeReadPointSizes.add(pointCount);
            for (DataPoint point : points) {
                recordPointCadence(point, nowNanos);
            }
            recordBurst(nowNanos, pointCount);
        }

        private boolean loadActive() {
            return collectorActive.get();
        }

        private void activateRuntimeCollector() {
            collectorActive.set(true);
        }

        private void deactivateRuntimeCollector() {
            collectorActive.set(false);
        }

        private void startMeasurement() {
            submitted.set(0L);
            succeeded.set(0L);
            failed.set(0L);
            rejected.set(0L);
            rounds.set(0L);
            runtimeReadPointsCalls.set(0L);
            runtimeReadPointsItems.set(0L);
            runtimeReadPointSizes.clear();
            runtimeCadenceIntervalsMs.clear();
            lastRuntimeReadNanosByPoint.clear();
            burst100MsBuckets.clear();
            burst500MsBuckets.clear();
            burst1SBuckets.clear();
            loadStartedAt.set(System.currentTimeMillis());
            loadFinishedAt.set(0L);
            measurementActive.set(true);
        }

        private void finishMeasurement() {
            if (measurementActive.getAndSet(false)) {
                loadFinishedAt.set(System.currentTimeMillis());
            } else if (loadFinishedAt.get() <= 0L) {
                loadFinishedAt.set(System.currentTimeMillis());
            }
        }

        private void recordPointCadence(DataPoint point, long nowNanos) {
            if (point == null || point.getDeviceId() == null || point.getDeviceId().isBlank()
                    || point.getPointId() == null || point.getPointId().isBlank()) {
                return;
            }
            String key = point.getDeviceId() + '|' + point.getPointId();
            lastRuntimeReadNanosByPoint.compute(key, (ignored, lastNanos) -> {
                if (lastNanos != null) {
                    runtimeCadenceIntervalsMs.add(TimeUnit.NANOSECONDS.toMillis(nowNanos - lastNanos));
                }
                return nowNanos;
            });
        }

        private void recordBurst(long nowNanos, int pointCount) {
            long nowMs = TimeUnit.NANOSECONDS.toMillis(nowNanos);
            addBurst(burst100MsBuckets, nowMs / 100L, pointCount);
            addBurst(burst500MsBuckets, nowMs / 500L, pointCount);
            addBurst(burst1SBuckets, nowMs / 1000L, pointCount);
        }

        private void addBurst(ConcurrentMap<Long, AtomicLong> buckets, long bucket, int pointCount) {
            buckets.computeIfAbsent(bucket, ignored -> new AtomicLong()).addAndGet(pointCount);
        }

        private List<Integer> runtimeReadPointSizesSnapshot() {
            synchronized (runtimeReadPointSizes) {
                return List.copyOf(runtimeReadPointSizes);
            }
        }

        private List<Long> runtimeCadenceIntervalsMsSnapshot() {
            synchronized (runtimeCadenceIntervalsMs) {
                return List.copyOf(runtimeCadenceIntervalsMs);
            }
        }

        private long maxBurst100Ms() {
            return maxBurst(burst100MsBuckets);
        }

        private long maxBurst500Ms() {
            return maxBurst(burst500MsBuckets);
        }

        private long maxBurst1s() {
            return maxBurst(burst1SBuckets);
        }

        private long maxBurst(ConcurrentMap<Long, AtomicLong> buckets) {
            return buckets.values().stream().mapToLong(AtomicLong::get).max().orElse(0L);
        }
    }

    public static class RuntimeSoakCollector extends BaseCollector {
        private SoakCounters counters;

        void attachCounters(SoakCounters counters) {
            this.counters = counters;
        }

        @Override
        public String getCollectorType() {
            return "RUNTIME_SOAK";
        }

        @Override
        public String getProtocolType() {
            return "MODBUS_TCP";
        }

        @Override
        protected void doConnect() {
        }

        @Override
        protected void doDisconnect() {
        }

        @Override
        protected Object doReadPoint(DataPoint point) {
            return valueFor(point);
        }

        @Override
        protected Map<String, Object> doReadPoints(List<DataPoint> points) {
            Map<String, Object> values = new LinkedHashMap<>();
            if (points == null || points.isEmpty()) {
                return values;
            }
            if (counters != null && !counters.loadActive()) {
                return values;
            }
            if (counters != null) {
                counters.recordRuntimeRead(points);
            }
            for (DataPoint point : points) {
                if (point != null) {
                    values.put(point.getPointId(), valueFor(point));
                }
            }
            return values;
        }

        @Override
        protected boolean doWritePoint(DataPoint point, Object value) {
            return true;
        }

        @Override
        protected Map<String, Boolean> doWritePoints(Map<DataPoint, Object> points) {
            Map<String, Boolean> result = new LinkedHashMap<>();
            if (points != null) {
                points.keySet().forEach(point -> result.put(point != null ? point.getPointId() : "<null>", true));
            }
            return result;
        }

        @Override
        protected void doSubscribe(List<DataPoint> points) {
        }

        @Override
        protected void doUnsubscribe(List<DataPoint> points) {
        }

        @Override
        protected Map<String, Object> doGetDeviceStatus() {
            return Map.of("runtimeSoak", true);
        }

        @Override
        protected Object doExecuteCommand(int unitId, String command, Map<String, Object> params) {
            return Map.of("command", command, "unitId", unitId);
        }

        @Override
        protected void buildReadPlans(String deviceId, List<DataPoint> points) {
        }

        private Object valueFor(DataPoint point) {
            long sequence = totalReadCount.get() + 1L;
            String dataType = point != null && point.getDataType() != null
                    ? point.getDataType().toUpperCase(Locale.ROOT) : "DOUBLE";
            return switch (dataType) {
                case "LONG", "INT", "INTEGER" -> sequence;
                case "BOOLEAN", "BOOL" -> (sequence & 1L) == 0L;
                case "STRING", "TEXT" -> String.valueOf(sequence);
                default -> (double) sequence;
            };
        }
    }

    private record SoakOptions(String runId,
                               SoakRunIsolationSupport.RedisNamespace redisNamespace,
                               String tdengineDevicePrefix,
                               String scenario,
                               int points,
                               int devices,
                               long durationSeconds,
                               long warmupSeconds,
                               long settleTimeoutSeconds,
                               long collectionIntervalMs,
                               long sampleIntervalSeconds,
                               long drainWaitSeconds,
                               long startedAt,
                               String streamKey,
                               String mqttBrokerUrl,
                               String ingressMode,
                               String capacityProfile,
                               boolean ackBridgeEnabled,
                               boolean historyEnabled,
                               boolean streamEnabled,
                               boolean cloudEnabled,
                               boolean spreadWithinInterval,
                               double loadDeviationTolerancePercent,
                               long estimatedCloudMessageBytes,
                               long maxAllowedRejected,
                               Path outputDir) {

        static SoakOptions from(Environment environment) {
            int points = intValue(environment, "soak.points", 10_000);
            int defaultDevices = Math.max(1, points / 1000);
            int devices = intValue(environment, "soak.devices", defaultDevices);
            long startedAt = System.currentTimeMillis();
            String scenario = value(environment, "soak.scenario", "normal");
            String runId = SoakRunIsolationSupport.safeSegment(scenario, 24)
                    + '-' + RUN_ID_FORMATTER.format(Instant.ofEpochMilli(startedAt))
                    + '-' + Long.toString(ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE), Character.MAX_RADIX);
            SoakRunIsolationSupport.RedisNamespace redisNamespace =
                    SoakRunIsolationSupport.redisNamespace(scenario, runId);
            String tdengineDevicePrefix = "soak-" + SoakRunIsolationSupport.safeSegment(scenario, 24)
                    + '-' + SoakRunIsolationSupport.safeSegment(runId, 48);
            String output = value(environment, "soak.metricsOutput", "target/soak-results/" + runId);
            return new SoakOptions(
                    runId,
                    redisNamespace,
                    tdengineDevicePrefix,
                    scenario,
                    points,
                    devices,
                    longValue(environment, "soak.durationSeconds", 300L),
                    longValue(environment, "soak.warmupSeconds", 60L),
                    longValue(environment, "soak.settleTimeoutSeconds", 120L),
                    longValue(environment, "soak.collectionIntervalMs", 1000L),
                    longValue(environment, "soak.sampleIntervalSeconds", 5L),
                    longValue(environment, "soak.drainWaitSeconds", 30L),
                    startedAt,
                    redisNamespace.streamKey(),
                    value(environment, "collector.report.mqtt.broker-url", "tcp://127.0.0.1:1883"),
                    value(environment, "soak.ingressMode", "point"),
                    value(environment, "soak.capacityProfile", "fixed"),
                    booleanValue(environment, "soak.cloudAckBridgeEnabled", true),
                    booleanValue(environment, "soak.historyEnabled", true),
                    booleanValue(environment, "soak.streamEnabled", true),
                    booleanValue(environment, "soak.cloudEnabled", true),
                    booleanValue(environment, "soak.spreadWithinInterval", true),
                    doubleValue(environment, "soak.loadDeviationTolerancePercent",
                            DEFAULT_LOAD_DEVIATION_TOLERANCE_PERCENT),
                    longValue(environment, "soak.estimatedCloudMessageBytes", 1024L),
                    longValue(environment, "soak.maxAllowedRejected", 0L),
                    Path.of(output));
        }

        private boolean batchIngressMode() {
            return "batch".equalsIgnoreCase(ingressMode);
        }

        private boolean runtimeIngressMode() {
            return "runtime".equalsIgnoreCase(ingressMode);
        }

        private boolean fixedCapacityMode() {
            return "fixed".equalsIgnoreCase(capacityProfile);
        }

        private static String value(Environment environment, String key, String defaultValue) {
            String value = environment.getProperty(key);
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }

        private static int intValue(Environment environment, String key, int defaultValue) {
            return (int) longValue(environment, key, defaultValue);
        }

        private static long longValue(Environment environment, String key, long defaultValue) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException exception) {
                return defaultValue;
            }
        }

        private static boolean booleanValue(Environment environment, String key, boolean defaultValue) {
            String value = environment.getProperty(key);
            return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.trim());
        }

        private static double doubleValue(Environment environment, String key, double defaultValue) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException exception) {
                return defaultValue;
            }
        }
    }

    private static final class MqttAckBridge implements AutoCloseable {
        private final MqttClient client;
        private final ObjectMapper objectMapper;
        private final AtomicLong received = new AtomicLong();
        private final AtomicLong sent = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();

        private MqttAckBridge(MqttClient client, ObjectMapper objectMapper) {
            this.client = client;
            this.objectMapper = objectMapper;
        }

        static MqttAckBridge start(SoakOptions options, ObjectMapper objectMapper) throws MqttException {
            String clientId = "soak-ack-bridge-" + System.currentTimeMillis();
            MqttClient client = new MqttClient(options.mqttBrokerUrl(), clientId, new MemoryPersistence());
            MqttAckBridge bridge = new MqttAckBridge(client, objectMapper);
            client.setCallback(bridge.callback());
            MqttConnectOptions connectOptions = new MqttConnectOptions();
            connectOptions.setCleanSession(true);
            connectOptions.setConnectionTimeout(10);
            connectOptions.setKeepAliveInterval(30);
            client.connect(connectOptions);
            client.subscribe("/sys/+/+/thing/#", 1);
            return bridge;
        }

        private MqttCallback callback() {
            return new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handle(topic, message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            };
        }

        private void handle(String topic, MqttMessage message) {
            if (topic == null || topic.endsWith("_reply")) {
                return;
            }
            received.incrementAndGet();
            try {
                Map<?, ?> payload = objectMapper.readValue(message.getPayload(), Map.class);
                Object id = firstNonNull(payload.get("requestId"), payload.get("messageId"), payload.get("id"));
                if (id == null || String.valueOf(id).isBlank()) {
                    return;
                }
                Map<String, Object> ack = new LinkedHashMap<>();
                ack.put("id", String.valueOf(id));
                ack.put("requestId", String.valueOf(id));
                ack.put("messageId", String.valueOf(id));
                ack.put("code", 0);
                ack.put("msg", "soak ack");
                MqttMessage reply = new MqttMessage(objectMapper.writeValueAsBytes(ack));
                reply.setQos(1);
                client.publish(topic + "_reply", reply);
                sent.incrementAndGet();
            } catch (Exception exception) {
                failed.incrementAndGet();
            }
        }

        private Object firstNonNull(Object... values) {
            for (Object value : values) {
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        @Override
        public void close() throws MqttException {
            if (client.isConnected()) {
                client.disconnect(1000L);
            }
            client.close();
        }
    }
}
