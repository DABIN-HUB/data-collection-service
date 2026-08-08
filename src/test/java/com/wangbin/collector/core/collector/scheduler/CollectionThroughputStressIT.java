package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorTestProviders;
import com.wangbin.collector.core.port.CollectionHealthReporter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 调度器和进程内采集路径的合成吞吐基线。
 *
 * <p>测试使用内存模拟采集，不连接 PLC、Redis、TDengine 或云端目标。默认覆盖 10k、50k、100k
 * 点位三档，结果写入 target/stress 供后续回归对比。</p>
 */
class CollectionThroughputStressIT {

    private static final String PROTOCOL = "MODBUS_TCP";
    private static final long WAIT_TIMEOUT_MS = 120_000L;

    @Test
    void measureSyntheticPointThroughput() throws Exception {
        StressOptions options = StressOptions.fromSystemProperties();
        List<StressResult> results = new ArrayList<>();

        for (int pointCount : options.pointCounts()) {
            results.add(runScenario(pointCount, options));
        }

        writeReports(results, options);
        printSummary(results, options);

        for (StressResult result : results) {
            assertEquals(result.expectedProcessedPoints(), result.processedPoints(),
                    "not all synthetic points were processed for pointCount=" + result.pointCount());
            assertEquals(0L, result.failedPoints(), "synthetic baseline should not produce failed points");
            assertEquals(0L, result.rejectedTasks(), "synthetic baseline should not reject runtime tasks");
            assertEquals(0, result.remainingInFlightFutures(), "in-flight futures should be drained");
            assertEquals(0, result.remainingActiveDevices(), "active devices should be stopped after scenario");
            assertTrue(result.durationMs() < options.scenarioTimeoutMs(),
                    "scenario exceeded conservative runtime bound for pointCount=" + result.pointCount());
        }
    }

    private StressResult runScenario(int pointCount, StressOptions options) throws Exception {
        ScheduledExecutorService timeSliceScheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> daemonThread(runnable, "stress-time-slice"));
        ThreadPoolExecutor batchDispatcher = fixedPool("stress-batch", options.batchThreads(), 20_000);
        ThreadPoolExecutor asyncCollectorPool = fixedPool("stress-collect", options.collectThreads(), 40_000);
        ThreadPoolExecutor dataProcessorPool = fixedPool("stress-process", options.processThreads(), 40_000);
        ThreadPoolExecutor deviceStartExecutor = fixedPool("stress-start", Math.max(2, options.batchThreads() / 2), 512);
        ThreadPoolExecutor reconnectExecutor = fixedPool("stress-reconnect", 2, 512);

        CollectionScheduler scheduler = null;
        CountingCollectedDataProcessor processor = null;
        RuntimeDiagnostics beforeDiagnostics = RuntimeDiagnostics.capture();

        try {
            CollectorProperties properties = collectorProperties(options);
            List<DeviceScenario> devices = createDevices(pointCount, options.pointsPerDevice());
            CollectionManager collectionManager = mock(CollectionManager.class);
            ConfigManager configManager = mock(ConfigManager.class);
            CollectionStatistics collectionStatistics = mock(CollectionStatistics.class);
            CollectionHealthReporter healthTracker = mock(CollectionHealthReporter.class);
            CollectionTaskGuard collectionTaskGuard = new CollectionTaskGuard();
            SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
            PerformanceMonitor performanceMonitor = new PerformanceMonitor();
            ProtocolBatchStrategy protocolBatchStrategy = new ProtocolBatchStrategy();
            DeviceBatchPlanner batchPlanner = new DeviceBatchPlanner(
                    configManager,
                    protocolBatchStrategy,
                    ProtocolDescriptorTestProviders.registry(),
                    performanceMonitor
            );
            processor = new CountingCollectedDataProcessor(properties, performanceMonitor);
            ReconnectCoordinator reconnectCoordinator = new ReconnectCoordinator(
                    collectionManager,
                    properties,
                    collectionTaskGuard,
                    runtimeState,
                    reconnectExecutor
            );
            DeviceBatchExecutor batchExecutor = new DeviceBatchExecutor(
                    collectionManager,
                    configManager,
                    collectionStatistics,
                    properties,
                    processor,
                    collectionTaskGuard,
                    runtimeState,
                    performanceMonitor,
                    reconnectCoordinator,
                    batchDispatcher,
                    asyncCollectorPool,
                    dataProcessorPool
            );
            DeviceLifecycleCoordinator lifecycleCoordinator = new DeviceLifecycleCoordinator(
                    collectionManager,
                    configManager,
                    collectionStatistics,
                    properties,
                    healthTracker,
                    batchPlanner,
                    protocolBatchStrategy,
                    collectionTaskGuard,
                    new PointRuntimeStateService(),
                    runtimeState,
                    performanceMonitor,
                    batchExecutor,
                    reconnectCoordinator,
                    deviceStartExecutor
            );
            scheduler = new CollectionScheduler(
                    collectionManager,
                    configManager,
                    collectionStatistics,
                    properties,
                    null,
                    runtimeState,
                    performanceMonitor,
                    lifecycleCoordinator,
                    batchExecutor,
                    reconnectCoordinator,
                    timeSliceScheduler
            );

            Set<String> connectedDevices = ConcurrentHashMap.newKeySet();
            configureSyntheticRuntimeMocks(collectionManager, configManager, devices, connectedDevices, options);
            runtimeState.initializeTimeSlices(options.sliceCount(), options.sliceWaitMs());

            long scenarioBegin = System.nanoTime();
            long startBegin = System.nanoTime();
            for (DeviceScenario device : devices) {
                assertTrue(scheduler.startDevice(device.deviceId()), "stress device failed to start: " + device.deviceId());
            }
            long startMs = elapsedMillis(startBegin);
            int batchCount = safeInt(runtimeState.getTotalTaskCount());
            ExecutorProbe executorProbe = new ExecutorProbe();
            List<Long> roundDurations = new ArrayList<>(options.rounds());

            for (int round = 0; round < options.rounds(); round++) {
                long expectedAfterRound = processor.processedPoints() + pointCount;
                long cycleBegin = System.nanoTime();
                long revision = runtimeState.getTimeSliceRevision();
                for (int slice = 0; slice < options.sliceCount(); slice++) {
                    scheduler.executeTimeSlice(slice, revision);
                    executorProbe.sample(batchDispatcher, asyncCollectorPool, dataProcessorPool, batchExecutor);
                }
                waitUntilProcessed(processor, expectedAfterRound, executorProbe,
                        batchDispatcher, asyncCollectorPool, dataProcessorPool, batchExecutor);
                waitUntilIdle(batchExecutor, batchDispatcher, asyncCollectorPool, dataProcessorPool, executorProbe);
                roundDurations.add(Math.max(1L, elapsedMillis(cycleBegin)));
            }

            scheduler.stopAllDevices();
            waitUntil(() -> runtimeState.getActiveDeviceIds().isEmpty()
                    && batchExecutor.getTotalInFlightFutureCountForTest() == 0, 10_000L);
            executorProbe.sample(batchDispatcher, asyncCollectorPool, dataProcessorPool, batchExecutor);

            long durationMs = Math.max(1L, elapsedMillis(scenarioBegin));
            PerformanceStatsSnapshot snapshot = scheduler.getPerformanceSnapshot();
            RuntimeDiagnostics afterDiagnostics = RuntimeDiagnostics.capture();

            return new StressResult(
                    pointCount,
                    devices.size(),
                    options.rounds(),
                    batchCount,
                    startMs,
                    durationMs,
                    processor.processedPoints(),
                    processor.failedPoints(),
                    processor.processCalls(),
                    pointCount * (long) options.rounds(),
                    pointsPerSecond(processor.processedPoints(), durationMs),
                    percentile(roundDurations, 0.50D),
                    percentile(roundDurations, 0.95D),
                    percentile(roundDurations, 0.99D),
                    roundDurations.stream().max(Comparator.naturalOrder()).orElse(0L),
                    snapshot.getBatchDispatchRejectedCount(),
                    snapshot.getCollectRejectedCount(),
                    snapshot.getProcessRejectedCount(),
                    snapshot.getReconnectAttemptCount(),
                    snapshot.getReconnectFailureCount(),
                    snapshot.getOverloadedSlices().size(),
                    batchExecutor.getTotalInFlightFutureCountForTest(),
                    runtimeState.getActiveDeviceIds().size(),
                    executorProbe.summary(batchDispatcher, asyncCollectorPool, dataProcessorPool),
                    beforeDiagnostics.diff(afterDiagnostics)
            );
        } finally {
            try {
                if (scheduler != null) {
                    scheduler.destroy();
                }
            } finally {
                shutdown(timeSliceScheduler);
                shutdown(batchDispatcher);
                shutdown(asyncCollectorPool);
                shutdown(dataProcessorPool);
                shutdown(deviceStartExecutor);
                shutdown(reconnectExecutor);
            }
        }
    }

    private void configureSyntheticRuntimeMocks(CollectionManager collectionManager,
                                                ConfigManager configManager,
                                                List<DeviceScenario> devices,
                                                Set<String> connectedDevices,
                                                StressOptions options) throws Exception {
        List<String> deviceIds = devices.stream().map(DeviceScenario::deviceId).toList();
        when(configManager.getAllDeviceIds()).thenReturn(deviceIds);
        when(collectionManager.getAllDeviceIds()).thenReturn(deviceIds);
        when(collectionManager.getCollector(anyString())).thenReturn(null);
        when(collectionManager.isDeviceConnected(anyString()))
                .thenAnswer(invocation -> connectedDevices.contains(invocation.getArgument(0)));
        doAnswer(invocation -> {
            connectedDevices.add(invocation.getArgument(0));
            return null;
        }).when(collectionManager).connectDevice(anyString());
        doAnswer(invocation -> {
            connectedDevices.remove(invocation.getArgument(0));
            return null;
        }).when(collectionManager).disconnectDevice(anyString());
        doAnswer(invocation -> {
            connectedDevices.remove(invocation.getArgument(0));
            return null;
        }).when(collectionManager).cleanupDevice(anyString());
        doAnswer(invocation -> null).when(collectionManager).registerDevice(org.mockito.ArgumentMatchers.any(DeviceInfo.class));
        doAnswer(invocation -> null).when(collectionManager).rebuildReadPlans(anyString(), anyList());

        for (DeviceScenario device : devices) {
            when(configManager.getDevice(device.deviceId())).thenReturn(device.deviceInfo());
            when(configManager.getDataPoints(device.deviceId())).thenReturn(device.points());
            when(configManager.getDataPointsAndAdaptiveConfig(device.deviceId())).thenReturn(device.points());
            when(configManager.getConnectionConfig(device.deviceId())).thenReturn(device.connection());
        }

        when(collectionManager.readPoints(anyString(), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<DataPoint> batch = invocation.getArgument(1);
            if (options.readDelayMs() > 0) {
                TimeUnit.MILLISECONDS.sleep(options.readDelayMs());
            }
            Map<String, Object> values = new HashMap<>(Math.max(16, batch.size() * 2));
            for (DataPoint point : batch) {
                values.put(point.getPointId(), point.getId());
            }
            return values;
        });
    }

    private void waitUntilProcessed(CountingCollectedDataProcessor processor,
                                    long expectedPoints,
                                    ExecutorProbe executorProbe,
                                    ThreadPoolExecutor batchDispatcher,
                                    ThreadPoolExecutor asyncCollectorPool,
                                    ThreadPoolExecutor dataProcessorPool,
                                    DeviceBatchExecutor batchExecutor) throws InterruptedException {
        waitUntil(() -> {
            executorProbe.sample(batchDispatcher, asyncCollectorPool, dataProcessorPool, batchExecutor);
            return processor.processedPoints() >= expectedPoints;
        }, WAIT_TIMEOUT_MS);
    }

    private void waitUntilIdle(DeviceBatchExecutor batchExecutor,
                               ThreadPoolExecutor batchDispatcher,
                               ThreadPoolExecutor asyncCollectorPool,
                               ThreadPoolExecutor dataProcessorPool,
                               ExecutorProbe executorProbe) throws InterruptedException {
        waitUntil(() -> {
            executorProbe.sample(batchDispatcher, asyncCollectorPool, dataProcessorPool, batchExecutor);
            return batchExecutor.getTotalInFlightFutureCountForTest() == 0
                    && batchDispatcher.getQueue().isEmpty()
                    && asyncCollectorPool.getQueue().isEmpty()
                    && dataProcessorPool.getQueue().isEmpty();
        }, WAIT_TIMEOUT_MS);
    }

    private void waitUntil(Condition condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.isSatisfied());
    }

    private List<DeviceScenario> createDevices(int pointCount, int pointsPerDevice) {
        int deviceCount = Math.max(1, (int) Math.ceil(pointCount / (double) Math.max(1, pointsPerDevice)));
        List<DeviceScenario> devices = new ArrayList<>(deviceCount);
        int remaining = pointCount;
        long pointSequence = 0;
        for (int deviceIndex = 0; deviceIndex < deviceCount; deviceIndex++) {
            int currentPointCount = Math.min(pointsPerDevice, remaining);
            String deviceId = "stress-device-" + String.format(Locale.ROOT, "%03d", deviceIndex);
            DeviceInfo device = createDevice(deviceId);
            DeviceConnection connection = createConnection(deviceId);
            List<DataPoint> points = createPoints(deviceId, pointSequence, currentPointCount);
            pointSequence += currentPointCount;
            remaining -= currentPointCount;
            devices.add(new DeviceScenario(deviceId, device, connection, points));
        }
        return devices;
    }

    private List<DataPoint> createPoints(String deviceId, long startSequence, int pointCount) {
        List<DataPoint> points = new ArrayList<>(pointCount);
        for (int i = 0; i < pointCount; i++) {
            long sequence = startSequence + i;
            DataPoint point = new DataPoint();
            point.setId(sequence);
            point.setDeviceId(deviceId);
            point.setPointId(deviceId + "-p" + i);
            point.setPointCode("p" + i);
            point.setPointName("stress-point-" + sequence);
            point.setAddress(String.valueOf(40001 + i));
            point.setDataType("INT");
            point.setStatus(1);
            point.setCollectionMode("POLLING");
            point.setCacheEnabled(0);
            points.add(point);
        }
        return points;
    }

    private DeviceInfo createDevice(String deviceId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName("Synthetic Stress Device " + deviceId);
        device.setProtocolType(PROTOCOL);
        device.setConnectionType(PROTOCOL);
        device.setStatus("ONLINE");
        return device;
    }

    private DeviceConnection createConnection(String deviceId) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType(PROTOCOL);
        connection.setHost("127.0.0.1");
        connection.setPort(502);
        connection.setConnectTimeout(60_000);
        connection.setReadTimeout(60_000);
        connection.setTimeout(60_000);
        return connection;
    }

    private CollectorProperties collectorProperties(StressOptions options) {
        CollectorProperties properties = new CollectorProperties();
        properties.getAdaptiveCollection().setEnabled(false);
        properties.getScheduler().setCollectTimeoutMs(60_000);
        properties.getScheduler().setDeviceStartTimeoutMs(60_000);
        properties.getScheduler().setMinTimeSliceIntervalMs(1);
        properties.getScheduler().setInitialTimeSliceCount(options.sliceCount());
        properties.getScheduler().setMaxTimeSliceCount(Math.max(options.sliceCount(), 128));
        properties.getScheduler().setInitialTimeSliceIntervalMs(options.sliceWaitMs());
        properties.getScheduler().setDefaultTimeSliceIntervalMs(options.sliceWaitMs());
        properties.getScheduler().setReconnectBaseDelayMs(100);
        properties.getScheduler().setReconnectMaxDelayMs(1_000);
        return properties;
    }

    private ThreadPoolExecutor fixedPool(String prefix, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> daemonThread(runnable, prefix),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private Thread daemonThread(Runnable runnable, String prefix) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName(prefix + "-" + thread.getId());
        return thread;
    }

    private void writeReports(List<StressResult> results, StressOptions options) throws IOException {
        Path dir = Path.of("target", "stress");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("collection-throughput.csv"), toCsv(results), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("collection-throughput-report.md"), toMarkdown(results, options), StandardCharsets.UTF_8);
    }

    private String toCsv(List<StressResult> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("pointCount,deviceCount,rounds,batchCount,startMs,durationMs,processedPoints,failedPoints,")
                .append("processCalls,pointsPerSecond,p50Ms,p95Ms,p99Ms,maxRoundMs,batchRejected,collectRejected,")
                .append("processRejected,reconnectAttempts,reconnectFailures,timeSliceTimeouts,remainingInflight,")
                .append("maxQueueSize,maxInflight,heapDeltaMb,gcCountDelta,gcTimeMsDelta,processCpuLoad\n");
        for (StressResult result : results) {
            builder.append(result.pointCount()).append(',')
                    .append(result.deviceCount()).append(',')
                    .append(result.rounds()).append(',')
                    .append(result.batchCount()).append(',')
                    .append(result.startMs()).append(',')
                    .append(result.durationMs()).append(',')
                    .append(result.processedPoints()).append(',')
                    .append(result.failedPoints()).append(',')
                    .append(result.processCalls()).append(',')
                    .append(format(result.pointsPerSecond())).append(',')
                    .append(result.p50Ms()).append(',')
                    .append(result.p95Ms()).append(',')
                    .append(result.p99Ms()).append(',')
                    .append(result.maxRoundMs()).append(',')
                    .append(result.batchRejected()).append(',')
                    .append(result.collectRejected()).append(',')
                    .append(result.processRejected()).append(',')
                    .append(result.reconnectAttempts()).append(',')
                    .append(result.reconnectFailures()).append(',')
                    .append(result.timeSliceTimeouts()).append(',')
                    .append(result.remainingInFlightFutures()).append(',')
                    .append(result.executorSummary().maxQueueSize()).append(',')
                    .append(result.executorSummary().maxInFlightFutures()).append(',')
                    .append(format(result.runtimeDiagnostics().heapDeltaMb())).append(',')
                    .append(result.runtimeDiagnostics().gcCountForReport()).append(',')
                    .append(result.runtimeDiagnostics().gcTimeMsForReport()).append(',')
                    .append(format(result.runtimeDiagnostics().processCpuLoad()))
                    .append('\n');
        }
        return builder.toString();
    }

    private String toMarkdown(List<StressResult> results, StressOptions options) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Collection Runtime Performance Baseline\n\n");
        builder.append("Generated at: ").append(LocalDateTime.now()).append("\n\n");
        builder.append("Options:\n\n");
        builder.append("- pointCounts: ").append(options.pointCounts()).append('\n');
        builder.append("- pointsPerDevice: ").append(options.pointsPerDevice()).append('\n');
        builder.append("- rounds: ").append(options.rounds()).append('\n');
        builder.append("- sliceCount: ").append(options.sliceCount()).append('\n');
        builder.append("- sliceWaitMs: ").append(options.sliceWaitMs()).append('\n');
        builder.append("- batchThreads: ").append(options.batchThreads()).append('\n');
        builder.append("- collectThreads: ").append(options.collectThreads()).append('\n');
        builder.append("- processThreads: ").append(options.processThreads()).append('\n');
        builder.append("- readDelayMs: ").append(options.readDelayMs()).append("\n\n");
        builder.append("| Points | Devices | Rounds | Batches | Duration ms | Points/s | P50 ms | P95 ms | P99 ms | Failed | Rejected | Max queue | Max in-flight | Remaining in-flight | Heap delta MB |\n");
        builder.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (StressResult result : results) {
            builder.append("| ").append(result.pointCount())
                    .append(" | ").append(result.deviceCount())
                    .append(" | ").append(result.rounds())
                    .append(" | ").append(result.batchCount())
                    .append(" | ").append(result.durationMs())
                    .append(" | ").append(format(result.pointsPerSecond()))
                    .append(" | ").append(result.p50Ms())
                    .append(" | ").append(result.p95Ms())
                    .append(" | ").append(result.p99Ms())
                    .append(" | ").append(result.failedPoints())
                    .append(" | ").append(result.rejectedTasks())
                    .append(" | ").append(result.executorSummary().maxQueueSize())
                    .append(" | ").append(result.executorSummary().maxInFlightFutures())
                    .append(" | ").append(result.remainingInFlightFutures())
                    .append(" | ").append(format(result.runtimeDiagnostics().heapDeltaMb()))
                    .append(" |\n");
        }
        builder.append("\nNotes:\n\n");
        builder.append("This benchmark uses a synthetic in-memory collector path and excludes real PLC/network latency, Redis, history storage, and cloud report target I/O.\n");
        return builder.toString();
    }

    private void printSummary(List<StressResult> results, StressOptions options) {
        System.out.println("CollectionThroughputStressIT options=" + options);
        for (StressResult result : results) {
            System.out.printf(Locale.ROOT,
                    "scenario=throughput points=%d devices=%d rounds=%d batches=%d durationMs=%d pointsPerSecond=%.2f p50Ms=%d p95Ms=%d p99Ms=%d failed=%d rejected=%d maxQueue=%d maxInflight=%d remainingInflight=%d heapDeltaMb=%.2f gcCountDelta=%d gcTimeMsDelta=%d processCpuLoad=%.2f%n",
                    result.pointCount(),
                    result.deviceCount(),
                    result.rounds(),
                    result.batchCount(),
                    result.durationMs(),
                    result.pointsPerSecond(),
                    result.p50Ms(),
                    result.p95Ms(),
                    result.p99Ms(),
                    result.failedPoints(),
                    result.rejectedTasks(),
                    result.executorSummary().maxQueueSize(),
                    result.executorSummary().maxInFlightFutures(),
                    result.remainingInFlightFutures(),
                    result.runtimeDiagnostics().heapDeltaMb(),
                    result.runtimeDiagnostics().gcCountForReport(),
                    result.runtimeDiagnostics().gcTimeMsForReport(),
                    result.runtimeDiagnostics().processCpuLoad());
        }
    }

    private double pointsPerSecond(long pointCount, long durationMs) {
        return pointCount * 1000.0 / Math.max(1L, durationMs);
    }

    private long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private long elapsedMillis(long beginNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginNanos);
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static final class CountingCollectedDataProcessor extends CollectedDataProcessor {
        private final LongAdder processedPoints = new LongAdder();
        private final LongAdder failedPoints = new LongAdder();
        private final LongAdder processCalls = new LongAdder();
        private final PerformanceMonitor performanceMonitor;

        private CountingCollectedDataProcessor(CollectorProperties collectorProperties, PerformanceMonitor performanceMonitor) {
            super(collectorProperties, new PointRuntimeStateService(), performanceMonitor);
            this.performanceMonitor = performanceMonitor;
        }

        @Override
        void process(String deviceId,
                     List<DataPoint> points,
                     Map<String, Object> values) {
            processCalls.increment();
            for (DataPoint point : points) {
                if (values.containsKey(point.getPointId()) && values.get(point.getPointId()) != null) {
                    processedPoints.increment();
                    performanceMonitor.recordDataProcessed(deviceId);
                } else {
                    failedPoints.increment();
                }
            }
        }

        long processedPoints() {
            return processedPoints.sum();
        }

        long failedPoints() {
            return failedPoints.sum();
        }

        long processCalls() {
            return processCalls.sum();
        }
    }

    private static final class ExecutorProbe {
        private int maxBatchQueueSize;
        private int maxCollectQueueSize;
        private int maxProcessQueueSize;
        private int maxInFlightFutures;

        private void sample(ThreadPoolExecutor batchDispatcher,
                            ThreadPoolExecutor asyncCollectorPool,
                            ThreadPoolExecutor dataProcessorPool,
                            DeviceBatchExecutor batchExecutor) {
            maxBatchQueueSize = Math.max(maxBatchQueueSize, batchDispatcher.getQueue().size());
            maxCollectQueueSize = Math.max(maxCollectQueueSize, asyncCollectorPool.getQueue().size());
            maxProcessQueueSize = Math.max(maxProcessQueueSize, dataProcessorPool.getQueue().size());
            maxInFlightFutures = Math.max(maxInFlightFutures, batchExecutor.getTotalInFlightFutureCountForTest());
        }

        private ExecutorSummary summary(ThreadPoolExecutor batchDispatcher,
                                        ThreadPoolExecutor asyncCollectorPool,
                                        ThreadPoolExecutor dataProcessorPool) {
            return new ExecutorSummary(
                    maxBatchQueueSize,
                    maxCollectQueueSize,
                    maxProcessQueueSize,
                    Math.max(maxBatchQueueSize, Math.max(maxCollectQueueSize, maxProcessQueueSize)),
                    maxInFlightFutures,
                    ExecutorSnapshot.from("batch", batchDispatcher),
                    ExecutorSnapshot.from("collect", asyncCollectorPool),
                    ExecutorSnapshot.from("process", dataProcessorPool)
            );
        }
    }

    private record StressOptions(List<Integer> pointCounts,
                                 int pointsPerDevice,
                                 int rounds,
                                 int sliceCount,
                                 int sliceWaitMs,
                                 int batchThreads,
                                 int collectThreads,
                                 int processThreads,
                                 int readDelayMs,
                                 long scenarioTimeoutMs) {

        private static StressOptions fromSystemProperties() {
            int cpu = Math.max(2, Runtime.getRuntime().availableProcessors());
            return new StressOptions(
                    parsePointCounts(System.getProperty("collector.stress.pointCounts", "10000,50000,100000")),
                    Integer.getInteger("collector.stress.pointsPerDevice", 1000),
                    Integer.getInteger("collector.stress.rounds", 3),
                    Integer.getInteger("collector.stress.sliceCount", Math.min(16, Math.max(4, cpu))),
                    Integer.getInteger("collector.stress.sliceWaitMs", 60_000),
                    Integer.getInteger("collector.stress.batchThreads", cpu),
                    Integer.getInteger("collector.stress.collectThreads", cpu * 4),
                    Integer.getInteger("collector.stress.processThreads", cpu),
                    Integer.getInteger("collector.stress.readDelayMs", 0),
                    Long.getLong("collector.stress.scenarioTimeoutMs", WAIT_TIMEOUT_MS)
            );
        }

        private static List<Integer> parsePointCounts(String raw) {
            List<Integer> counts = new ArrayList<>();
            for (String token : raw.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    counts.add(Integer.parseInt(trimmed));
                }
            }
            if (counts.isEmpty()) {
                counts.add(10_000);
            }
            return counts;
        }
    }

    private record DeviceScenario(String deviceId,
                                  DeviceInfo deviceInfo,
                                  DeviceConnection connection,
                                  List<DataPoint> points) {
    }

    private record StressResult(int pointCount,
                                int deviceCount,
                                int rounds,
                                int batchCount,
                                long startMs,
                                long durationMs,
                                long processedPoints,
                                long failedPoints,
                                long processCalls,
                                long expectedProcessedPoints,
                                double pointsPerSecond,
                                long p50Ms,
                                long p95Ms,
                                long p99Ms,
                                long maxRoundMs,
                                long batchRejected,
                                long collectRejected,
                                long processRejected,
                                long reconnectAttempts,
                                long reconnectFailures,
                                long timeSliceTimeouts,
                                int remainingInFlightFutures,
                                int remainingActiveDevices,
                                ExecutorSummary executorSummary,
                                RuntimeDiagnostics runtimeDiagnostics) {

        private long rejectedTasks() {
            return batchRejected + collectRejected + processRejected;
        }
    }

    private record ExecutorSummary(int maxBatchQueueSize,
                                   int maxCollectQueueSize,
                                   int maxProcessQueueSize,
                                   int maxQueueSize,
                                   int maxInFlightFutures,
                                   ExecutorSnapshot batch,
                                   ExecutorSnapshot collect,
                                   ExecutorSnapshot process) {
    }

    private record ExecutorSnapshot(String name,
                                    int activeCount,
                                    int poolSize,
                                    int queueSize,
                                    long completedTaskCount) {
        private static ExecutorSnapshot from(String name, ThreadPoolExecutor executor) {
            return new ExecutorSnapshot(
                    name,
                    executor.getActiveCount(),
                    executor.getPoolSize(),
                    executor.getQueue().size(),
                    executor.getCompletedTaskCount());
        }
    }

    private record RuntimeDiagnostics(long heapUsedBytes,
                                      long gcCount,
                                      long gcTimeMs,
                                      double processCpuLoad) {
        private static RuntimeDiagnostics capture() {
            Runtime runtime = Runtime.getRuntime();
            long heapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
            long gcCount = 0L;
            long gcTimeMs = 0L;
            for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                long count = gcBean.getCollectionCount();
                long time = gcBean.getCollectionTime();
                if (count > 0) {
                    gcCount += count;
                }
                if (time > 0) {
                    gcTimeMs += time;
                }
            }
            return new RuntimeDiagnostics(heapUsedBytes, gcCount, gcTimeMs, readProcessCpuLoad());
        }

        private static double readProcessCpuLoad() {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean osBean) {
                double value = osBean.getProcessCpuLoad();
                return value < 0D ? -1D : value * 100D;
            }
            return -1D;
        }

        private RuntimeDiagnostics diff(RuntimeDiagnostics after) {
            return new RuntimeDiagnostics(
                    after.heapUsedBytes - heapUsedBytes,
                    after.gcCount - gcCount,
                    after.gcTimeMs - gcTimeMs,
                    after.processCpuLoad
            );
        }

        private double heapDeltaMb() {
            return heapUsedBytes / 1024.0 / 1024.0;
        }

        private long gcCountForReport() {
            return gcCount;
        }

        private long gcTimeMsForReport() {
            return gcTimeMs;
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}
