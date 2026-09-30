package com.wangbin.collector.monitor.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionMonitorServiceTest {

    @AfterEach
    void cleanupMdc() {
        MDC.clear();
    }

    @Test
    void recentExceptionBufferShouldRemainBoundedToOneHundred() {
        ExceptionMonitorService service = new ExceptionMonitorService();

        for (int index = 0; index < 150; index++) {
            service.record(new IllegalStateException("boom-" + index), "device-" + index, "point");
        }

        ExceptionStatsSnapshot snapshot = service.getStats();
        assertThat(snapshot.getRecent()).hasSize(ExceptionMonitorService.MAX_RECENT);
        assertThat(snapshot.getTotalExceptions()).isEqualTo(150);
    }

    @Test
    void deviceCounterShouldHaveHardBoundAndRetainOverflow() {
        ExceptionMonitorService service = new ExceptionMonitorService();

        for (int index = 0; index < ExceptionMonitorService.MAX_DEVICE_COUNTER_KEYS + 25; index++) {
            service.record(new IllegalStateException("boom"), "device-" + index, "point");
        }

        ExceptionStatsSnapshot snapshot = service.getStats();
        assertThat(snapshot.getByDevice()).hasSizeLessThanOrEqualTo(ExceptionMonitorService.MAX_DEVICE_COUNTER_KEYS);
        assertThat(snapshot.getTrackedDeviceCount()).isLessThanOrEqualTo(ExceptionMonitorService.MAX_DEVICE_COUNTER_KEYS);
        assertThat(snapshot.getDeviceCapacity()).isEqualTo(ExceptionMonitorService.MAX_DEVICE_COUNTER_KEYS);
        assertThat(snapshot.getOtherDeviceExceptions()).isGreaterThan(0);
    }

    @Test
    void existingDeviceCounterShouldContinueAfterCapacityReached() {
        ExceptionMonitorService service = new ExceptionMonitorService();
        service.record(new IllegalStateException("first"), "device-0", "point");
        for (int index = 1; index < ExceptionMonitorService.MAX_DEVICE_COUNTER_KEYS + 10; index++) {
            service.record(new IllegalStateException("boom"), "device-" + index, "point");
        }

        service.record(new IllegalStateException("again"), "device-0", "point");

        ExceptionStatsSnapshot snapshot = service.getStats();
        assertThat(snapshot.getByDevice().get("device-0")).isEqualTo(2L);
        assertThat(snapshot.getByDevice()).hasSizeLessThanOrEqualTo(ExceptionMonitorService.MAX_DEVICE_COUNTER_KEYS);
    }

    @Test
    void categoryCounterShouldHaveHardBoundAndRetainOverflow() {
        ExceptionMonitorService service = new ExceptionMonitorService();
        List<RuntimeException> throwables = categoryThrowables();

        throwables.forEach(throwable -> service.record(throwable, "device", "point"));

        ExceptionStatsSnapshot snapshot = service.getStats();
        assertThat(snapshot.getByCategory()).hasSizeLessThanOrEqualTo(ExceptionMonitorService.MAX_CATEGORY_COUNTER_KEYS);
        assertThat(snapshot.getTrackedCategoryCount()).isLessThanOrEqualTo(ExceptionMonitorService.MAX_CATEGORY_COUNTER_KEYS);
        assertThat(snapshot.getCategoryCapacity()).isEqualTo(ExceptionMonitorService.MAX_CATEGORY_COUNTER_KEYS);
        assertThat(snapshot.getOtherCategoryExceptions()).isGreaterThan(0);
    }

    @Test
    void concurrentUniqueDeviceRecordingShouldNotBreakHardBound() throws Exception {
        ExceptionMonitorService service = new ExceptionMonitorService();
        int threads = 100;
        int perThread = 80;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int thread = 0; thread < threads; thread++) {
            final int threadIndex = thread;
            executor.submit(() -> {
                try {
                    start.await();
                    for (int index = 0; index < perThread; index++) {
                        service.record(new IllegalStateException("boom"), "concurrent-" + threadIndex + "-" + index, "point");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        ExceptionStatsSnapshot snapshot = service.getStats();
        assertThat(snapshot.getByDevice()).hasSizeLessThanOrEqualTo(ExceptionMonitorService.MAX_DEVICE_COUNTER_KEYS);
        assertThat(snapshot.getTrackedDeviceCount()).isLessThanOrEqualTo(ExceptionMonitorService.MAX_DEVICE_COUNTER_KEYS);
        assertThat(snapshot.getOtherDeviceExceptions()).isGreaterThan(0);
        assertThat(snapshot.getTotalExceptions()).isEqualTo((long) threads * perThread);
    }

    @Test
    void exceptionMessageShouldBeSanitizedAndLengthBounded() {
        ExceptionMonitorService service = new ExceptionMonitorService();
        String longTail = "x".repeat(10_000);
        service.record(new IllegalStateException("password=OBS_SECRET_054 token:OBS_TOKEN_054 Authorization=Bearer ABCDEFG " + longTail),
                "device", "point");

        String message = service.getStats().getRecent().get(0).getMessage();
        assertThat(message).doesNotContain("OBS_SECRET_054", "OBS_TOKEN_054", "ABCDEFG");
        assertThat(message).contains("password=***", "token:***", "Authorization=***");
        assertThat(message.length()).isLessThanOrEqualTo(ExceptionMonitorService.MAX_EXCEPTION_MESSAGE_LENGTH);
    }

    @Test
    void contextIdsAndRequestIdShouldBeBoundedAndCapturedFromMdc() {
        ExceptionMonitorService service = new ExceptionMonitorService();
        MDC.put("requestId", "obs-054-request-001");

        service.record(new IllegalStateException("boom"), "d".repeat(300), "p".repeat(300));

        ExceptionSummary summary = service.getStats().getRecent().get(0);
        assertThat(summary.getDeviceId()).hasSize(ExceptionMonitorService.MAX_CONTEXT_ID_LENGTH);
        assertThat(summary.getPointId()).hasSize(ExceptionMonitorService.MAX_CONTEXT_ID_LENGTH);
        assertThat(summary.getRequestId()).isEqualTo("obs-054-request-001");
        assertThat(summary.getExceptionType()).isEqualTo("IllegalStateException");
    }

    @Test
    void backgroundExceptionShouldNotInventRequestIdAndNullThrowableShouldBeUnknown() {
        ExceptionMonitorService service = new ExceptionMonitorService();

        service.record(null, null, null);

        ExceptionSummary summary = service.getStats().getRecent().get(0);
        assertThat(summary.getCategory()).isEqualTo("UNKNOWN");
        assertThat(summary.getExceptionType()).isEmpty();
        assertThat(summary.getMessage()).isEmpty();
        assertThat(summary.getRequestId()).isEmpty();
    }

    private List<RuntimeException> categoryThrowables() {
        return new ArrayList<>(List.of(
                new CategoryException00(), new CategoryException01(), new CategoryException02(), new CategoryException03(), new CategoryException04(),
                new CategoryException05(), new CategoryException06(), new CategoryException07(), new CategoryException08(), new CategoryException09(),
                new CategoryException10(), new CategoryException11(), new CategoryException12(), new CategoryException13(), new CategoryException14(),
                new CategoryException15(), new CategoryException16(), new CategoryException17(), new CategoryException18(), new CategoryException19(),
                new CategoryException20(), new CategoryException21(), new CategoryException22(), new CategoryException23(), new CategoryException24(),
                new CategoryException25(), new CategoryException26(), new CategoryException27(), new CategoryException28(), new CategoryException29(),
                new CategoryException30(), new CategoryException31(), new CategoryException32(), new CategoryException33(), new CategoryException34(),
                new CategoryException35(), new CategoryException36(), new CategoryException37(), new CategoryException38(), new CategoryException39(),
                new CategoryException40(), new CategoryException41(), new CategoryException42(), new CategoryException43(), new CategoryException44(),
                new CategoryException45(), new CategoryException46(), new CategoryException47(), new CategoryException48(), new CategoryException49(),
                new CategoryException50(), new CategoryException51(), new CategoryException52(), new CategoryException53(), new CategoryException54(),
                new CategoryException55(), new CategoryException56(), new CategoryException57(), new CategoryException58(), new CategoryException59(),
                new CategoryException60(), new CategoryException61(), new CategoryException62(), new CategoryException63(), new CategoryException64(),
                new CategoryException65(), new CategoryException66(), new CategoryException67(), new CategoryException68(), new CategoryException69()));
    }

    private static class CategoryException00 extends RuntimeException { }
    private static class CategoryException01 extends RuntimeException { }
    private static class CategoryException02 extends RuntimeException { }
    private static class CategoryException03 extends RuntimeException { }
    private static class CategoryException04 extends RuntimeException { }
    private static class CategoryException05 extends RuntimeException { }
    private static class CategoryException06 extends RuntimeException { }
    private static class CategoryException07 extends RuntimeException { }
    private static class CategoryException08 extends RuntimeException { }
    private static class CategoryException09 extends RuntimeException { }
    private static class CategoryException10 extends RuntimeException { }
    private static class CategoryException11 extends RuntimeException { }
    private static class CategoryException12 extends RuntimeException { }
    private static class CategoryException13 extends RuntimeException { }
    private static class CategoryException14 extends RuntimeException { }
    private static class CategoryException15 extends RuntimeException { }
    private static class CategoryException16 extends RuntimeException { }
    private static class CategoryException17 extends RuntimeException { }
    private static class CategoryException18 extends RuntimeException { }
    private static class CategoryException19 extends RuntimeException { }
    private static class CategoryException20 extends RuntimeException { }
    private static class CategoryException21 extends RuntimeException { }
    private static class CategoryException22 extends RuntimeException { }
    private static class CategoryException23 extends RuntimeException { }
    private static class CategoryException24 extends RuntimeException { }
    private static class CategoryException25 extends RuntimeException { }
    private static class CategoryException26 extends RuntimeException { }
    private static class CategoryException27 extends RuntimeException { }
    private static class CategoryException28 extends RuntimeException { }
    private static class CategoryException29 extends RuntimeException { }
    private static class CategoryException30 extends RuntimeException { }
    private static class CategoryException31 extends RuntimeException { }
    private static class CategoryException32 extends RuntimeException { }
    private static class CategoryException33 extends RuntimeException { }
    private static class CategoryException34 extends RuntimeException { }
    private static class CategoryException35 extends RuntimeException { }
    private static class CategoryException36 extends RuntimeException { }
    private static class CategoryException37 extends RuntimeException { }
    private static class CategoryException38 extends RuntimeException { }
    private static class CategoryException39 extends RuntimeException { }
    private static class CategoryException40 extends RuntimeException { }
    private static class CategoryException41 extends RuntimeException { }
    private static class CategoryException42 extends RuntimeException { }
    private static class CategoryException43 extends RuntimeException { }
    private static class CategoryException44 extends RuntimeException { }
    private static class CategoryException45 extends RuntimeException { }
    private static class CategoryException46 extends RuntimeException { }
    private static class CategoryException47 extends RuntimeException { }
    private static class CategoryException48 extends RuntimeException { }
    private static class CategoryException49 extends RuntimeException { }
    private static class CategoryException50 extends RuntimeException { }
    private static class CategoryException51 extends RuntimeException { }
    private static class CategoryException52 extends RuntimeException { }
    private static class CategoryException53 extends RuntimeException { }
    private static class CategoryException54 extends RuntimeException { }
    private static class CategoryException55 extends RuntimeException { }
    private static class CategoryException56 extends RuntimeException { }
    private static class CategoryException57 extends RuntimeException { }
    private static class CategoryException58 extends RuntimeException { }
    private static class CategoryException59 extends RuntimeException { }
    private static class CategoryException60 extends RuntimeException { }
    private static class CategoryException61 extends RuntimeException { }
    private static class CategoryException62 extends RuntimeException { }
    private static class CategoryException63 extends RuntimeException { }
    private static class CategoryException64 extends RuntimeException { }
    private static class CategoryException65 extends RuntimeException { }
    private static class CategoryException66 extends RuntimeException { }
    private static class CategoryException67 extends RuntimeException { }
    private static class CategoryException68 extends RuntimeException { }
    private static class CategoryException69 extends RuntimeException { }
}
