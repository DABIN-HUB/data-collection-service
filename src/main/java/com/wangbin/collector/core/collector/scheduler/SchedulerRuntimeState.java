package com.wangbin.collector.core.collector.scheduler;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 保存调度器运行期状态，并集中处理时间片任务桶和设备运行态的并发访问。
 */
@Component
public class SchedulerRuntimeState {

    private final ConcurrentHashMap<String, DeviceScheduleInfo> deviceScheduleInfo = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, List<DeviceBatchTask>> timeSliceTasks = new ConcurrentHashMap<>();
    private final Set<String> startingDevices = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger timeSliceCount = new java.util.concurrent.atomic.AtomicInteger(2);
    private final java.util.concurrent.atomic.AtomicInteger timeSliceInterval = new java.util.concurrent.atomic.AtomicInteger(1000);
    private final java.util.concurrent.atomic.AtomicLong timeSliceRevision = new java.util.concurrent.atomic.AtomicLong(0);
    private final ReentrantLock stateLock = new ReentrantLock();

    void runExclusive(Runnable action) {
        stateLock.lock();
        try {
            action.run();
        } finally {
            stateLock.unlock();
        }
    }

    <T> T callExclusive(Supplier<T> action) {
        stateLock.lock();
        try {
            return action.get();
        } finally {
            stateLock.unlock();
        }
    }

    void initializeTimeSlices(int sliceCount, int intervalMs) {
        timeSliceCount.set(Math.max(1, sliceCount));
        timeSliceInterval.set(Math.max(1, intervalMs));
        timeSliceRevision.set(1L);
        resetTimeSliceBuckets(timeSliceCount.get());
    }

    int getTimeSliceCount() {
        return timeSliceCount.get();
    }

    int getTimeSliceInterval() {
        return timeSliceInterval.get();
    }

    long getTimeSliceRevision() {
        return timeSliceRevision.get();
    }

    long updateTimeSliceConfig(int sliceCount, int intervalMs) {
        timeSliceCount.set(Math.max(1, sliceCount));
        timeSliceInterval.set(Math.max(1, intervalMs));
        return timeSliceRevision.incrementAndGet();
    }

    void resetTimeSliceBuckets(int sliceCount) {
        timeSliceTasks.clear();
        for (int i = 0; i < Math.max(1, sliceCount); i++) {
            timeSliceTasks.put(i, new CopyOnWriteArrayList<>());
        }
    }

    List<DeviceBatchTask> getSliceTasks(int sliceIndex) {
        List<DeviceBatchTask> tasks = timeSliceTasks.get(sliceIndex);
        return tasks == null ? List.of() : List.copyOf(tasks);
    }

    void addBatchTasks(List<DeviceBatchTask> batchTasks) {
        if (batchTasks == null || batchTasks.isEmpty()) {
            return;
        }
        for (DeviceBatchTask batchTask : batchTasks) {
            if (batchTask == null) {
                continue;
            }
            List<DeviceBatchTask> tasks = timeSliceTasks.get(batchTask.timeSliceIndex);
            if (tasks != null) {
                tasks.add(batchTask);
            }
        }
    }

    void removeDeviceTasks(String deviceId) {
        for (List<DeviceBatchTask> tasks : timeSliceTasks.values()) {
            tasks.removeIf(task -> {
                if (task.deviceId.equals(deviceId)) {
                    task.cancel();
                    return true;
                }
                return false;
            });
        }
    }

    boolean markStarting(String deviceId) {
        return startingDevices.add(deviceId);
    }

    void clearStarting(String deviceId) {
        startingDevices.remove(deviceId);
    }

    boolean isStarting(String deviceId) {
        return startingDevices.contains(deviceId);
    }

    void markRunning(String deviceId, long generation) {
        deviceScheduleInfo.put(deviceId, new DeviceScheduleInfo(deviceId, generation, true));
    }

    DeviceScheduleInfo removeDevice(String deviceId) {
        clearStarting(deviceId);
        return deviceScheduleInfo.remove(deviceId);
    }

    boolean isRunning(String deviceId) {
        DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
        return info != null && info.isRunning();
    }

    DeviceScheduleInfo getScheduleInfo(String deviceId) {
        return deviceScheduleInfo.get(deviceId);
    }

    List<String> getRunningDevices() {
        return deviceScheduleInfo.entrySet().stream()
                .filter(entry -> entry.getValue().isRunning())
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    int getRunningDeviceCount() {
        return deviceScheduleInfo.size();
    }

    Set<String> getKnownDeviceIds() {
        Set<String> deviceIds = new HashSet<>(deviceScheduleInfo.keySet());
        deviceIds.addAll(startingDevices);
        return deviceIds;
    }

    long getTotalTaskCount() {
        return timeSliceTasks.values().stream().mapToInt(List::size).sum();
    }

    long getDeviceBackoffUntil(String deviceId) {
        return timeSliceTasks.values().stream()
                .flatMap(List::stream)
                .filter(task -> deviceId.equals(task.deviceId))
                .mapToLong(DeviceBatchTask::getNextAllowedExecutionTime)
                .max()
                .orElse(0L);
    }

    void clear() {
        for (List<DeviceBatchTask> tasks : timeSliceTasks.values()) {
            for (DeviceBatchTask task : tasks) {
                task.cancel();
            }
        }
        deviceScheduleInfo.clear();
        timeSliceTasks.clear();
        startingDevices.clear();
    }
}
