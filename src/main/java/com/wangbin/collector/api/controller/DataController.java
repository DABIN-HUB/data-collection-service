package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.controller.dto.AdaptiveResetResponse;
import com.wangbin.collector.api.controller.dto.AlarmHistoryDataResponse;
import com.wangbin.collector.api.controller.dto.DeviceBriefResponse;
import com.wangbin.collector.api.controller.dto.DeviceListResponse;
import com.wangbin.collector.api.controller.dto.DevicePointListResponse;
import com.wangbin.collector.api.controller.dto.DeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.HistoryDataResponse;
import com.wangbin.collector.api.controller.dto.PointRealtimePayload;
import com.wangbin.collector.api.controller.dto.PointRealtimeResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.storage.service.AlarmHistoryService;
import com.wangbin.collector.storage.service.HistoryDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据查询控制器。
 *
 * <p>负责控制台实时数据、点位配置摘要和历史查询，不直接参与采集执行。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/data")
public class DataController {

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_ERROR = "error";
    private static final String STATUS_DISABLED = "disabled";

    private final MultiLevelCacheManager cacheManager;
    private final ConfigManager configManager;
    private final HistoryDataService historyDataService;
    private final AlarmHistoryService alarmHistoryService;
    private final PointRuntimeStateService pointRuntimeStateService;

    /**
     * 创建数据查询控制器。
     *
     * @param cacheManager 多级缓存管理器
     * @param configManager 配置管理器
     * @param historyDataServiceProvider 历史数据服务提供器
     * @param alarmHistoryServiceProvider 告警历史服务提供器
     * @param pointRuntimeStateService 点位运行状态服务
     */
    public DataController(@Qualifier("multiLevelCacheManager") MultiLevelCacheManager cacheManager,
                          ConfigManager configManager,
                          ObjectProvider<HistoryDataService> historyDataServiceProvider,
                          ObjectProvider<AlarmHistoryService> alarmHistoryServiceProvider,
                          PointRuntimeStateService pointRuntimeStateService) {
        this.cacheManager = cacheManager;
        this.configManager = configManager;
        this.historyDataService = historyDataServiceProvider.getIfAvailable();
        this.alarmHistoryService = alarmHistoryServiceProvider.getIfAvailable();
        this.pointRuntimeStateService = pointRuntimeStateService;
    }

    /**
     * 查询指定设备的指定点位实时数据。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointId 稳定点位唯一标识
     * @return 单点实时数据响应
     */
    @GetMapping("/device/{deviceId}/point/{pointId}")
    public PointRealtimeResponse getPointData(@PathVariable String deviceId,
                                               @PathVariable String pointId) {
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
        } catch (Exception e) {
            log.error("查询点位实时数据失败，设备={}，点位={}", deviceId, pointId, e);
            return pointError(deviceId, pointId, "查询失败: " + e.getMessage());
        }
    }

    /**
     * 查询指定设备的实时数据。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointIds 可选点位过滤条件
     * @return 设备实时数据响应
     */
    @GetMapping("/device/{deviceId}")
    public DeviceRealtimeDataResponse getDeviceData(@PathVariable String deviceId,
                                                     @RequestParam(required = false) List<String> pointIds) {
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

            List<CacheKey> cacheKeys = new ArrayList<>();
            for (DataPoint point : dataPoints) {
                cacheKeys.add(CacheKey.dataKey(deviceId, point.getPointId()));
            }

            Map<CacheKey, Object> values = cacheManager.getAll(cacheKeys);
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
        } catch (Exception e) {
            log.error("查询设备实时数据失败，设备={}", deviceId, e);
            return DeviceRealtimeDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + e.getMessage())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询所有设备的基本摘要。
     *
     * @return 设备摘要列表
     */
    @GetMapping("/devices")
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
        } catch (Exception e) {
            log.error("查询所有设备失败", e);
            return DeviceListResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + e.getMessage())
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
    @GetMapping("/device/{deviceId}/points")
    public DevicePointListResponse getDevicePoints(@PathVariable String deviceId) {
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
        } catch (Exception e) {
            log.error("查询设备点位失败，设备={}", deviceId, e);
            return DevicePointListResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + e.getMessage())
                    .deviceId(deviceId)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 重置设备下点位的自适应采集状态。
     *
     * @param deviceId 本地设备唯一标识
     * @return 重置结果
     */
    @PostMapping("/device/{deviceId}/reset-adaptive")
    public AdaptiveResetResponse resetAdaptiveConfig(@PathVariable String deviceId) {
        try {
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            for (DataPoint point : dataPoints) {
                pointRuntimeStateService.reset(deviceId, point);
            }
            return AdaptiveResetResponse.builder()
                    .code(200)
                    .message("重置成功")
                    .build();
        } catch (Exception e) {
            log.error("重置设备自适应配置失败，设备={}", deviceId, e);
            return AdaptiveResetResponse.builder()
                    .code(400)
                    .message("重置失败")
                    .build();
        }
    }

    /**
     * 查询点位历史数据。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointId 稳定点位唯一标识
     * @param startTs 查询开始时间戳
     * @param endTs 查询结束时间戳
     * @param limit 返回数量上限
     * @return 点位历史数据响应
     */
    @GetMapping("/history/device/{deviceId}/point/{pointId}")
    public HistoryDataResponse getPointHistory(@PathVariable String deviceId,
                                               @PathVariable String pointId,
                                               @RequestParam(required = false) Long startTs,
                                               @RequestParam(required = false) Long endTs,
                                               @RequestParam(required = false) Integer limit) {
        try {
            if (historyDataService == null || !historyDataService.isEnabled()) {
                return HistoryDataResponse.builder()
                        .status(STATUS_DISABLED)
                        .message("TDengine 历史存储未启用")
                        .deviceId(deviceId)
                        .pointId(pointId)
                        .build();
            }
            List<Map<String, Object>> rows = historyDataService.queryPointHistory(deviceId, pointId, startTs, endTs, limit);
            return HistoryDataResponse.builder()
                    .status(STATUS_SUCCESS)
                    .deviceId(deviceId)
                    .pointId(pointId)
                    .count(rows.size())
                    .data(rows)
                    .startTs(startTs)
                    .endTs(endTs)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("查询历史数据失败，设备={}，点位={}", deviceId, pointId, e);
            return HistoryDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + e.getMessage())
                    .deviceId(deviceId)
                    .pointId(pointId)
                    .build();
        }
    }

    /**
     * 查询最近告警历史。
     *
     * @return 告警历史响应
     */
    @GetMapping("/history/alarms")
    public AlarmHistoryDataResponse getRecentAlarmHistory(@RequestParam(required = false) String deviceId,
                                                          @RequestParam(required = false) String pointId,
                                                          @RequestParam(required = false) String pointCode,
                                                          @RequestParam(required = false) String level,
                                                          @RequestParam(required = false) String ruleId,
                                                          @RequestParam(required = false) Long startTs,
                                                          @RequestParam(required = false) Long endTs,
                                                          @RequestParam(required = false) Integer limit) {
        try {
            if (alarmHistoryService == null || !alarmHistoryService.isEnabled()) {
                return alarmDisabledResponse(deviceId, pointId, pointCode, level, ruleId, startTs, endTs);
            }
            List<Map<String, Object>> rows = alarmHistoryService.queryRecentAlarmHistory(
                    deviceId, pointId, pointCode, level, ruleId, startTs, endTs, limit);
            long total = alarmHistoryService.countRecentAlarmHistory(
                    deviceId, pointId, pointCode, level, ruleId, startTs, endTs);
            return alarmSuccessResponse(deviceId, pointId, pointCode, level, ruleId, startTs, endTs, rows, total);
        } catch (Exception e) {
            log.error("查询最近告警历史失败，设备={}，点位={}，点位编码={}，级别={}，规则={}",
                    deviceId, pointId, pointCode, level, ruleId, e);
            return AlarmHistoryDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + e.getMessage())
                    .count(0)
                    .data(Collections.emptyList())
                    .build();
        }
    }

    /**
     * 查询指定设备的告警历史。
     *
     * @return 告警历史响应
     */
    @GetMapping("/history/device/{deviceId}/alarms")
    public AlarmHistoryDataResponse getAlarmHistory(@PathVariable String deviceId,
                                                    @RequestParam(required = false) String pointId,
                                                    @RequestParam(required = false) String pointCode,
                                                    @RequestParam(required = false) String level,
                                                    @RequestParam(required = false) String ruleId,
                                                    @RequestParam(required = false) Long startTs,
                                                    @RequestParam(required = false) Long endTs,
                                                    @RequestParam(required = false) Integer limit) {
        try {
            if (alarmHistoryService == null || !alarmHistoryService.isEnabled()) {
                return AlarmHistoryDataResponse.builder()
                        .status(STATUS_DISABLED)
                        .message("TDengine 告警历史存储未启用")
                        .deviceId(deviceId)
                        .build();
            }
            List<Map<String, Object>> rows = alarmHistoryService.queryAlarmHistory(
                    deviceId, pointId, pointCode, level, ruleId, startTs, endTs, limit);
            return AlarmHistoryDataResponse.builder()
                    .status(STATUS_SUCCESS)
                    .deviceId(deviceId)
                    .pointId(pointId)
                    .pointCode(pointCode)
                    .level(level)
                    .ruleId(ruleId)
                    .count(rows.size())
                    .data(rows)
                    .startTs(startTs)
                    .endTs(endTs)
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception e) {
            log.error("查询告警历史失败，设备={}，点位={}，点位编码={}，级别={}，规则={}",
                    deviceId, pointId, pointCode, level, ruleId, e);
            return AlarmHistoryDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + e.getMessage())
                    .deviceId(deviceId)
                    .build();
        }
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

    /**
     * 构建告警历史未启用响应。
     */
    private AlarmHistoryDataResponse alarmDisabledResponse(String deviceId,
                                                           String pointId,
                                                           String pointCode,
                                                           String level,
                                                           String ruleId,
                                                           Long startTs,
                                                           Long endTs) {
        return AlarmHistoryDataResponse.builder()
                .status(STATUS_DISABLED)
                .message("TDengine 告警历史存储未启用")
                .deviceId(deviceId)
                .pointId(pointId)
                .pointCode(pointCode)
                .level(level)
                .ruleId(ruleId)
                .count(0)
                .data(Collections.emptyList())
                .startTs(startTs)
                .endTs(endTs)
                .build();
    }

    /**
     * 构建告警历史成功响应。
     */
    private AlarmHistoryDataResponse alarmSuccessResponse(String deviceId,
                                                          String pointId,
                                                          String pointCode,
                                                          String level,
                                                          String ruleId,
                                                          Long startTs,
                                                          Long endTs,
                                                          List<Map<String, Object>> rows,
                                                          long total) {
        return AlarmHistoryDataResponse.builder()
                .status(STATUS_SUCCESS)
                .deviceId(deviceId)
                .pointId(pointId)
                .pointCode(pointCode)
                .level(level)
                .ruleId(ruleId)
                .count(rows.size())
                .total(total)
                .data(rows)
                .startTs(startTs)
                .endTs(endTs)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}