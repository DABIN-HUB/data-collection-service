package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.AllDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.CompactAllDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.CompactDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.CompactRealtimeDeviceStatus;
import com.wangbin.collector.api.controller.dto.CompactRealtimePointPayload;
import com.wangbin.collector.api.controller.dto.DeviceBriefResponse;
import com.wangbin.collector.api.controller.dto.DeviceListResponse;
import com.wangbin.collector.api.controller.dto.DevicePointListResponse;
import com.wangbin.collector.api.controller.dto.DeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.PointRealtimePayload;
import com.wangbin.collector.api.controller.dto.PointRealtimeResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时缓存数据查询应用服务。
 *
 * <p>只负责从配置和实时缓存组装点位实时值、设备实时值以及设备/点位摘要。</p>
 */
@Slf4j
@Service
public class RealtimeDataQueryApplicationService {

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_ERROR = "error";
    private static final String DEVICE_POINTS_MISSING_MESSAGE = "设备不存在或无数据点";

    private final MultiLevelCacheManager cacheManager;
    private final ConfigManager configManager;
    private final PointRuntimeStateService pointRuntimeStateService;

    /**
     * 创建实时缓存数据查询应用服务。
     */
    public RealtimeDataQueryApplicationService(
            @Qualifier("multiLevelCacheManager") MultiLevelCacheManager cacheManager,
            ConfigManager configManager,
            PointRuntimeStateService pointRuntimeStateService) {
        this.cacheManager = cacheManager;
        this.configManager = configManager;
        this.pointRuntimeStateService = pointRuntimeStateService;
    }

    /**
     * 查询指定设备的指定点位实时数据。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointId 稳定点位唯一标识
     * @return 单点实时数据响应
     */
    public PointRealtimeResponse getPointData(String deviceId, String pointId) {
        try {
            DataPoint dataPoint = configManager.getDataPointByPointId(deviceId, pointId);
            if (dataPoint == null) {
                return pointError(deviceId, pointId, "数据点不存在");
            }
            Object value = cacheManager.get(CacheKey.dataKey(deviceId, pointId));
            PointRealtimePayload payload = buildPointPayload(dataPoint, value);
            return PointRealtimeResponse.builder()
                    .status(STATUS_SUCCESS)
                    .deviceId(deviceId)
                    .pointId(pointId)
                    .data(payload)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("查询点位实时数据失败，设备={}，点位={}", deviceId, pointId, exception);
            return pointError(deviceId, pointId, "查询失败: " + exception.getMessage());
        }
    }

    /**
     * 查询指定设备的实时数据。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointIds 可选点位过滤条件
     * @return 设备实时数据响应
     */
    public DeviceRealtimeDataResponse getDeviceData(String deviceId, List<String> pointIds) {
        try {
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            if (dataPoints.isEmpty()) {
                return DeviceRealtimeDataResponse.builder()
                        .status(STATUS_ERROR)
                        .message(DEVICE_POINTS_MISSING_MESSAGE)
                        .timestamp(System.currentTimeMillis())
                        .build();
            }

            if (pointIds != null && !pointIds.isEmpty()) {
                dataPoints = dataPoints.stream()
                        .filter(point -> pointIds.contains(point.getPointId()))
                        .toList();
            }

            Map<CacheKey, Object> values = dataPoints.isEmpty()
                    ? Collections.emptyMap()
                    : cacheManager.getAll(buildCacheKeys(deviceId, dataPoints));
            Map<String, PointRealtimePayload> dataMap = buildPointDataMap(deviceId, dataPoints, values);

            return DeviceRealtimeDataResponse.builder()
                    .status(STATUS_SUCCESS)
                    .deviceId(deviceId)
                    .dataCount(dataMap.size())
                    .data(dataMap)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("查询设备实时数据失败，设备={}", deviceId, exception);
            return DeviceRealtimeDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + exception.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询全部设备的实时数据。
     *
     * @return 全设备实时数据聚合响应
     */
    public AllDeviceRealtimeDataResponse getAllRealtimeData() {
        try {
            List<String> deviceIds = configManager.getAllDeviceIds();
            if (deviceIds.isEmpty()) {
                return AllDeviceRealtimeDataResponse.builder()
                        .status(STATUS_SUCCESS)
                        .deviceCount(0)
                        .dataCount(0)
                        .devices(List.of())
                        .timestamp(System.currentTimeMillis())
                        .build();
            }

            Map<String, List<DataPoint>> pointsByDevice = new LinkedHashMap<>();
            List<CacheKey> allCacheKeys = new ArrayList<>();
            for (String deviceId : deviceIds) {
                List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
                List<DataPoint> safePoints = dataPoints != null ? dataPoints : List.of();
                pointsByDevice.put(deviceId, safePoints);
                if (!safePoints.isEmpty()) {
                    allCacheKeys.addAll(buildCacheKeys(deviceId, safePoints));
                }
            }

            Map<CacheKey, Object> values = allCacheKeys.isEmpty()
                    ? Collections.emptyMap()
                    : cacheManager.getAll(allCacheKeys);
            List<DeviceRealtimeDataResponse> devices = new ArrayList<>();
            int totalDataCount = 0;
            for (Map.Entry<String, List<DataPoint>> entry : pointsByDevice.entrySet()) {
                DeviceRealtimeDataResponse response = buildAggregateDeviceRealtimeDataResponse(
                        entry.getKey(),
                        entry.getValue(),
                        values);
                devices.add(response);
                totalDataCount += response.getDataCount() == null ? 0 : response.getDataCount();
            }

            return AllDeviceRealtimeDataResponse.builder()
                    .status(STATUS_SUCCESS)
                    .deviceCount(devices.size())
                    .dataCount(totalDataCount)
                    .devices(devices)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("查询全部设备实时数据失败", exception);
            return AllDeviceRealtimeDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + exception.getMessage())
                    .deviceCount(0)
                    .dataCount(0)
                    .devices(List.of())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询指定设备的实时表格紧凑快照。
     *
     * @param deviceId 本地设备唯一标识
     * @return 单设备实时表格紧凑快照
     */
    public CompactDeviceRealtimeDataResponse getCompactDeviceData(String deviceId) {
        try {
            List<DataPoint> dataPoints = safeDataPoints(configManager.getDataPoints(deviceId));
            if (dataPoints.isEmpty()) {
                return CompactDeviceRealtimeDataResponse.builder()
                        .status(STATUS_ERROR)
                        .message(DEVICE_POINTS_MISSING_MESSAGE)
                        .deviceId(deviceId)
                        .dataCount(0)
                        .rows(List.of())
                        .timestamp(System.currentTimeMillis())
                        .build();
            }

            Map<CacheKey, Object> values = cacheManager.getAll(buildCacheKeys(deviceId, dataPoints));
            List<CompactRealtimePointPayload> rows = buildCompactRows(deviceId, dataPoints, values);
            return CompactDeviceRealtimeDataResponse.builder()
                    .status(STATUS_SUCCESS)
                    .deviceId(deviceId)
                    .dataCount(rows.size())
                    .rows(rows)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("查询设备实时紧凑数据失败，设备={}", deviceId, exception);
            return CompactDeviceRealtimeDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + exception.getMessage())
                    .deviceId(deviceId)
                    .dataCount(0)
                    .rows(List.of())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询全部设备的实时表格紧凑快照。
     *
     * @return 全设备实时表格紧凑聚合响应
     */
    public CompactAllDeviceRealtimeDataResponse getCompactAllRealtimeData() {
        try {
            List<String> deviceIds = configManager.getAllDeviceIds();
            if (deviceIds.isEmpty()) {
                return CompactAllDeviceRealtimeDataResponse.builder()
                        .status(STATUS_SUCCESS)
                        .deviceCount(0)
                        .dataCount(0)
                        .rows(List.of())
                        .devices(List.of())
                        .timestamp(System.currentTimeMillis())
                        .build();
            }

            Map<String, List<DataPoint>> pointsByDevice = new LinkedHashMap<>();
            List<CacheKey> allCacheKeys = new ArrayList<>();
            for (String deviceId : deviceIds) {
                List<DataPoint> dataPoints = safeDataPoints(configManager.getDataPoints(deviceId));
                pointsByDevice.put(deviceId, dataPoints);
                if (!dataPoints.isEmpty()) {
                    allCacheKeys.addAll(buildCacheKeys(deviceId, dataPoints));
                }
            }

            Map<CacheKey, Object> values = allCacheKeys.isEmpty()
                    ? Collections.emptyMap()
                    : cacheManager.getAll(allCacheKeys);
            List<CompactRealtimePointPayload> rows = new ArrayList<>();
            List<CompactRealtimeDeviceStatus> devices = new ArrayList<>();
            for (Map.Entry<String, List<DataPoint>> entry : pointsByDevice.entrySet()) {
                String deviceId = entry.getKey();
                List<DataPoint> dataPoints = entry.getValue();
                if (dataPoints.isEmpty()) {
                    devices.add(compactDeviceStatus(deviceId, STATUS_ERROR, DEVICE_POINTS_MISSING_MESSAGE, 0));
                    continue;
                }
                List<CompactRealtimePointPayload> deviceRows = buildCompactRows(deviceId, dataPoints, values);
                rows.addAll(deviceRows);
                devices.add(compactDeviceStatus(deviceId, STATUS_SUCCESS, null, deviceRows.size()));
            }

            return CompactAllDeviceRealtimeDataResponse.builder()
                    .status(STATUS_SUCCESS)
                    .deviceCount(devices.size())
                    .dataCount(rows.size())
                    .rows(rows)
                    .devices(devices)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("查询全部设备实时紧凑数据失败", exception);
            return CompactAllDeviceRealtimeDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + exception.getMessage())
                    .deviceCount(0)
                    .dataCount(0)
                    .rows(List.of())
                    .devices(List.of())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询所有设备的基本摘要。
     *
     * @return 设备摘要列表
     */
    public DeviceListResponse getAllDevices() {
        try {
            List<String> deviceIds = configManager.getAllDeviceIds();
            List<DeviceBriefResponse> devices = new ArrayList<>();
            for (String deviceId : deviceIds) {
                List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
                devices.add(DeviceBriefResponse.builder()
                        .deviceId(deviceId)
                        .pointCount(dataPoints.size())
                        .build());
            }
            return DeviceListResponse.builder()
                    .status(STATUS_SUCCESS)
                    .deviceCount(devices.size())
                    .devices(devices)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("查询所有设备失败", exception);
            return DeviceListResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + exception.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询指定设备的点位配置摘要。
     *
     * @param deviceId 本地设备唯一标识
     * @return 点位配置摘要列表
     */
    public DevicePointListResponse getDevicePoints(String deviceId) {
        try {
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            List<PointRealtimePayload> pointsInfo = dataPoints.stream()
                    .map(point -> buildPointPayload(point, null))
                    .toList();
            return DevicePointListResponse.builder()
                    .status(STATUS_SUCCESS)
                    .deviceId(deviceId)
                    .pointCount(pointsInfo.size())
                    .points(pointsInfo)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("查询设备点位失败，设备={}", deviceId, exception);
            return DevicePointListResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + exception.getMessage())
                    .deviceId(deviceId)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 构建设备点位缓存键列表。
     *
     * @param deviceId 本地设备唯一标识
     * @param dataPoints 点位配置列表
     * @return 缓存键列表
     */
    private List<CacheKey> buildCacheKeys(String deviceId, List<DataPoint> dataPoints) {
        List<CacheKey> cacheKeys = new ArrayList<>();
        for (DataPoint point : dataPoints) {
            cacheKeys.add(CacheKey.dataKey(deviceId, point.getPointId()));
        }
        return cacheKeys;
    }

    /**
     * 将可能为 null 的点位列表归一化为空列表。
     *
     * @param dataPoints 点位配置列表
     * @return 非 null 点位配置列表
     */
    private List<DataPoint> safeDataPoints(List<DataPoint> dataPoints) {
        return dataPoints == null ? List.of() : dataPoints;
    }

    /**
     * 构建实时表格紧凑点位行。
     *
     * @param deviceId 本地设备唯一标识
     * @param dataPoints 点位配置列表
     * @param values 缓存值映射
     * @return 实时表格紧凑点位行
     */
    private List<CompactRealtimePointPayload> buildCompactRows(String deviceId,
                                                               List<DataPoint> dataPoints,
                                                               Map<CacheKey, Object> values) {
        List<CompactRealtimePointPayload> rows = new ArrayList<>();
        for (DataPoint point : dataPoints) {
            CacheKey cacheKey = CacheKey.dataKey(deviceId, point.getPointId());
            rows.add(CompactRealtimePointPayload.from(point, values.get(cacheKey)));
        }
        return rows;
    }

    /**
     * 构建紧凑聚合中的轻量设备状态。
     *
     * @param deviceId 本地设备唯一标识
     * @param status 设备级业务状态
     * @param message 设备级业务提示
     * @param dataCount 当前设备点位行数
     * @return 轻量设备状态
     */
    private CompactRealtimeDeviceStatus compactDeviceStatus(String deviceId,
                                                            String status,
                                                            String message,
                                                            int dataCount) {
        return CompactRealtimeDeviceStatus.builder()
                .status(status)
                .message(message)
                .deviceId(deviceId)
                .dataCount(dataCount)
                .build();
    }

    /**
     * 构建设备点位实时数据映射。
     *
     * @param deviceId 本地设备唯一标识
     * @param dataPoints 点位配置列表
     * @param values 缓存值映射
     * @return 点位实时数据映射
     */
    private Map<String, PointRealtimePayload> buildPointDataMap(String deviceId,
                                                                List<DataPoint> dataPoints,
                                                                Map<CacheKey, Object> values) {
        Map<String, PointRealtimePayload> dataMap = new LinkedHashMap<>();
        for (DataPoint point : dataPoints) {
            CacheKey cacheKey = CacheKey.dataKey(deviceId, point.getPointId());
            dataMap.put(point.getPointId(), buildPointPayload(point, values.get(cacheKey)));
        }
        return dataMap;
    }

    /**
     * 构建全设备聚合场景下的单设备实时响应。
     *
     * @param deviceId 本地设备唯一标识
     * @param dataPoints 点位配置列表
     * @param values 聚合缓存值映射
     * @return 单设备实时响应
     */
    private DeviceRealtimeDataResponse buildAggregateDeviceRealtimeDataResponse(String deviceId,
                                                                                List<DataPoint> dataPoints,
                                                                                Map<CacheKey, Object> values) {
        if (dataPoints == null || dataPoints.isEmpty()) {
            return DeviceRealtimeDataResponse.builder()
                    .status(STATUS_ERROR)
                    .deviceId(deviceId)
                    .message(DEVICE_POINTS_MISSING_MESSAGE)
                    .dataCount(0)
                    .data(Collections.emptyMap())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
        Map<String, PointRealtimePayload> dataMap = buildPointDataMap(deviceId, dataPoints, values);
        return DeviceRealtimeDataResponse.builder()
                .status(STATUS_SUCCESS)
                .deviceId(deviceId)
                .dataCount(dataMap.size())
                .data(dataMap)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 构建点位实时负载。
     *
     * @param point 点位配置
     * @param cachedValue 缓存值
     * @return 点位实时负载
     */
    private PointRealtimePayload buildPointPayload(DataPoint point, Object cachedValue) {
        PointRuntimeStateSnapshot runtimeState = pointRuntimeStateService.snapshot(point.getDeviceId(), point);
        PointRealtimePayload payload = PointRealtimePayload.fromPoint(point, runtimeState);
        payload.applyCachedValue(cachedValue);
        return payload;
    }

    /**
     * 构建单点查询失败响应。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointId 稳定点位唯一标识
     * @param message 失败原因
     * @return 单点查询失败响应
     */
    private PointRealtimeResponse pointError(String deviceId, String pointId, String message) {
        return PointRealtimeResponse.builder()
                .status(STATUS_ERROR)
                .message(message)
                .deviceId(deviceId)
                .pointId(pointId)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
