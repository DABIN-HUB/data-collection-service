package com.wangbin.collector.api.controller;

import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import com.wangbin.collector.storage.service.HistoryDataService;
import com.wangbin.collector.storage.service.AlarmHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据查询控制器
 * 提供API接口查询采集的数据
 */
@Slf4j
@RestController
@RequestMapping("/api/data")
public class DataController {

    @Autowired
    @Qualifier("multiLevelCacheManager")
    private MultiLevelCacheManager cacheManager;

    @Autowired
    private ConfigManager configManager;
    
    @Autowired(required = false)
    private HistoryDataService historyDataService;

    @Autowired(required = false)
    private AlarmHistoryService alarmHistoryService;

    @Autowired
    private PointRuntimeStateService pointRuntimeStateService;

    /**
     * 查询指定设备的指定数据点的实时值
     */
    @GetMapping("/device/{deviceId}/point/{pointId}")
    public Map<String, Object> getPointData(
            @PathVariable String deviceId,
            @PathVariable String pointId) {
        
        Map<String, Object> result = new HashMap<>();
        try {
            // 构建缓存键
            CacheKey cacheKey = CacheKey.dataKey(deviceId, pointId);
            
            // 从缓存中获取数据
            Object value = cacheManager.get(cacheKey);
            
            // 获取数据点信息
            DataPoint dataPoint = configManager.getDataPointByPointId(deviceId, pointId);
            
            if (dataPoint != null) {
                putPointConfigPayload(result, dataPoint);
                result.put("deviceId", deviceId);
                enrichWithCachedPayload(result, value);
                result.put("timestamp", System.currentTimeMillis());
                result.put("status", "success");
            } else {
                result.put("status", "error");
                result.put("message", "数据点不存在");
            }
        } catch (Exception e) {
            log.error("查询数据失败: deviceId={}, pointId={}", deviceId, pointId, e);
            result.put("status", "error");
            result.put("message", "查询失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 查询指定设备的所有数据点的实时值
     */
    @GetMapping("/device/{deviceId}")
    public Map<String, Object> getDeviceData(
            @PathVariable String deviceId,
            @RequestParam(required = false) List<String> pointIds) {
        
        Map<String, Object> result = new HashMap<>();
        try {
            // 获取设备的所有数据点
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            
            if (dataPoints.isEmpty()) {
                result.put("status", "error");
                result.put("message", "设备不存在或无数据点");
                return result;
            }
            
            // 如果指定了pointIds，只查询这些数据点
            if (pointIds != null && !pointIds.isEmpty()) {
                dataPoints = dataPoints.stream()
                        .filter(point -> pointIds.contains(point.getPointId()))
                        .toList();
            }
            
            // 批量查询数据
            List<CacheKey> cacheKeys = new ArrayList<>();
            for (DataPoint point : dataPoints) {
                cacheKeys.add(CacheKey.dataKey(deviceId, point.getPointId()));
            }
            
            Map<CacheKey, Object> values = cacheManager.getAll(cacheKeys);
            
            // 组织结果
            Map<String, Map<String, Object>> dataMap = new HashMap<>();
            for (DataPoint point : dataPoints) {
                CacheKey cacheKey = CacheKey.dataKey(deviceId, point.getPointId());
                Object value = values.get(cacheKey);
                
                Map<String, Object> pointData = new HashMap<>();
                putPointConfigPayload(pointData, point);
                enrichWithCachedPayload(pointData, value);
                
                dataMap.put(point.getPointId(), pointData);
            }
            
            result.put("deviceId", deviceId);
            result.put("dataCount", dataMap.size());
            result.put("data", dataMap);
            result.put("timestamp", System.currentTimeMillis());
            result.put("status", "success");
            
        } catch (Exception e) {
            log.error("查询设备数据失败: deviceId={}", deviceId, e);
            result.put("status", "error");
            result.put("message", "查询失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 查询所有设备的基本信息
     */
    @GetMapping("/devices")
    public Map<String, Object> getAllDevices() {
        
        Map<String, Object> result = new HashMap<>();
        try {
            // 获取所有设备ID
            List<String> deviceIds = configManager.getAllDeviceIds();
            
            List<Map<String, Object>> devices = new ArrayList<>();
            for (String deviceId : deviceIds) {
                Map<String, Object> deviceInfo = new HashMap<>();
                deviceInfo.put("deviceId", deviceId);
                
                // 获取设备数据点数量
                List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
                deviceInfo.put("pointCount", dataPoints.size());
                
                devices.add(deviceInfo);
            }
            
            result.put("deviceCount", devices.size());
            result.put("devices", devices);
            result.put("timestamp", System.currentTimeMillis());
            result.put("status", "success");
            
        } catch (Exception e) {
            log.error("查询所有设备失败", e);
            result.put("status", "error");
            result.put("message", "查询失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 查询指定设备的所有数据点配置
     */
    @GetMapping("/device/{deviceId}/points")
    public Map<String, Object> getDevicePoints(
            @PathVariable String deviceId) {
        
        Map<String, Object> result = new HashMap<>();
        try {
            // 获取设备的所有数据点
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            
            List<Map<String, Object>> pointsInfo = new ArrayList<>();
            for (DataPoint point : dataPoints) {
                Map<String, Object> pointInfo = new HashMap<>();
                putPointConfigPayload(pointInfo, point);
                
                pointsInfo.add(pointInfo);
            }
            
            result.put("deviceId", deviceId);
            result.put("pointCount", pointsInfo.size());
            result.put("points", pointsInfo);
            result.put("timestamp", System.currentTimeMillis());
            result.put("status", "success");
            
        } catch (Exception e) {
            log.error("查询设备数据点失败: deviceId={}", deviceId, e);
            result.put("status", "error");
            result.put("message", "查询失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 写入点位配置字段，保证配置接口和实时接口返回结构一致。
     */
    private void putPointConfigPayload(Map<String, Object> target, DataPoint point) {
        target.put("id", point.getId());
        target.put("unitId", point.getUnitId());
        target.put("commonAddress", point.getCommonAddress());
        target.put("pointId", point.getPointId());
        target.put("pointCode", point.getPointCode());
        target.put("pointName", point.getPointName());
        target.put("pointAlias", point.getPointAlias());
        target.put("deviceId", point.getDeviceId());
        target.put("deviceName", point.getDeviceName());
        target.put("groupId", point.getGroupId());
        target.put("address", point.getAddress());
        target.put("dataType", point.getDataType());
        target.put("readWrite", point.getReadWrite());
        target.put("scalingFactor", point.getScalingFactor());
        target.put("offset", point.getOffset());
        target.put("deadband", point.getDeadband());
        target.put("unit", point.getUnit());
        target.put("minValue", point.getMinValue());
        target.put("maxValue", point.getMaxValue());
        target.put("collectionMode", point.getCollectionMode());
        target.put("priority", point.getPriority());
        target.put("cacheEnabled", point.getCacheEnabled());
        target.put("cacheDuration", point.getCacheDuration());
        target.put("alarmEnabled", point.getAlarmEnabled());
        target.put("status", point.getStatus());
        target.put("createTime", point.getCreateTime());
        target.put("updateTime", point.getUpdateTime());
        target.put("precision", point.getPrecision());
        target.put("remark", point.getRemark());
        target.put("additionalConfig", point.getAdditionalConfig());
        target.put("baseCollectionInterval", point.getBaseCollectionInterval());
        PointRuntimeStateSnapshot runtimeState = pointRuntimeStateService.snapshot(point.getDeviceId(), point);
        target.put("currentCollectionInterval", runtimeState.currentCollectionInterval());
        target.put("minCollectionInterval", point.getMinCollectionInterval());
        target.put("maxCollectionInterval", point.getMaxCollectionInterval());
        target.put("pointChangeThreshold", point.getPointChangeThreshold());
        target.put("stableCount", runtimeState.stableCount());
        target.put("lastValue", runtimeState.lastValue());
        target.put("changeRate", runtimeState.changeRate());
        target.put("lastAdjustTime", runtimeState.lastAdjustTime());
    }

    /**
     * 将缓存中的ProcessResult或普通值展开为响应字段。
     */
    private void enrichWithCachedPayload(Map<String, Object> target, Object cachedValue) {
        if (cachedValue instanceof ProcessResult processResult) {
            Object finalValue = processResult.getFinalValue();
            Map<String, Object> metadata = processResult.getMetadata();
            Map<String, Object> metadataPayload = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            target.put("value", finalValue);
            target.put("rawValue", processResult.getRawValue());
            target.put("processedValue", processResult.getProcessedValue());
            target.put("hasCachedValue", true);
            target.put("quality", processResult.getQuality());
            target.put("qualityDescription", processResult.getQualityDescription());
            target.put("qualityLevel", processResult.getQualityLevel());
            target.put("qualityAcceptable", processResult.isQualityAcceptable());
            target.put("qualityAvailable", true);
            target.put("processMessage", processResult.getMessage());
            target.put("processSuccess", processResult.isSuccess());
            target.put("skipped", processResult.isSkipped());
            target.put("processorName", processResult.getProcessorName());
            target.put("processingTime", processResult.getProcessingTime());
            target.put("processingTimeAvailable", true);
            target.put("metadata", metadataPayload);
            target.put("lastUpdateTime", metadataPayload.get(ProcessResultMetadataKeys.COLLECT_TIME));
        } else {
            target.put("value", cachedValue);
            target.put("rawValue", cachedValue);
            target.put("hasCachedValue", cachedValue != null);
            target.put("qualityAvailable", false);
            target.put("processingTimeAvailable", false);
            target.put("metadata", new HashMap<>());
        }
    }


    @PostMapping("/device/{deviceId}/reset-adaptive")
    public Map<String, Object> resetAdaptiveConfig(@PathVariable String deviceId) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            for (DataPoint point : dataPoints) {
                pointRuntimeStateService.reset(deviceId, point);
            }
            result.put("code", 200);
            result.put("message", "重置成功");
        } catch (Exception e) {
            log.error("重置设备 {} 自适应配置失败", deviceId, e);
            result.put("code", 400);
            result.put("message", "重置失败");
        }
        return result;
    }

    @GetMapping("/history/device/{deviceId}/point/{pointId}")
    public Map<String, Object> getPointHistory(@PathVariable String deviceId,
                                               @PathVariable String pointId,
                                               @RequestParam(required = false) Long startTs,
                                               @RequestParam(required = false) Long endTs,
                                               @RequestParam(required = false) Integer limit) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (historyDataService == null || !historyDataService.isEnabled()) {
                result.put("status", "disabled");
                result.put("message", "TDengine历史存储未启用");
                result.put("deviceId", deviceId);
                result.put("pointId", pointId);
                return result;
            }
            List<Map<String, Object>> rows = historyDataService.queryPointHistory(deviceId, pointId, startTs, endTs, limit);
            result.put("status", "success");
            result.put("deviceId", deviceId);
            result.put("pointId", pointId);
            result.put("count", rows.size());
            result.put("data", rows);
            result.put("startTs", startTs);
            result.put("endTs", endTs);
            result.put("timestamp", System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            log.error("查询历史数据失败, deviceId={}, pointId={}", deviceId, pointId, e);
            result.put("status", "error");
            result.put("message", "查询失败: " + e.getMessage());
            return result;
        }
    }

    @GetMapping("/history/alarms")
    public Map<String, Object> getRecentAlarmHistory(@RequestParam(required = false) String deviceId,
                                                     @RequestParam(required = false) String pointId,
                                                     @RequestParam(required = false) String pointCode,
                                                     @RequestParam(required = false) String level,
                                                     @RequestParam(required = false) String ruleId,
                                                     @RequestParam(required = false) Long startTs,
                                                     @RequestParam(required = false) Long endTs,
                                                     @RequestParam(required = false) Integer limit) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (alarmHistoryService == null || !alarmHistoryService.isEnabled()) {
                result.put("status", "disabled");
                result.put("message", "TDengine 告警历史存储未启用");
                result.put("count", 0);
                result.put("data", List.of());
                return result;
            }
            List<Map<String, Object>> rows = alarmHistoryService.queryRecentAlarmHistory(
                    deviceId, pointId, pointCode, level, ruleId, startTs, endTs, limit);
            result.put("status", "success");
            result.put("deviceId", deviceId);
            result.put("pointId", pointId);
            result.put("pointCode", pointCode);
            result.put("level", level);
            result.put("ruleId", ruleId);
            result.put("count", rows.size());
            result.put("data", rows);
            result.put("startTs", startTs);
            result.put("endTs", endTs);
            result.put("timestamp", System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            log.error("query recent alarm history failed, deviceId={}, pointId={}, pointCode={}, level={}, ruleId={}",
                    deviceId, pointId, pointCode, level, ruleId, e);
            result.put("status", "error");
            result.put("message", "查询失败: " + e.getMessage());
            result.put("count", 0);
            result.put("data", List.of());
            return result;
        }
    }

    @GetMapping("/history/device/{deviceId}/alarms")
    public Map<String, Object> getAlarmHistory(@PathVariable String deviceId,
                                               @RequestParam(required = false) String pointId,
                                               @RequestParam(required = false) String pointCode,
                                               @RequestParam(required = false) String level,
                                               @RequestParam(required = false) String ruleId,
                                               @RequestParam(required = false) Long startTs,
                                               @RequestParam(required = false) Long endTs,
                                               @RequestParam(required = false) Integer limit) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (alarmHistoryService == null || !alarmHistoryService.isEnabled()) {
                result.put("status", "disabled");
                result.put("message", "TDengine alarm history storage disabled");
                result.put("deviceId", deviceId);
                return result;
            }
            List<Map<String, Object>> rows = alarmHistoryService.queryAlarmHistory(
                    deviceId, pointId, pointCode, level, ruleId, startTs, endTs, limit);
            result.put("status", "success");
            result.put("deviceId", deviceId);
            result.put("pointId", pointId);
            result.put("pointCode", pointCode);
            result.put("level", level);
            result.put("ruleId", ruleId);
            result.put("count", rows.size());
            result.put("data", rows);
            result.put("startTs", startTs);
            result.put("endTs", endTs);
            result.put("timestamp", System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            log.error("query alarm history failed, deviceId={}, pointId={}, pointCode={}, level={}, ruleId={}",
                    deviceId, pointId, pointCode, level, ruleId, e);
            result.put("status", "error");
            result.put("message", "query failed: " + e.getMessage());
            return result;
        }
    }
}
