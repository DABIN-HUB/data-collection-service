package com.wangbin.collector.core.cache.aspect;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.port.HistoryTelemetrySink;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.model.ReportConfig;
import com.wangbin.collector.core.report.model.ReportData;
import com.wangbin.collector.core.report.model.ReportResult;
import com.wangbin.collector.core.report.outbox.CloudOutboxCoordinator;
import com.wangbin.collector.core.report.outbox.CloudOutboxMessage;
import com.wangbin.collector.core.report.outbox.CloudOutboxMetadataKeys;
import com.wangbin.collector.core.report.outbox.CloudOutboxRepository;
import com.wangbin.collector.core.report.outbox.CloudOutboxService;
import com.wangbin.collector.core.report.outbox.CloudOutboxStatus;
import com.wangbin.collector.core.report.service.CacheReportService;
import com.wangbin.collector.core.report.service.ReportManager;
import com.wangbin.collector.core.report.service.support.ReportConfigProvider;
import com.wangbin.collector.core.report.shadow.ShadowManager;
import com.wangbin.collector.storage.buffer.HistoryBufferMetrics;
import com.wangbin.collector.storage.buffer.HistoryBufferProperties;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
import com.wangbin.collector.storage.buffer.HistoryWriteRequest;
import com.wangbin.collector.storage.service.TimeSeriesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CombinedDownstreamFailureTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    private final List<ExecutorService> executors = new ArrayList<>();
    private final Map<Logger, Level> originalLogLevels = new LinkedHashMap<>();

    @BeforeEach
    void muteExpectedFailureLogs() {
        for (Class<?> loggerClass : List.of(
                TelemetryPostProcessPipeline.class,
                CollectorDataPostProcessor.class,
                HistoryWriteBuffer.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
            originalLogLevels.put(logger, logger.getLevel());
            logger.setLevel(Level.OFF);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Map.Entry<Logger, Level> entry : originalLogLevels.entrySet()) {
            entry.getKey().setLevel(entry.getValue());
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void healthyCombinedPipelineShouldDrainAllStages() throws Exception {
        ExecutorBundle executors = executors("combined-healthy", 2, 64);
        CacheHarness cache = new CacheHarness(false);
        StreamHarness stream = new StreamHarness(false);
        HistoryHarness history = HistoryHarness.healthy(20);
        CloudHarness cloud = CloudHarness.healthy();
        PipelineHarness pipeline = pipeline(executors, cache, stream, history, cloud);
        List<DataPoint> points = points("combined-healthy-dev", 6);

        pipeline.submitBatch("combined-healthy-dev", points);

        waitUntil(() -> cache.attempts() == 6L
                && stream.attempts() == 6L
                && history.appendAttempts() == 6L
                && cloud.reportAttempts() == 6L
                && cloud.outboxCount() == 0L
                && executors.idle());

        assertEquals(6L, cache.successes());
        assertEquals(6L, stream.successes());
        assertEquals(6L, history.appendSuccesses());
        assertEquals(6L, cloud.sendAttempts());
        assertCombinedSummary("healthy", 6, history.metrics(), cloud, executors);
    }

    @Test
    void redisAndTdengineFailureShouldFallbackThenDrainByRecoveryOrder() throws Exception {
        ExecutorBundle executors = executors("combined-redis-td", 2, 128);
        CacheHarness cache = new CacheHarness(true);
        StreamHarness stream = new StreamHarness(true);
        HistoryHarness history = HistoryHarness.tdengineFailingWithRedisPendingFailure(20, 12, 100);
        CloudHarness cloud = CloudHarness.healthy();
        PipelineHarness pipeline = pipeline(executors, cache, stream, history, cloud);

        pipeline.submitBatch("redis-td-dev", points("redis-td-dev", 6));
        waitUntil(() -> history.localPending() == 6 && cloud.reportAttempts() == 6L && executors.idle());

        assertEquals(6, history.localPending());
        assertEquals(0L, history.redisPending());
        assertEquals(6L, cache.failures());
        assertEquals(6L, stream.failures());

        history.redisPendingRecovered();
        pipeline.submitBatch("redis-td-dev", points("redis-td-dev", 3, 100));
        waitUntil(() -> history.redisPending() == 3L && executors.idle());

        assertEquals(6, history.localPending());
        assertEquals(3L, history.redisPending());

        history.tdengineRecovered();
        history.replay();

        assertEquals(0L, history.redisPending());
        assertEquals(0L, history.redisProcessing());
        assertEquals(0, history.localPending());
        assertCombinedSummary("redis+tdengine", 9, history.metrics(), cloud, executors);
    }

    @Test
    void redisAndCloudFailureShouldNotReportSuccessAndShouldRecoverInOrder() throws Exception {
        ExecutorBundle executors = executors("combined-redis-cloud", 2, 128);
        CacheHarness cache = new CacheHarness(true);
        StreamHarness stream = new StreamHarness(true);
        HistoryHarness history = HistoryHarness.healthy(20);
        CloudHarness cloud = CloudHarness.healthy();
        cloud.redisOutboxDown();
        cloud.cloudDown();
        PipelineHarness pipeline = pipeline(executors, cache, stream, history, cloud);

        pipeline.submitBatch("redis-cloud-dev", points("redis-cloud-dev", 4));
        waitUntil(() -> cloud.reportAttempts() == 4L && executors.idle());

        assertEquals(4L, cloud.storeFailures());
        assertEquals(0L, cloud.sendAttempts());
        assertEquals(0L, cloud.outboxCount());
        assertEquals(0L, cloud.completedMessages());

        cloud.redisOutboxRecovered();
        pipeline.submitBatch("redis-cloud-dev", points("redis-cloud-dev", 4, 100));
        waitUntil(() -> cloud.sendAttempts() == 4L && cloud.outboxCount() == 4L && executors.idle());

        assertEquals(4L, cloud.retryingMessages());
        assertEquals(0L, cloud.completedMessages());

        cloud.cloudRecovered();
        cloud.forceAllDue();
        cloud.dispatch();

        assertEquals(0L, cloud.outboxCount());
        assertEquals(4L, cloud.completedMessages());
        assertCombinedSummary("redis+cloud", 8, history.metrics(), cloud, executors);
    }

    @Test
    void tdengineAndCloudFailureShouldRecoverBacklogsIndependentlyWithRedisHealthy() throws Exception {
        ExecutorBundle executors = executors("combined-td-cloud", 2, 128);
        CacheHarness cache = new CacheHarness(false);
        StreamHarness stream = new StreamHarness(false);
        HistoryHarness history = HistoryHarness.tdengineFailingWithRedisHealthy(20);
        CloudHarness cloud = CloudHarness.healthy();
        cloud.cloudDown();
        PipelineHarness pipeline = pipeline(executors, cache, stream, history, cloud);

        pipeline.submitBatch("td-cloud-dev", points("td-cloud-dev", 8));
        waitUntil(() -> history.redisPending() == 8L && cloud.outboxCount() == 8L && executors.idle());

        assertEquals(8L, history.redisPending());
        assertEquals(8L, cloud.retryingMessages());

        history.tdengineRecovered();
        history.replay();

        assertEquals(0L, history.redisPending());
        assertEquals(0L, history.redisProcessing());
        assertEquals(8L, cloud.outboxCount());

        cloud.cloudRecovered();
        cloud.forceAllDue();
        cloud.dispatch();

        assertEquals(0L, cloud.outboxCount());
        assertEquals(8L, cloud.completedMessages());
        assertCombinedSummary("tdengine+cloud", 8, history.metrics(), cloud, executors);
    }

    @Test
    void tripleFailureStormShouldRemainBoundedAndDrainByRedisTdengineCloudOrder() throws Exception {
        ExecutorBundle executors = executors("combined-triple-a", 4, 512);
        CacheHarness cache = new CacheHarness(true);
        StreamHarness stream = new StreamHarness(true);
        HistoryHarness history = HistoryHarness.tdengineFailingWithRedisPendingFailure(100, 10, 1000);
        CloudHarness cloud = CloudHarness.healthy();
        cloud.redisOutboxDown();
        cloud.cloudDown();
        PipelineHarness pipeline = pipeline(executors, cache, stream, history, cloud);

        pipeline.submitManyDevices(20, 2);
        waitUntil(() -> cache.attempts() == 40L
                && stream.attempts() == 40L
                && history.appendAttempts() == 40L
                && cloud.reportAttempts() == 40L
                && executors.idle());

        assertEquals(10, history.localPending());
        assertEquals(10, history.localCapacity());
        assertEquals(40L, cloud.storeFailures());
        assertEquals(0L, cloud.sendAttempts());
        assertEquals(0L, cloud.outboxCount());
        assertTrue(history.appendAttempts() <= 40L);

        history.redisPendingRecovered();
        cloud.redisOutboxRecovered();
        pipeline.submitBatch("triple-recovery-a", points("triple-recovery-a", 5, 1_000));
        waitUntil(() -> history.redisPending() == 5L
                && cloud.sendAttempts() == 5L
                && cloud.outboxCount() == 5L
                && executors.idle());

        history.tdengineRecovered();
        history.replay();
        assertEquals(0L, history.redisPending());
        assertEquals(0, history.localPending());
        assertEquals(5L, cloud.outboxCount());

        cloud.cloudRecovered();
        cloud.forceAllDue();
        cloud.dispatch();
        assertEquals(0L, cloud.outboxCount());
        assertEquals(5L, cloud.completedMessages());
        assertCombinedSummary("triple-a", 45, history.metrics(), cloud, executors);
    }

    @Test
    void tdengineAndCloudRecoveringBeforeRedisShouldDrainHistoryLocalButCloudWaitsForOutboxRedis() throws Exception {
        ExecutorBundle executors = executors("combined-triple-b", 2, 128);
        CacheHarness cache = new CacheHarness(true);
        StreamHarness stream = new StreamHarness(true);
        HistoryHarness history = HistoryHarness.tdengineFailingWithRedisPendingFailure(20, 5, 100);
        CloudHarness cloud = CloudHarness.healthy();
        cloud.redisOutboxDown();
        cloud.cloudDown();
        PipelineHarness pipeline = pipeline(executors, cache, stream, history, cloud);

        pipeline.submitBatch("triple-b-dev", points("triple-b-dev", 5));
        waitUntil(() -> history.localPending() == 5 && cloud.storeFailures() == 5L && executors.idle());

        history.tdengineRecovered();
        cloud.cloudRecovered();
        history.replay();

        assertEquals(0, history.localPending());
        assertEquals(0L, cloud.outboxCount());

        pipeline.submitBatch("triple-b-dev", points("triple-b-dev", 2, 100));
        waitUntil(() -> cloud.storeFailures() == 7L && executors.idle());
        assertEquals(0L, cloud.completedMessages());

        cloud.redisOutboxRecovered();
        pipeline.submitBatch("triple-b-dev", points("triple-b-dev", 2, 200));
        waitUntil(() -> cloud.completedMessages() == 2L && executors.idle());

        assertEquals(0L, cloud.outboxCount());
        assertCombinedSummary("triple-b", 9, history.metrics(), cloud, executors);
    }

    @Test
    void slowCombinedStagesShouldIsolateExecutorsAndShutdown() throws Exception {
        ExecutorBundle executors = executors("combined-slow", 2, 32);
        CacheHarness cache = new CacheHarness(true);
        StreamHarness stream = new StreamHarness(true);
        BlockingHistorySink historySink = new BlockingHistorySink();
        BlockingReportService reportService = new BlockingReportService();
        TelemetryStreamProperties streamProperties = new TelemetryStreamProperties();
        streamProperties.setEnabled(true);
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(
                        new CacheTelemetryPostProcessStage(cache.manager()),
                        new StreamTelemetryPostProcessStage(stream, streamProperties),
                        new HistoryTelemetryPostProcessStage(historySink),
                        new ReportTelemetryPostProcessStage(reportService.mock())),
                executors.cache,
                executors.stream,
                executors.history,
                executors.report);
        CollectorDataPostProcessor processor = new CollectorDataPostProcessor(
                executors.entry, pipeline, new com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard());
        DataPoint point = point("slow-combined-dev", "p1");

        processor.savePointAsync("slow-combined-dev", point, ProcessResult.success(1, 1));
        assertTrue(historySink.awaitEntered());
        assertTrue(reportService.awaitEntered());
        waitUntil(() -> executors.entry.getQueue().isEmpty() && executors.entry.getActiveCount() == 0);

        assertEquals(1, executors.history.getActiveCount());
        assertEquals(1, executors.report.getActiveCount());

        executors.shutdownNow();
        assertTrue(historySink.interruptedCount() > 0);
        assertTrue(reportService.interruptedCount() > 0);
        assertCombinedSummary("slow-block", 1, null, null, executors);
    }

    @Test
    void poisonAndCapacityBoundaryShouldNotStarveIndependentBacklogs() {
        HistoryHarness history = HistoryHarness.healthy(2);
        history.pushProcessingPoison("{not-json");
        history.pushPendingHistory("poison-history-dev", "normal", 10_000L);
        CloudHarness cloud = CloudHarness.healthy();
        cloud.stageOnly("cloud-poison-dev", "msg-poison", "poison");
        cloud.stageOnly("cloud-normal-dev", "msg-normal", "normal");
        cloud.forceAllDue();
        cloud.failPoint("poison");

        history.replay();
        cloud.dispatch();

        assertEquals(1L, history.redisDeadLetter());
        assertEquals(0L, history.redisProcessing());
        assertEquals(0L, history.redisPending());
        assertEquals(1L, cloud.outboxCount());
        assertTrue(cloud.find("msg-poison").isPresent());
        assertTrue(cloud.find("msg-normal").isEmpty());
        assertCombinedSummary("poison-capacity", 2, history.metrics(), cloud, null);
    }

    @Test
    void persistentBacklogsShouldRecoverAfterServiceRestartWhenRedisIsAvailable() {
        HistoryHarness firstHistory = HistoryHarness.tdengineFailingWithRedisHealthy(10);
        for (int i = 0; i < 3; i++) {
            firstHistory.savePoint("restart-history", point("restart-history", "p" + i),
                    ProcessResult.success(i, i));
        }
        assertEquals(3L, firstHistory.redisPending());

        HistoryHarness restartedHistory = HistoryHarness.withSharedRedis(firstHistory.redisLists(), false, 10);
        restartedHistory.replay();
        assertEquals(0L, restartedHistory.redisPending());
        assertEquals(3L, restartedHistory.appendSuccesses());

        InMemoryCloudOutboxRepository repository = new InMemoryCloudOutboxRepository();
        CloudHarness firstCloud = CloudHarness.withRepository(repository);
        firstCloud.cloudDown();
        for (int i = 0; i < 3; i++) {
            firstCloud.reportPoint("restart-cloud", point("restart-cloud", "p" + i),
                    ProcessResult.success(i, i));
        }
        assertEquals(3L, firstCloud.outboxCount());

        CloudHarness restartedCloud = CloudHarness.withRepository(repository);
        restartedCloud.cloudRecovered();
        restartedCloud.forceAllDue();
        restartedCloud.dispatch();
        assertEquals(0L, restartedCloud.outboxCount());
        assertEquals(3L, restartedCloud.completedMessages());
        assertCombinedSummary("restart", 6, restartedHistory.metrics(), restartedCloud, null);
    }

    private PipelineHarness pipeline(ExecutorBundle executors,
                                     CacheHarness cache,
                                     StreamHarness stream,
                                     HistoryTelemetrySink history,
                                     CloudHarness cloud) {
        CacheReportService reportService = mock(CacheReportService.class);
        doAnswer(invocation -> {
            cloud.reportPoint(
                    invocation.getArgument(0),
                    invocation.getArgument(2),
                    invocation.getArgument(3));
            return null;
        }).when(reportService).reportPoint(anyString(), anyString(), any(), any());
        TelemetryStreamProperties streamProperties = new TelemetryStreamProperties();
        streamProperties.setEnabled(true);
        TelemetryPostProcessPipeline pipeline = new TelemetryPostProcessPipeline(
                List.of(
                        new CacheTelemetryPostProcessStage(cache.manager()),
                        new StreamTelemetryPostProcessStage(stream, streamProperties),
                        new HistoryTelemetryPostProcessStage(history),
                        new ReportTelemetryPostProcessStage(reportService)),
                executors.cache,
                executors.stream,
                executors.history,
                executors.report);
        return new PipelineHarness(executors, pipeline);
    }

    private ExecutorBundle executors(String name, int threads, int queueCapacity) {
        ExecutorBundle bundle = new ExecutorBundle(
                fixedPool(name + "-entry", threads, queueCapacity),
                fixedPool(name + "-cache", threads, queueCapacity),
                fixedPool(name + "-stream", threads, queueCapacity),
                fixedPool(name + "-history", threads, queueCapacity),
                fixedPool(name + "-report", threads, queueCapacity));
        executors.add(bundle.entry);
        executors.add(bundle.cache);
        executors.add(bundle.stream);
        executors.add(bundle.history);
        executors.add(bundle.report);
        return bundle;
    }

    private ThreadPoolExecutor fixedPool(String namePrefix, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    thread.setName(namePrefix + "-" + thread.getId());
                    return thread;
                });
    }

    private List<DataPoint> points(String deviceId, int count) {
        return points(deviceId, count, 0);
    }

    private List<DataPoint> points(String deviceId, int count, int offset) {
        List<DataPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(point(deviceId, "p" + (offset + i)));
        }
        return points;
    }

    private DataPoint point(String deviceId, String pointId) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setPointName(pointId);
        point.setStatus(1);
        point.setCacheEnabled(1);
        point.setCacheDuration(60);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("streamEnabled", true);
        config.put("historyEnabled", true);
        config.put("reportEnabled", true);
        config.put("reportField", pointId);
        config.put("eventEnabled", false);
        point.setAdditionalConfig(config);
        return point;
    }

    private Map<String, Object> values(List<DataPoint> points) {
        Map<String, Object> values = new LinkedHashMap<>();
        int index = 0;
        for (DataPoint point : points) {
            values.put(point.getPointId(), ProcessResult.success(index, index));
            index++;
        }
        return values;
    }

    private void waitUntil(Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isSatisfied()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(condition.isSatisfied());
    }

    private static ReportResult successResult() {
        return ReportResult.success("p1", "gateway-1");
    }

    private static ReportResult errorResult(String message) {
        return ReportResult.error("p1", message, "gateway-1");
    }

    private static ReportConfig validConfig() {
        ReportConfig config = new ReportConfig();
        config.setProtocol("MQTT");
        config.setTargetId("gateway-1");
        config.setHost("localhost");
        config.setPort(1883);
        config.setParams(new java.util.HashMap<>());
        return config;
    }

    private static void assertCombinedSummary(String scenario,
                                              int telemetryCount,
                                              HistoryBufferMetrics history,
                                              CloudHarness cloud,
                                              ExecutorBundle executors) {
        String summary = "CombinedFailureSummary scenario=" + scenario
                + " telemetry=" + telemetryCount
                + " historyPending=" + (history == null ? -1L : history.redisPending())
                + " historyProcessing=" + (history == null ? -1L : history.redisProcessing())
                + " historyLocal=" + (history == null ? -1 : history.localPending())
                + " cloudOutbox=" + (cloud == null ? -1L : cloud.outboxCount())
                + " cloudSendAttempts=" + (cloud == null ? -1L : cloud.sendAttempts())
                + " cloudCompleted=" + (cloud == null ? -1L : cloud.completedMessages())
                + " entryQueue=" + (executors == null ? -1 : executors.entry.getQueue().size())
                + " stageQueue=" + (executors == null ? -1 : executors.stageQueueSize())
                + " active=" + (executors == null ? -1 : executors.activeCount());
        System.out.println(summary);
        if (executors != null) {
            assertTrue(executors.stageQueueSize() >= 0);
        }
    }

    private final class PipelineHarness {
        private final ExecutorBundle executors;
        private final CollectorDataPostProcessor processor;
        private final com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard guard =
                new com.wangbin.collector.core.collector.scheduler.CollectionTaskGuard();

        private PipelineHarness(ExecutorBundle executors, TelemetryPostProcessPipeline pipeline) {
            this.executors = executors;
            this.processor = new CollectorDataPostProcessor(executors.entry, pipeline, guard);
        }

        private void submitBatch(String deviceId, List<DataPoint> points) {
            long generation = guard.activateNextGeneration(deviceId);
            guard.runWithContext(deviceId, generation,
                    () -> processor.saveBatchAsync(deviceId, points, values(points), null));
        }

        private void submitManyDevices(int deviceCount, int pointsPerDevice) {
            for (int device = 0; device < deviceCount; device++) {
                String deviceId = "combined-runtime-" + device;
                submitBatch(deviceId, points(deviceId, pointsPerDevice));
            }
        }
    }

    private static final class ExecutorBundle {
        private final ThreadPoolExecutor entry;
        private final ThreadPoolExecutor cache;
        private final ThreadPoolExecutor stream;
        private final ThreadPoolExecutor history;
        private final ThreadPoolExecutor report;

        private ExecutorBundle(ThreadPoolExecutor entry,
                               ThreadPoolExecutor cache,
                               ThreadPoolExecutor stream,
                               ThreadPoolExecutor history,
                               ThreadPoolExecutor report) {
            this.entry = entry;
            this.cache = cache;
            this.stream = stream;
            this.history = history;
            this.report = report;
        }

        private boolean idle() {
            return entry.getQueue().isEmpty()
                    && cache.getQueue().isEmpty()
                    && stream.getQueue().isEmpty()
                    && history.getQueue().isEmpty()
                    && report.getQueue().isEmpty()
                    && entry.getActiveCount() == 0
                    && cache.getActiveCount() == 0
                    && stream.getActiveCount() == 0
                    && history.getActiveCount() == 0
                    && report.getActiveCount() == 0;
        }

        private int stageQueueSize() {
            return cache.getQueue().size()
                    + stream.getQueue().size()
                    + history.getQueue().size()
                    + report.getQueue().size();
        }

        private int activeCount() {
            return entry.getActiveCount()
                    + cache.getActiveCount()
                    + stream.getActiveCount()
                    + history.getActiveCount()
                    + report.getActiveCount();
        }

        private void shutdownNow() throws InterruptedException {
            for (ThreadPoolExecutor executor : List.of(entry, cache, stream, history, report)) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    private static final class CacheHarness {
        private final MultiLevelCacheManager manager = mock(MultiLevelCacheManager.class);
        private final AtomicBoolean redisFailing = new AtomicBoolean();
        private final LongAdder attempts = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder successes = new LongAdder();

        private CacheHarness(boolean failing) {
            redisFailing.set(failing);
            doAnswer(invocation -> {
                attempts.increment();
                if (redisFailing.get()) {
                    failures.increment();
                    throw new RedisConnectionFailureException("cache redis unavailable");
                }
                successes.increment();
                return true;
            }).when(manager).put(any(), any(), anyLong());
        }

        private MultiLevelCacheManager manager() {
            return manager;
        }

        private long attempts() {
            return attempts.sum();
        }

        private long failures() {
            return failures.sum();
        }

        private long successes() {
            return successes.sum();
        }
    }

    private static final class StreamHarness implements com.wangbin.collector.core.cache.service.TelemetryStreamService {
        private final AtomicBoolean redisFailing = new AtomicBoolean();
        private final LongAdder attempts = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder successes = new LongAdder();

        private StreamHarness(boolean failing) {
            redisFailing.set(failing);
        }

        @Override
        public void append(String deviceId, DataPoint point, ProcessResult processResult) {
            attempts.increment();
            if (redisFailing.get()) {
                failures.increment();
                throw new RedisSystemException("stream redis unavailable",
                        new RedisConnectionFailureException("stream redis unavailable"));
            }
            successes.increment();
        }

        private long attempts() {
            return attempts.sum();
        }

        private long failures() {
            return failures.sum();
        }

        private long successes() {
            return successes.sum();
        }
    }

    private final class HistoryHarness implements HistoryTelemetrySink {
        private final TimeSeriesService timeSeriesService = mock(TimeSeriesService.class);
        private final RedisLists redisLists;
        private final HistoryBufferProperties properties;
        private final HistoryWriteBuffer buffer;
        private final AtomicBoolean tdengineFailing = new AtomicBoolean();
        private final LongAdder appendAttempts = new LongAdder();
        private final LongAdder appendSuccesses = new LongAdder();
        private final LongAdder appendFailures = new LongAdder();

        private HistoryHarness(RedisLists redisLists, boolean tdengineFailing, int localCapacity) {
            this.redisLists = redisLists;
            this.properties = historyProperties(100, localCapacity);
            this.tdengineFailing.set(tdengineFailing);
            doAnswer(invocation -> {
                appendAttempts.increment();
                if (this.tdengineFailing.get()) {
                    appendFailures.increment();
                    throw new DataAccessResourceFailureException("TDengine unavailable");
                }
                appendSuccesses.increment();
                return null;
            }).when(timeSeriesService).append(anyString(), anyString(), any(), any(), anyLong());
            this.buffer = new HistoryWriteBuffer(timeSeriesService, redisLists.template, OBJECT_MAPPER, properties);
        }

        private static HistoryHarness healthy(int localCapacity) {
            return new CombinedDownstreamFailureTest().new HistoryHarness(new RedisLists(), false, localCapacity);
        }

        private static HistoryHarness tdengineFailingWithRedisHealthy(int localCapacity) {
            return new CombinedDownstreamFailureTest().new HistoryHarness(new RedisLists(), true, localCapacity);
        }

        private static HistoryHarness tdengineFailingWithRedisPendingFailure(int batchSize,
                                                                             int localCapacity,
                                                                             int failTimes) {
            CombinedDownstreamFailureTest owner = new CombinedDownstreamFailureTest();
            RedisLists redisLists = new RedisLists();
            HistoryHarness harness = owner.new HistoryHarness(redisLists, true, localCapacity);
            harness.properties.setReplayBatchSize(batchSize);
            redisLists.failLeftPush(harness.properties.getPendingKey(), failTimes);
            return harness;
        }

        private static HistoryHarness withSharedRedis(RedisLists redisLists, boolean tdengineFailing, int localCapacity) {
            return new CombinedDownstreamFailureTest().new HistoryHarness(redisLists, tdengineFailing, localCapacity);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void savePoint(String deviceId, DataPoint point, ProcessResult processResult) {
            buffer.writeOrBuffer(new HistoryWriteRequest(
                    deviceId,
                    "MODBUS_TCP",
                    point,
                    processResult,
                    System.currentTimeMillis()));
        }

        private void tdengineRecovered() {
            tdengineFailing.set(false);
        }

        private void redisPendingRecovered() {
            redisLists.clearLeftPushFailures(properties.getPendingKey());
        }

        private void replay() {
            buffer.replay();
        }

        private void pushProcessingPoison(String json) {
            redisLists.leftPush(properties.getProcessingKey(), json);
        }

        private void pushPendingHistory(String deviceId, String pointId, long eventTs) {
            try {
                redisLists.leftPush(properties.getPendingKey(), OBJECT_MAPPER.writeValueAsString(new HistoryWriteRequest(
                        deviceId,
                        "MODBUS_TCP",
                        point(deviceId, pointId),
                        ProcessResult.success(1, 1),
                        eventTs)));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private HistoryBufferMetrics metrics() {
            return buffer.metrics();
        }

        private int localPending() {
            return metrics().localPending();
        }

        private int localCapacity() {
            return metrics().localCapacity();
        }

        private long redisPending() {
            return redisLists.size(properties.getPendingKey());
        }

        private long redisProcessing() {
            return redisLists.size(properties.getProcessingKey());
        }

        private long redisDeadLetter() {
            return redisLists.size(properties.getDeadLetterKey());
        }

        private long appendAttempts() {
            return appendAttempts.sum();
        }

        private long appendSuccesses() {
            return appendSuccesses.sum();
        }

        private RedisLists redisLists() {
            return redisLists;
        }

        private HistoryBufferProperties historyProperties(int replayBatchSize, int localCapacity) {
            HistoryBufferProperties properties = new HistoryBufferProperties();
            properties.setEnabled(true);
            properties.setPendingKey("combined:history:pending");
            properties.setProcessingKey("combined:history:processing");
            properties.setDeadLetterKey("combined:history:dead");
            properties.setReplayBatchSize(replayBatchSize);
            properties.setLocalQueueCapacity(localCapacity);
            return properties;
        }
    }

    private final class CloudHarness {
        private final InMemoryCloudOutboxRepository repository;
        private final ReportManager reportManager = mock(ReportManager.class);
        private final ReportProperties properties = new ReportProperties();
        private final CloudOutboxCoordinator coordinator;
        private final CloudOutboxService service;
        private final AtomicBoolean cloudFailing = new AtomicBoolean();
        private final java.util.Set<String> failingPoints = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final LongAdder reportAttempts = new LongAdder();
        private final LongAdder sendAttempts = new LongAdder();
        private final LongAdder completedMessages = new LongAdder();

        private CloudHarness(InMemoryCloudOutboxRepository repository) {
            this.repository = repository;
            properties.getOutbox().setEnabled(true);
            properties.getOutbox().setMaxRetryTimes(20);
            properties.getOutbox().setLeaseMs(1000L);
            properties.getOutbox().setClaimBatchSize(200);
            properties.setRetryBackoffMs(100L);
            properties.setMaxRetryBackoffMs(500L);
            properties.setRetryJitterEnabled(false);
            this.coordinator = new CloudOutboxCoordinator(repository, mock(ShadowManager.class), properties);
            ReportConfigProvider configProvider = mock(ReportConfigProvider.class);
            when(configProvider.getConfig("gateway-1")).thenReturn(validConfig());
            this.service = new CloudOutboxService(repository, coordinator, reportManager, configProvider, properties);
            when(reportManager.reportAsync(any(), any())).thenAnswer(invocation -> {
                sendAttempts.increment();
                ReportData data = invocation.getArgument(0);
                if (cloudFailing.get() || failingPoints.contains(data.getPointCode())) {
                    return CompletableFuture.completedFuture(errorResult("cloud unavailable"));
                }
                completedMessages.increment();
                return CompletableFuture.completedFuture(successResult());
            });
        }

        private static CloudHarness healthy() {
            return new CombinedDownstreamFailureTest().new CloudHarness(new InMemoryCloudOutboxRepository());
        }

        private static CloudHarness withRepository(InMemoryCloudOutboxRepository repository) {
            return new CombinedDownstreamFailureTest().new CloudHarness(repository);
        }

        private void reportPoint(String localDeviceId, DataPoint point, Object cacheValue) {
            reportAttempts.increment();
            ReportData data = reportData(localDeviceId, point, cacheValue);
            String messageId = service.stage(localDeviceId, 1L, data.getTimestamp(), data.getTimestamp(), data);
            repository.forceDue(messageId);
            service.dispatchDueMessages();
        }

        private void stageOnly(String localDeviceId, String messageId, String pointCode) {
            DataPoint point = point(localDeviceId, pointCode);
            ReportData data = reportData(localDeviceId, point, ProcessResult.success(1, 1));
            data.addMetadata(MessageConstant.FIELD_MESSAGE_ID, messageId);
            service.stage(localDeviceId, 1L, data.getTimestamp(), data.getTimestamp(), data);
        }

        private Optional<CloudOutboxMessage> find(String messageId) {
            return repository.find(messageId);
        }

        private void redisOutboxDown() {
            repository.setStoreFailing(true);
        }

        private void redisOutboxRecovered() {
            repository.setStoreFailing(false);
        }

        private void cloudDown() {
            cloudFailing.set(true);
        }

        private void cloudRecovered() {
            cloudFailing.set(false);
        }

        private void failPoint(String pointCode) {
            failingPoints.add(pointCode);
        }

        private void forceAllDue() {
            repository.forceAllDue();
        }

        private void dispatch() {
            service.dispatchDueMessages();
        }

        private long reportAttempts() {
            return reportAttempts.sum();
        }

        private long sendAttempts() {
            return sendAttempts.sum();
        }

        private long completedMessages() {
            return completedMessages.sum();
        }

        private long storeFailures() {
            return repository.storeFailures();
        }

        private long retryingMessages() {
            return repository.messages().stream()
                    .filter(message -> message.getStatus() == CloudOutboxStatus.PENDING)
                    .filter(message -> message.getAttempts() > 0)
                    .count();
        }

        private long outboxCount() {
            return repository.countPending();
        }

        private ReportData reportData(String localDeviceId, DataPoint point, Object cacheValue) {
            ReportData data = new ReportData();
            data.setDeviceId("cloud-" + localDeviceId);
            data.setPointId(point.getPointId());
            data.setPointCode(point.getPointCode());
            data.setPointName(point.getPointName());
            data.setTimestamp(System.currentTimeMillis());
            Object value = cacheValue instanceof ProcessResult result ? result.getFinalValue() : cacheValue;
            data.setValue(value);
            data.addMetadata(MessageConstant.FIELD_MESSAGE_ID, localDeviceId + "-" + point.getPointId() + "-" + data.getTimestamp());
            data.addMetadata(CloudOutboxMetadataKeys.PRODUCT_KEY, "pk-1");
            data.addMetadata(CloudOutboxMetadataKeys.GATEWAY_DEVICE_ID, "gateway-1");
            data.addProperty(point.getPointId(), value, data.getTimestamp(), "GOOD");
            return data;
        }
    }

    private static final class InMemoryCloudOutboxRepository implements CloudOutboxRepository {
        private final Map<String, CloudOutboxMessage> messages = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicBoolean storeFailing = new AtomicBoolean();
        private final LongAdder storeFailures = new LongAdder();

        @Override
        public synchronized CloudOutboxMessage saveIfAbsent(CloudOutboxMessage message, long leaseUntil) {
            if (storeFailing.get()) {
                storeFailures.increment();
                throw new RedisConnectionFailureException("cloud outbox redis unavailable");
            }
            message.setNextAttemptAt(leaseUntil);
            return messages.computeIfAbsent(message.getMessageId(), ignored -> message);
        }

        @Override
        public synchronized Optional<CloudOutboxMessage> find(String messageId) {
            return Optional.ofNullable(messages.get(messageId));
        }

        @Override
        public synchronized List<CloudOutboxMessage> claimDue(long now, int limit, long leaseUntil) {
            List<CloudOutboxMessage> due = new ArrayList<>();
            messages.values().stream()
                    .filter(message -> message.getStatus() != CloudOutboxStatus.ISOLATED)
                    .filter(message -> message.getNextAttemptAt() <= now)
                    .sorted(Comparator.comparingLong(CloudOutboxMessage::getCreatedAt))
                    .limit(limit)
                    .forEach(message -> {
                        message.setNextAttemptAt(leaseUntil);
                        due.add(message);
                    });
            return due;
        }

        @Override
        public synchronized void reschedule(CloudOutboxMessage message) {
            messages.put(message.getMessageId(), message);
        }

        @Override
        public synchronized boolean rescheduleIfPresent(CloudOutboxMessage message) {
            return messages.replace(message.getMessageId(), message) != null;
        }

        @Override
        public synchronized void complete(String messageId) {
            messages.remove(messageId);
        }

        @Override
        public synchronized long countPending() {
            return messages.values().stream()
                    .filter(message -> message.getStatus() != CloudOutboxStatus.ISOLATED)
                    .count();
        }

        @Override
        public synchronized long countIsolated() {
            return messages.values().stream()
                    .filter(message -> message.getStatus() == CloudOutboxStatus.ISOLATED)
                    .count();
        }

        @Override
        public synchronized long oldestCreatedAt() {
            return messages.values().stream()
                    .mapToLong(CloudOutboxMessage::getCreatedAt)
                    .min()
                    .orElse(0L);
        }

        @Override
        public synchronized boolean hasPendingForDevice(String localDeviceId) {
            return messages.values().stream()
                    .flatMap(message -> message.resolveCommits().stream())
                    .anyMatch(commit -> localDeviceId.equals(commit.getLocalDeviceId()));
        }

        private void setStoreFailing(boolean failing) {
            storeFailing.set(failing);
        }

        private synchronized void forceAllDue() {
            messages.values().forEach(message -> message.setNextAttemptAt(0L));
        }

        private synchronized void forceDue(String messageId) {
            CloudOutboxMessage message = messages.get(messageId);
            if (message != null) {
                message.setNextAttemptAt(0L);
            }
        }

        private synchronized List<CloudOutboxMessage> messages() {
            return List.copyOf(messages.values());
        }

        private long storeFailures() {
            return storeFailures.sum();
        }
    }

    private static final class RedisLists {
        private final StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final ListOperations<String, String> operations = mock(ListOperations.class);
        private final Map<String, Deque<String>> lists = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> leftPushFailures = new java.util.concurrent.ConcurrentHashMap<>();

        private RedisLists() {
            when(template.opsForList()).thenReturn(operations);
            when(operations.leftPush(anyString(), anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                String value = invocation.getArgument(1);
                if (shouldFail(leftPushFailures, key)) {
                    throw new RedisConnectionFailureException("history pending redis unavailable");
                }
                Deque<String> list = list(key);
                list.addFirst(value);
                return (long) list.size();
            });
            when(operations.rightPopAndLeftPush(anyString(), anyString())).thenAnswer(invocation -> {
                String source = invocation.getArgument(0);
                String destination = invocation.getArgument(1);
                String value = list(source).pollLast();
                if (value == null) {
                    return null;
                }
                list(destination).addFirst(value);
                return value;
            });
            when(operations.index(anyString(), anyLong())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                long index = invocation.getArgument(1);
                if (index < 0) {
                    return null;
                }
                int current = 0;
                for (String value : list(key)) {
                    if (current++ == index) {
                        return value;
                    }
                }
                return null;
            });
            when(operations.remove(anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                String value = invocation.getArgument(2);
                return list(key).removeFirstOccurrence(value) ? 1L : 0L;
            });
            when(operations.size(anyString())).thenAnswer(invocation -> (long) list(invocation.getArgument(0)).size());
        }

        private void leftPush(String key, String value) {
            list(key).addFirst(value);
        }

        private long size(String key) {
            return list(key).size();
        }

        private void failLeftPush(String key, int times) {
            leftPushFailures.put(key, new AtomicInteger(times));
        }

        private void clearLeftPushFailures(String key) {
            leftPushFailures.remove(key);
        }

        private Deque<String> list(String key) {
            return lists.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        }

        private boolean shouldFail(Map<String, AtomicInteger> failures, String key) {
            AtomicInteger remaining = failures.get(key);
            return remaining != null && remaining.getAndDecrement() > 0;
        }
    }

    private static final class BlockingHistorySink implements HistoryTelemetrySink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final LongAdder interrupted = new LongAdder();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void savePoint(String deviceId, DataPoint point, ProcessResult processResult) {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted.increment();
                Thread.currentThread().interrupt();
            }
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        private long interruptedCount() {
            return interrupted.sum();
        }
    }

    private static final class BlockingReportService {
        private final CacheReportService service = org.mockito.Mockito.mock(CacheReportService.class);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final LongAdder interrupted = new LongAdder();

        private BlockingReportService() {
            doAnswer(invocation -> {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    interrupted.increment();
                    Thread.currentThread().interrupt();
                }
                return null;
            }).when(service).reportPoint(anyString(), anyString(), any(), any());
        }

        private CacheReportService mock() {
            return service;
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        private long interruptedCount() {
            return interrupted.sum();
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean isSatisfied();
    }
}
