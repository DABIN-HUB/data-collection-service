package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import com.wangbin.collector.monitor.health.CollectionServiceHealthTracker;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * Synthetic throughput benchmark for the scheduler and in-process collection path.
 *
 * Run manually:
 * mvn -q "-Dtest=CollectionThroughputStressIT" test
 */
class CollectionThroughputStressIT {

    private static final String DEVICE_ID = "stress-device";
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
            assertEquals(result.pointCount(), result.processedPoints(),
                    "not all synthetic points were processed for pointCount=" + result.pointCount());
        }
    }

    private StressResult runScenario(int pointCount, StressOptions options) throws Exception {
        ScheduledExecutorService timeSliceScheduler = Executors.newSingleThreadScheduledExecutor();
        ThreadPoolExecutor batchDispatcher = fixedPool("stress-batch", options.batchThreads(), 20_000);
        ThreadPoolExecutor asyncCollectorPool = fixedPool("stress-collect", options.collectThreads(), 40_000);
        ThreadPoolExecutor dataProcessorPool = fixedPool("stress-process", options.processThreads(), 40_000);

        CollectionScheduler scheduler = new CollectionScheduler(
                timeSliceScheduler,
                batchDispatcher,
                asyncCollectorPool,
                dataProcessorPool
        );
        CountingCollectedDataProcessor processor = null;
        long usedMemoryBefore = usedMemoryBytes();

        try {
            CollectorProperties properties = collectorProperties();
            List<DataPoint> points = createPoints(pointCount);
            DeviceInfo device = createDevice();
            DeviceConnection connection = createConnection();

            CollectionManager collectionManager = mock(CollectionManager.class);
            ConfigManager configManager = mock(ConfigManager.class);
            CollectionStatistics collectionStatistics = mock(CollectionStatistics.class);
            CollectionServiceHealthTracker healthTracker = mock(CollectionServiceHealthTracker.class);
            CollectionTaskGuard collectionTaskGuard = new CollectionTaskGuard();
            ProtocolBatchStrategy protocolBatchStrategy = new ProtocolBatchStrategy();
            DeviceBatchPlanner batchPlanner = new DeviceBatchPlanner(
                    configManager,
                    protocolBatchStrategy,
                    new ProtocolDescriptorRegistry()
            );
            processor = new CountingCollectedDataProcessor(properties);

            when(configManager.getDevice(DEVICE_ID)).thenReturn(device);
            when(configManager.getDataPoints(DEVICE_ID)).thenReturn(points);
            when(configManager.getDataPointsAndAdaptiveConfig(DEVICE_ID)).thenReturn(points);
            when(configManager.getConnectionConfig(DEVICE_ID)).thenReturn(connection);
            when(collectionManager.isDeviceConnected(DEVICE_ID)).thenReturn(true);
            when(collectionManager.getCollector(DEVICE_ID)).thenReturn(null);
            doAnswer(invocation -> null).when(collectionManager).registerDevice(device);
            doAnswer(invocation -> null).when(collectionManager).connectDevice(DEVICE_ID);
            doAnswer(invocation -> null).when(collectionManager).disconnectDevice(DEVICE_ID);
            doAnswer(invocation -> null).when(collectionManager).cleanupDevice(anyString());
            doAnswer(invocation -> null).when(collectionManager).rebuildReadPlans(eq(DEVICE_ID), anyList());
            when(collectionManager.readPoints(eq(DEVICE_ID), anyList())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                List<DataPoint> batch = invocation.getArgument(1);
                Map<String, Object> values = new HashMap<>(Math.max(16, batch.size() * 2));
                if (options.readDelayMs() > 0) {
                    TimeUnit.MILLISECONDS.sleep(options.readDelayMs());
                }
                for (DataPoint point : batch) {
                    values.put(point.getPointId(), point.getId());
                }
                return values;
            });

            injectSchedulerDependencies(
                    scheduler,
                    collectionManager,
                    configManager,
                    collectionStatistics,
                    healthTracker,
                    batchPlanner,
                    protocolBatchStrategy,
                    processor,
                    collectionTaskGuard,
                    properties,
                    options.sliceCount(),
                    options.sliceWaitMs()
            );

            long startBegin = System.nanoTime();
            assertTrue(scheduler.startDevice(DEVICE_ID), "stress device failed to start");
            long startMs = elapsedMillis(startBegin);
            int batchCount = batchCount(scheduler);

            long cycleBegin = System.nanoTime();
            long revision = ((AtomicLong) ReflectionTestUtils.getField(scheduler, "timeSliceRevision")).get();
            for (int slice = 0; slice < options.sliceCount(); slice++) {
                ReflectionTestUtils.invokeMethod(scheduler, "executeTimeSlice", slice, revision);
            }
            waitUntilProcessed(processor, pointCount);
            long cycleMs = Math.max(1L, elapsedMillis(cycleBegin));
            long usedMemoryAfter = usedMemoryBytes();

            return new StressResult(
                    pointCount,
                    batchCount,
                    startMs,
                    cycleMs,
                    processor.processedPoints(),
                    processor.processCalls(),
                    pointsPerSecond(pointCount, cycleMs),
                    (usedMemoryAfter - usedMemoryBefore) / 1024.0 / 1024.0
            );
        } finally {
            try {
                scheduler.destroy();
            } catch (Exception ignored) {
                shutdown(timeSliceScheduler);
                shutdown(batchDispatcher);
                shutdown(asyncCollectorPool);
                shutdown(dataProcessorPool);
            }
        }
    }

    private void injectSchedulerDependencies(CollectionScheduler scheduler,
                                             CollectionManager collectionManager,
                                             ConfigManager configManager,
                                             CollectionStatistics collectionStatistics,
                                             CollectionServiceHealthTracker healthTracker,
                                             DeviceBatchPlanner batchPlanner,
                                             ProtocolBatchStrategy protocolBatchStrategy,
                                             CollectedDataProcessor processor,
                                             CollectionTaskGuard collectionTaskGuard,
                                             CollectorProperties properties,
                                             int sliceCount,
                                             int sliceWaitMs) {
        ReflectionTestUtils.setField(scheduler, "collectionManager", collectionManager);
        ReflectionTestUtils.setField(scheduler, "configManager", configManager);
        ReflectionTestUtils.setField(scheduler, "collectionStatistics", collectionStatistics);
        ReflectionTestUtils.setField(scheduler, "collectionServiceHealthTracker", healthTracker);
        ReflectionTestUtils.setField(scheduler, "deviceBatchPlanner", batchPlanner);
        ReflectionTestUtils.setField(scheduler, "protocolBatchStrategy", protocolBatchStrategy);
        ReflectionTestUtils.setField(scheduler, "collectedDataProcessor", processor);
        ReflectionTestUtils.setField(scheduler, "collectionTaskGuard", collectionTaskGuard);
        ReflectionTestUtils.setField(scheduler, "collectorProperties", properties);

        ((AtomicInteger) ReflectionTestUtils.getField(scheduler, "timeSliceCount")).set(sliceCount);
        ((AtomicInteger) ReflectionTestUtils.getField(scheduler, "timeSliceInterval")).set(sliceWaitMs);
        ((AtomicLong) ReflectionTestUtils.getField(scheduler, "timeSliceRevision")).set(1L);
        ReflectionTestUtils.invokeMethod(scheduler, "resetTimeSliceTaskBuckets", sliceCount);
    }

    private void waitUntilProcessed(CountingCollectedDataProcessor processor, int expectedPoints) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (processor.processedPoints() >= expectedPoints) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private int batchCount(CollectionScheduler scheduler) {
        @SuppressWarnings("unchecked")
        Map<Integer, List<DeviceBatchTask>> tasks =
                (Map<Integer, List<DeviceBatchTask>>) ReflectionTestUtils.getField(scheduler, "timeSliceTasks");
        return tasks.values().stream().mapToInt(List::size).sum();
    }

    private List<DataPoint> createPoints(int pointCount) {
        List<DataPoint> points = new ArrayList<>(pointCount);
        for (int i = 0; i < pointCount; i++) {
            DataPoint point = new DataPoint();
            point.setId((long) i);
            point.setDeviceId(DEVICE_ID);
            point.setPointId("p" + i);
            point.setPointCode("p" + i);
            point.setPointName("stress-point-" + i);
            point.setAddress(String.valueOf(40001 + i));
            point.setDataType("INT");
            point.setStatus(1);
            point.setCollectionMode("POLLING");
            point.setCacheEnabled(0);
            points.add(point);
        }
        return points;
    }

    private DeviceInfo createDevice() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceName("Synthetic Stress Device");
        device.setProtocolType(PROTOCOL);
        device.setConnectionType(PROTOCOL);
        device.setStatus("ONLINE");
        return device;
    }

    private DeviceConnection createConnection() {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(DEVICE_ID);
        connection.setConnectionType(PROTOCOL);
        connection.setHost("127.0.0.1");
        connection.setPort(502);
        connection.setConnectTimeout(60_000);
        connection.setReadTimeout(60_000);
        connection.setTimeout(60_000);
        return connection;
    }

    private CollectorProperties collectorProperties() {
        CollectorProperties properties = new CollectorProperties();
        properties.getAdaptiveCollection().setEnabled(false);
        properties.getScheduler().setCollectTimeoutMs(60_000);
        properties.getScheduler().setDeviceStartTimeoutMs(60_000);
        properties.getScheduler().setMinTimeSliceIntervalMs(1);
        properties.getScheduler().setInitialTimeSliceCount(1);
        properties.getScheduler().setMaxTimeSliceCount(128);
        properties.getScheduler().setInitialTimeSliceIntervalMs(60_000);
        properties.getScheduler().setDefaultTimeSliceIntervalMs(60_000);
        return properties;
    }

    private ThreadPoolExecutor fixedPool(String prefix, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setName(prefix + "-" + thread.getId());
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private void writeReports(List<StressResult> results, StressOptions options) throws IOException {
        Path dir = Path.of("target", "stress");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("collection-throughput.csv"), toCsv(results), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("collection-throughput-report.md"), toMarkdown(results, options), StandardCharsets.UTF_8);
    }

    private String toCsv(List<StressResult> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("pointCount,batchCount,startMs,cycleMs,processedPoints,processCalls,pointsPerSecond,memoryDeltaMb\n");
        for (StressResult result : results) {
            builder.append(result.pointCount()).append(',')
                    .append(result.batchCount()).append(',')
                    .append(result.startMs()).append(',')
                    .append(result.cycleMs()).append(',')
                    .append(result.processedPoints()).append(',')
                    .append(result.processCalls()).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", result.pointsPerSecond())).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", result.memoryDeltaMb()))
                    .append('\n');
        }
        return builder.toString();
    }

    private String toMarkdown(List<StressResult> results, StressOptions options) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Collection Throughput Stress Report\n\n");
        builder.append("Generated at: ").append(LocalDateTime.now()).append("\n\n");
        builder.append("Options:\n\n");
        builder.append("- sliceCount: ").append(options.sliceCount()).append('\n');
        builder.append("- sliceWaitMs: ").append(options.sliceWaitMs()).append('\n');
        builder.append("- batchThreads: ").append(options.batchThreads()).append('\n');
        builder.append("- collectThreads: ").append(options.collectThreads()).append('\n');
        builder.append("- processThreads: ").append(options.processThreads()).append('\n');
        builder.append("- readDelayMs: ").append(options.readDelayMs()).append("\n\n");
        builder.append("| Points | Batches | Start ms | Cycle ms | Processed | Process calls | Points/s | Memory delta MB |\n");
        builder.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (StressResult result : results) {
            builder.append("| ").append(result.pointCount())
                    .append(" | ").append(result.batchCount())
                    .append(" | ").append(result.startMs())
                    .append(" | ").append(result.cycleMs())
                    .append(" | ").append(result.processedPoints())
                    .append(" | ").append(result.processCalls())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", result.pointsPerSecond()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", result.memoryDeltaMb()))
                    .append(" |\n");
        }
        builder.append("\nNotes:\n\n");
        builder.append("This benchmark uses a synthetic in-memory collector path. It does not include real PLC/network latency, Redis, history storage, or report target I/O.\n");
        return builder.toString();
    }

    private void printSummary(List<StressResult> results, StressOptions options) {
        System.out.println("CollectionThroughputStressIT options=" + options);
        for (StressResult result : results) {
            System.out.printf(Locale.ROOT,
                    "points=%d batches=%d startMs=%d cycleMs=%d processed=%d pointsPerSecond=%.2f memoryDeltaMb=%.2f%n",
                    result.pointCount(),
                    result.batchCount(),
                    result.startMs(),
                    result.cycleMs(),
                    result.processedPoints(),
                    result.pointsPerSecond(),
                    result.memoryDeltaMb());
        }
    }

    private double pointsPerSecond(int pointCount, long cycleMs) {
        return pointCount * 1000.0 / Math.max(1L, cycleMs);
    }

    private long elapsedMillis(long beginNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginNanos);
    }

    private long usedMemoryBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private void shutdown(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static final class CountingCollectedDataProcessor extends CollectedDataProcessor {
        private final LongAdder processedPoints = new LongAdder();
        private final LongAdder processCalls = new LongAdder();

        private CountingCollectedDataProcessor(CollectorProperties collectorProperties) {
            super(collectorProperties);
        }

        @Override
        void process(String deviceId,
                     List<DataPoint> points,
                     Map<String, Object> values,
                     PerformanceMonitor performanceMonitor) {
            processCalls.increment();
            for (DataPoint point : points) {
                if (values.containsKey(point.getPointId())) {
                    processedPoints.increment();
                    performanceMonitor.recordDataProcessed(deviceId);
                }
            }
        }

        long processedPoints() {
            return processedPoints.sum();
        }

        long processCalls() {
            return processCalls.sum();
        }
    }

    private record StressOptions(List<Integer> pointCounts,
                                 int sliceCount,
                                 int sliceWaitMs,
                                 int batchThreads,
                                 int collectThreads,
                                 int processThreads,
                                 int readDelayMs) {

        private static StressOptions fromSystemProperties() {
            int cpu = Math.max(2, Runtime.getRuntime().availableProcessors());
            return new StressOptions(
                    parsePointCounts(System.getProperty("collector.stress.pointCounts", "1000,5000,10000,20000,50000,100000")),
                    Integer.getInteger("collector.stress.sliceCount", Math.min(16, Math.max(4, cpu))),
                    Integer.getInteger("collector.stress.sliceWaitMs", 60_000),
                    Integer.getInteger("collector.stress.batchThreads", cpu),
                    Integer.getInteger("collector.stress.collectThreads", cpu * 4),
                    Integer.getInteger("collector.stress.processThreads", cpu),
                    Integer.getInteger("collector.stress.readDelayMs", 0)
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

    private record StressResult(int pointCount,
                                int batchCount,
                                long startMs,
                                long cycleMs,
                                long processedPoints,
                                long processCalls,
                                double pointsPerSecond,
                                double memoryDeltaMb) {
    }
}
