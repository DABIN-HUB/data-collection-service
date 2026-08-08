package com.wangbin.collector.soak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.service.TimeSeriesService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 历史写入容量专项入口，使用真实 TimeSeriesService/DataRepository/TDengine 路径测量单条写入能力。
 */
@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "telemetry.tdengine.enabled=true",
        "collector.config.loader=file"
})
class HistoryStageCapacityIT {

    private static final DateTimeFormatter RUN_ID_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());

    @Autowired
    private TimeSeriesService timeSeriesService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Environment environment;

    @Test
    void benchmarkApplicationLevelSingleTelemetryWrites() throws IOException {
        CapacityOptions options = CapacityOptions.from(environment);
        Files.createDirectories(options.outputDir());
        List<Map<String, Object>> results = new ArrayList<>();

        for (int deviceCount : options.deviceVariants()) {
            for (int recordCount : options.recordCounts()) {
                results.add(runScenario(deviceCount, recordCount));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recordCounts", options.recordCounts());
        summary.put("deviceVariants", options.deviceVariants());
        summary.put("jdbcPool", jdbcPoolSnapshot());
        summary.put("results", results);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                options.outputDir().resolve("summary.json").toFile(), summary);
    }

    private Map<String, Object> runScenario(int deviceCount, int recordCount) {
        long baseEventTs = System.currentTimeMillis();
        List<Long> latencies = new ArrayList<>(recordCount);
        int succeeded = 0;
        long startedAt = System.nanoTime();
        for (int index = 0; index < recordCount; index++) {
            String deviceId = "history-capacity-dev-" + deviceCount + "-" + (index % deviceCount);
            DataPoint point = point(deviceId, index);
            ProcessResult result = ProcessResult.success(index, index);
            long writeStartedAt = System.nanoTime();
            timeSeriesService.append(deviceId, "MODBUS_TCP", point, result, baseEventTs + index);
            latencies.add(System.nanoTime() - writeStartedAt);
            succeeded++;
        }
        long totalNanos = System.nanoTime() - startedAt;
        assertEquals(recordCount, succeeded);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("devices", deviceCount);
        result.put("records", recordCount);
        result.put("succeeded", succeeded);
        result.put("totalMs", TimeUnit.NANOSECONDS.toMillis(totalNanos));
        result.put("rowsPerSecond", recordCount * 1_000_000_000.0d / Math.max(1L, totalNanos));
        result.put("writeP50Ms", percentileMillis(latencies, 0.50d));
        result.put("writeP95Ms", percentileMillis(latencies, 0.95d));
        result.put("writeP99Ms", percentileMillis(latencies, 0.99d));
        result.put("writeMaxMs", TimeUnit.NANOSECONDS.toMillis(latencies.stream().mapToLong(Long::longValue).max().orElse(0L)));
        result.put("jdbcPoolAfter", jdbcPoolSnapshot());
        return result;
    }

    private DataPoint point(String deviceId, int index) {
        DataPoint point = new DataPoint();
        point.setId((long) index + 1L);
        point.setDeviceId(deviceId);
        point.setDeviceName(deviceId);
        point.setPointId(deviceId + "-p-" + index);
        point.setPointCode("p_" + index);
        point.setPointName("capacity-" + index);
        point.setAddress("4" + String.format(Locale.ROOT, "%04d", (index % 9999) + 1));
        point.setDataType("DOUBLE");
        point.setReadWrite("R");
        point.setStatus(1);
        point.setUnit("value");
        point.setAdditionalConfig(Map.of("historyEnabled", true));
        return point;
    }

    private Map<String, Object> jdbcPoolSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            snapshot.put("maximumPoolSize", hikari.getMaximumPoolSize());
            snapshot.put("minimumIdle", hikari.getMinimumIdle());
            snapshot.put("activeConnections", pool != null ? pool.getActiveConnections() : -1);
            snapshot.put("idleConnections", pool != null ? pool.getIdleConnections() : -1);
            snapshot.put("threadsAwaitingConnection", pool != null ? pool.getThreadsAwaitingConnection() : -1);
            snapshot.put("totalConnections", pool != null ? pool.getTotalConnections() : -1);
        } else {
            snapshot.put("type", dataSource.getClass().getName());
        }
        return snapshot;
    }

    private long percentileMillis(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(sorted.size() * percentile) - 1;
        return TimeUnit.NANOSECONDS.toMillis(sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))));
    }

    private record CapacityOptions(List<Integer> recordCounts,
                                   List<Integer> deviceVariants,
                                   Path outputDir) {

        static CapacityOptions from(Environment environment) {
            long startedAt = System.currentTimeMillis();
            String runId = RUN_ID_FORMATTER.format(Instant.ofEpochMilli(startedAt));
            String output = value(environment, "history.capacity.output",
                    "target/soak-results/history-capacity-" + runId);
            return new CapacityOptions(
                    intList(value(environment, "history.capacity.records", "1000")),
                    intList(value(environment, "history.capacity.devices", "10")),
                    Path.of(output));
        }

        private static String value(Environment environment, String key, String defaultValue) {
            String value = environment.getProperty(key);
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }

        private static List<Integer> intList(String raw) {
            List<Integer> values = new ArrayList<>();
            for (String item : raw.split(",")) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                values.add(Math.max(1, Integer.parseInt(item.trim())));
            }
            return values.isEmpty() ? List.of(1) : values;
        }
    }
}
