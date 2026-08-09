package com.wangbin.collector.soak;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.cloud.CloudDeviceType;
import com.wangbin.collector.common.domain.cloud.CloudTargetConfig;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBuffer;
import com.wangbin.collector.core.cache.ingress.TelemetryIngressBufferMetrics;
import com.wangbin.collector.core.collector.ingress.TelemetryIngressService;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.report.config.ReportProperties;
import com.wangbin.collector.core.report.outbox.CloudOutboxStatus;
import com.wangbin.collector.monitor.metrics.SystemResourceMonitorService;
import com.wangbin.collector.monitor.metrics.SystemResourceSnapshot;
import com.wangbin.collector.storage.buffer.HistoryBatchMetrics;
import com.wangbin.collector.storage.buffer.HistoryBatchWriter;
import com.wangbin.collector.storage.buffer.HistoryBufferMetrics;
import com.wangbin.collector.storage.buffer.HistoryWriteBuffer;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
        "collector.config.loader=file"
})
class RealEnvironmentSoakIT {

    private static final DateTimeFormatter RUN_ID_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final String DEFAULT_SOURCE = "REAL_SOAK";
    private static final long BYTES_PER_MIB = 1024L * 1024L;

    @Autowired
    private TelemetryIngressService telemetryIngressService;

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private SystemResourceMonitorService systemResourceMonitorService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ReportProperties reportProperties;

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
    private ObjectProvider<DataSource> dataSourceProvider;

    @Test
    void runRealEnvironmentSoak() throws Exception {
        SoakOptions options = SoakOptions.from(environment);
        Path outputDir = options.outputDir();
        Files.createDirectories(outputDir);

        List<DevicePoints> devicePoints = registerDevices(options);
        SoakCounters counters = new SoakCounters();
        List<Long> roundDurations = new ArrayList<>();
        List<MetricSample> samples = new ArrayList<>();
        MqttAckBridge ackBridge = null;
        BufferedWriter metricsWriter = Files.newBufferedWriter(outputDir.resolve("metrics.csv"), StandardCharsets.UTF_8);
        try (metricsWriter) {
            writeRunInfo(outputDir, options, devicePoints);
            writeMetricsHeader(metricsWriter);
            if (options.ackBridgeEnabled()) {
                ackBridge = MqttAckBridge.start(options, objectMapper);
            }
            runLoad(options, devicePoints, counters, roundDurations, samples, metricsWriter);
            waitForDrain(Duration.ofSeconds(options.drainWaitSeconds()));
            MetricSample finalSample = collectSample(options, counters, roundDurations, ackBridge, true);
            samples.add(finalSample);
            writeMetric(metricsWriter, finalSample);
            writeSummary(outputDir, options, devicePoints, counters, roundDurations, samples, ackBridge);
            assertTrue(counters.rejected.get() <= options.maxAllowedRejected(),
                    "Soak rejected task count exceeded threshold: " + counters.rejected.get());
        } finally {
            if (ackBridge != null) {
                ackBridge.close();
            }
            cleanupDevices(devicePoints);
        }
    }

    private void runLoad(SoakOptions options,
                         List<DevicePoints> devicePoints,
                         SoakCounters counters,
                         List<Long> roundDurations,
                         List<MetricSample> samples,
                         BufferedWriter metricsWriter) throws Exception {
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
                    for (DataPoint point : device.points()) {
                        appendPoint(device.deviceId(), point, eventTs, roundIndex, counters);
                        emitted++;
                        paceWithinRound(options, roundStart, emitted);
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

    private void appendPoint(String deviceId,
                             DataPoint point,
                             long eventTs,
                             int roundIndex,
                             SoakCounters counters) {
        try {
            double value = roundIndex + ThreadLocalRandom.current().nextDouble(0.0d, 100.0d);
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
        for (int deviceIndex = 0; deviceIndex < options.devices(); deviceIndex++) {
            int devicesLeft = options.devices() - deviceIndex;
            int pointCount = Math.max(1, remaining / devicesLeft);
            String deviceId = "soak-dev-" + String.format(Locale.ROOT, "%04d", deviceIndex);
            DeviceInfo device = device(deviceId, options.collectionIntervalMs());
            DeviceConnection connection = connection(deviceId);
            List<DataPoint> points = points(deviceId, pointCount, options);
            configManager.saveLocalDeviceConfig(device, connection, points, true);
            result.add(new DevicePoints(deviceId, points));
            remaining -= pointCount;
        }
        return result;
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
            point.setDataType("DOUBLE");
            point.setReadWrite("R");
            point.setUnitId(1);
            point.setUnit("value");
            point.setStatus(1);
            point.setCacheEnabled(1);
            point.setCacheDuration(300);
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
        RedisSnapshot redis = redisSnapshot(options);
        CloudSnapshot cloud = cloudSnapshot(ackBridge);
        GcSnapshot gc = gcSnapshot();
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
                redis,
                new EntryIngressSnapshot(entry.redisPending(), entry.redisProcessing(), entry.redisDeadLetter(),
                        entry.localPending(), entry.localCapacity(), entry.rejectedTasks(), entry.rejectedItems(),
                        entry.redisBufferedItems(), entry.localBufferedItems(), entry.droppedItems(),
                        entry.replayCompletedItems(), entry.pendingRemoveFailures(), entry.poisonDeadLetterItems(),
                        entry.staleSameRuntimeDroppedItems(), entry.crossRuntimeRecoveredItems(),
                        entry.legacyEnvelopeRecoveredItems()),
                new HistorySnapshot(history.redisPending(), history.redisProcessing(), history.redisDeadLetter(),
                        history.localPending(), history.localCapacity(),
                        history.writeFailureRedisBuffered(), history.rejectedRedisBuffered(),
                        history.writeFailureLocalBuffered(), history.rejectedLocalBuffered(),
                        history.writeFailureDropped(), history.rejectedDropped()),
                new HistoryBatchSnapshot(batch.acceptedRows(), batch.flushedBatches(), batch.flushedRows(),
                        batch.batchWriteSuccess(), batch.batchWriteFailure(), batch.fallbackRows(),
                        batch.currentBufferedRows(), batch.bufferedRowsPeak(), batch.averageBatchSize(),
                        batch.batchSizeP50(), batch.batchSizeP95(), batch.batchSizeMax(),
                        batch.flushLatencyP50Ms(), batch.flushLatencyP95Ms(), batch.flushLatencyP99Ms(),
                        batch.oldestBufferedAgeMs(), batch.shutdownFlushedRows()),
                cloud
        );
    }

    private HistoryBatchMetrics emptyHistoryBatchMetrics() {
        return new HistoryBatchMetrics(
                0L, 0L, 0L, 0L, 0L, 0L, 0, 0, 0D,
                0, 0, 0, 0D, 0D, 0D, 0L, 0L);
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

    private void writeRunInfo(Path outputDir, SoakOptions options, List<DevicePoints> devicePoints) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("scenario", options.scenario());
        info.put("points", options.points());
        info.put("devices", options.devices());
        info.put("pointsPerDevice", devicePoints.stream().map(DevicePoints::points).map(Collection::size).toList());
        info.put("durationSeconds", options.durationSeconds());
        info.put("collectionIntervalMs", options.collectionIntervalMs());
        info.put("spreadWithinInterval", options.spreadWithinInterval());
        info.put("warmupSeconds", options.warmupSeconds());
        info.put("startedAt", Instant.ofEpochMilli(options.startedAt()).toString());
        info.put("branch", command(List.of("git", "rev-parse", "--abbrev-ref", "HEAD")));
        info.put("commit", command(List.of("git", "rev-parse", "HEAD")));
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVm", System.getProperty("java.vm.name"));
        info.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments());
        info.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        info.put("maxMemoryBytes", Runtime.getRuntime().maxMemory());
        info.put("redis", redisSnapshot(options));
        info.put("tdengine", tdengineInfo());
        info.put("mqttBrokerUrl", options.mqttBrokerUrl());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("run-info.json").toFile(), info);
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

    private void writeSummary(Path outputDir,
                              SoakOptions options,
                              List<DevicePoints> devicePoints,
                              SoakCounters counters,
                              List<Long> roundDurations,
                              List<MetricSample> samples,
                              MqttAckBridge ackBridge) throws IOException {
        MetricSample finalSample = samples.get(samples.size() - 1);
        long elapsedMs = Math.max(1L, finalSample.elapsedMs());
        long loadElapsedMs = Math.max(1L, counters.loadFinishedAt.get() - counters.loadStartedAt.get());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scenario", options.scenario());
        summary.put("points", options.points());
        summary.put("devices", options.devices());
        summary.put("durationSeconds", options.durationSeconds());
        summary.put("actualElapsedMs", elapsedMs);
        summary.put("activeLoadElapsedMs", loadElapsedMs);
        summary.put("rounds", counters.rounds.get());
        summary.put("submitted", counters.submitted.get());
        summary.put("succeeded", counters.succeeded.get());
        summary.put("failed", counters.failed.get());
        summary.put("rejected", counters.rejected.get());
        summary.put("pointsPerSecond", counters.submitted.get() * 1000.0d / loadElapsedMs);
        summary.put("roundP50Ms", percentile(roundDurations, 0.50d));
        summary.put("roundP95Ms", percentile(roundDurations, 0.95d));
        summary.put("roundP99Ms", percentile(roundDurations, 0.99d));
        summary.put("roundMaxMs", max(roundDurations));
        summary.put("roundMetricNote", "round*Ms 表示本测试发射周期，开启 pacing 时接近 collectionInterval，不代表 History 写入延迟");
        summary.put("heapPeakBytes", samples.stream().mapToLong(MetricSample::heapUsed).max().orElse(-1L));
        summary.put("heapEndBytes", finalSample.heapUsed());
        summary.put("threadPeak", samples.stream().mapToInt(MetricSample::threadCount).max().orElse(-1));
        summary.put("gcCount", finalSample.gcCount());
        summary.put("gcTimeMs", finalSample.gcTimeMs());
        summary.put("redisFinal", finalSample.redis());
        summary.put("telemetryEntryFinal", finalSample.entry());
        summary.put("historyFinal", finalSample.history());
        summary.put("historyBatchFinal", finalSample.historyBatch());
        summary.put("cloudFinal", finalSample.cloud());
        summary.put("executorQueuePeaks", executorQueuePeaks(samples));
        summary.put("executorActivePeaks", executorActivePeaks(samples));
        summary.put("executorRejectedPeaks", executorRejectedPeaks(samples));
        summary.put("executorCompletedFinal", executorCompletedFinal(finalSample));
        summary.put("historyLocalQueueSecondsAtMeasuredRate",
                estimateHistoryLocalSeconds(finalSample.history(), counters, loadElapsedMs));
        summary.put("cloudOutboxEstimate", estimateCloudOutbox(options, counters, loadElapsedMs));
        summary.put("ackBridgeReceived", ackBridge != null ? ackBridge.received.get() : 0L);
        summary.put("ackBridgeSent", ackBridge != null ? ackBridge.sent.get() : 0L);
        summary.put("ackBridgeFailed", ackBridge != null ? ackBridge.failed.get() : 0L);
        summary.put("deviceIds", devicePoints.stream().map(DevicePoints::deviceId).toList());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("summary.json").toFile(), summary);
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
                + "entryLegacyEnvelopeRecoveredItems,historyLocalPending,historyRejectedRedisBuffered,historyRejectedLocalBuffered,"
                + "historyRejectedDropped,historyBatchAcceptedRows,historyBatchFlushedBatches,historyBatchFlushedRows,"
                + "historyBatchWriteSuccess,historyBatchWriteFailure,historyBatchFallbackRows,historyBatchCurrentBuffered,"
                + "historyBatchBufferedPeak,historyBatchAverageSize,historyBatchSizeP50,historyBatchSizeP95,"
                + "historyBatchSizeMax,historyBatchLatencyP50Ms,historyBatchLatencyP95Ms,historyBatchLatencyP99Ms,"
                + "cloudTotal,cloudPending,cloudPublishing,cloudWaitingAck,cloudIsolated,"
                + "ackReceived,ackSent,ackFailed,threadPoolsJson\n");
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
                String.valueOf(sample.history().localPending()),
                String.valueOf(sample.history().rejectedRedisBuffered()),
                String.valueOf(sample.history().rejectedLocalBuffered()),
                String.valueOf(sample.history().rejectedDropped()),
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
                String.valueOf(sample.cloud().total()),
                String.valueOf(sample.cloud().pending()),
                String.valueOf(sample.cloud().publishing()),
                String.valueOf(sample.cloud().waitingAck()),
                String.valueOf(sample.cloud().isolated()),
                String.valueOf(sample.cloud().ackReceived()),
                String.valueOf(sample.cloud().ackSent()),
                String.valueOf(sample.cloud().ackFailed()),
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
                configManager.deleteLocalDeviceConfig(device.deviceId());
            } catch (RuntimeException ignored) {
                // Soak 清理失败不覆盖主测试结果，最终指标仍保留在结果目录。
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

    private static long percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
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
                                   long rejectedDropped) {
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
                                        long shutdownFlushedRows) {
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
                                RedisSnapshot redis,
                                EntryIngressSnapshot entry,
                                HistorySnapshot history,
                                HistoryBatchSnapshot historyBatch,
                                CloudSnapshot cloud) {
    }

    private static final class SoakCounters {
        private final AtomicLong submitted = new AtomicLong();
        private final AtomicLong succeeded = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong rejected = new AtomicLong();
        private final AtomicLong rounds = new AtomicLong();
        private final AtomicLong loadStartedAt = new AtomicLong();
        private final AtomicLong loadFinishedAt = new AtomicLong();
    }

    private record SoakOptions(String scenario,
                               int points,
                               int devices,
                               long durationSeconds,
                               long warmupSeconds,
                               long collectionIntervalMs,
                               long sampleIntervalSeconds,
                               long drainWaitSeconds,
                               long startedAt,
                               String streamKey,
                               String mqttBrokerUrl,
                               boolean ackBridgeEnabled,
                               boolean historyEnabled,
                               boolean streamEnabled,
                               boolean cloudEnabled,
                               boolean spreadWithinInterval,
                               long estimatedCloudMessageBytes,
                               long maxAllowedRejected,
                               Path outputDir) {

        static SoakOptions from(Environment environment) {
            int points = intValue(environment, "soak.points", 10_000);
            int defaultDevices = Math.max(1, points / 1000);
            int devices = intValue(environment, "soak.devices", defaultDevices);
            long startedAt = System.currentTimeMillis();
            String runId = RUN_ID_FORMATTER.format(Instant.ofEpochMilli(startedAt));
            String output = value(environment, "soak.metricsOutput", "target/soak-results/" + runId);
            return new SoakOptions(
                    value(environment, "soak.scenario", "normal"),
                    points,
                    devices,
                    longValue(environment, "soak.durationSeconds", 300L),
                    longValue(environment, "soak.warmupSeconds", 60L),
                    longValue(environment, "soak.collectionIntervalMs", 1000L),
                    longValue(environment, "soak.sampleIntervalSeconds", 5L),
                    longValue(environment, "soak.drainWaitSeconds", 30L),
                    startedAt,
                    value(environment, "spring.data.redis.stream.key", "collector:telemetry:stream"),
                    value(environment, "collector.report.mqtt.broker-url", "tcp://127.0.0.1:1883"),
                    booleanValue(environment, "soak.cloudAckBridgeEnabled", true),
                    booleanValue(environment, "soak.historyEnabled", true),
                    booleanValue(environment, "soak.streamEnabled", true),
                    booleanValue(environment, "soak.cloudEnabled", true),
                    booleanValue(environment, "soak.spreadWithinInterval", true),
                    longValue(environment, "soak.estimatedCloudMessageBytes", 1024L),
                    longValue(environment, "soak.maxAllowedRejected", 0L),
                    Path.of(output));
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
