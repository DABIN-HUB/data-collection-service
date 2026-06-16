package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 采集调度总控。
 *
 * <p>负责设备启动停止、时间片调度、批次分发、性能监控以及配置变更后的重调度。
 */
@Slf4j
@Service
public class CollectionScheduler {

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

    /** 调度执行资源。 */
    private final ScheduledExecutorService timeSliceScheduler;
    private final ExecutorService batchDispatcher;
    private final ThreadPoolExecutor asyncCollectorPool;
    private final ThreadPoolExecutor dataProcessorPool;

    /** 运行态调度信息。 */
    private final Map<String, DeviceScheduleInfo> deviceScheduleInfo = new ConcurrentHashMap<>();
    private final Map<Integer, List<DeviceBatchTask>> timeSliceTasks = new ConcurrentHashMap<>();
    private final Map<Integer, ScheduledFuture<?>> timeSliceScheduleFutures = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingConfigRestartTasks = new ConcurrentHashMap<>();
    private static final long CONFIG_RESTART_DEBOUNCE_MS = 1000L;
    private final PerformanceMonitor performanceMonitor = new PerformanceMonitor();
    private final ReentrantLock scheduleLock = new ReentrantLock();
    private AtomicInteger TIME_SLICE_COUNT = new AtomicInteger(2);
    private AtomicInteger TIME_SLICE_INTERVAL = new AtomicInteger(1000);
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

    /**
     * 返回当前调度器的性能快照。
     */
    public PerformanceStatsSnapshot getPerformanceSnapshot() {
        return PerformanceStatsSnapshot.builder()
                .timeSliceCount(TIME_SLICE_COUNT.get())
                .timeSliceIntervalMs(TIME_SLICE_INTERVAL.get())
                .timeSliceExecutionTimes(performanceMonitor.getTimeSliceExecutionTimesSnapshot())
                .overloadedSlices(performanceMonitor.getOverloadedSlicesSnapshot())
                .slowestDevices(performanceMonitor.getSlowestDevicesSnapshot())
                .deviceStats(performanceMonitor.getAllDevicePerformance())
                .build();
    }

    /**
     * 初始化时间片参数、定时任务和监控任务。
     */
    @PostConstruct
    public void init() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int normalizedSliceCount = Math.max(1, Math.min(
                collectorProperties.getScheduler().getInitialTimeSliceCount(),
                collectorProperties.getScheduler().getMaxTimeSliceCount()
        ));
        TIME_SLICE_COUNT.set(normalizedSliceCount);
        int normalizedInterval = Math.max(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                collectorProperties.getScheduler().getInitialTimeSliceIntervalMs()
        );
        TIME_SLICE_INTERVAL.set(normalizedInterval);
        int maxInterval = Math.max(
                collectorProperties.getScheduler().getDefaultTimeSliceIntervalMs() * 2,
                normalizedInterval
        );
        this.timeSliceTuner = new TimeSliceTuner(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                maxInterval,
                normalizedInterval
        );
        resetTimeSliceTaskBuckets(TIME_SLICE_COUNT.get());
        startTimeSliceScheduling();
        startDynamicTimeSliceAdjustment();
        startPerformanceMonitoring();

        log.info("采集调度器初始化完成，CPU 核心数：{}，时间片数量：{}", cpuCores, TIME_SLICE_COUNT.get());
        timeSliceScheduler.schedule(this::autoStartAllDevices, 5, TimeUnit.SECONDS);
    }

    /**
     * 销毁调度器并释放线程与运行态资源。
     */
    @PreDestroy
    public void destroy() {
        log.info("开始销毁采集调度器");
        stopAllDevices();
        shutdownExecutor("timeSliceScheduler", timeSliceScheduler);
        shutdownExecutor("batchDispatcher", batchDispatcher);
        shutdownExecutor("asyncCollectorPool", asyncCollectorPool);
        shutdownExecutor("dataProcessorPool", dataProcessorPool);
        deviceScheduleInfo.clear();
        cancelTimeSliceScheduling();
        timeSliceTasks.clear();
        timeSliceScheduleFutures.clear();
        pendingConfigRestartTasks.values().forEach(future -> future.cancel(false));
        pendingConfigRestartTasks.clear();

        log.info("采集调度器已销毁");
    }

    /**
     * 为每个时间片注册固定频率调度任务。
     */
    private void startTimeSliceScheduling() {
        cancelTimeSliceScheduling();
        int sliceCount = Math.max(1, TIME_SLICE_COUNT.get());
        int sliceInterval = Math.max(1, TIME_SLICE_INTERVAL.get());
        
        for (int sliceIndex = 0; sliceIndex < sliceCount; sliceIndex++) {
            final int currentSlice = sliceIndex;
            ScheduledFuture<?> future = timeSliceScheduler.scheduleAtFixedRate(() -> {
                try {
                    executeTimeSlice(currentSlice);
                } catch (Exception e) {
                    log.error("时间片 {} 执行失败", currentSlice, e);
                }
            }, (long) sliceIndex * sliceInterval, (long) sliceInterval * sliceCount, TimeUnit.MILLISECONDS);
            timeSliceScheduleFutures.put(currentSlice, future);
        }

        log.info("时间片调度已启动，时间片数量：{}，单片间隔：{}ms", sliceCount, sliceInterval);
    }

    /**
     * 取消当前所有时间片定时任务。
     */
    private void cancelTimeSliceScheduling() {
        timeSliceScheduleFutures.values().forEach(future -> future.cancel(false));
        timeSliceScheduleFutures.clear();
    }

    /**
     * 重建时间片任务桶。
     */
    private void resetTimeSliceTaskBuckets(int sliceCount) {
        timeSliceTasks.clear();
        for (int i = 0; i < Math.max(1, sliceCount); i++) {
            timeSliceTasks.put(i, new CopyOnWriteArrayList<>());
        }
    }

    /**
     * 执行单个时间片中的所有设备批次。
     */
    private void executeTimeSlice(int sliceIndex) {
        long startTime = System.currentTimeMillis();
        int currentSliceInterval = TIME_SLICE_INTERVAL.get();

        try {
            List<DeviceBatchTask> tasks = timeSliceTasks.get(sliceIndex);
            if (tasks == null || tasks.isEmpty()) {
                return;
            }
            // 同一时间片内的批次并发派发，尽量在当前时间片周期内收敛。
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (DeviceBatchTask task : tasks) {
                if (task.shouldSkip()) {
                    continue;
                }

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        processDeviceBatch(task);
                    } catch (Exception e) {
                        log.error("设备 {} 批次执行失败", task.deviceId, e);
                    }
                }, batchDispatcher);

                futures.add(future);
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(currentSliceInterval - 10, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("时间片 {} 执行超时", sliceIndex);
            } catch (Exception e) {
                log.error("时间片 {} 执行失败", sliceIndex, e);
            }

        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            performanceMonitor.recordTimeSliceExecution(sliceIndex, executionTime, TIME_SLICE_INTERVAL);

            if (executionTime > currentSliceInterval) {
                log.warn("时间片 {} 执行耗时 {}ms，超过当前时间片间隔 {}ms",
                        sliceIndex, executionTime, currentSliceInterval);
            }
        }
    }

    /**
     * 执行单个设备批次采集。
     */
    private void processDeviceBatch(DeviceBatchTask batchTask) {
        String deviceId = batchTask.deviceId;
        List<DataPoint> points = batchTask.points;

        long startTime = System.currentTimeMillis();
        boolean success = false;

        try {
            // 设备离线时优先尝试重连，避免无效读取。
            if (!collectionManager.isDeviceConnected(deviceId)) {
                if (!reconnectDevice(deviceId)) {
                    log.warn("设备 {} 当前离线且重连失败，跳过本批次采集", deviceId);
                    return;
                }
            }
            Future<Map<String, Object>> collectFuture =
                    asyncCollectorPool.submit(() -> {
                        try {
                            return collectionManager.readPoints(deviceId, points);
                        } catch (Exception e) {
                            throw e;
                        }
                    });
            Map<String, Object> values;
            try {
                values = collectFuture.get(
                        collectorProperties.getScheduler().getCollectTimeoutMs(),
                        TimeUnit.MILLISECONDS
                );
            } catch (TimeoutException e) {
                collectFuture.cancel(true);
                log.warn("设备 {} 批次采集超时，底层任务已取消", deviceId);
                return;
            } catch (InterruptedException e) {
                collectFuture.cancel(true);
                Thread.currentThread().interrupt();
                log.warn("设备 {} 批次采集中断，底层任务已取消", deviceId);
                return;
            }

            if (!values.isEmpty()) {
                CompletableFuture.runAsync(() -> {
                    processCollectedData(deviceId, points, values);
                }, dataProcessorPool);

                success = true;
            }

        } catch (Exception e) {
            log.error("设备 {} 批次采集失败", deviceId, e);
        } finally {
            long executionTime = System.currentTimeMillis() - startTime;
            if (success) {
                collectionStatistics.collectionSuccess(deviceId, executionTime);
                performanceMonitor.recordBatchSuccess(deviceId, points.size(), executionTime);
            } else {
                collectionStatistics.collectionFailed(deviceId);
                performanceMonitor.recordBatchFailure(deviceId);
            }
            // 根据最近一次批次耗时微调后续批大小，避免长期过大或过小。
            if (executionTime > 100) {
                adjustBatchSize(deviceId, -10);
            } else if (executionTime < 20) {
                adjustBatchSize(deviceId, 5);
            }
        }
    }

    /**
     * 延迟自动启动全部设备。
     */
    private void autoStartAllDevices() {
        try {
            log.info("开始自动启动全部设备");
            startAllDevices();
            log.info("全部设备自动启动完成");
        } catch (Exception e) {
            log.error("自动启动全部设备失败", e);
        }
    }

    /**
     * 启动单个设备的采集调度。
     */
    public boolean startDevice(String deviceId) {
        scheduleLock.lock();
        try {
            DeviceScheduleInfo scheduleInfo = deviceScheduleInfo.get(deviceId);
            if (scheduleInfo != null && scheduleInfo.isRunning()) {
                log.warn("设备 {} 已处于采集运行状态", deviceId);
                return false;
            }
            DeviceInfo deviceInfo = configManager.getDevice(deviceId);
            if (deviceInfo == null) {
                log.error("设备 {} 配置不存在", deviceId);
                return false;
            }
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            if (dataPoints.isEmpty()) {
                log.warn("设备 {} 未配置点位", deviceId);
                return false;
            }
            if (collectorProperties.getAdaptiveCollection().isEnabled()) {
                for (DataPoint dataPoint : dataPoints) {
                    AdaptiveCollectionUtil.initDataPointAdaptiveConfig(dataPoint);
                }
            }
            try {
                collectionManager.registerDevice(deviceInfo);
            } catch (Exception e) {
                log.debug("设备 {} 已注册，跳过重复注册", deviceId);
            }
            if (!connectDevice(deviceId)) {
                log.error("设备 {} 启动时连接失败", deviceId);
                return false;
            }
            scheduleDevicePoints(deviceId, dataPoints);
            collectionManager.rebuildReadPlans(deviceId, dataPoints);
            deviceScheduleInfo.put(deviceId, new DeviceScheduleInfo(deviceId, true));
            collectionStatistics.startCollection(deviceId, dataPoints.size());

            collectionServiceHealthTracker.markDeviceStarted(deviceId);

            log.info("设备 {} 采集启动成功，点位数：{}", deviceId, dataPoints.size());
            return true;

        } catch (Exception e) {
            log.error("启动设备 {} 采集失败", deviceId, e);
            return false;
        } finally {
            scheduleLock.unlock();
        }
    }

    /**
     * 将设备点位规划为批次并分配到时间片。
     */
    private void scheduleDevicePoints(String deviceId, List<DataPoint> points) {
        List<DeviceBatchTask> batchTasks = deviceBatchPlanner.plan(
                deviceId,
                points,
                TIME_SLICE_COUNT.get(),
                performanceMonitor);
        for (DeviceBatchTask batchTask : batchTasks) {
            List<DeviceBatchTask> tasks = timeSliceTasks.get(batchTask.timeSliceIndex);
            if (tasks != null) {
                tasks.add(batchTask);
            }
        }
    }

    /**
     * 建立设备连接并预热自适应配置。
     */
    private boolean connectDevice(String deviceId) {
        try {
            collectionManager.connectDevice(deviceId);
            configManager.getDataPointsAndAdaptiveConfig(deviceId);
            return true;
        } catch (Exception e) {
            log.error("设备 {} 连接失败", deviceId, e);
            return false;
        }
    }

    /**
     * 断线后重连设备。
     */
    private boolean reconnectDevice(String deviceId) {
        try {
            collectionManager.reconnectDevice(deviceId);
            return true;
        } catch (Exception e) {
            log.error("设备 {} 重连失败", deviceId, e);
            return false;
        }
    }

    /**
     * 停止单个设备采集并清理其调度状态。
     */
    public boolean stopDevice(String deviceId) {
        scheduleLock.lock();
        try {
            for (List<DeviceBatchTask> tasks : timeSliceTasks.values()) {
                tasks.removeIf(task -> task.deviceId.equals(deviceId));
            }
            try {
                collectionManager.disconnectDevice(deviceId);
            } catch (Exception e) {
                log.warn("设备 {} 断开连接失败", deviceId, e);
            }
            deviceScheduleInfo.remove(deviceId);
            collectionStatistics.stopCollection(deviceId);
            collectionServiceHealthTracker.markDeviceStopped(deviceId);

            log.info("设备 {} 采集已停止", deviceId);
            return true;

        } catch (Exception e) {
            log.error("停止设备 {} 采集失败", deviceId, e);
            return false;
        } finally {
            scheduleLock.unlock();
        }
    }

    /**
     * 启动全部已配置设备。
     */
    public void startAllDevices() {
        List<String> deviceIds = configManager.getAllDeviceIds();
        log.info("开始启动全部设备采集，总数：{}", deviceIds.size());

        int successCount = 0;
        int failCount = 0;

        for (String deviceId : deviceIds) {
            try {
                DeviceContext context = configManager.getDeviceContext(deviceId);
                if (context != null
                        && context.getDeviceInfo() != null
                        && context.getConnectionConfig() != null) {
                    if (startDevice(deviceId)) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                }
            } catch (Exception e) {
                log.error("启动设备 {} 失败", deviceId, e);
                failCount++;
            }
        }

        log.info("全部设备启动完成，成功：{}，失败：{}", successCount, failCount);
    }

    /**
     * 停止全部运行中的设备。
     */
    public void stopAllDevices() {
        List<String> runningDevices = new ArrayList<>(deviceScheduleInfo.keySet());
        log.info("开始停止全部设备采集，当前运行设备数：{}", runningDevices.size());

        for (String deviceId : runningDevices) {
            try {
                stopDevice(deviceId);
            } catch (Exception e) {
                log.error("停止设备 {} 失败", deviceId, e);
            }
        }

        log.info("全部设备采集已停止");
    }

    /**
     * 重载全部设备采集。
     */
    public void reloadAllDevices() {
        log.info("开始重载全部设备采集");
        stopAllDevices();
        timeSliceScheduler.schedule(this::startAllDevices, 2, TimeUnit.SECONDS);
    }

    /**
     * 将采集结果交给后处理链路。
     */
    private void processCollectedData(String deviceId, List<DataPoint> points,
                                      Map<String, Object> values) {
        collectedDataProcessor.process(deviceId, points, values, performanceMonitor);
    }

    /**
     * 记录批大小调整结果，便于问题定位。
     */
    private void updateOptimalBatchSize(String deviceId, int newSize) {
        log.debug("设备 {} 的目标批大小已更新为 {}", deviceId, newSize);
    }

    /**
     * 按比例调整设备批大小。
     */
    private void adjustBatchSize(String deviceId, int percentChange) {
        performanceMonitor.adjustBatchSize(deviceId, percentChange);
    }

    /**
     * 启动周期性能统计输出。
     */
    private void startPerformanceMonitoring() {
        timeSliceScheduler.scheduleAtFixedRate(
                () -> performanceMonitor.logStatistics(TIME_SLICE_INTERVAL),
                60, 60, TimeUnit.SECONDS
        );
    }
    
    /**
     * 启动动态时间片调优任务。
     */
    private void startDynamicTimeSliceAdjustment() {
        int dynamicAdjustInterval = collectorProperties.getScheduler().getDynamicAdjustIntervalMs();
        timeSliceScheduler.scheduleAtFixedRate(this::adjustTimeSlicesDynamically, dynamicAdjustInterval, dynamicAdjustInterval, TimeUnit.MILLISECONDS);
        log.info("动态时间片调优任务已启动，调优周期：{}ms", dynamicAdjustInterval);
    }
    
    /**
     * 根据运行负载动态调整时间片数量和间隔。
     */
    private void adjustTimeSlicesDynamically() {
        try {
            double cpuLoad = getSystemCpuLoad();
            int activeDevices = deviceScheduleInfo.size();
            long totalTasks = timeSliceTasks.values().stream().mapToInt(List::size).sum();
            int newSliceCount = calculateOptimalSliceCount(activeDevices, totalTasks, cpuLoad);
            long avgExecution = performanceMonitor.getAverageTimeSliceExecution();
            boolean timeoutDetected = performanceMonitor.consumeTimeSliceTimeout();
            int tunedInterval = timeSliceTuner != null
                    ? timeSliceTuner.adjustInterval(TIME_SLICE_INTERVAL.get(), avgExecution, timeoutDetected)
                    : TIME_SLICE_INTERVAL.get();
            applyTimeSliceConfigUpdate(newSliceCount, tunedInterval);

            log.info("动态调优完成，设备数：{}，批次数：{}，CPU 负载：{}，平均时间片耗时：{}ms，是否检测到超时：{}，时间片数量：{}，时间片间隔：{}ms，调优模式：{}",
                    activeDevices,
                    totalTasks,
                    String.format("%.2f", cpuLoad),
                    avgExecution,
                    timeoutDetected,
                    newSliceCount,
                    tunedInterval,
                    timeSliceTuner != null ? timeSliceTuner.getMode() : "UNKNOWN");
        } catch (Exception e) {
            log.error("动态时间片调优失败", e);
        }
    }
    
    /**
     * 根据设备数、任务数和线程池负载估算目标时间片数量。
     */
    private int calculateOptimalSliceCount(int activeDevices, long totalTasks, double cpuLoad) {
        int baseSlices = Math.max(1, Math.min(activeDevices / 5 + 1, collectorProperties.getScheduler().getMaxTimeSliceCount()));
        if (cpuLoad > 0.8) {
            baseSlices = Math.min(collectorProperties.getScheduler().getMaxTimeSliceCount(), baseSlices + 2);
        } else if (cpuLoad < 0.3) {
            baseSlices = Math.max(2, baseSlices - 1);
        }
        
        return baseSlices;
    }
    
    /**
     * 应用时间片配置变更，并按需重建分片和定时任务。
     */
    private void applyTimeSliceConfigUpdate(int newSliceCount, int newSliceInterval) {
        int normalizedSliceCount = Math.max(1, newSliceCount);
        int normalizedSliceInterval = Math.max(
                collectorProperties.getScheduler().getMinTimeSliceIntervalMs(),
                newSliceInterval
        );

        scheduleLock.lock();
        try {
            int oldSliceCount = TIME_SLICE_COUNT.get();
            int oldSliceInterval = TIME_SLICE_INTERVAL.get();
            boolean sliceCountChanged = normalizedSliceCount != oldSliceCount;
            boolean intervalChanged = normalizedSliceInterval != oldSliceInterval;
            if (!sliceCountChanged && !intervalChanged) {
                return;
            }

            TIME_SLICE_COUNT.set(normalizedSliceCount);
            TIME_SLICE_INTERVAL.set(normalizedSliceInterval);
            if (sliceCountChanged) {
                rebuildTimeSliceAssignments();
            }
            startTimeSliceScheduling();
        } finally {
            scheduleLock.unlock();
        }
    }

    /**
     * 按当前时间片参数重新分配所有运行中设备。
     */
    private void rebuildTimeSliceAssignments() {
        resetTimeSliceTaskBuckets(TIME_SLICE_COUNT.get());
        List<String> deviceIds = new ArrayList<>(deviceScheduleInfo.keySet());
        for (String deviceId : deviceIds) {
            try {
                List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
                if (dataPoints == null || dataPoints.isEmpty()) {
                    log.warn("设备 {} 在重分配时间片时没有点位，已跳过", deviceId);
                    continue;
                }
                scheduleDevicePoints(deviceId, dataPoints);
            } catch (Exception e) {
                log.error("重建设备 {} 的时间片分配失败", deviceId, e);
            }
        }
    }

    /**
     * 兼容旧调用路径，统一走时间片配置更新入口。
     */
    private void updateTimeSliceConfig(int newSliceCount, int newSliceInterval) {
        applyTimeSliceConfigUpdate(newSliceCount, newSliceInterval);
    }
    
    /**
     * 重新分配全部运行中设备的时间片。
     */
    private void rescheduleAllDevices() {
        rebuildTimeSliceAssignments();
    }
    
    /**
     * 以采集与处理线程池活跃度近似估算系统负载。
     */
    private double getSystemCpuLoad() {
        int activeThreads = asyncCollectorPool.getActiveCount() + dataProcessorPool.getActiveCount();
        int maxThreads = asyncCollectorPool.getMaximumPoolSize() + dataProcessorPool.getMaximumPoolSize();
        return Math.min(1.0, (double) activeThreads / maxThreads);
    }

    /**
     * 平滑关闭指定线程池。
     */
    private void shutdownExecutor(String name, ExecutorService executor) {
        if (executor != null && !executor.isShutdown()) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("{} 已关闭", name);
        }
    }

    /**
     * 查询单设备调度状态。
     */
    public Map<String, Object> getDeviceScheduleStatus(String deviceId) {
        Map<String, Object> status = new HashMap<>();
        status.put("deviceId", deviceId);

        DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
        status.put("isRunning", info != null && info.isRunning());
        status.put("connected", collectionManager.isDeviceConnected(deviceId));
        status.put("statistics", collectionStatistics.getDeviceStatistics(deviceId));
        status.put("performance", performanceMonitor.getDevicePerformance(deviceId));

        return status;
    }

    /**
     * 返回当前所有处于运行状态的设备 ID。
     */
    public List<String> getRunningDevices() {
        return deviceScheduleInfo.entrySet().stream()
                .filter(entry -> entry.getValue().isRunning())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 判断设备是否处于运行状态。
     */
    public boolean isDeviceRunning(String deviceId) {
        DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
        return info != null && info.isRunning();
    }

    /**
     * 处理配置变更事件。
     *
     * <p>本地删除事件直接停采，其他更新事件做防抖后重启设备。
     */
    @EventListener
    public void handleConfigUpdate(ConfigUpdateEvent event) {
        String deviceId = event.getDeviceId();
        if ("local-delete".equals(event.getConfigType())) {
            if (deviceId != null && isDeviceRunning(deviceId)) {
                log.info("设备 {} 收到本地删除配置事件，停止采集", deviceId);
                stopDevice(deviceId);
            }
            return;
        }
        if (deviceId != null && isDeviceRunning(deviceId)) {
            log.info("设备 {} 收到配置更新事件，准备重启采集", deviceId);
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

