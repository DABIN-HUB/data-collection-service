package com.wangbin.collector.api.application;

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
                        .message("设备不存在或无数据点")
                        .timestamp(System.currentTimeMillis())
                        .build();
            }

            if (pointIds != null && !pointIds.isEmpty()) {
                dataPoints = dataPoints.stream()
                        .filter(point -> pointIds.contains(point.getPointId()))
                        .toList();
            }

            Map<CacheKey, Object> values = cacheManager.getAll(buildCacheKeys(deviceId, dataPoints));
            Map<String, PointRealtimePayload> dataMap = new LinkedHashMap<>();
            for (DataPoint point : dataPoints) {
                CacheKey cacheKey = CacheKey.dataKey(deviceId, point.getPointId());
                dataMap.put(point.getPointId(), buildPointPayload(point, values.get(cacheKey)));
            }

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
