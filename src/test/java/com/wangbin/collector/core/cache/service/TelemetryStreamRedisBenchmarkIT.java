package com.wangbin.collector.core.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.config.TelemetryStreamProperties;
import com.wangbin.collector.core.processor.ProcessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用真实 Redis 评估当前 Redis Stream 单条 XADD 路径吞吐。
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "telemetry.tdengine.enabled=false",
        "collector.report.enabled=false",
        "collector.config.loader=file",
        "spring.data.redis.stream.enabled=true",
        "spring.data.redis.stream.key=collector:test:stream:xadd:benchmark",
        "spring.data.redis.stream.max-length=1000000"
})
@EnabledIfSystemProperty(named = "stream.benchmark.enabled", matches = "true")
class TelemetryStreamRedisBenchmarkIT {

    @Autowired
    private TelemetryStreamService telemetryStreamService;

    @Autowired
    private TelemetryStreamProperties streamProperties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void benchmarkSingleAndParallelXadd() throws Exception {
        redisTemplate.delete(streamProperties.getKey());
        List<Map<String, Object>> results = new ArrayList<>();
        results.add(runClusteredRateCase("clustered-2000ps", 2_000, 10));
        results.add(runClusteredRateCase("clustered-2500ps", 2_500, 10));
        results.add(runClusteredRateCase("clustered-3000ps", 3_000, 10));
        writeSummary(results);
    }

    private Map<String, Object> runCase(String name, int threads, int records) throws Exception {
        TelemetryStreamMetrics before = telemetryStreamService.metrics();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(records));
        long startedAt = System.nanoTime();
        if (threads == 1) {
            for (int index = 0; index < records; index++) {
                appendOne(name, index, latencies);
            }
        } else {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                for (int index = 0; index < records; index++) {
                    int current = index;
                    executor.execute(() -> appendOne(name, current, latencies));
                }
            } finally {
                executor.shutdown();
                assertTrue(executor.awaitTermination(2, TimeUnit.MINUTES));
            }
        }
        long elapsedNanos = System.nanoTime() - startedAt;
        waitForRows(before.redisXaddRows() + records, TimeUnit.SECONDS.toNanos(30));
        TelemetryStreamMetrics after = telemetryStreamService.metrics();
        long success = after.xaddSuccess() - before.xaddSuccess();
        long failure = after.xaddFailure() - before.xaddFailure();
        long dropped = after.admissionDropped() - before.admissionDropped();
        assertTrue(success >= records - dropped);
        assertEquals(0L, failure);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", name);
        result.put("threads", threads);
        result.put("records", records);
        result.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        result.put("throughputPerSecond", records * 1_000_000_000.0D / Math.max(1L, elapsedNanos));
        result.put("latencyP50Ms", percentileMillis(latencies, 0.50D));
        result.put("latencyP95Ms", percentileMillis(latencies, 0.95D));
        result.put("latencyP99Ms", percentileMillis(latencies, 0.99D));
        result.put("xaddSuccessObserved", success);
        result.put("xaddFailure", failure);
        result.put("admissionDropped", dropped);
        result.put("bufferPeak", after.bufferPeak());
        result.put("redisPipelineCalls", after.redisPipelineCalls() - before.redisPipelineCalls());
        result.put("redisBatchLatencyP95Ms", after.redisBatchLatencyP95Ms());
        return result;
    }

    private Map<String, Object> runClusteredRateCase(String name, int targetRowsPerSecond, int seconds) throws Exception {
        TelemetryStreamMetrics before = telemetryStreamService.metrics();
        int totalRows = targetRowsPerSecond * seconds;
        List<Long> admissionLatencies = new ArrayList<>(totalRows);
        long startedAt = System.nanoTime();
        for (int second = 0; second < seconds; second++) {
            long secondStart = System.nanoTime();
            for (int index = 0; index < targetRowsPerSecond; index++) {
                appendOne(name, second * targetRowsPerSecond + index, admissionLatencies);
            }
            long targetElapsed = TimeUnit.SECONDS.toNanos(second + 1L);
            long remaining = startedAt + targetElapsed - System.nanoTime();
            if (remaining > 0L) {
                TimeUnit.NANOSECONDS.sleep(remaining);
            } else if (System.nanoTime() - secondStart < TimeUnit.MILLISECONDS.toNanos(100)) {
                Thread.yield();
            }
        }
        waitForRows(before.redisXaddRows() + totalRows, TimeUnit.SECONDS.toNanos(30));
        long elapsedNanos = System.nanoTime() - startedAt;
        TelemetryStreamMetrics after = telemetryStreamService.metrics();
        long success = after.redisXaddRows() - before.redisXaddRows();
        long failure = after.redisXaddFailures() - before.redisXaddFailures();
        long dropped = after.admissionDropped() - before.admissionDropped();
        assertEquals(0L, failure);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", name);
        result.put("threads", 1);
        result.put("records", totalRows);
        result.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        result.put("throughputPerSecond", success * 1_000_000_000.0D / Math.max(1L, elapsedNanos));
        result.put("latencyP50Ms", percentileMillis(admissionLatencies, 0.50D));
        result.put("latencyP95Ms", percentileMillis(admissionLatencies, 0.95D));
        result.put("latencyP99Ms", percentileMillis(admissionLatencies, 0.99D));
        result.put("xaddSuccessObserved", success);
        result.put("xaddFailure", failure);
        result.put("admissionDropped", dropped);
        result.put("bufferPeak", after.bufferPeak());
        result.put("redisPipelineCalls", after.redisPipelineCalls() - before.redisPipelineCalls());
        result.put("redisBatchLatencyP95Ms", after.redisBatchLatencyP95Ms());
        return result;
    }

    private void appendOne(String caseName, int index, List<Long> latencies) {
        DataPoint point = new DataPoint();
        point.setDeviceId("stream-bench-" + caseName);
        point.setPointId("p-" + index);
        point.setPointCode("p-" + index);
        point.setPointName("benchmark-" + index);
        long startedAt = System.nanoTime();
        telemetryStreamService.append(point.getDeviceId(), point, ProcessResult.success(index, index));
        latencies.add(System.nanoTime() - startedAt);
    }

    private void writeSummary(List<Map<String, Object>> results) throws IOException {
        Path output = Path.of("target", "soak-results", "stream-xadd-benchmark");
        Files.createDirectories(output);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generatedAt", Instant.now().toString());
        summary.put("redisStreamKey", streamProperties.getKey());
        summary.put("results", results);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("summary.json").toFile(), summary);
        List<String> lines = new ArrayList<>();
        lines.add("case,threads,records,elapsedMs,throughputPerSecond,latencyP50Ms,latencyP95Ms,latencyP99Ms,xaddSuccessObserved,xaddFailure,admissionDropped,bufferPeak,redisPipelineCalls,redisBatchLatencyP95Ms");
        for (Map<String, Object> result : results) {
            lines.add(String.join(",",
                    String.valueOf(result.get("case")),
                    String.valueOf(result.get("threads")),
                    String.valueOf(result.get("records")),
                    String.valueOf(result.get("elapsedMs")),
                    String.valueOf(result.get("throughputPerSecond")),
                    String.valueOf(result.get("latencyP50Ms")),
                    String.valueOf(result.get("latencyP95Ms")),
                    String.valueOf(result.get("latencyP99Ms")),
                    String.valueOf(result.get("xaddSuccessObserved")),
                    String.valueOf(result.get("xaddFailure")),
                    String.valueOf(result.get("admissionDropped")),
                    String.valueOf(result.get("bufferPeak")),
                    String.valueOf(result.get("redisPipelineCalls")),
                    String.valueOf(result.get("redisBatchLatencyP95Ms"))));
        }
        Files.write(output.resolve("summary.csv"), lines, StandardCharsets.UTF_8);
    }

    private void waitForRows(long expectedRows, long timeoutNanos) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutNanos;
        while (System.nanoTime() < deadline) {
            if (telemetryStreamService.metrics().redisXaddRows() >= expectedRows) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(telemetryStreamService.metrics().redisXaddRows() >= expectedRows);
    }

    private double percentileMillis(List<Long> values, double percentile) {
        List<Long> snapshot;
        synchronized (values) {
            snapshot = new ArrayList<>(values);
        }
        snapshot.sort(Long::compareTo);
        int index = Math.min(snapshot.size() - 1, (int) Math.ceil(snapshot.size() * percentile) - 1);
        return snapshot.get(Math.max(0, index)) / 1_000_000.0D;
    }
}
