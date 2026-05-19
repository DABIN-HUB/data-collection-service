package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备批次规划器：负责点位分组、分批与时间片分配。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DeviceBatchPlanner {

    private final ConfigManager configManager;
    private final ProtocolBatchStrategy protocolBatchStrategy;

    List<DeviceBatchTask> plan(String deviceId,
                               List<DataPoint> points,
                               int timeSliceCount,
                               PerformanceMonitor performanceMonitor) {
        List<List<DataPoint>> batches = smartBatchGrouping(points, deviceId, performanceMonitor);
        List<DeviceBatchTask> tasks = new ArrayList<>(batches.size());
        for (int i = 0; i < batches.size(); i++) {
            int timeSliceIndex = calculateOptimalTimeSlice(deviceId, i, batches.size(), timeSliceCount);
            tasks.add(new DeviceBatchTask(deviceId, batches.get(i), timeSliceIndex));
        }
        log.debug("设备 {} 点位调度完成，批次数: {}", deviceId, tasks.size());
        return tasks;
    }

    private List<List<DataPoint>> smartBatchGrouping(List<DataPoint> points,
                                                      String deviceId,
                                                      PerformanceMonitor performanceMonitor) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<DataPoint>> dataTypeGroups = new HashMap<>();
        for (DataPoint point : points) {
            String dataType = point.getDataType() != null ? point.getDataType() : "UNKNOWN";
            dataTypeGroups.computeIfAbsent(dataType, k -> new ArrayList<>()).add(point);
        }

        List<List<DataPoint>> allBatches = new ArrayList<>();
        for (List<DataPoint> typeGroup : dataTypeGroups.values()) {
            typeGroup.sort(this::comparePointsByAddress);
            allBatches.addAll(createSmartBatches(typeGroup, deviceId, performanceMonitor));
        }

        String protocol = resolveProtocol(deviceId);
        allBatches = mergeSmallBatches(allBatches, 10, protocolBatchStrategy.maxMergedBatchSize(protocol));
        log.debug("设备 {} 智能分组完成: {}点 -> {}批", deviceId, points.size(), allBatches.size());
        return allBatches;
    }

    private int comparePointsByAddress(DataPoint p1, DataPoint p2) {
        String addr1 = p1.getAddress();
        String addr2 = p2.getAddress();
        if (addr1 == null || addr2 == null) {
            return 0;
        }
        try {
            Integer num1 = extractNumberFromAddress(addr1);
            Integer num2 = extractNumberFromAddress(addr2);
            if (num1 != null && num2 != null) {
                return num1.compareTo(num2);
            }
        } catch (Exception ignored) {
        }
        return addr1.compareTo(addr2);
    }

    private Integer extractNumberFromAddress(String address) {
        if (address == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(address);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private List<List<DataPoint>> createSmartBatches(List<DataPoint> points,
                                                      String deviceId,
                                                      PerformanceMonitor performanceMonitor) {
        List<List<DataPoint>> batches = new ArrayList<>();
        if (points.isEmpty()) {
            return batches;
        }

        int optimalBatchSize = getOptimalBatchSize(deviceId, performanceMonitor);
        int addressGapThreshold = protocolBatchStrategy.addressGapThreshold(resolveProtocol(deviceId));
        List<DataPoint> currentBatch = new ArrayList<>();
        String lastAddress = null;

        for (DataPoint point : points) {
            String currentAddress = point.getAddress();
            if (currentBatch.size() >= optimalBatchSize) {
                batches.add(new ArrayList<>(currentBatch));
                currentBatch.clear();
                lastAddress = null;
            }
            if (lastAddress != null && currentAddress != null) {
                Integer lastNum = extractNumberFromAddress(lastAddress);
                Integer currentNum = extractNumberFromAddress(currentAddress);
                if (lastNum != null && currentNum != null && currentNum - lastNum > addressGapThreshold) {
                    if (!currentBatch.isEmpty()) {
                        batches.add(new ArrayList<>(currentBatch));
                        currentBatch.clear();
                    }
                }
            }
            currentBatch.add(point);
            lastAddress = currentAddress;
        }

        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        return batches;
    }

    private List<List<DataPoint>> mergeSmallBatches(List<List<DataPoint>> batches, int minBatchSize, int maxBatchSize) {
        if (batches.size() <= 1) {
            return batches;
        }

        List<List<DataPoint>> mergedBatches = new ArrayList<>();
        List<DataPoint> currentBatch = new ArrayList<>();
        for (List<DataPoint> batch : batches) {
            if (currentBatch.size() + batch.size() <= minBatchSize * 2) {
                currentBatch.addAll(batch);
            } else {
                if (!currentBatch.isEmpty()) {
                    mergedBatches.add(new ArrayList<>(currentBatch));
                }
                currentBatch = new ArrayList<>(batch);
            }
        }
        if (!currentBatch.isEmpty()) {
            mergedBatches.add(currentBatch);
        }

        List<List<DataPoint>> finalBatches = new ArrayList<>();
        for (List<DataPoint> batch : mergedBatches) {
            if (batch.size() > maxBatchSize) {
                for (int i = 0; i < batch.size(); i += maxBatchSize) {
                    int end = Math.min(i + maxBatchSize, batch.size());
                    finalBatches.add(new ArrayList<>(batch.subList(i, end)));
                }
            } else {
                finalBatches.add(batch);
            }
        }
        return finalBatches;
    }

    private int getOptimalBatchSize(String deviceId, PerformanceMonitor performanceMonitor) {
        try {
            DevicePerformance perf = performanceMonitor.devicePerformance.get(deviceId);
            if (perf != null) {
                double successRate = perf.successfulBatches.get() /
                        Math.max(1.0, perf.successfulBatches.get() + perf.failedBatches.get());
                long avgExecutionTime = perf.successfulBatches.get() > 0
                        ? perf.totalExecutionTime.get() / perf.successfulBatches.get()
                        : 0;
                int optimalSize = perf.currentBatchSize;
                if (successRate < 0.8) {
                    optimalSize = Math.max(10, optimalSize * 80 / 100);
                } else if (avgExecutionTime < 50 && successRate > 0.95) {
                    optimalSize = Math.min(200, optimalSize * 120 / 100);
                } else if (avgExecutionTime > 200) {
                    optimalSize = Math.max(10, optimalSize * 70 / 100);
                }

                DeviceInfo deviceInfo = configManager.getDevice(deviceId);
                if (deviceInfo != null) {
                    String protocol = deviceInfo.getProtocolType();
                    if (protocol != null) {
                        optimalSize = Math.min(optimalSize, getProtocolMaxBatchSize(protocol));
                    }
                }
                return optimalSize;
            }
        } catch (Exception e) {
            log.warn("获取设备 {} 最优批量大小失败，使用默认值", deviceId, e);
        }
        return getDefaultBatchSizeByProtocol(deviceId);
    }

    private int getDefaultBatchSizeByProtocol(String deviceId) {
        try {
            DeviceInfo deviceInfo = configManager.getDevice(deviceId);
            if (deviceInfo != null) {
                String protocol = deviceInfo.getProtocolType();
                if (protocol != null) {
                    return protocolBatchStrategy.defaultBatchSize(protocol);
                }
            }
        } catch (Exception e) {
            log.warn("获取设备 {} 协议类型失败，使用默认批量大小", deviceId, e);
        }
        return protocolBatchStrategy.defaultBatchSize(null);
    }

    private int getProtocolMaxBatchSize(String protocol) {
        return protocolBatchStrategy.maxBatchSize(protocol);
    }

    private String resolveProtocol(String deviceId) {
        DeviceInfo deviceInfo = configManager.getDevice(deviceId);
        return deviceInfo != null ? deviceInfo.getProtocolType() : null;
    }

    private int calculateOptimalTimeSlice(String deviceId, int batchIndex, int totalBatches, int timeSliceCount) {
        int sliceCount = Math.max(1, timeSliceCount);
        int deviceHash = Math.abs(deviceId.hashCode());
        int baseSlice = deviceHash % sliceCount;
        int sliceIncrement = sliceCount / Math.min(totalBatches, sliceCount);
        return (baseSlice + batchIndex * sliceIncrement) % sliceCount;
    }
}

