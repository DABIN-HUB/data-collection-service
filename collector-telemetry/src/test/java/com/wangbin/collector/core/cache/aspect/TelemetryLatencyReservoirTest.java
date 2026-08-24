package com.wangbin.collector.core.cache.aspect;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryLatencyReservoirTest {

    @Test
    void reservoirMustRemainBoundedAfterMoreThanCapacitySamples() {
        TelemetryLatencyReservoir reservoir = new TelemetryLatencyReservoir(3);

        for (int index = 1; index <= 10; index++) {
            reservoir.add(index);
        }

        TelemetryLatencyReservoir.Snapshot snapshot = reservoir.snapshot();
        assertEquals(3, snapshot.sampleCount());
        assertEquals(10L, snapshot.totalRecorded());
        assertEquals(7L, snapshot.overwrittenSamples());
    }

    @Test
    void reservoirMustContinueSamplingAfterCapacityReached() {
        TelemetryLatencyReservoir reservoir = new TelemetryLatencyReservoir(3);

        for (int index = 1; index <= 6; index++) {
            reservoir.add(index);
        }

        assertEquals(5, reservoir.percentileInt(0.50D));
        assertEquals(6, reservoir.maxInt());
    }

    @Test
    void reservoirMustNeverUseNegativeArrayIndex() {
        TelemetryLatencyReservoir reservoir = new TelemetryLatencyReservoir(8);
        reservoir.setSequenceForTest(Integer.MAX_VALUE - 1L);

        assertDoesNotThrow(() -> {
            for (int index = 0; index < 8; index++) {
                reservoir.add(index + 1L);
            }
        });
        assertEquals(8, reservoir.snapshot().sampleCount());
    }

    @Test
    void sequenceNearIntegerMaxMustRemainSafe() {
        TelemetryLatencyReservoir reservoir = new TelemetryLatencyReservoir(8);
        reservoir.setSequenceForTest(Integer.MAX_VALUE - 2L);

        assertDoesNotThrow(() -> {
            for (int index = 0; index < 6; index++) {
                reservoir.add(index + 10L);
            }
        });
        assertEquals(6L, reservoir.snapshot().totalRecorded());
    }

    @Test
    void sequenceNearLongBoundaryMustRemainSafe() {
        TelemetryLatencyReservoir reservoir = new TelemetryLatencyReservoir(17);
        reservoir.setSequenceForTest(Long.MAX_VALUE - 2L);

        assertDoesNotThrow(() -> {
            for (int index = 0; index < 8; index++) {
                reservoir.add(index + 100L);
            }
        });
        assertEquals(8L, reservoir.snapshot().totalRecorded());
    }

    @Test
    void concurrentAddMustNotThrow() throws Exception {
        TelemetryLatencyReservoir reservoir = new TelemetryLatencyReservoir(128);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int thread = 0; thread < 8; thread++) {
            futures.add(executor.submit(() -> {
                await(start);
                for (int index = 0; index < 1_000; index++) {
                    reservoir.add(index + 1L);
                }
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        TelemetryLatencyReservoir.Snapshot snapshot = reservoir.snapshot();
        assertEquals(128, snapshot.sampleCount());
        assertEquals(8_000L, snapshot.totalRecorded());
    }

    @Test
    void concurrentAddAndSnapshotMustNotThrow() throws Exception {
        TelemetryLatencyReservoir reservoir = new TelemetryLatencyReservoir(256);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            await(start);
            for (int index = 0; index < 2_000; index++) {
                reservoir.add(index + 1L);
            }
        }));
        futures.add(executor.submit(() -> {
            await(start);
            for (int index = 0; index < 2_000; index++) {
                reservoir.snapshot();
            }
        }));

        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertTrue(reservoir.snapshot().sampleCount() <= 256);
    }

    @Test
    void resetDuringConcurrentAddMustRemainSafe() throws Exception {
        TelemetryLatencyReservoir reservoir = new TelemetryLatencyReservoir(64);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            await(start);
            for (int index = 0; index < 1_000; index++) {
                reservoir.add(index + 1L);
            }
        }));
        futures.add(executor.submit(() -> {
            await(start);
            for (int index = 0; index < 100; index++) {
                reservoir.reset();
            }
        }));

        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertTrue(reservoir.snapshot().sampleCount() <= 64);
    }

    @Test
    void percentilesMustReflectRecentSamplesAfterRingWrap() {
        TelemetryLatencyReservoir reservoir = new TelemetryLatencyReservoir(5);

        for (int index = 1; index <= 5; index++) {
            reservoir.add(index);
        }
        for (int index = 100; index <= 104; index++) {
            reservoir.add(index);
        }

        assertEquals(102, reservoir.percentileInt(0.50D));
        assertEquals(104, reservoir.maxInt());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
