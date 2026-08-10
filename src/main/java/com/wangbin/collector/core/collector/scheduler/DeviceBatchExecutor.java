package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 执行时间片分发出的设备批量采集任务，并负责在途任务取消和旧代次隔离。
 */
@Slf4j
@Component
public class DeviceBatchExecutor {

    private final CollectionManager collectionManager;
    private final ConfigManager configManager;
    private final CollectionStatistics collectionStatistics;
    private final CollectorProperties collectorProperties;
    private final CollectedDataProcessor collectedDataProcessor;
    private final CollectionTaskGuard collectionTaskGuard;
    private final SchedulerRuntimeState runtimeState;
    private final PerformanceMonitor performanceMonitor;
    private final ReconnectCoordinator reconnectCoordinator;
    private final ExecutorService batchDispatcherExecutor;
    private final ThreadPoolExecutor asyncCollectorExecutor;
    private final ThreadPoolExecutor dataProcessorExecutor;
    private final Map<String, Set<Future<?>>> deviceInFlightCollectFutures = new ConcurrentHashMap<>();
    private final Map<String, Set<CompletableFuture<?>>> deviceInFlightProcessFutures = new ConcurrentHashMap<>();
    private final AtomicLong batchDispatchRejectedCount = new AtomicLong(0);
    private final AtomicLong collectRejectedCount = new AtomicLong(0);
    private final AtomicLong processRejectedCount = new AtomicLong(0);

    public DeviceBatchExecutor(CollectionManager collectionManager,
                               ConfigManager configManager,
                               CollectionStatistics collectionStatistics,
                               CollectorProperties collectorProperties,
                               CollectedDataProcessor collectedDataProcessor,
                               CollectionTaskGuard collectionTaskGuard,
                               SchedulerRuntimeState runtimeState,
                               PerformanceMonitor performanceMonitor,
                               ReconnectCoordinator reconnectCoordinator,
                               @Qualifier("batchDispatcherExecutor") ExecutorService batchDispatcherExecutor,
                               @Qualifier("asyncCollectorExecutor") ThreadPoolExecutor asyncCollectorExecutor,
                               @Qualifier("dataProcessorExecutor") ThreadPoolExecutor dataProcessorExecutor) {
        this.collectionManager = collectionManager;
        this.configManager = configManager;
        this.collectionStatistics = collectionStatistics;
        this.collectorProperties = collectorProperties;
        this.collectedDataProcessor = collectedDataProcessor;
        this.collectionTaskGuard = collectionTaskGuard;
        this.runtimeState = runtimeState;
        this.performanceMonitor = performanceMonitor;
        this.reconnectCoordinator = reconnectCoordinator;
        this.batchDispatcherExecutor = batchDispatcherExecutor;
        this.asyncCollectorExecutor = asyncCollectorExecutor;
        this.dataProcessorExecutor = dataProcessorExecutor;
    }

    public CompletableFuture<Void> submit(DeviceBatchTask task) {
        return submitBatchDispatchTask(task, task != null ? task.points : List.of());
    }

    public CompletableFuture<Void> submit(DeviceBatchTask task, List<DataPoint> duePoints) {
        return submitBatchDispatchTask(task, duePoints);
    }

    CompletableFuture<Void> submitBatchDispatchTask(DeviceBatchTask task, List<DataPoint> duePoints) {
        if (task == null || duePoints == null || duePoints.isEmpty()) {
            return null;
        }
        if (!isBatchTaskDispatchable(task)) {
            return null;
        }
        if (!task.tryStartExecution()) {
            return null;
        }
        SchedulerRuntimeState.PointDispatchClaim claim = task.claimDuePoints(runtimeState, duePoints);
        if (claim.isEmpty()) {
            task.finishExecution();
            return null;
        }
        if (!isBatchTaskExecutionStillValid(task)) {
            runtimeState.rollbackClaim(claim);
            task.finishExecution();
            return null;
        }
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    processDeviceBatch(task, claim.points());
                } catch (Exception e) {
                    log.error("设备批量执行失败，设备={}", task.deviceId, e);
                } finally {
                    task.finishExecution(runtimeState, claim);
                }
            }, batchDispatcherExecutor);
            return future;
        } catch (RejectedExecutionException e) {
            runtimeState.rollbackClaim(claim);
            task.finishExecution();
            batchDispatchRejectedCount.incrementAndGet();
            task.recordFailure();
            log.warn("批量分发被拒绝, 设备={}, 分片={}, 队列长度={}",
                    task.deviceId,
                    task.timeSliceIndex,
                    executorQueueSize(batchDispatcherExecutor),
                    e);
            return null;
        }
    }

    void processDeviceBatch(DeviceBatchTask batchTask) {
        processDeviceBatch(batchTask, batchTask != null ? batchTask.points : List.of());
    }

    void processDeviceBatch(DeviceBatchTask batchTask, List<DataPoint> duePoints) {
        String deviceId = batchTask.deviceId;
        List<DataPoint> points = duePoints;
        if (points == null || points.isEmpty()) {
            return;
        }
        long generation = batchTask.generation;

        long startTime = System.currentTimeMillis();
        boolean success = false;
        try {
            if (!isBatchTaskExecutionStillValid(batchTask)) {
                return;
            }

            if (!collectionManager.isDeviceConnected(deviceId)) {
                reconnectCoordinator.scheduleIfNeeded(deviceId, generation);
                log.debug("设备已断开，跳过本轮批量任务，等待异步重连完成，设备={}", deviceId);
                return;
            }

            Future<Map<String, Object>> collectFuture = submitCollectTask(deviceId, generation, points);
            if (collectFuture == null) {
                return;
            }
            batchTask.registerInFlight(collectFuture);
            registerCollectFuture(deviceId, collectFuture);

            long collectTimeoutMs = resolveCollectTimeoutMs(deviceId);
            Map<String, Object> values;
            try {
                values = collectFuture.get(collectTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                collectFuture.cancel(true);
                batchTask.recordFailure();
                log.warn("批量采集超时, 设备={}, 超时毫秒={}", deviceId, collectTimeoutMs);
                return;
            } catch (CancellationException e) {
                log.debug("批量采集已取消, 设备={}", deviceId);
                return;
            } catch (InterruptedException e) {
                collectFuture.cancel(true);
                Thread.currentThread().interrupt();
                log.warn("批量采集被中断, 设备={}", deviceId);
                return;
            } finally {
                batchTask.unregisterInFlight(collectFuture);
                unregisterCollectFuture(deviceId, collectFuture);
            }

            if (!isBatchTaskExecutionStillValid(batchTask) || values == null || values.isEmpty()) {
                return;
            }

            CompletableFuture<Void> processFuture = submitProcessTask(deviceId, generation, points, values);
            if (processFuture == null) {
                return;
            }
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
            log.error("设备批量采集失败, 设备={}", deviceId, e);
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

    Future<Map<String, Object>> submitCollectTask(String deviceId,
                                                  long generation,
                                                  List<DataPoint> points) {
        try {
            return asyncCollectorExecutor.submit(() ->
                    collectionTaskGuard.callWithContext(
                            deviceId,
                            generation,
                            () -> collectionManager.readPoints(deviceId, points)
                    ));
        } catch (RejectedExecutionException e) {
            collectRejectedCount.incrementAndGet();
            log.warn("采集任务被拒绝, 设备={}, 点位数量={}, 队列长度={}",
                    deviceId,
                    points != null ? points.size() : 0,
                    asyncCollectorExecutor.getQueue().size(),
                    e);
            return null;
        }
    }

    CompletableFuture<Void> submitProcessTask(String deviceId,
                                              long generation,
                                              List<DataPoint> points,
                                              Map<String, Object> values) {
        try {
            return CompletableFuture.runAsync(
                    () -> collectionTaskGuard.runWithContext(
                            deviceId,
                            generation,
                            () -> processCollectedData(deviceId, generation, points, values)
                    ),
                    dataProcessorExecutor
            );
        } catch (RejectedExecutionException e) {
            processRejectedCount.incrementAndGet();
            log.warn("处理任务被拒绝，设备={}，点位数量={}，队列长度={}",
                    deviceId,
                    points != null ? points.size() : 0,
                    dataProcessorExecutor.getQueue().size(),
                    e);
            return null;
        }
    }

    void processCollectedData(String deviceId,
                              long generation,
                              List<DataPoint> points,
                              Map<String, Object> values) {
        if (!collectionTaskGuard.isCurrent(deviceId, generation)) {
            log.debug("跳过旧代次采集数据, 设备={}, 运行代次={}", deviceId, generation);
            return;
        }
        collectedDataProcessor.process(deviceId, points, values);
    }

    void adjustBatchSize(String deviceId, int percentChange) {
        performanceMonitor.adjustBatchSize(deviceId, percentChange);
    }

    long resolveCollectTimeoutMs(String deviceId) {
        long defaultTimeoutMs = Math.max(100L, collectorProperties.getScheduler().getCollectTimeoutMs());
        DeviceConnection connection = configManager.getConnectionConfig(deviceId);
        if (connection == null) {
            return defaultTimeoutMs;
        }

        Long configuredTimeout = firstPositive(
                toLong(connection.getReadTimeout()),
                toLong(connection.getInt("requestTimeoutMs", null)),
                toLong(connection.getInt("requestTimeout", null)),
                toLong(connection.getTimeout()));
        if (configuredTimeout == null) {
            return defaultTimeoutMs;
        }

        long bufferMs = Math.max(250L, Math.min(1000L, configuredTimeout / 10L));
        return Math.max(defaultTimeoutMs, configuredTimeout + bufferMs);
    }

    boolean isBatchTaskActive(DeviceBatchTask batchTask) {
        return isBatchTaskDispatchable(batchTask);
    }

    boolean isBatchTaskDispatchable(DeviceBatchTask batchTask) {
        if (batchTask == null || batchTask.isCancelled()) {
            return false;
        }
        DeviceScheduleInfo scheduleInfo = runtimeState.getScheduleInfo(batchTask.deviceId);
        if (scheduleInfo == null || !scheduleInfo.isRunning()) {
            return false;
        }
        return scheduleInfo.getGeneration() == batchTask.generation
                && batchTask.timeSliceRevision == runtimeState.getTimeSliceRevision()
                && collectionTaskGuard.isCurrent(batchTask.deviceId, batchTask.generation);
    }

    boolean isBatchTaskExecutionStillValid(DeviceBatchTask batchTask) {
        if (batchTask == null || batchTask.isCancelled()) {
            return false;
        }
        DeviceScheduleInfo scheduleInfo = runtimeState.getScheduleInfo(batchTask.deviceId);
        if (scheduleInfo == null || !scheduleInfo.isRunning()) {
            return false;
        }
        return scheduleInfo.getGeneration() == batchTask.generation
                && collectionTaskGuard.isCurrent(batchTask.deviceId, batchTask.generation);
    }

    int executorQueueSize(ExecutorService executor) {
        if (executor instanceof ThreadPoolExecutor threadPoolExecutor) {
            return threadPoolExecutor.getQueue().size();
        }
        return -1;
    }

    double estimateWorkerLoad() {
        int activeThreads = asyncCollectorExecutor.getActiveCount() + dataProcessorExecutor.getActiveCount();
        int maxThreads = asyncCollectorExecutor.getMaximumPoolSize() + dataProcessorExecutor.getMaximumPoolSize();
        return maxThreads <= 0 ? 0D : Math.min(1.0, (double) activeThreads / maxThreads);
    }

    void registerCollectFuture(String deviceId, Future<?> future) {
        if (future == null) {
            return;
        }
        deviceInFlightCollectFutures
                .computeIfAbsent(deviceId, ignored -> ConcurrentHashMap.newKeySet())
                .add(future);
    }

    void unregisterCollectFuture(String deviceId, Future<?> future) {
        unregisterFuture(deviceInFlightCollectFutures, deviceId, future);
    }

    void registerProcessFuture(String deviceId, CompletableFuture<?> future) {
        if (future == null) {
            return;
        }
        deviceInFlightProcessFutures
                .computeIfAbsent(deviceId, ignored -> ConcurrentHashMap.newKeySet())
                .add(future);
    }

    void unregisterProcessFuture(String deviceId, CompletableFuture<?> future) {
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

    void cancelDeviceInFlightTasks(String deviceId) {
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

    long getBatchDispatchRejectedCount() {
        return batchDispatchRejectedCount.get();
    }

    long getCollectRejectedCount() {
        return collectRejectedCount.get();
    }

    long getProcessRejectedCount() {
        return processRejectedCount.get();
    }

    int getInFlightCollectFutureCountForTest() {
        return futureCount(deviceInFlightCollectFutures);
    }

    int getInFlightProcessFutureCountForTest() {
        return futureCount(deviceInFlightProcessFutures);
    }

    int getTotalInFlightFutureCountForTest() {
        return getInFlightCollectFutureCountForTest() + getInFlightProcessFutureCountForTest();
    }

    private int futureCount(Map<String, ? extends Set<?>> futureRegistry) {
        return futureRegistry.values().stream().mapToInt(Set::size).sum();
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
}
