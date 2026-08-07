package com.wangbin.collector.core.collector.scheduler;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 保存调度器运行期状态，并集中处理时间片任务桶和设备运行态的并发访问。
 */
@Component
public class SchedulerRuntimeState {

    private final ConcurrentHashMap<String, DeviceScheduleInfo> deviceScheduleInfo = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, List<DeviceBatchTask>> timeSliceTasks = new ConcurrentHashMap<>();
    private final Set<String> startingDevices = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> startingGenerations = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger timeSliceCount = new java.util.concurrent.atomic.AtomicInteger(2);
    private final java.util.concurrent.atomic.AtomicInteger timeSliceInterval = new java.util.concurrent.atomic.AtomicInteger(1000);
    private final java.util.concurrent.atomic.AtomicLong timeSliceRevision = new java.util.concurrent.atomic.AtomicLong(0);
    private final ReentrantLock stateLock = new ReentrantLock();

    void initializeTimeSlices(int sliceCount, int intervalMs) {
        stateLock.lock();
        try {
            timeSliceCount.set(Math.max(1, sliceCount));
            timeSliceInterval.set(Math.max(1, intervalMs));
            timeSliceRevision.set(1L);
            resetTimeSliceBucketsLocked(timeSliceCount.get());
        } finally {
            stateLock.unlock();
        }
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
        stateLock.lock();
        try {
            timeSliceCount.set(Math.max(1, sliceCount));
            timeSliceInterval.set(Math.max(1, intervalMs));
            return timeSliceRevision.incrementAndGet();
        } finally {
            stateLock.unlock();
        }
    }

    void resetTimeSliceBuckets(int sliceCount) {
        stateLock.lock();
        try {
            resetTimeSliceBucketsLocked(sliceCount);
        } finally {
            stateLock.unlock();
        }
    }

    private void resetTimeSliceBucketsLocked(int sliceCount) {
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
        stateLock.lock();
        try {
            addBatchTasksLocked(batchTasks);
        } finally {
            stateLock.unlock();
        }
    }

    boolean addBatchTasksIfRunning(String deviceId, long generation, List<DeviceBatchTask> batchTasks) {
        if (batchTasks == null || batchTasks.isEmpty()) {
            return true;
        }
        stateLock.lock();
        try {
            DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
            if (info == null || !info.isRunning() || info.getGeneration() != generation) {
                return false;
            }
            addBatchTasksLocked(batchTasks);
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    private void addBatchTasksLocked(List<DeviceBatchTask> batchTasks) {
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

    List<DeviceBatchTask> removeDeviceTasks(String deviceId) {
        List<DeviceBatchTask> removedTasks = new ArrayList<>();
        stateLock.lock();
        try {
            for (List<DeviceBatchTask> tasks : timeSliceTasks.values()) {
                tasks.removeIf(task -> {
                    if (Objects.equals(task.deviceId, deviceId)) {
                        removedTasks.add(task);
                        return true;
                    }
                    return false;
                });
            }
        } finally {
            stateLock.unlock();
        }
        removedTasks.forEach(DeviceBatchTask::cancel);
        return List.copyOf(removedTasks);
    }

    List<DeviceBatchTask> removeDeviceTasksIfGeneration(String deviceId, long generation) {
        List<DeviceBatchTask> removedTasks = new ArrayList<>();
        stateLock.lock();
        try {
            for (List<DeviceBatchTask> tasks : timeSliceTasks.values()) {
                tasks.removeIf(task -> {
                    if (Objects.equals(task.deviceId, deviceId) && task.generation == generation) {
                        removedTasks.add(task);
                        return true;
                    }
                    return false;
                });
            }
        } finally {
            stateLock.unlock();
        }
        removedTasks.forEach(DeviceBatchTask::cancel);
        return List.copyOf(removedTasks);
    }

    boolean markStarting(String deviceId) {
        return markStartingIfNotActive(deviceId);
    }

    boolean markStartingIfNotActive(String deviceId) {
        stateLock.lock();
        try {
            DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
            if ((info != null && info.isRunning()) || startingDevices.contains(deviceId)) {
                return false;
            }
            return startingDevices.add(deviceId);
        } finally {
            stateLock.unlock();
        }
    }

    void markStartingGeneration(String deviceId, long generation) {
        stateLock.lock();
        try {
            if (startingDevices.contains(deviceId)) {
                startingGenerations.put(deviceId, generation);
            }
        } finally {
            stateLock.unlock();
        }
    }

    void clearStarting(String deviceId) {
        stateLock.lock();
        try {
            startingDevices.remove(deviceId);
            startingGenerations.remove(deviceId);
        } finally {
            stateLock.unlock();
        }
    }

    boolean clearStartingIfGeneration(String deviceId, long generation) {
        stateLock.lock();
        try {
            if (!Objects.equals(startingGenerations.get(deviceId), generation)) {
                return false;
            }
            startingGenerations.remove(deviceId);
            startingDevices.remove(deviceId);
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    boolean isStarting(String deviceId) {
        return startingDevices.contains(deviceId);
    }

    boolean isStartingGeneration(String deviceId, long generation) {
        return Objects.equals(startingGenerations.get(deviceId), generation);
    }

    long getStartingGeneration(String deviceId) {
        return startingGenerations.getOrDefault(deviceId, 0L);
    }

    void markRunning(String deviceId, long generation) {
        stateLock.lock();
        try {
            deviceScheduleInfo.put(deviceId, new DeviceScheduleInfo(deviceId, generation, true));
            startingDevices.remove(deviceId);
            startingGenerations.remove(deviceId);
        } finally {
            stateLock.unlock();
        }
    }

    boolean commitRunning(String deviceId, long generation, List<DeviceBatchTask> batchTasks) {
        stateLock.lock();
        try {
            if (!startingDevices.contains(deviceId) || !Objects.equals(startingGenerations.get(deviceId), generation)) {
                return false;
            }
            deviceScheduleInfo.put(deviceId, new DeviceScheduleInfo(deviceId, generation, true));
            addBatchTasksLocked(batchTasks);
            startingDevices.remove(deviceId);
            startingGenerations.remove(deviceId);
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    DeviceScheduleInfo removeDevice(String deviceId) {
        stateLock.lock();
        try {
            startingDevices.remove(deviceId);
            startingGenerations.remove(deviceId);
            return deviceScheduleInfo.remove(deviceId);
        } finally {
            stateLock.unlock();
        }
    }

    boolean removeDeviceIfGeneration(String deviceId, long generation) {
        stateLock.lock();
        try {
            boolean removed = false;
            if (Objects.equals(startingGenerations.get(deviceId), generation)) {
                startingGenerations.remove(deviceId);
                startingDevices.remove(deviceId);
                removed = true;
            }
            DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
            if (info != null && info.getGeneration() == generation) {
                deviceScheduleInfo.remove(deviceId);
                removed = true;
            }
            return removed;
        } finally {
            stateLock.unlock();
        }
    }

    boolean isRunning(String deviceId) {
        DeviceScheduleInfo info = deviceScheduleInfo.get(deviceId);
        return info != null && info.isRunning();
    }

    DeviceScheduleInfo getScheduleInfo(String deviceId) {
        return deviceScheduleInfo.get(deviceId);
    }

    List<String> getRunningDevices() {
        stateLock.lock();
        try {
            return deviceScheduleInfo.entrySet().stream()
                    .filter(entry -> entry.getValue().isRunning())
                    .map(java.util.Map.Entry::getKey)
                    .toList();
        } finally {
            stateLock.unlock();
        }
    }

    Set<String> getActiveDeviceIds() {
        stateLock.lock();
        try {
            Set<String> deviceIds = new HashSet<>(deviceScheduleInfo.keySet());
            deviceIds.addAll(startingDevices);
            return Set.copyOf(deviceIds);
        } finally {
            stateLock.unlock();
        }
    }

    List<DeviceScheduleInfo> getRunningScheduleSnapshot() {
        stateLock.lock();
        try {
            return deviceScheduleInfo.values().stream()
                    .filter(DeviceScheduleInfo::isRunning)
                    .toList();
        } finally {
            stateLock.unlock();
        }
    }

    int getRunningDeviceCount() {
        return deviceScheduleInfo.size();
    }

    Set<String> getKnownDeviceIds() {
        return getActiveDeviceIds();
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
        List<DeviceBatchTask> tasksToCancel = new ArrayList<>();
        stateLock.lock();
        try {
            for (List<DeviceBatchTask> tasks : timeSliceTasks.values()) {
                tasksToCancel.addAll(tasks);
            }
            deviceScheduleInfo.clear();
            timeSliceTasks.clear();
            startingDevices.clear();
            startingGenerations.clear();
        } finally {
            stateLock.unlock();
        }
        for (DeviceBatchTask task : tasksToCancel) {
            task.cancel();
        }
    }
}
