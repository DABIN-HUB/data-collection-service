package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorTestProviders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionSchedulingCadenceTest {

    private ScheduledExecutorService timeSliceScheduler;

    @BeforeEach
    void setUp() {
        timeSliceScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        when(timeSliceScheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenReturn((ScheduledFuture) scheduledFuture);
        when(timeSliceScheduler.shutdownNow()).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        timeSliceScheduler.shutdownNow();
    }

    @Test
    void differentDeviceIntervalsMustProduceDifferentExecutionCounts() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-5s", 5_000L);
        fixture.addTask("device-10s", 10_000L);
        fixture.addTask("device-30s", 30_000L);

        for (int second = 0; second <= 30; second++) {
            fixture.executeAtMillis(TimeUnit.SECONDS.toMillis(second));
        }

        assertEquals(7, fixture.submittedPoints("device-5s"));
        assertEquals(4, fixture.submittedPoints("device-10s"));
        assertEquals(2, fixture.submittedPoints("device-30s"));
    }

    @Test
    void deviceCollectionIntervalMustControlActualCollectionCadence() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-interval", 5_000L);

        fixture.executeAtMillis(0);
        fixture.executeAtMillis(1_000);
        fixture.executeAtMillis(4_999);
        fixture.executeAtMillis(5_000);

        assertEquals(2, fixture.submittedPoints("device-interval"));
    }

    @Test
    void runtimeCurrentCollectionIntervalMustAffectNextDue() {
        AtomicLong nowNanos = new AtomicLong(0L);
        AtomicLong runtimeInterval = new AtomicLong(5_000L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-adaptive", point("device-adaptive", "p1", 5_000L),
                ignored -> runtimeInterval.get());

        fixture.executeAtMillis(0);
        runtimeInterval.set(10_000L);
        fixture.executeAtMillis(5_000);
        fixture.executeAtMillis(10_000);
        runtimeInterval.set(2_000L);
        fixture.executeAtMillis(12_000);

        assertEquals(3, fixture.submittedPoints("device-adaptive"));
    }

    @Test
    void timeSliceIntervalChangeMustNotChangeBusinessCollectionInterval() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-slice-interval", 5_000L);

        for (int millis = 0; millis < 5_000; millis += 500) {
            fixture.executeAtMillis(millis);
        }
        fixture.executeAtMillis(5_000);

        assertEquals(2, fixture.submittedPoints("device-slice-interval"));
    }

    @Test
    void timeSliceReplanMustPreservePointCadence() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-replan", 30_000L);

        fixture.executeAtMillis(0);
        fixture.executeAtMillis(10_000);
        fixture.replan(2, 500);
        fixture.executeAtMillis(11_000);
        fixture.executeAtMillis(30_000);

        assertEquals(2, fixture.submittedPoints("device-replan"));
    }

    @Test
    void timeSliceCountChangeMustNotResetCadenceAcrossReplan() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-count-replan", 10_000L);

        fixture.executeAtMillis(0);
        fixture.replan(8, 1_000);
        fixture.executeAtMillis(5_000);
        fixture.executeAtMillis(10_000);

        assertEquals(2, fixture.submittedPoints("device-count-replan"));
    }

    @Test
    void timeSliceIntervalChangeMustNotResetCadenceAcrossReplan() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-interval-replan", 5_000L);

        fixture.executeAtMillis(0);
        fixture.replan(1, 500);
        fixture.executeAtMillis(3_000);
        fixture.executeAtMillis(5_000);

        assertEquals(2, fixture.submittedPoints("device-interval-replan"));
    }

    @Test
    void adaptiveIntervalMustRemainContinuousAcrossReplan() {
        AtomicLong nowNanos = new AtomicLong(0L);
        AtomicLong runtimeInterval = new AtomicLong(5_000L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-adaptive-replan", point("device-adaptive-replan", "p1", 5_000L),
                ignored -> runtimeInterval.get());

        fixture.executeAtMillis(0);
        runtimeInterval.set(10_000L);
        fixture.executeAtMillis(2_000);
        fixture.replan(2, 500);
        fixture.executeAtMillis(3_000);
        fixture.executeAtMillis(10_000);
        runtimeInterval.set(2_000L);
        fixture.executeAtMillis(12_000);

        assertEquals(3, fixture.submittedPoints("device-adaptive-replan"));
    }

    @Test
    void multiplePointIntervalsMustRemainContinuousAcrossReplan() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        String deviceId = "device-multi-replan";
        List<DataPoint> points = List.of(
                point(deviceId, "p5", 5_000L),
                point(deviceId, "p10", 10_000L),
                point(deviceId, "p30", 30_000L));
        fixture.addTask(deviceId, points, DataPoint::getBaseCollectionInterval);

        fixture.executeAtMillis(0);
        fixture.executeAtMillis(5_000);
        fixture.executeAtMillis(10_000);
        fixture.replan(3, 500);
        fixture.executeAtMillis(12_000);
        fixture.executeAtMillis(15_000);
        fixture.executeAtMillis(20_000);
        fixture.executeAtMillis(30_000);

        assertEquals(6, fixture.submittedPoint("p5"));
        assertEquals(4, fixture.submittedPoint("p10"));
        assertEquals(2, fixture.submittedPoint("p30"));
    }

    @Test
    void repeatedTimeSliceReplanMustNotLeakCadenceState() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        String deviceId = "device-replan-leak";
        List<DataPoint> points = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            points.add(point(deviceId, "p" + i, 30_000L));
        }
        fixture.addTask(deviceId, points, DataPoint::getBaseCollectionInterval);

        fixture.executeAtMillis(0);
        for (int i = 0; i < 100; i++) {
            fixture.replan(1 + i % 4, 500);
        }

        assertEquals(1_000, fixture.cadenceStateSize());
    }

    @Test
    void deviceStopMustClearCadenceState() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-stop-cadence", 5_000L);

        fixture.executeAtMillis(0);
        assertEquals(1, fixture.cadenceStateSize());
        fixture.stopDevice("device-stop-cadence");

        assertEquals(0, fixture.cadenceStateSize());
    }

    @Test
    void stopStartNewGenerationMustStartWithFreshCadence() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        String deviceId = "device-generation-cadence";
        fixture.addTask(deviceId, 30_000L);
        fixture.executeAtMillis(0);

        fixture.stopDevice(deviceId);
        fixture.addTask(deviceId, 30_000L);
        fixture.executeAtMillis(1_000);

        assertEquals(2, fixture.submittedPoints(deviceId));
    }

    @Test
    void highFrequencyReplanMustNotCreateExtraCollectionBurst() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-high-replan", 30_000L);

        for (int second = 0; second <= 60; second++) {
            fixture.executeAtMillis(TimeUnit.SECONDS.toMillis(second));
            fixture.replan(1 + second % 4, 500);
        }

        assertEquals(3, fixture.submittedPoints("device-high-replan"));
    }

    @Test
    void oldSliceRunnableAfterReplanMustNotSubmitStaleTask() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-old-slice", 5_000L);
        long oldRevision = fixture.currentRevision();

        fixture.replan(2, 500);
        fixture.executeAtMillisWithRevision(0, oldRevision);

        assertEquals(0, fixture.submittedPoints("device-old-slice"));
    }

    @Test
    void concurrentDueCheckMustNotDoubleScheduleSamePoint() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-double-check", 5_000L);

        fixture.executeAtMillis(0);
        fixture.executeAtMillis(0);

        assertEquals(1, fixture.submittedPoints("device-double-check"));
    }

    @Test
    void slowCollectionWithReplanMustNotOverlapSamePoint() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.keepSubmittedPointsInFlight(true);
        fixture.addTask("device-slow-replan", 5_000L);

        fixture.executeAtMillis(0);
        fixture.replan(2, 500);
        fixture.executeAtMillis(5_000);
        fixture.executeAtMillis(12_000);

        assertEquals(1, fixture.submittedPoints("device-slow-replan"));
    }

    @Test
    void timeSliceCountChangeMustNotChangeBusinessCollectionInterval() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-slice-count", 5_000L);

        fixture.executeAtMillis(0);
        fixture.executeAtMillis(2_000);
        fixture.executeAtMillis(4_000);
        fixture.executeAtMillis(5_000);

        assertEquals(2, fixture.submittedPoints("device-slice-count"));
    }

    @Test
    void slowCollectionMustNotOverlapSameDevice() {
        AtomicLong nowNanos = new AtomicLong(TimeUnit.SECONDS.toNanos(5));
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        DeviceBatchTask task = fixture.addTask("device-slow", 5_000L);
        assertTrue(task.tryStartExecution());

        fixture.executeAtMillis(5_000);

        assertEquals(0, fixture.submittedPoints("device-slow"));
        task.finishExecution();
    }

    @Test
    void missedIntervalsMustNotCauseCatchUpStorm() {
        AtomicLong nowNanos = new AtomicLong(0L);
        SchedulerFixture fixture = new SchedulerFixture(nowNanos);
        fixture.addTask("device-missed", 5_000L);

        fixture.executeAtMillis(0);
        fixture.executeAtMillis(20_000);
        fixture.executeAtMillis(20_000);

        assertEquals(2, fixture.submittedPoints("device-missed"));
    }

    @Test
    void schedulerRuntimeShouldProducePlannerSizedReadPointsBatches() {
        ConfigManager configManager = mock(ConfigManager.class);
        DeviceInfo device = device("modbus-device", 5_000);
        when(configManager.getDevice("modbus-device")).thenReturn(device);
        DeviceBatchPlanner planner = new DeviceBatchPlanner(
                configManager,
                new CollectorProperties(),
                new PointRuntimeStateService(),
                new ProtocolBatchStrategy(),
                ProtocolDescriptorTestProviders.registry(),
                new PerformanceMonitor());
        List<DataPoint> points = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            DataPoint point = point("modbus-device", "p" + i, 5_000L);
            point.setDataType("INT16");
            point.setAddress(String.valueOf(40_001 + i));
            points.add(point);
        }

        List<DeviceBatchTask> tasks = planner.plan("modbus-device", points, 4, 1L, 1L);

        assertEquals(8, tasks.size());
        assertEquals(1_000, tasks.stream().mapToInt(task -> task.points.size()).sum());
        assertTrue(tasks.stream().allMatch(task -> task.points.size() <= 125));
    }

    @Test
    void realRuntime1000PointsShouldProduceExpectedMultipleReadPointsCalls() {
        schedulerRuntimeShouldProducePlannerSizedReadPointsBatches();
    }

    private DeviceInfo device(String deviceId, int intervalMs) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setProtocolType("MODBUS_TCP");
        device.setConnectionType("MODBUS_TCP");
        device.setCollectionInterval(intervalMs);
        return device;
    }

    private static DataPoint point(String deviceId, String pointId, long intervalMs) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setBaseCollectionInterval(intervalMs);
        return point;
    }

    private final class SchedulerFixture {
        private final AtomicLong nowNanos;
        private final SchedulerRuntimeState runtimeState = new SchedulerRuntimeState();
        private final ConfigManager configManager = mock(ConfigManager.class);
        private final DeviceLifecycleCoordinator lifecycleCoordinator = mock(DeviceLifecycleCoordinator.class);
        private final DeviceBatchExecutor batchExecutor = mock(DeviceBatchExecutor.class);
        private final CollectionScheduler scheduler;
        private final Map<String, Integer> submittedPoints = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, Integer> submittedPointCounts = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, java.util.function.ToLongFunction<DataPoint>> intervalResolvers =
                new java.util.concurrent.ConcurrentHashMap<>();
        private volatile boolean keepInFlight;

        private SchedulerFixture(AtomicLong nowNanos) {
            this.nowNanos = nowNanos;
            runtimeState.initializeTimeSlices(1, 1_000);
            doAnswer(invocation -> {
                String deviceId = invocation.getArgument(0);
                long generation = invocation.getArgument(1);
                List<DataPoint> points = invocation.getArgument(2);
                DeviceBatchTask task = new DeviceBatchTask(
                        deviceId,
                        points,
                        0,
                        generation,
                        runtimeState.getTimeSliceRevision(),
                        intervalResolvers.get(deviceId),
                        nowNanos::get);
                runtimeState.addBatchTasksIfRunning(deviceId, generation, List.of(task));
                return null;
            }).when(lifecycleCoordinator).scheduleDevicePoints(anyString(), anyLong(), anyList());
            when(batchExecutor.isBatchTaskActive(any(DeviceBatchTask.class))).thenReturn(true);
            when(batchExecutor.submit(any(DeviceBatchTask.class), anyList())).thenAnswer(invocation -> {
                DeviceBatchTask task = invocation.getArgument(0);
                List<DataPoint> duePoints = invocation.getArgument(1);
                task.markScheduled(runtimeState, duePoints);
                submittedPoints.merge(task.deviceId, duePoints.size(), Integer::sum);
                duePoints.forEach(point -> submittedPointCounts.merge(point.getPointId(), 1, Integer::sum));
                if (!keepInFlight) {
                    runtimeState.completePointSchedules(task.deviceId, task.generation, duePoints);
                }
                return CompletableFuture.completedFuture(null);
            });
            scheduler = new CollectionScheduler(
                    mock(CollectionManager.class),
                    configManager,
                    mock(CollectionStatistics.class),
                    new CollectorProperties(),
                    null,
                    runtimeState,
                    new PerformanceMonitor(),
                    lifecycleCoordinator,
                    batchExecutor,
                    mock(ReconnectCoordinator.class),
                    timeSliceScheduler);
        }

        private DeviceBatchTask addTask(String deviceId, long intervalMs) {
            return addTask(deviceId, point(deviceId, "p1", intervalMs), point -> intervalMs);
        }

        private DeviceBatchTask addTask(String deviceId,
                                        DataPoint point,
                                        java.util.function.ToLongFunction<DataPoint> intervalResolver) {
            return addTask(deviceId, List.of(point), intervalResolver);
        }

        private DeviceBatchTask addTask(String deviceId,
                                        List<DataPoint> points,
                                        java.util.function.ToLongFunction<DataPoint> intervalResolver) {
            long generation = 1L;
            DeviceScheduleInfo existing = runtimeState.getScheduleInfo(deviceId);
            if (existing != null) {
                generation = existing.getGeneration() + 1;
            }
            runtimeState.markRunning(deviceId, generation);
            when(configManager.getDataPoints(deviceId)).thenReturn(points);
            intervalResolvers.put(deviceId, intervalResolver);
            DeviceBatchTask task = new DeviceBatchTask(
                    deviceId,
                    points,
                    0,
                    generation,
                    runtimeState.getTimeSliceRevision(),
                    intervalResolver,
                    nowNanos::get);
            runtimeState.addBatchTasks(List.of(task));
            return task;
        }

        private void replan(int sliceCount, int intervalMs) {
            scheduler.applyTimeSliceConfigUpdate(sliceCount, intervalMs);
        }

        private void executeAtMillis(long millis) {
            executeAtMillisWithRevision(millis, runtimeState.getTimeSliceRevision());
        }

        private void executeAtMillisWithRevision(long millis, long revision) {
            nowNanos.set(TimeUnit.MILLISECONDS.toNanos(millis));
            scheduler.executeTimeSlice(0, revision);
        }

        private void stopDevice(String deviceId) {
            runtimeState.removeDeviceTasks(deviceId);
            runtimeState.removeDevice(deviceId);
        }

        private int submittedPoints(String deviceId) {
            return submittedPoints.getOrDefault(deviceId, 0);
        }

        private int submittedPoint(String pointId) {
            return submittedPointCounts.getOrDefault(pointId, 0);
        }

        private int cadenceStateSize() {
            return runtimeState.getCadenceStateSizeForTest();
        }

        private long currentRevision() {
            return runtimeState.getTimeSliceRevision();
        }

        private void keepSubmittedPointsInFlight(boolean keepInFlight) {
            this.keepInFlight = keepInFlight;
        }
    }
}
