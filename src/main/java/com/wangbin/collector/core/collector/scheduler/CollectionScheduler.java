package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import com.wangbin.collector.core.config.model.DeviceContext;
import com.wangbin.collector.monitor.health.CollectionServiceHealthTracker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 采集调度总控。
 */
@Slf4j
@Service
public class CollectionScheduler {

    private static final long CONFIG_RESTART_DEBOUNCE_MS = 1000L;

    @Autowired
    private CollectionManager collectionManager;

    @Autowired
    private ConfigManager configManager;

    @Autowired
    private CollectionStatistics collectionStatistics;

    @Autowired
    private CollectorProperties collectorProperties;

    @Autowired
    private CollectionServiceHealthTracker collectionServiceHealthTracker;

    @Autowired
    private DeviceBatchPlanner deviceBatchPlanner;

    @Autowired
    private CollectedDataProcessor collectedDataProcessor;

    @Autowired
    private CollectionTaskGuard collectionTaskGuard;

    private final ScheduledExecutorService timeSliceScheduler;
    private final ExecutorService batchDispatcher;
    private final ThreadPoolExecutor asyncCollectorPool;
    private final ThreadPoolExecutor dataProcessorPool;

    private final Map<String, DeviceScheduleInfo> deviceScheduleInfo = new ConcurrentHashMap<>();
    private final Map<Integer, List<DeviceBatchTask>> timeSliceTasks = new ConcurrentHashMap<>();
    private final Map<Integer, ScheduledFuture<?>> timeSliceScheduleFutures = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingConfigRestartTasks = new ConcurrentHashMap<>();
    private final Map<String, Set<Future<?>>> deviceInFlightCollectFutures = new ConcurrentHashMap<>();
    private final Map<String, Set<CompletableFuture<?>>> deviceInFlightProcessFutures = new ConcurrentHashMap<>();
    private final Set<String> startingDevices = ConcurrentHashMap.newKeySet();
    private final PerformanceMonitor performanceMonitor = new PerformanceMonitor();
    private final ReentrantLock scheduleLock = new ReentrantLock();
    private final AtomicInteger deviceStartThreadIndex = new AtomicInteger(0);
    private final ExecutorService deviceStartExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "device-start-" + deviceStartThreadIndex.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicInteger timeSliceCount = new AtomicInteger(2);
    private final AtomicInteger timeSliceInterval = new AtomicInteger(1000);
    private final AtomicLong timeSliceRevision = new AtomicLong(0);
    private TimeSliceTuner timeSliceTuner;

    @Autowired
    public CollectionScheduler(
            @Qualifier("timeSliceScheduler") ScheduledExecutorService timeSliceScheduler,
            @Qualifier("batchDispatcherExecutor") ExecutorService batchDispatcher,
            @Qualifier("asyncCollectorExecutor") ThreadPoolExecutor asyncCollectorPool,
            @Qualifier("dataProcessorExecutor") ThreadPoolExecutor dataProcessorPool) {
        this.timeSliceScheduler = timeSliceScheduler;
        this.batchDispatcher = batchDispatcher;
        this.asyncCollectorPool = asyncCollectorPool;
        this.dataProcessorPool = dataProcessorPool;
    }

    public PerformanceStatsSnapshot getPerformanceSnapshot() {
        return PerformanceStatsSnapshot.builder()
                .timeSliceCount(timeSliceCount.get())
                .timeSliceIntervalMs(timeSliceInterval.get())
                .timeSliceExecutionTimes(performanceMonitor.getTimeSliceExecutionTimesSnapshot())
                .overloadedSlices(performanceMonitor.getOverloadedSlicesSnapshot())
                .slowestDevices(performanceMonitor.getSlowestDevicesSnapshot())
                .deviceStats(performanceMonitor.getAllDevicePerformance())
                .build();
    }

    @PostConstruct
    public void init() {
        int normalizedSliceCount = Math.max(1, Math.min(
                collectorProperties.getScheduler().getInitialTimeSliceCount(),
                collectorProperties.getScheduler().getMaxTimeSliceCount()
        ));
        timeSliceCount.set(normalizedSliceCount);
        int normalizedInterval = Math.max(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                collectorProperties.getScheduler().getInitialTimeSliceIntervalMs()
        );
        timeSliceInterval.set(normalizedInterval);
        int maxInterval = Math.max(
                collectorProperties.getScheduler().getDefaultTimeSliceIntervalMs() * 2,
                normalizedInterval
        );
        this.timeSliceTuner = new TimeSliceTuner(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                maxInterval,
                normalizedInterval
        );
        timeSliceRevision.set(1L);
        resetTimeSliceTaskBuckets(timeSliceCount.get());
        startTimeSliceScheduling();
        startDynamicTimeSliceAdjustment();
        startPerformanceMonitoring();
        timeSliceScheduler.schedule(this::autoStartAllDevices, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void destroy() {
        stopAllDevices();
        shutdownExecutor("timeSliceScheduler", timeSliceScheduler);
        shutdownExecutor("batchDispatcher", batchDispatcher);
        shutdownExecutor("asyncCollectorPool", asyncCollectorPool);
        shutdownExecutor("dataProcessorPool", dataProcessorPool);
        shutdownExecutor("deviceStartExecutor", deviceStartExecutor);
        deviceScheduleInfo.clear();
        cancelTimeSliceScheduling();
        timeSliceTasks.clear();
        timeSliceScheduleFutures.clear();
        pendingConfigRestartTasks.values().forEach(future -> future.cancel(false));
        pendingConfigRestartTasks.clear();
        startingDevices.clear();
    }

    private void startTimeSliceScheduling() {
        cancelTimeSliceScheduling();
        int sliceCount = Math.max(1, timeSliceCount.get());
        int sliceInterval = Math.max(1, timeSliceInterval.get());
        long revision = timeSliceRevision.get();
        for (int sliceIndex = 0; sliceIndex < sliceCount; sliceIndex++) {
            final int currentSlice = sliceIndex;
            final long currentRevision = revision;
            ScheduledFuture<?> future = timeSliceScheduler.scheduleAtFixedRate(() -> {
                try {
                    executeTimeSlice(currentSlice, currentRevision);
                } catch (Exception e) {
                    log.error("time slice execute failed, slice={}", currentSlice, e);
                }
            }, (long) sliceIndex * sliceInterval, (long) sliceInterval * sliceCount, TimeUnit.MILLISECONDS);
            timeSliceScheduleFutures.put(currentSlice, future);
        }
    }

    private void cancelTimeSliceScheduling() {
        timeSliceScheduleFutures.values().forEach(future -> future.cancel(false));
        timeSliceScheduleFutures.clear();
    }

    private void resetTimeSliceTaskBuckets(int sliceCount) {
        timeSliceTasks.clear();
        for (int i = 0; i < Math.max(1, sliceCount); i++) {
            timeSliceTasks.put(i, new CopyOnWriteArrayList<>());
        }
    }

    private void executeTimeSlice(int sliceIndex, long revision) {
        long startTime = System.currentTimeMillis();
        int currentSliceInterval = timeSliceInterval.get();
        try {
            if (revision != timeSliceRevision.get()) {
                return;
            }
            List<DeviceBatchTask> tasks = timeSliceTasks.get(sliceIndex);
            if (tasks == null || tasks.isEmpty()) {
                return;
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (DeviceBatchTask task : tasks) {
                if (task.shouldSkip() || !isBatchTaskActive(task) || task.timeSliceRevision != revision) {
                    continue;
                }
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        processDeviceBatch(task);
                    } catch (Exception e) {
                        log.error("device batch execute failed, device={}", task.deviceId, e);
                    }
                }, batchDispatcher);
                futures.add(future);
            }

            if (!futures.isEmpty()) {
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(Math.max(1, currentSliceInterval - 10L), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    cancelSliceTasks(sliceIndex, revision);
                    log.warn("time slice execute timeout, slice={}", sliceIndex);
                } catch (Exception e) {
                    log.error("time slice execute failed, slice={}", sliceIndex, e);
                }
            }
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            performanceMonitor.recordTimeSliceExecution(sliceIndex, executionTime, timeSliceInterval);
        }
    }

    private void processDeviceBatch(DeviceBatchTask batchTask) {
        String deviceId = batchTask.deviceId;
        List<DataPoint> points = batchTask.points;
        long generation = batchTask.generation;

        long startTime = System.currentTimeMillis();
        boolean success = false;
        try {
            if (!isBatchTaskActive(batchTask)) {
                return;
            }

            if (!collectionManager.isDeviceConnected(deviceId) && !reconnectDevice(deviceId)) {
                log.warn("device reconnect failed before batch collection, device={}", deviceId);
                return;
            }

            Future<Map<String, Object>> collectFuture = asyncCollectorPool.submit(() ->
                    collectionTaskGuard.callWithContext(
                            deviceId,
                            generation,
                            () -> collectionManager.readPoints(deviceId, points)
                    ));
            batchTask.registerInFlight(collectFuture);
            registerCollectFuture(deviceId, collectFuture);

            Map<String, Object> values;
            try {
                values = collectFuture.get(
                        collectorProperties.getScheduler().getCollectTimeoutMs(),
                        TimeUnit.MILLISECONDS
                );
            } catch (TimeoutException e) {
                collectFuture.cancel(true);
                log.warn("batch collection timeout, device={}", deviceId);
                return;
            } catch (InterruptedException e) {
                collectFuture.cancel(true);
                Thread.currentThread().interrupt();
                log.warn("batch collection interrupted, device={}", deviceId);
                return;
            } finally {
                batchTask.unregisterInFlight(collectFuture);
                unregisterCollectFuture(deviceId, collectFuture);
            }

            if (!isBatchTaskActive(batchTask) || values == null || values.isEmpty()) {
                return;
            }

            CompletableFuture<Void> processFuture = CompletableFuture.runAsync(
                    () -> collectionTaskGuard.runWithContext(
                            deviceId,
                            generation,
                            () -> processCollectedData(deviceId, generation, points, values)
                    ),
                    dataProcessorPool
            );
            batchTask.registerInFlight(processFuture);
            registerProcessFuture(deviceId, processFuture);
            processFuture.whenComplete((ignored, throwable) -> {
                batchTask.unregisterInFlight(processFuture);
                unregisterProcessFuture(deviceId, processFuture);
            });
            success = true;
            batchTask.recordSuccess();
        } catch (Exception e) {
            batchTask.recordFailure();
            log.error("device batch collect failed, device={}", deviceId, e);
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            if (success) {
                collectionStatistics.collectionSuccess(deviceId, executionTime);
                performanceMonitor.recordBatchSuccess(deviceId, points.size(), executionTime);
            } else {
                collectionStatistics.collectionFailed(deviceId);
                performanceMonitor.recordBatchFailure(deviceId);
            }
            if (executionTime > 100) {
                adjustBatchSize(deviceId, -10);
            } else if (executionTime < 20) {
                adjustBatchSize(deviceId, 5);
            }
        }
    }

    private void autoStartAllDevices() {
        try {
            startAllDevices();
        } catch (Exception e) {
            log.error("auto start all devices failed", e);
        }
    }

    public boolean startDevice(String deviceId) {
        DeviceInfo deviceInfo;
        List<DataPoint> dataPoints;
        long generation;
        scheduleLock.lock();
        try {
            DeviceScheduleInfo scheduleInfo = deviceScheduleInfo.get(deviceId);
            if ((scheduleInfo != null && scheduleInfo.isRunning()) || startingDevices.contains(deviceId)) {
                return false;
            }

            deviceInfo = configManager.getDevice(deviceId);
            if (deviceInfo == null) {
                return false;
            }
            dataPoints = configManager.getDataPoints(deviceId);
            if (dataPoints == null || dataPoints.isEmpty()) {
                return false;
            }

            if (collectorProperties.getAdaptiveCollection().isEnabled()) {
                for (DataPoint dataPoint : dataPoints) {
                    AdaptiveCollectionUtil.initDataPointAdaptiveConfig(dataPoint);
                }
            }

            generation = collectionTaskGuard.activateNextGeneration(deviceId);
            startingDevices.add(deviceId);
        } catch (Exception e) {
            log.error("start device failed, device={}", deviceId, e);
            return false;
        } finally {
            scheduleLock.unlock();
        }

        try {
            try {
                collectionManager.registerDevice(deviceInfo);
            } catch (Exception e) {
                log.debug("register device skipped, device={}", deviceId, e);
            }

            long connectTimeoutMs = resolveDeviceStartTimeoutMs(deviceId);
            if (!connectDevice(deviceId, connectTimeoutMs)) {
                cleanupFailedStart(deviceId);
                return false;
            }

            scheduleLock.lock();
            try {
                scheduleDevicePoints(deviceId, generation, dataPoints);
                collectionManager.rebuildReadPlans(deviceId, dataPoints);
                deviceScheduleInfo.put(deviceId, new DeviceScheduleInfo(deviceId, generation, true));
            } finally {
                scheduleLock.unlock();
            }

            collectionStatistics.startCollection(deviceId, dataPoints.size());
            collectionServiceHealthTracker.markDeviceStarted(deviceId);
            return true;
        } catch (Exception e) {
            log.error("start device failed, device={}", deviceId, e);
            cleanupFailedStart(deviceId);
            return false;
        } finally {
            startingDevices.remove(deviceId);
        }
    }

    private void scheduleDevicePoints(String deviceId, long generation, List<DataPoint> points) {
        long revision = timeSliceRevision.get();
        List<DeviceBatchTask> batchTasks = deviceBatchPlanner.plan(
                deviceId,
                points,
                timeSliceCount.get(),
                generation,
                revision,
                performanceMonitor
        );
        for (DeviceBatchTask batchTask : batchTasks) {
            List<DeviceBatchTask> tasks = timeSliceTasks.get(batchTask.timeSliceIndex);
            if (tasks != null) {
                tasks.add(batchTask);
            }
        }
    }

    private boolean connectDevice(String deviceId, long timeoutMs) {
        Future<?> connectFuture = null;
        try {
            connectFuture = deviceStartExecutor.submit(() -> {
                collectionManager.connectDevice(deviceId);
                configManager.getDataPointsAndAdaptiveConfig(deviceId);
            });
            connectFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            if (connectFuture != null) {
                connectFuture.cancel(true);
            }
            log.error("connect device timed out, device={}, timeoutMs={}", deviceId, timeoutMs);
            return false;
        } catch (InterruptedException e) {
            if (connectFuture != null) {
                connectFuture.cancel(true);
            }
            Thread.currentThread().interrupt();
            log.error("connect device interrupted, device={}", deviceId, e);
            return false;
        } catch (Exception e) {
            log.error("connect device failed, device={}", deviceId, e);
            return false;
        }
    }

    private boolean reconnectDevice(String deviceId) {
        try {
            collectionManager.reconnectDevice(deviceId);
            return true;
        } catch (Exception e) {
            log.error("reconnect device failed, device={}", deviceId, e);
            return false;
        }
    }

    public boolean stopDevice(String deviceId) {
        scheduleLock.lock();
        try {
            for (List<DeviceBatchTask> tasks : timeSliceTasks.values()) {
                tasks.removeIf(task -> {
                    if (task.deviceId.equals(deviceId)) {
                        task.cancel();
                        return true;
                    }
                    return false;
                });
            }
            cancelDeviceInFlightTasks(deviceId);
            try {
                collectionManager.disconnectDevice(deviceId);
            } catch (Exception e) {
                log.warn("disconnect device failed, device={}", deviceId, e);
            }
            deviceScheduleInfo.remove(deviceId);
            collectionTaskGuard.clearDevice(deviceId);
            collectionStatistics.stopCollection(deviceId);
            collectionServiceHealthTracker.markDeviceStopped(deviceId);
            return true;
        } catch (Exception e) {
            log.error("stop device failed, device={}", deviceId, e);
            return false;
        } finally {
            scheduleLock.unlock();
        }
    }

    public void startAllDevices() {
        List<String> deviceIds = configManager.getAllDeviceIds();
        for (String deviceId : deviceIds) {
            try {
                DeviceContext context = configManager.getDeviceContext(deviceId);
                if (context != null
                        && context.getDeviceInfo() != null
                        && context.getConnectionConfig() != null) {
                    startDevice(deviceId);
                }
            } catch (Exception e) {
                log.error("start device failed, device={}", deviceId, e);
            }
        }
    }

    public void stopAllDevices() {
        List<String> runningDevices = new ArrayList<>(deviceScheduleInfo.keySet());
        for (String deviceId : runningDevices) {
            try {
                stopDevice(deviceId);
            } catch (Exception e) {
                log.error("stop device failed, device={}", deviceId, e);
            }
        }
    }

    public void reloadAllDevices() {
        stopAllDevices();
        timeSliceScheduler.schedule(this::startAllDevices, 2, TimeUnit.SECONDS);
    }

    private void processCollectedData(String deviceId,
                                      long generation,
                                      List<DataPoint> points,
                                      Map<String, Object> values) {
        if (!collectionTaskGuard.isCurrent(deviceId, generation)) {
            log.debug("skip stale collected data, device={}, generation={}", deviceId, generation);
            return;
        }
        collectedDataProcessor.process(deviceId, points, values, performanceMonitor);
    }

    private void updateOptimalBatchSize(String deviceId, int newSize) {
        log.debug("update optimal batch size, device={}, size={}", deviceId, newSize);
    }

    private void adjustBatchSize(String deviceId, int percentChange) {
        performanceMonitor.adjustBatchSize(deviceId, percentChange);
    }

    private void startPerformanceMonitoring() {
        timeSliceScheduler.scheduleAtFixedRate(
                () -> performanceMonitor.logStatistics(timeSliceInterval),
                60, 60, TimeUnit.SECONDS
        );
    }

    private void startDynamicTimeSliceAdjustment() {
        int interval = collectorProperties.getScheduler().getDynamicAdjustIntervalMs();
        timeSliceScheduler.scheduleAtFixedRate(this::adjustTimeSlicesDynamically, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void adjustTimeSlicesDynamically() {
        try {
            double cpuLoad = getSystemCpuLoad();
            int activeDevices = deviceScheduleInfo.size();
            long totalTasks = timeSliceTasks.values().stream().mapToInt(List::size).sum();
            int newSliceCount = calculateOptimalSliceCount(activeDevices, totalTasks, cpuLoad);
            long avgExecution = performanceMonitor.getAverageTimeSliceExecution();
            boolean timeoutDetected = performanceMonitor.consumeTimeSliceTimeout();
            int tunedInterval = timeSliceTuner != null
                    ? timeSliceTuner.adjustInterval(timeSliceInterval.get(), avgExecution, timeoutDetected)
                    : timeSliceInterval.get();
            applyTimeSliceConfigUpdate(newSliceCount, tunedInterval);
        } catch (Exception e) {
            log.error("adjust time slices failed", e);
        }
    }

    private int calculateOptimalSliceCount(int activeDevices, long totalTasks, double cpuLoad) {
        int baseSlices = Math.max(1, Math.min(
                activeDevices / 5 + 1,
                collectorProperties.getScheduler().getMaxTimeSliceCount()
        ));
        if (cpuLoad > 0.8) {
            baseSlices = Math.min(collectorProperties.getScheduler().getMaxTimeSliceCount(), baseSlices + 2);
        } else if (cpuLoad < 0.3) {
            baseSlices = Math.max(2, baseSlices - 1);
        }
        return baseSlices;
    }

    private void applyTimeSliceConfigUpdate(int newSliceCount, int newSliceInterval) {
        int normalizedSliceCount = Math.max(1, newSliceCount);
        int normalizedSliceInterval = Math.max(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                newSliceInterval
        );
        scheduleLock.lock();
        try {
            int oldSliceCount = timeSliceCount.get();
            int oldSliceInterval = timeSliceInterval.get();
            boolean sliceCountChanged = normalizedSliceCount != oldSliceCount;
            boolean intervalChanged = normalizedSliceInterval != oldSliceInterval;
            if (!sliceCountChanged && !intervalChanged) {
                return;
            }

            timeSliceCount.set(normalizedSliceCount);
            timeSliceInterval.set(normalizedSliceInterval);
            timeSliceRevision.incrementAndGet();
            rebuildTimeSliceAssignments();
            startTimeSliceScheduling();
        } finally {
            scheduleLock.unlock();
        }
    }

    private void rebuildTimeSliceAssignments() {
        resetTimeSliceTaskBuckets(timeSliceCount.get());
        List<String> deviceIds = new ArrayList<>(deviceScheduleInfo.keySet());
        for (String deviceId : deviceIds) {
            try {
                List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
                DeviceScheduleInfo scheduleInfo = deviceScheduleInfo.get(deviceId);
                if (scheduleInfo == null || !scheduleInfo.isRunning() || dataPoints == null || dataPoints.isEmpty()) {
                    continue;
                }
                scheduleDevicePoints(deviceId, scheduleInfo.getGeneration(), dataPoints);
            } catch (Exception e) {
                log.error("rebuild time slice assignments failed, device={}", deviceId, e);
            }
        }
    }

    private void updateTimeSliceConfig(int newSliceCount, int newSliceInterval) {
        applyTimeSliceConfigUpdate(newSliceCount, newSliceInterval);
    }

    private void rescheduleAllDevices() {
        rebuildTimeSliceAssignments();
    }

    private double getSystemCpuLoad() {
        int activeThreads = asyncCollectorPool.getActiveCount() + dataProcessorPool.getActiveCount();
        int maxThreads = asyncCollectorPool.getMaximumPoolSize() + dataProcessorPool.getMaximumPoolSize();
        return maxThreads <= 0 ? 0D : Math.min(1.0, (double) activeThreads / maxThreads);
    }

    private void shutdownExecutor(String name, ExecutorService executor) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        try {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public Map<String, Object> getDeviceScheduleStatus(String deviceId) {
        Map<String, Object> status = new HashMap<>();
        status.put("deviceId", deviceId);
        DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
        status.put("isRunning", info != null && info.isRunning());
        status.put("isStarting", startingDevices.contains(deviceId));
        status.put("connected", collectionManager.isDeviceConnected(deviceId));
        status.put("statistics", collectionStatistics.getDeviceStatistics(deviceId));
        status.put("performance", performanceMonitor.getDevicePerformance(deviceId));
        return status;
    }

    public List<String> getRunningDevices() {
        return deviceScheduleInfo.entrySet().stream()
                .filter(entry -> entry.getValue().isRunning())
                .map(Map.Entry::getKey)
                .toList();
    }

    public boolean isDeviceRunning(String deviceId) {
        DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
        return info != null && info.isRunning();
    }

    private long resolveDeviceStartTimeoutMs(String deviceId) {
        long defaultTimeoutMs = Math.max(1000L, collectorProperties.getScheduler().getDeviceStartTimeoutMs());
        DeviceConnection connection = configManager.getConnectionConfig(deviceId);
        if (connection == null) {
            return defaultTimeoutMs;
        }

        Long configuredTimeout = firstPositive(
                toLong(connection.getConnectTimeout()),
                toLong(connection.getInt("connectTimeoutMs", null)),
                toLong(connection.getInt("connectTimeout", null)),
                toLong(connection.getTimeout()));
        if (configuredTimeout == null) {
            return defaultTimeoutMs;
        }
        return Math.max(1000L, Math.min(configuredTimeout, defaultTimeoutMs));
    }

    private Long firstPositive(Long... candidates) {
        if (candidates == null) {
            return null;
        }
        for (Long candidate : candidates) {
            if (candidate != null && candidate > 0) {
                return candidate;
            }
        }
        return null;
    }

    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private void cleanupFailedStart(String deviceId) {
        try {
            collectionManager.cleanupDevice(deviceId);
        } catch (Exception e) {
            log.warn("cleanup failed after start error, device={}", deviceId, e);
        } finally {
            collectionTaskGuard.clearDevice(deviceId);
            deviceScheduleInfo.remove(deviceId);
        }
    }

    private boolean isBatchTaskActive(DeviceBatchTask batchTask) {
        if (batchTask == null || batchTask.isCancelled()) {
            return false;
        }
        DeviceScheduleInfo scheduleInfo = deviceScheduleInfo.get(batchTask.deviceId);
        if (scheduleInfo == null || !scheduleInfo.isRunning()) {
            return false;
        }
        return scheduleInfo.getGeneration() == batchTask.generation
                && batchTask.timeSliceRevision == timeSliceRevision.get()
                && collectionTaskGuard.isCurrent(batchTask.deviceId, batchTask.generation);
    }

    private void cancelSliceTasks(int sliceIndex, long revision) {
        List<DeviceBatchTask> tasks = timeSliceTasks.get(sliceIndex);
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (DeviceBatchTask task : tasks) {
            if (task.timeSliceRevision == revision) {
                task.cancel();
            }
        }
    }

    private void registerCollectFuture(String deviceId, Future<?> future) {
        if (future == null) {
            return;
        }
        deviceInFlightCollectFutures
                .computeIfAbsent(deviceId, ignored -> ConcurrentHashMap.newKeySet())
                .add(future);
    }

    private void unregisterCollectFuture(String deviceId, Future<?> future) {
        unregisterFuture(deviceInFlightCollectFutures, deviceId, future);
    }

    private void registerProcessFuture(String deviceId, CompletableFuture<?> future) {
        if (future == null) {
            return;
        }
        deviceInFlightProcessFutures
                .computeIfAbsent(deviceId, ignored -> ConcurrentHashMap.newKeySet())
                .add(future);
    }

    private void unregisterProcessFuture(String deviceId, CompletableFuture<?> future) {
        unregisterFuture(deviceInFlightProcessFutures, deviceId, future);
    }

    private <T> void unregisterFuture(Map<String, Set<T>> futureRegistry, String deviceId, T future) {
        Set<T> futures = futureRegistry.get(deviceId);
        if (futures == null || future == null) {
            return;
        }
        futures.remove(future);
        if (futures.isEmpty()) {
            futureRegistry.remove(deviceId, futures);
        }
    }

    private void cancelDeviceInFlightTasks(String deviceId) {
        cancelFutures(deviceInFlightCollectFutures.remove(deviceId));
        cancelFutures(deviceInFlightProcessFutures.remove(deviceId));
    }

    private void cancelFutures(Set<? extends Future<?>> futures) {
        if (futures == null || futures.isEmpty()) {
            return;
        }
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
        futures.clear();
    }

    @EventListener
    public void handleConfigUpdate(ConfigUpdateEvent event) {
        String deviceId = event.getDeviceId();
        if ("local-delete".equals(event.getConfigType())) {
            if (deviceId != null && isDeviceRunning(deviceId)) {
                stopDevice(deviceId);
            }
            return;
        }
        if (deviceId != null && isDeviceRunning(deviceId)) {
            ScheduledFuture<?> oldTask = pendingConfigRestartTasks.get(deviceId);
            if (oldTask != null && !oldTask.isDone()) {
                oldTask.cancel(false);
            }
            ScheduledFuture<?> restartTask = timeSliceScheduler.schedule(() -> {
                stopDevice(deviceId);
                startDevice(deviceId);
                configManager.getDataPointsAndAdaptiveConfig(deviceId);
                pendingConfigRestartTasks.remove(deviceId);
            }, CONFIG_RESTART_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            pendingConfigRestartTasks.put(deviceId, restartTask);
        }
    }
}
