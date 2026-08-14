package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.config.CollectorProperties;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.protocol.ProtocolAddressingMode;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptor;
import com.wangbin.collector.core.config.protocol.ProtocolDescriptorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * 设备批次规划器：负责点位分组、分批与时间片分配。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class DeviceBatchPlanner {

    private final ConfigManager configManager;
    private final CollectorProperties collectorProperties;
    private final PointRuntimeStateService pointRuntimeStateService;
    private final ProtocolBatchStrategy protocolBatchStrategy;
    private final ProtocolDescriptorRegistry protocolDescriptorRegistry;
    private final PerformanceMonitor performanceMonitor;

    /**
     * 执行当前业务逻辑。
     */
    List<DeviceBatchTask> plan(String deviceId,
                               List<DataPoint> points,
                               int timeSliceCount,
                               long generation,
                               long timeSliceRevision) {
        List<List<DataPoint>> batches = smartBatchGrouping(points, deviceId, performanceMonitor);
        DeviceInfo deviceInfo = configManager.getDevice(deviceId);
        ToLongFunction<DataPoint> intervalResolver = buildCollectionIntervalResolver(deviceId, deviceInfo);
        List<DeviceBatchTask> tasks = new ArrayList<>(batches.size());
        for (int i = 0; i < batches.size(); i++) {
            int timeSliceIndex = calculateOptimalTimeSlice(deviceId, i, batches.size(), timeSliceCount);
            tasks.add(new DeviceBatchTask(deviceId, batches.get(i), timeSliceIndex, generation, timeSliceRevision,
                    intervalResolver, System::nanoTime));
        }
        log.debug("设备 {} 点位调度完成，批次数: {}", deviceId, tasks.size());
        return tasks;
    }

    /**
     * 执行当前业务逻辑。
     */
    private List<List<DataPoint>> smartBatchGrouping(List<DataPoint> points,
                                                     String deviceId,
                                                     PerformanceMonitor performanceMonitor) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }

        String protocol = resolveProtocol(deviceId);
        ProtocolAddressingMode addressingMode = resolveAddressingMode(protocol);

        Map<String, List<DataPoint>> groups = new HashMap<>();
        for (DataPoint point : points) {
            String dataType = point.getDataType() != null ? point.getDataType() : "UNKNOWN";
            String groupKey = buildGroupingKey(dataType, point.getAddress(), addressingMode);
            groups.computeIfAbsent(groupKey, key -> new ArrayList<>()).add(point);
        }

        List<List<DataPoint>> allBatches = new ArrayList<>();
        for (List<DataPoint> group : groups.values()) {
            group.sort((left, right) -> comparePointsByAddress(left, right, addressingMode));
            allBatches.addAll(createSmartBatches(group, deviceId, performanceMonitor, protocol, addressingMode));
        }

        allBatches = mergeSmallBatches(allBatches, 10, protocolBatchStrategy.maxMergedBatchSize(protocol));
        log.debug("设备 {} 智能分组完成: {}点 -> {}批", deviceId, points.size(), allBatches.size());
        return allBatches;
    }

    /**
     * 执行当前业务逻辑。
     */
    private int comparePointsByAddress(DataPoint left,
                                       DataPoint right,
                                       ProtocolAddressingMode addressingMode) {
        String addr1 = left.getAddress();
        String addr2 = right.getAddress();
        if (addr1 == null || addr2 == null) {
            return 0;
        }
        if (addressingMode == ProtocolAddressingMode.SYMBOLIC) {
            return normalizeAddress(addr1).compareTo(normalizeAddress(addr2));
        }
        Integer num1 = extractNumberFromAddress(addr1);
        Integer num2 = extractNumberFromAddress(addr2);
        if (num1 != null && num2 != null) {
            return num1.compareTo(num2);
        }
        return normalizeAddress(addr1).compareTo(normalizeAddress(addr2));
    }

    /**
     * 解析或转换业务数据。
     */
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

    /**
     * 创建并返回业务对象。
     */
    private List<List<DataPoint>> createSmartBatches(List<DataPoint> points,
                                                     String deviceId,
                                                     PerformanceMonitor performanceMonitor,
                                                     String protocol,
                                                     ProtocolAddressingMode addressingMode) {
        List<List<DataPoint>> batches = new ArrayList<>();
        if (points.isEmpty()) {
            return batches;
        }

        int optimalBatchSize = getOptimalBatchSize(deviceId, performanceMonitor);
        int addressGapThreshold = protocolBatchStrategy.addressGapThreshold(protocol);
        List<DataPoint> currentBatch = new ArrayList<>();
        String lastAddress = null;

        for (DataPoint point : points) {
            String currentAddress = point.getAddress();
            if (currentBatch.size() >= optimalBatchSize) {
                batches.add(new ArrayList<>(currentBatch));
                currentBatch.clear();
                lastAddress = null;
            }
            if (shouldSplitBatch(addressingMode, lastAddress, currentAddress, addressGapThreshold)) {
                if (!currentBatch.isEmpty()) {
                    batches.add(new ArrayList<>(currentBatch));
                    currentBatch.clear();
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

    /**
     * 执行当前业务逻辑。
     */
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

    private ToLongFunction<DataPoint> buildCollectionIntervalResolver(String deviceId, DeviceInfo deviceInfo) {
        long deviceInterval = resolveDeviceCollectionInterval(deviceInfo);
        return point -> {
            if (point == null) {
                return deviceInterval;
            }
            if (collectorProperties.getAdaptiveCollection().isEnabled()) {
                long runtimeInterval = pointRuntimeStateService.snapshot(deviceId, point).currentCollectionInterval();
                if (runtimeInterval > 0) {
                    return runtimeInterval;
                }
            }
            if (point.getCurrentCollectionInterval() > 0) {
                return point.getCurrentCollectionInterval();
            }
            if (point.getBaseCollectionInterval() != null && point.getBaseCollectionInterval() > 0) {
                return point.getBaseCollectionInterval();
            }
            return deviceInterval;
        };
    }

    private long resolveDeviceCollectionInterval(DeviceInfo deviceInfo) {
        if (deviceInfo != null && deviceInfo.getCollectionInterval() != null && deviceInfo.getCollectionInterval() > 0) {
            return deviceInfo.getCollectionInterval();
        }
        return AdaptiveCollectionUtil.DEFAULT_BASE_COLLECTION_INTERVAL;
    }

    /**
     * 解析或转换业务数据。
     */
    private String resolveProtocol(String deviceId) {
        DeviceInfo deviceInfo = configManager.getDevice(deviceId);
        return deviceInfo != null ? deviceInfo.getProtocolType() : null;
    }

    /**
     * 解析或转换业务数据。
     */
    private ProtocolAddressingMode resolveAddressingMode(String protocol) {
        ProtocolDescriptor descriptor = protocolDescriptorRegistry.resolve(protocol);
        return descriptor != null ? descriptor.addressingMode() : ProtocolAddressingMode.NUMERIC;
    }

    /**
     * 创建并返回业务对象。
     */
    private String buildGroupingKey(String dataType, String address, ProtocolAddressingMode addressingMode) {
        if (addressingMode == ProtocolAddressingMode.NUMERIC) {
            return dataType;
        }
        String addressKey = extractSymbolicGroupKey(address);
        return addressKey == null || addressKey.isBlank() ? dataType : dataType + "|" + addressKey;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean shouldSplitBatch(ProtocolAddressingMode addressingMode,
                                     String lastAddress,
                                     String currentAddress,
                                     int addressGapThreshold) {
        if (lastAddress == null || currentAddress == null || addressingMode == ProtocolAddressingMode.SYMBOLIC) {
            return false;
        }
        Integer lastNum = extractNumberFromAddress(lastAddress);
        Integer currentNum = extractNumberFromAddress(currentAddress);
        if (lastNum == null || currentNum == null) {
            return false;
        }
        if (addressingMode == ProtocolAddressingMode.MIXED) {
            String lastGroup = normalizeAddress(extractSymbolicGroupKey(lastAddress));
            String currentGroup = normalizeAddress(extractSymbolicGroupKey(currentAddress));
            if (!lastGroup.equals(currentGroup)) {
                return false;
            }
        }
        return currentNum - lastNum > addressGapThreshold;
    }

    /**
     * 解析或转换业务数据。
     */
    private String extractSymbolicGroupKey(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        String normalized = normalizeAddress(address);
        int namespaceEnd = normalized.indexOf(';');
        if (normalized.startsWith("NS=") && namespaceEnd > 0) {
            return normalized.substring(0, namespaceEnd);
        }
        java.util.regex.Matcher dbMatcher = java.util.regex.Pattern.compile("(DB\\d+)").matcher(normalized);
        if (dbMatcher.find()) {
            return dbMatcher.group(1);
        }
        int slash = normalized.lastIndexOf('/');
        if (slash > 0) {
            return normalized.substring(0, slash);
        }
        int dot = normalized.lastIndexOf('.');
        if (dot > 0) {
            return normalized.substring(0, dot);
        }
        int colon = normalized.lastIndexOf(':');
        if (colon > 0) {
            return normalized.substring(0, colon);
        }
        return normalized.replaceAll("(\\d+|\\[[^\\]]+])$", "");
    }

    /**
     * 解析或转换业务数据。
     */
    private String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toUpperCase();
    }

    /**
     * 执行当前业务逻辑。
     */
    private int calculateOptimalTimeSlice(String deviceId, int batchIndex, int totalBatches, int timeSliceCount) {
        int sliceCount = Math.max(1, timeSliceCount);
        int deviceHash = Math.abs(deviceId.hashCode());
        int baseSlice = deviceHash % sliceCount;
        int sliceIncrement = sliceCount / Math.min(totalBatches, sliceCount);
        return (baseSlice + batchIndex * Math.max(1, sliceIncrement)) % sliceCount;
    }
}
