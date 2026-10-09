package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.manager.CollectionManager;
import com.wangbin.collector.core.collector.runtime.AcquisitionRuntimeTracker;
import com.wangbin.collector.core.collector.runtime.PointMappingException;
import com.wangbin.collector.core.collector.statistics.CollectionStatistics;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

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
    private AcquisitionRuntimeTracker acquisitionRuntimeTracker;

    @Autowired(required = false)
    public void setAcquisitionRuntimeTracker(AcquisitionRuntimeTracker acquisitionRuntimeTracker) {
        this.acquisitionRuntimeTracker = acquisitionRuntimeTracker;
    }
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

    public CompletableFuture<Void> submit(DeviceBatchTask task, long claimNanos) {
        return submitBatchDispatchTask(task, task != null ? task.points : List.of(), claimNanos);
    }

    public CompletableFuture<Void> submit(DeviceBatchTask task, List<DataPoint> duePoints) {
        return submitBatchDispatchTask(task, duePoints);
    }

    CompletableFuture<Void> submitBatchDispatchTask(DeviceBatchTask task, List<DataPoint> duePoints) {
        return submitBatchDispatchTask(task, duePoints, Long.MIN_VALUE);
    }

    CompletableFuture<Void> submitBatchDispatchTask(DeviceBatchTask task, List<DataPoint> duePoints, long claimNanos) {
        if (task == null || duePoints == null || duePoints.isEmpty()) {
            return null;
        }
        if (!isBatchTaskDispatchable(task)) {
            return null;
        }
        if (!task.tryStartExecution()) {
            return null;
        }
        SchedulerRuntimeState.PointDispatchClaim claim = claimNanos == Long.MIN_VALUE
                ? task.claimDuePoints(runtimeState, duePoints)
                : task.claimDuePoints(runtimeState, duePoints, claimNanos);
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
        if (batchTask == null || duePoints == null || duePoints.isEmpty()) return;
        String deviceId = batchTask.deviceId;
        long generation = batchTask.generation;
        long startTime = System.currentTimeMillis();
        CollectionProcessingReceipt receipt = new CollectionProcessingReceipt(duePoints);
        String failureReason = "COMM_ERROR";
        String failureDetail = null;
        boolean awaitingCore = false;
        try {
            if (!isBatchTaskExecutionStillValid(batchTask)) return;
            if (!collectionManager.isDeviceConnected(deviceId)) {
                reconnectCoordinator.scheduleIfNeeded(deviceId, generation);
                return;
            }
            Future<Map<String, Object>> collectFuture = submitCollectTask(deviceId, generation, duePoints, receipt);
            if (collectFuture == null) return;
            batchTask.registerInFlight(collectFuture);
            registerCollectFuture(deviceId, collectFuture);
            Map<String, Object> values;
            try {
                values = collectFuture.get(resolveCollectTimeoutMs(deviceId), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                receipt.cancel("COMM_ERROR");
                collectFuture.cancel(true);
                failureDetail = "采集超时";
                return;
            } catch (CancellationException exception) {
                receipt.cancel("COMM_ERROR");
                return;
            } catch (InterruptedException exception) {
                receipt.cancel("COMM_ERROR");
                collectFuture.cancel(true);
                Thread.currentThread().interrupt();
                return;
            } finally {
                batchTask.unregisterInFlight(collectFuture);
                unregisterCollectFuture(deviceId, collectFuture);
            }
            if (!isBatchTaskExecutionStillValid(batchTask)) {
                receipt.cancel("PROCESS_ERROR");
                return;
            }
            receipt.seal();
            // 核心异步链复用本设备读取超时预算；超时副本不能破坏回执本身的逐点完成结果。
            CompletableFuture<Void> coreFuture = receipt.completion().copy()
                    .orTimeout(resolveCollectTimeoutMs(deviceId), TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        receipt.cancel("PROCESS_ERROR");
                        return receipt.completion().join();
                    }).thenAccept(results -> {
                collectionTaskGuard.commitIfCurrent(deviceId, generation, () -> {
                    if (!isBatchTaskExecutionStillValid(batchTask)) return;
                    Map<String, DataPoint> observedPointById = new java.util.LinkedHashMap<>();
                    for (DataPoint point : duePoints) {
                        if (point == null) continue;
                        CollectionProcessingReceipt.PointCompletion result = results.get(point.getPointId());
                        if (result != null && !"CACHE_READ".equals(result.reason())
                                && !"STALE_SAMPLE".equals(result.reason())) {
                            observedPointById.putIfAbsent(point.getPointId(), point);
                        }
                    }
                    List<DataPoint> observedPoints = List.copyOf(observedPointById.values());
                    if (observedPoints.isEmpty()) return;
                    if (acquisitionRuntimeTracker != null) {
                        AcquisitionRuntimeTracker.WindowSnapshot snapshot = null;
                        for (DataPoint point : observedPoints) {
                            CollectionProcessingReceipt.PointCompletion result = results.get(point.getPointId());
                            if (!result.valid() && result.sampleAt() == 0L && "PROCESS_ERROR".equals(result.reason())) {
                                if (snapshot == null) snapshot = acquisitionRuntimeTracker.snapshot(deviceId, generation);
                                AcquisitionRuntimeTracker.PointFactSnapshot fact = snapshot.points().get(point.getPointId());
                                if (fact == null || fact.lastFailureAt() < startTime) {
                                    // 入口拒绝和回执超时没有核心点位事实，先补错误，避免 null 返回值覆盖原因或重复计数。
                                    acquisitionRuntimeTracker.recordPollingFailure(deviceId, generation,
                                            List.of(point), result.reason());
                                }
                            }
                        }
                        acquisitionRuntimeTracker.recordPollingResults(deviceId, generation, observedPoints, values, startTime);
                    }
                    Map<String, Object> validValues = new java.util.LinkedHashMap<>();
                    long sampleAt = 0L;
                    for (Map.Entry<String, CollectionProcessingReceipt.PointCompletion> entry : results.entrySet()) {
                        if (entry.getValue().valid()) {
                            if (values != null && values.get(entry.getKey()) != null) {
                                validValues.put(entry.getKey(), values.get(entry.getKey()));
                                sampleAt = Math.max(sampleAt, entry.getValue().sampleAt());
                            }
                        }
                    }
                    int validCount = validValues.size();
                    int failedCount = observedPoints.size() - validCount;
                    long executionTime = System.currentTimeMillis() - startTime;
                    if (failedCount == 0 && validCount > 0) {
                        batchTask.recordSuccess();
                        collectionStatistics.collectionSuccess(deviceId, executionTime);
                    } else {
                        batchTask.recordFailure();
                        collectionStatistics.collectionFailed(deviceId);
                    }
                    performanceMonitor.recordBatchOutcome(deviceId, generation, validCount, failedCount,
                            executionTime, sampleAt);
                    if (!validValues.isEmpty()) {
                        List<DataPoint> validPoints = observedPoints.stream()
                                .filter(point -> validValues.containsKey(point.getPointId())).toList();
                        submitProcessTask(deviceId, generation, validPoints, validValues);
                    }
                    if (executionTime > 100) adjustBatchSize(deviceId, -10);
                    else if (executionTime < 20) adjustBatchSize(deviceId, 5);
                });
            });
            batchTask.registerInFlight(coreFuture);
            registerProcessFuture(deviceId, coreFuture);
            coreFuture.whenComplete((ignored, error) -> {
                if (coreFuture.isCancelled()) receipt.cancel("PROCESS_ERROR");
                batchTask.unregisterInFlight(coreFuture);
                unregisterProcessFuture(deviceId, coreFuture);
                if (error != null && !(error instanceof CancellationException)) {
                    log.error("核心采集回执结算失败，设备={}", deviceId, error);
                }
            });
            awaitingCore = true;
            // 取消可能发生在读取有效性检查与回执 Future 注册之间，注册后再核对一次才能关闭取消窗口。
            if (!isBatchTaskExecutionStillValid(batchTask)) coreFuture.cancel(true);
        } catch (Exception exception) {
            receipt.cancel("PROCESS_ERROR");
            failureReason = classifyFailure(exception);
            failureDetail = failureDetail(exception);
            log.error("设备批量采集失败，设备={}", deviceId, exception);
        } finally {
            if (!awaitingCore) {
                receipt.cancel(failureReason);
                String reason = failureReason;
                String detail = failureDetail;
                collectionTaskGuard.commitIfCurrent(deviceId, generation, () -> {
                    if (!isBatchTaskExecutionStillValid(batchTask)) return;
                    batchTask.recordFailure();
                    if (acquisitionRuntimeTracker != null) acquisitionRuntimeTracker.recordPollingFailure(
                            deviceId, generation, duePoints, reason, detail);
                    collectionStatistics.collectionFailed(deviceId);
                    performanceMonitor.recordBatchOutcome(deviceId, generation, 0,
                            receipt.completion().join().size(), System.currentTimeMillis() - startTime, 0L);
                });
            }
        }
    }

    /** 保留异常因果链中的映射失败，避免把 HTTP 响应映射错误误归为通信失败。 */
    private String classifyFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof PointMappingException) return "MAPPING_ERROR";
        }
        return "COMM_ERROR";
    }

    private String failureDetail(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof PointMappingException) return "响应已到达，但未匹配配置的点位或 JSONPath";
            String message = current.getMessage();
            if (message != null && message.contains("404")) return "HTTP 请求返回 404";
        }
        return "协议读取或通信失败";
    }

    Future<Map<String, Object>> submitCollectTask(String deviceId,
                                                  long generation,
                                                  List<DataPoint> points) {
        return submitCollectTask(deviceId, generation, points, null);
    }

    private Future<Map<String, Object>> submitCollectTask(String deviceId, long generation,
                                                         List<DataPoint> points, CollectionProcessingReceipt receipt) {
        try {
            return asyncCollectorExecutor.submit(() ->
                    collectionTaskGuard.callWithContext(
                            new CollectionTaskGuard.CollectionTaskContext(deviceId, generation, receipt),
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
        collectionTaskGuard.commitIfCurrent(deviceId, generation,
                () -> collectedDataProcessor.process(deviceId, points, values));
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
