package com.wangbin.collector.api.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.api.controller.dto.CompactAllDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.CompactRealtimeDeltaResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.config.manager.ConfigManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Explicit realtime scale acceptance harness for Task 02.5.
 *
 * <p>This class intentionally ends with IT so it is not part of ordinary Maven test discovery.
 * Run explicitly with {@code -Dtest=RealtimeScaleSoakIT}.</p>
 */
class RealtimeScaleSoakIT {

    private static final int DEVICE_COUNT = 100;
    private static final int POINTS_PER_DEVICE = 1_000;
    private static final int TOTAL_POINTS = DEVICE_COUNT * POINTS_PER_DEVICE;
    private static final int MAX_DELTA_ROWS = 20_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void realtimeDeltaScaleShouldRemainBoundedByChangedRowsAndNotClients() throws Exception {
        ScaleFixture fixture = ScaleFixture.create(DEVICE_COUNT, POINTS_PER_DEVICE);
        RealtimeChangeTracker tracker = new RealtimeChangeTracker("scale-it-snapshot");
        RecordingCache cache = new RecordingCache(fixture.values);
        ConfigManager configManager = mock(ConfigManager.class);
        PointRuntimeStateService runtimeStateService = mock(PointRuntimeStateService.class);
        for (String deviceId : fixture.deviceIds) {
            when(configManager.getDataPoints(deviceId)).thenReturn(fixture.pointsByDevice.get(deviceId));
        }
        when(configManager.getAllDeviceIds()).thenReturn(fixture.deviceIds);
        MultiLevelCacheManager cacheManager = mock(MultiLevelCacheManager.class);
        when(cacheManager.getAll(ArgumentMatchers.<List<CacheKey>>any())).thenAnswer(invocation -> cache.getAll(invocation.getArgument(0)));
        RealtimeDataQueryApplicationService service = new RealtimeDataQueryApplicationService(
                cacheManager,
                configManager,
                runtimeStateService,
                tracker);

        MemorySnapshot startMemory = MemorySnapshot.capture();
        long recordStarted = System.nanoTime();
        for (DataPoint point : fixture.allPoints) {
            tracker.record(point.getDeviceId(), point.getPointId(), fixture.values.get(CacheKey.dataKey(point.getDeviceId(), point.getPointId())));
        }
        long recordNanos = System.nanoTime() - recordStarted;
        MemorySnapshot afterTrackerMemory = MemorySnapshot.capture();
        assertEquals(TOTAL_POINTS, tracker.trackedPointCount());

        CompactAllDeviceRealtimeDataResponse full = service.getCompactAllRealtimeData();
        assertEquals("success", full.getStatus());
        assertEquals(DEVICE_COUNT, full.getDeviceCount());
        assertEquals(TOTAL_POINTS, full.getDataCount());
        assertEquals(TOTAL_POINTS, full.getRows().size());
        long fullBytes = jsonBytes(full);

        List<RealtimeChangeTracker.SnapshotCursor> clientCursors = new ArrayList<>();
        for (int client = 0; client < 10; client += 1) {
            CompactAllDeviceRealtimeDataResponse clientFull = service.getCompactAllRealtimeData();
            clientCursors.add(new RealtimeChangeTracker.SnapshotCursor(
                    clientFull.getSnapshotId(), clientFull.getConfigEpoch(), clientFull.getRevision()));
        }
        assertEquals(TOTAL_POINTS, tracker.trackedPointCount(), "trackedPointCount must not grow by client count");

        DeltaMeasurement zero = measureDelta(service, cache, clientCursors, tracker, 0, fixture, fullBytes);
        assertEquals(0, zero.changedCount());
        assertEquals(0, zero.rows());
        assertEquals(0, zero.cacheGetAllCalls());
        assertEquals(0, zero.cacheRequestedKeys());
        assertFalse(zero.resetRequired());

        DeltaMeasurement onePercent = measureDelta(service, cache, clientCursors, tracker, 1_000, fixture, fullBytes);
        assertEquals(1_000, onePercent.changedCount());
        assertEquals(1_000, onePercent.rows());
        assertEquals(1, onePercent.cacheGetAllCalls());
        assertEquals(1_000, onePercent.cacheRequestedKeys());
        assertFalse(onePercent.resetRequired());

        DeltaMeasurement tenPercent = measureDelta(service, cache, clientCursors, tracker, 10_000, fixture, fullBytes);
        assertEquals(10_000, tenPercent.changedCount());
        assertEquals(10_000, tenPercent.rows());
        assertEquals(1, tenPercent.cacheGetAllCalls());
        assertEquals(10_000, tenPercent.cacheRequestedKeys());
        assertFalse(tenPercent.resetRequired());

        DeltaMeasurement twentyPercent = measureDelta(service, cache, clientCursors, tracker, MAX_DELTA_ROWS, fixture, fullBytes);
        assertEquals(MAX_DELTA_ROWS, twentyPercent.changedCount());
        assertEquals(MAX_DELTA_ROWS, twentyPercent.rows());
        assertEquals(1, twentyPercent.cacheGetAllCalls());
        assertEquals(MAX_DELTA_ROWS, twentyPercent.cacheRequestedKeys());
        assertFalse(twentyPercent.resetRequired());

        DeltaMeasurement boundaryOverflow = measureDelta(service, cache, clientCursors, tracker, MAX_DELTA_ROWS + 1, fixture, fullBytes);
        assertTrue(boundaryOverflow.resetRequired());
        assertEquals("DELTA_TOO_LARGE", boundaryOverflow.resetReason());
        assertEquals(0, boundaryOverflow.rows());
        assertEquals(0, boundaryOverflow.cacheGetAllCalls());
        assertEquals(0, boundaryOverflow.cacheRequestedKeys());

        DeltaMeasurement fiftyPercent = measureDelta(service, cache, clientCursors, tracker, 50_000, fixture, fullBytes);
        assertTrue(fiftyPercent.resetRequired());
        assertEquals("DELTA_TOO_LARGE", fiftyPercent.resetReason());
        assertEquals(0, fiftyPercent.cacheGetAllCalls());

        DeltaMeasurement fullChange = measureDelta(service, cache, clientCursors, tracker, TOTAL_POINTS, fixture, fullBytes);
        assertTrue(fullChange.resetRequired());
        assertEquals("DELTA_TOO_LARGE", fullChange.resetReason());
        assertEquals(0, fullChange.cacheGetAllCalls());

        long beforeSameValueRevision = tracker.currentRevision();
        int beforeSameValueTracked = tracker.trackedPointCount();
        DataPoint stablePoint = fixture.allPoints.get(0);
        Object stableValue = fixture.values.get(CacheKey.dataKey(stablePoint.getDeviceId(), stablePoint.getPointId()));
        for (int cycle = 0; cycle < 100; cycle += 1) {
            tracker.record(stablePoint.getDeviceId(), stablePoint.getPointId(), stableValue);
        }
        assertEquals(beforeSameValueRevision, tracker.currentRevision());
        assertEquals(beforeSameValueTracked, tracker.trackedPointCount());

        MemorySnapshot afterClientCyclesMemory = MemorySnapshot.capture();
        assertEquals(TOTAL_POINTS, tracker.trackedPointCount());

        System.out.printf("[METRIC] tracker populate 100k ms=%d trackedPointCount=%d%n",
                TimeUnit.NANOSECONDS.toMillis(recordNanos), tracker.trackedPointCount());
        System.out.printf("[METRIC] full 100k rawBytes=%d MiB=%.2f%n", fullBytes, fullBytes / 1024D / 1024D);
        printDeltaMetric("0%", zero);
        printDeltaMetric("1%", onePercent);
        printDeltaMetric("10%", tenPercent);
        printDeltaMetric("20%", twentyPercent);
        printDeltaMetric("20,001", boundaryOverflow);
        printDeltaMetric("50%", fiftyPercent);
        printDeltaMetric("100%", fullChange);
        System.out.printf("[METRIC] memory heapStart=%d heapAfterTracker=%d heapAfterClients=%d gcCount=%d gcTimeMs=%d%n",
                startMemory.heapUsed(), afterTrackerMemory.heapUsed(), afterClientCyclesMemory.heapUsed(),
                afterClientCyclesMemory.gcCount() - startMemory.gcCount(),
                afterClientCyclesMemory.gcTimeMs() - startMemory.gcTimeMs());
    }

    private DeltaMeasurement measureDelta(RealtimeDataQueryApplicationService service,
                                          RecordingCache cache,
                                          List<RealtimeChangeTracker.SnapshotCursor> clientCursors,
                                          RealtimeChangeTracker tracker,
                                          int changedRows,
                                          ScaleFixture fixture,
                                          long fullBytes) throws JsonProcessingException {
        RealtimeChangeTracker.SnapshotCursor cursor = tracker.capture();
        mutateFirstN(fixture, tracker, changedRows, tracker.currentRevision() + 1);
        for (int index = 1; index < clientCursors.size(); index += 1) {
            RealtimeChangeTracker.SnapshotCursor otherClient = clientCursors.get(index);
            CompactRealtimeDeltaResponse otherDelta = service.getCompactAllRealtimeDelta(
                    otherClient.snapshotId(), otherClient.configEpoch(), otherClient.revision());
            assertNotNull(otherDelta.getRevision());
            clientCursors.set(index, new RealtimeChangeTracker.SnapshotCursor(
                    otherDelta.getSnapshotId(), otherDelta.getConfigEpoch(), otherDelta.getRevision()));
        }
        assertEquals(TOTAL_POINTS, tracker.trackedPointCount(), "trackedPointCount must remain independent from clients");

        List<Long> timings = new ArrayList<>();
        CompactRealtimeDeltaResponse measured = null;
        int maxGetAllCallsPerRequest = 0;
        int maxRequestedKeysPerRequest = 0;
        for (int sample = 0; sample < 5; sample += 1) {
            cache.resetStats();
            long started = System.nanoTime();
            measured = service.getCompactAllRealtimeDelta(cursor.snapshotId(), cursor.configEpoch(), cursor.revision());
            timings.add(System.nanoTime() - started);
            maxGetAllCallsPerRequest = Math.max(maxGetAllCallsPerRequest, cache.getAllCalls);
            maxRequestedKeysPerRequest = Math.max(maxRequestedKeysPerRequest, cache.requestedKeys);
        }
        long responseBytes = jsonBytes(measured);
        boolean reset = Boolean.TRUE.equals(measured.getResetRequired());
        if (changedRows <= MAX_DELTA_ROWS) {
            assertFalse(reset, "changedRows=" + changedRows + " should stay delta");
        }
        List<Long> sorted = timings.stream().sorted().toList();
        return new DeltaMeasurement(
                changedRows,
                measured.getChangedCount() == null ? 0 : measured.getChangedCount(),
                measured.getRows() == null ? 0 : measured.getRows().size(),
                reset,
                measured.getResetReason(),
                maxGetAllCallsPerRequest,
                maxRequestedKeysPerRequest,
                responseBytes,
                fullBytes,
                TimeUnit.NANOSECONDS.toMillis(sorted.get(sorted.size() / 2)),
                TimeUnit.NANOSECONDS.toMillis(sorted.get(sorted.size() - 1)),
                TimeUnit.NANOSECONDS.toMillis(sorted.get(0)));
    }

    private static void mutateFirstN(ScaleFixture fixture,
                                     RealtimeChangeTracker tracker,
                                     int changedRows,
                                     long valueSeed) {
        for (int index = 0; index < changedRows; index += 1) {
            DataPoint point = fixture.allPoints.get(index);
            CacheKey key = CacheKey.dataKey(point.getDeviceId(), point.getPointId());
            long value = valueSeed + index;
            fixture.values.put(key, value);
            tracker.record(point.getDeviceId(), point.getPointId(), value);
        }
    }

    private static long jsonBytes(Object value) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void printDeltaMetric(String label, DeltaMeasurement measurement) {
        System.out.printf("[METRIC] delta %s changed=%d mode=%s cacheKeys=%d bytes=%d medianMs=%d maxMs=%d resetReason=%s%n",
                label,
                measurement.requestedChangeRows(),
                measurement.resetRequired() ? "full reset" : "delta",
                measurement.cacheRequestedKeys(),
                measurement.resetRequired() ? measurement.fullBytes() : measurement.responseBytes(),
                measurement.medianMs(),
                measurement.maxMs(),
                measurement.resetReason());
    }

    private static final class RecordingCache {
        private final Map<CacheKey, Object> values;
        private int getAllCalls;
        private int requestedKeys;

        private RecordingCache(Map<CacheKey, Object> values) {
            this.values = values;
        }

        private Map<CacheKey, Object> getAll(List<CacheKey> keys) {
            if (keys == null || keys.isEmpty()) {
                return Collections.emptyMap();
            }
            getAllCalls += 1;
            requestedKeys += keys.size();
            Map<CacheKey, Object> result = new LinkedHashMap<>();
            for (CacheKey key : keys) {
                if (values.containsKey(key)) {
                    result.put(key, values.get(key));
                }
            }
            return result;
        }

        private void resetStats() {
            getAllCalls = 0;
            requestedKeys = 0;
        }
    }

    private record DeltaMeasurement(int requestedChangeRows,
                                    int changedCount,
                                    int rows,
                                    boolean resetRequired,
                                    String resetReason,
                                    int cacheGetAllCalls,
                                    int cacheRequestedKeys,
                                    long responseBytes,
                                    long fullBytes,
                                    long medianMs,
                                    long maxMs,
                                    long minMs) {
    }

    private record MemorySnapshot(long heapUsed, long nonHeapUsed, long gcCount, long gcTimeMs) {
        private static MemorySnapshot capture() {
            MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();
            long gcCount = 0;
            long gcTime = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcCount += Math.max(bean.getCollectionCount(), 0);
                gcTime += Math.max(bean.getCollectionTime(), 0);
            }
            return new MemorySnapshot(heap.getUsed(), nonHeap.getUsed(), gcCount, gcTime);
        }
    }

    private record ScaleFixture(List<String> deviceIds,
                                List<DataPoint> allPoints,
                                Map<String, List<DataPoint>> pointsByDevice,
                                Map<CacheKey, Object> values) {
        private static ScaleFixture create(int devices, int pointsPerDevice) {
            List<String> deviceIds = new ArrayList<>();
            List<DataPoint> allPoints = new ArrayList<>(devices * pointsPerDevice);
            Map<String, List<DataPoint>> pointsByDevice = new LinkedHashMap<>();
            Map<CacheKey, Object> values = new LinkedHashMap<>();
            for (int deviceIndex = 1; deviceIndex <= devices; deviceIndex += 1) {
                String deviceId = "realtime-scale-it-" + String.format("%03d", deviceIndex);
                deviceIds.add(deviceId);
                List<DataPoint> points = new ArrayList<>(pointsPerDevice);
                for (int pointIndex = 1; pointIndex <= pointsPerDevice; pointIndex += 1) {
                    DataPoint point = point(deviceId, pointIndex);
                    points.add(point);
                    allPoints.add(point);
                    values.put(CacheKey.dataKey(deviceId, point.getPointId()), pointIndex);
                }
                pointsByDevice.put(deviceId, points);
            }
            allPoints.sort(Comparator.comparing(DataPoint::getDeviceId).thenComparing(DataPoint::getPointId));
            return new ScaleFixture(deviceIds, allPoints, pointsByDevice, values);
        }

        private static DataPoint point(String deviceId, int pointIndex) {
            String pointId = "p" + String.format("%06d", pointIndex);
            DataPoint point = new DataPoint();
            point.setDeviceId(deviceId);
            point.setPointId(pointId);
            point.setPointCode(pointId);
            point.setPointName("Scale Point " + pointId);
            point.setAddress("/points/" + pointId);
            point.setDataType("DOUBLE");
            point.setReadWrite("R");
            point.setScalingFactor(1.0D);
            point.setUnit("unit");
            point.setStatus(1);
            return point;
        }
    }
}
