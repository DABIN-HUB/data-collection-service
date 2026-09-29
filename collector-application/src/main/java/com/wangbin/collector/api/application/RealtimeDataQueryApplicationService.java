package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.AllDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.CompactAllDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.CompactDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.CompactRealtimeDeviceStatus;
import com.wangbin.collector.api.controller.dto.CompactRealtimeDeltaResponse;
import com.wangbin.collector.api.controller.dto.CompactRealtimePointPayload;
import com.wangbin.collector.api.controller.dto.DeviceBriefResponse;
import com.wangbin.collector.api.controller.dto.DeviceListResponse;
import com.wangbin.collector.api.controller.dto.DevicePointListResponse;
import com.wangbin.collector.api.controller.dto.DeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.PointRealtimePayload;
import com.wangbin.collector.api.controller.dto.PointRealtimeResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.enums.DataQuality;
import com.wangbin.collector.core.cache.manager.MultiLevelCacheManager;
import com.wangbin.collector.core.cache.model.CacheKey;
import com.wangbin.collector.core.cache.realtime.RealtimeChangeTracker;
import com.wangbin.collector.core.collector.CollectionService;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimePhase;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeSnapshot;
import com.wangbin.collector.core.collector.runtime.DeviceRuntimeState;
import com.wangbin.collector.core.collector.runtime.PointAcquisitionSnapshot;
import com.wangbin.collector.core.collector.runtime.RuntimeStateCoordinator;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int MAX_DELTA_ROWS = 20_000;

    private final MultiLevelCacheManager cacheManager;
    private final ConfigManager configManager;
    private final PointRuntimeStateService pointRuntimeStateService;
    private final RealtimeChangeTracker realtimeChangeTracker;
    private final CollectionService collectionService;
    private final RuntimeStateCoordinator runtimeStateCoordinator;
    private final Map<String, HealthState> healthStates = new ConcurrentHashMap<>();

    /**
     * 创建实时缓存数据查询应用服务。
     */
    public RealtimeDataQueryApplicationService(
            @Qualifier("multiLevelCacheManager") MultiLevelCacheManager cacheManager,
            ConfigManager configManager,
            PointRuntimeStateService pointRuntimeStateService,
            RealtimeChangeTracker realtimeChangeTracker,
            CollectionService collectionService,
            RuntimeStateCoordinator runtimeStateCoordinator) {
        this.cacheManager = cacheManager;
        this.configManager = configManager;
        this.pointRuntimeStateService = pointRuntimeStateService;
        this.realtimeChangeTracker = realtimeChangeTracker;
        this.collectionService = collectionService;
        this.runtimeStateCoordinator = runtimeStateCoordinator;
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
            overlay(payload, observeHealth(deviceId, realtimeChangeTracker.currentRevision()));
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
            DeviceRuntimeState health = observeHealth(deviceId, realtimeChangeTracker.currentRevision());
            dataMap.values().forEach(payload -> overlay(payload, health));

            return DeviceRealtimeDataResponse.builder()
                    .status(STATUS_SUCCESS)
                    .deviceId(deviceId)
                    .dataCount(dataMap.size())
                    .data(dataMap)
                    .deviceHealth(health.health().name())
                    .transportState(health.transport().name())
                    .protocolState(health.protocol().name())
                    .acquisitionState(health.acquisition().name())
                    .lastAttemptAt(lastAttemptAt(health))
                    .lastSuccessAt(health.lastValueAt())
                    .lastValueAt(health.lastValueAt())
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
                if (response.getData() != null && !response.getData().isEmpty()) {
                    DeviceRuntimeState health = observeHealth(entry.getKey(), realtimeChangeTracker.currentRevision());
                    response.getData().values().forEach(payload -> overlay(payload, health));
                    response.setDeviceHealth(health.health().name());
                    response.setTransportState(health.transport().name());
                    response.setProtocolState(health.protocol().name());
                    response.setAcquisitionState(health.acquisition().name());
                    response.setLastAttemptAt(lastAttemptAt(health));
                    response.setLastSuccessAt(health.lastValueAt());
                    response.setLastValueAt(health.lastValueAt());
                }
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
            RealtimeChangeTracker.SnapshotCursor cursor = realtimeChangeTracker.capture();
            List<DataPoint> dataPoints = safeDataPoints(configManager.getDataPoints(deviceId));
            if (dataPoints.isEmpty()) {
                return CompactDeviceRealtimeDataResponse.builder()
                        .status(STATUS_ERROR)
                        .message(DEVICE_POINTS_MISSING_MESSAGE)
                        .snapshotId(cursor.snapshotId())
                        .configEpoch(cursor.configEpoch())
                        .revision(cursor.revision())
                        .deviceId(deviceId)
                        .dataCount(0)
                        .rows(List.of())
                        .timestamp(System.currentTimeMillis())
                        .build();
            }

            Map<CacheKey, Object> values = cacheManager.getAll(buildCacheKeys(deviceId, dataPoints));
            List<CompactRealtimePointPayload> rows = buildCompactRows(deviceId, dataPoints, values);
            DeviceRuntimeState health = observeHealth(deviceId, cursor.revision());
            rows.forEach(row -> overlay(row, health));
            return CompactDeviceRealtimeDataResponse.builder()
                    .status(STATUS_SUCCESS)
                    .snapshotId(cursor.snapshotId())
                    .configEpoch(cursor.configEpoch())
                    .revision(cursor.revision())
                    .deviceId(deviceId)
                    .dataCount(rows.size())
                    .rows(rows)
                    .deviceHealth(health.health().name())
                    .transportState(health.transport().name())
                    .protocolState(health.protocol().name())
                    .acquisitionState(health.acquisition().name())
                    .lastAttemptAt(lastAttemptAt(health))
                    .lastSuccessAt(health.lastValueAt())
                    .lastValueAt(health.lastValueAt())
                    .timestamp(System.currentTimeMillis())
                    .build();
        } catch (Exception exception) {
            log.error("查询设备实时紧凑数据失败，设备={}", deviceId, exception);
            return CompactDeviceRealtimeDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + exception.getMessage())
                    .snapshotId(realtimeChangeTracker.snapshotId())
                    .configEpoch(realtimeChangeTracker.configEpoch())
                    .revision(realtimeChangeTracker.currentRevision())
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
            RealtimeChangeTracker.SnapshotCursor cursor = realtimeChangeTracker.capture();
            List<String> deviceIds = configManager.getAllDeviceIds();
            if (deviceIds.isEmpty()) {
                return CompactAllDeviceRealtimeDataResponse.builder()
                        .status(STATUS_SUCCESS)
                        .snapshotId(cursor.snapshotId())
                        .configEpoch(cursor.configEpoch())
                        .revision(cursor.revision())
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
                DeviceRuntimeState health = observeHealth(deviceId, cursor.revision());
                deviceRows.forEach(row -> overlay(row, health));
                rows.addAll(deviceRows);
                CompactRealtimeDeviceStatus deviceStatus = compactDeviceStatus(deviceId, STATUS_SUCCESS, null, deviceRows.size());
                deviceStatus.setDeviceHealth(health.health().name());
                deviceStatus.setTransportState(health.transport().name());
                deviceStatus.setProtocolState(health.protocol().name());
                deviceStatus.setAcquisitionState(health.acquisition().name());
                devices.add(deviceStatus);
            }

            return CompactAllDeviceRealtimeDataResponse.builder()
                    .status(STATUS_SUCCESS)
                    .snapshotId(cursor.snapshotId())
                    .configEpoch(cursor.configEpoch())
                    .revision(cursor.revision())
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
                    .snapshotId(realtimeChangeTracker.snapshotId())
                    .configEpoch(realtimeChangeTracker.configEpoch())
                    .revision(realtimeChangeTracker.currentRevision())
                    .deviceCount(0)
                    .dataCount(0)
                    .rows(List.of())
                    .devices(List.of())
                    .timestamp(System.currentTimeMillis())
                    .build();
        }
    }

    /**
     * 查询全部设备的实时表格紧凑增量。
     *
     * @param snapshotId 客户端快照标识
     * @param configEpoch 客户端配置纪元
     * @param sinceRevision 客户端已应用修订号
     * @return 全设备实时表格紧凑增量
     */
    public CompactRealtimeDeltaResponse getCompactAllRealtimeDelta(String snapshotId,
                                                                    long configEpoch,
                                                                    long sinceRevision) {
        return getCompactRealtimeDelta("all", null, snapshotId, configEpoch, sinceRevision);
    }

    /**
     * 查询指定设备的实时表格紧凑增量。
     *
     * @param deviceId 本地设备唯一标识
     * @param snapshotId 客户端快照标识
     * @param configEpoch 客户端配置纪元
     * @param sinceRevision 客户端已应用修订号
     * @return 单设备实时表格紧凑增量
     */
    public CompactRealtimeDeltaResponse getCompactDeviceRealtimeDelta(String deviceId,
                                                                       String snapshotId,
                                                                       long configEpoch,
                                                                       long sinceRevision) {
        return getCompactRealtimeDelta("device", deviceId, snapshotId, configEpoch, sinceRevision);
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

    private CompactRealtimeDeltaResponse getCompactRealtimeDelta(String scope,
                                                                 String deviceId,
                                                                 String snapshotId,
                                                                 long configEpoch,
                                                                 long sinceRevision) {
        RealtimeChangeTracker.SnapshotCursor boundary = realtimeChangeTracker.capture();
        RealtimeChangeTracker.CursorValidation validation = realtimeChangeTracker.validateCursor(
                boundary,
                snapshotId,
                configEpoch,
                sinceRevision);
        if (!validation.valid()) {
            return compactDeltaReset(scope, deviceId, sinceRevision, boundary, validation.resetReason());
        }

        List<RealtimeChangeTracker.PointKey> valueChangedKeys = realtimeChangeTracker.findChangedKeys(
                sinceRevision,
                boundary.revision(),
                deviceId,
                MAX_DELTA_ROWS + 1);
        if (valueChangedKeys.size() > MAX_DELTA_ROWS) {
            return compactDeltaReset(scope, deviceId, sinceRevision, boundary, "DELTA_TOO_LARGE");
        }
        // 健康变化不改变缓存修订号，按设备补发点位；同一修订号下重复补发可服务独立客户端。
        Set<RealtimeChangeTracker.PointKey> changed = new LinkedHashSet<>(valueChangedKeys);
        Map<String, DeviceRuntimeState> healthByDevice = new LinkedHashMap<>();
        List<String> healthDeviceIds = deviceId == null ? configManager.getAllDeviceIds() : List.of(deviceId);
        if (healthDeviceIds != null) {
            for (String healthDeviceId : healthDeviceIds) {
                DeviceRuntimeState health = observeHealth(healthDeviceId, boundary.revision());
                healthByDevice.put(healthDeviceId, health);
                HealthState state = healthStates.get(healthDeviceId);
                if (state != null && state.changedAtRevision() >= sinceRevision) {
                    for (DataPoint point : safeDataPoints(configManager.getDataPoints(healthDeviceId))) {
                        changed.add(new RealtimeChangeTracker.PointKey(healthDeviceId, point.getPointId()));
                        if (changed.size() > MAX_DELTA_ROWS) {
                            return compactDeltaReset(scope, deviceId, sinceRevision, boundary, "DELTA_TOO_LARGE");
                        }
                    }
                }
            }
        }
        List<RealtimeChangeTracker.PointKey> changedKeys = List.copyOf(changed);
        if (changedKeys.isEmpty()) {
            CompactRealtimeDeltaResponse boundaryReset = resetIfBoundaryInvalid(scope, deviceId, sinceRevision, boundary);
            if (boundaryReset != null) {
                return boundaryReset;
            }
            return compactDeltaSuccess(scope, deviceId, sinceRevision, boundary, List.of());
        }

        CompactRealtimeDeltaResponse boundaryReset = resetIfBoundaryInvalid(scope, deviceId, sinceRevision, boundary);
        if (boundaryReset != null) {
            return boundaryReset;
        }

        Map<String, Map<String, DataPoint>> pointIndex = buildPointIndex(loadAffectedDevicePoints(changedKeys));
        List<CacheKey> cacheKeys = new ArrayList<>();
        List<DataPoint> orderedPoints = new ArrayList<>();
        List<String> orderedDeviceIds = new ArrayList<>();
        for (RealtimeChangeTracker.PointKey key : changedKeys) {
            DataPoint point = pointIndex.getOrDefault(key.deviceId(), Map.of()).get(key.pointId());
            if (point == null) {
                boundaryReset = resetIfBoundaryInvalid(scope, deviceId, sinceRevision, boundary);
                if (boundaryReset != null) {
                    return boundaryReset;
                }
                return compactDeltaReset(scope, deviceId, sinceRevision, boundary, "ROW_IDENTITY_MISMATCH");
            }
            cacheKeys.add(CacheKey.dataKey(key.deviceId(), key.pointId()));
            orderedPoints.add(point);
            orderedDeviceIds.add(key.deviceId());
        }

        Map<CacheKey, Object> values = cacheManager.getAll(cacheKeys);
        List<CompactRealtimePointPayload> rows = new ArrayList<>();
        for (int index = 0; index < orderedPoints.size(); index += 1) {
            String rowDeviceId = orderedDeviceIds.get(index);
            DataPoint point = orderedPoints.get(index);
            CacheKey cacheKey = CacheKey.dataKey(rowDeviceId, point.getPointId());
            CompactRealtimePointPayload row = CompactRealtimePointPayload.from(point, rowDeviceId, values.get(cacheKey));
            DeviceRuntimeState health = healthByDevice.computeIfAbsent(rowDeviceId,
                    id -> observeHealth(id, boundary.revision()));
            overlay(row, health);
            rows.add(row);
        }
        boundaryReset = resetIfBoundaryInvalid(scope, deviceId, sinceRevision, boundary);
        if (boundaryReset != null) {
            return boundaryReset;
        }
        return compactDeltaSuccess(scope, deviceId, sinceRevision, boundary, rows);
    }

    private Map<String, List<DataPoint>> loadAffectedDevicePoints(List<RealtimeChangeTracker.PointKey> changedKeys) {
        Map<String, List<DataPoint>> pointsByDevice = new LinkedHashMap<>();
        for (RealtimeChangeTracker.PointKey key : changedKeys) {
            pointsByDevice.computeIfAbsent(key.deviceId(), id -> safeDataPoints(configManager.getDataPoints(id)));
        }
        return pointsByDevice;
    }

    private Map<String, Map<String, DataPoint>> buildPointIndex(Map<String, List<DataPoint>> pointsByDevice) {
        Map<String, Map<String, DataPoint>> index = new LinkedHashMap<>();
        for (Map.Entry<String, List<DataPoint>> entry : pointsByDevice.entrySet()) {
            Map<String, DataPoint> pointsById = new LinkedHashMap<>();
            for (DataPoint point : entry.getValue()) {
                pointsById.put(point.getPointId(), point);
            }
            index.put(entry.getKey(), pointsById);
        }
        return index;
    }

    private CompactRealtimeDeltaResponse compactDeltaSuccess(String scope,
                                                             String deviceId,
                                                             long fromRevision,
                                                             RealtimeChangeTracker.SnapshotCursor boundary,
                                                             List<CompactRealtimePointPayload> rows) {
        return CompactRealtimeDeltaResponse.builder()
                .status(STATUS_SUCCESS)
                .scope(scope)
                .deviceId(deviceId)
                .resetRequired(false)
                .snapshotId(boundary.snapshotId())
                .configEpoch(boundary.configEpoch())
                .fromRevision(fromRevision)
                .revision(boundary.revision())
                .changedCount(rows.size())
                .rows(rows)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private CompactRealtimeDeltaResponse compactDeltaReset(String scope,
                                                           String deviceId,
                                                           long fromRevision,
                                                           RealtimeChangeTracker.SnapshotCursor cursor,
                                                           String resetReason) {
        return CompactRealtimeDeltaResponse.builder()
                .status(STATUS_SUCCESS)
                .scope(scope)
                .deviceId(deviceId)
                .resetRequired(true)
                .resetReason(resetReason)
                .snapshotId(cursor.snapshotId())
                .configEpoch(cursor.configEpoch())
                .fromRevision(fromRevision)
                .revision(cursor.revision())
                .changedCount(0)
                .rows(List.of())
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private CompactRealtimeDeltaResponse resetIfBoundaryInvalid(String scope,
                                                                String deviceId,
                                                                long fromRevision,
                                                                RealtimeChangeTracker.SnapshotCursor boundary) {
        RealtimeChangeTracker.BoundaryValidation boundaryValidation = realtimeChangeTracker.validateBoundary(boundary);
        if (boundaryValidation.valid()) {
            return null;
        }
        return compactDeltaReset(scope, deviceId, fromRevision, realtimeChangeTracker.capture(), boundaryValidation.resetReason());
    }

    private DeviceRuntimeState observeHealth(String deviceId, long revision) {
        DeviceRuntimeState state = runtimeStateCoordinator.snapshot(deviceId);
        String signature = state.health() + ":" + state.transport() + ":" + state.protocol()
                + ":" + state.acquisition() + ":" + state.points().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue().outcome() + "/" + entry.getValue().failureReason())
                .toList();
        healthStates.compute(deviceId, (ignored, previous) -> {
            if (previous == null) {
                return new HealthState(signature, -1L);
            }
            return previous.signature().equals(signature)
                    ? previous : new HealthState(signature, revision);
        });
        return state;
    }

    private void overlay(PointRealtimePayload payload, DeviceRuntimeState state) {
        PointPresentation presentation = present(state, payload.getPointId(), payload.getValue());
        payload.setLastAttemptAt(presentation.lastAttemptAt());
        payload.setLastValueAt(presentation.lastValueAt());
        if (presentation.status() == null) return;
        payload.setRealtimeStatus(presentation.status());
        payload.setFailureType(presentation.failureType());
        payload.setErrorCode(presentation.failureType());
        payload.setErrorMessage(presentation.message());
        payload.setStale(presentation.stale());
        if (presentation.quality() != null) {
            DataQuality quality = presentation.quality();
            payload.setQuality(quality.getCode());
            payload.setQualityLevel(quality.getQualityLevel());
            payload.setQualityDescription(quality.getDescription());
            payload.setQualityAvailable(true);
            payload.setQualityAcceptable(false);
        }
    }

    private void overlay(CompactRealtimePointPayload payload, DeviceRuntimeState state) {
        PointPresentation presentation = present(state, payload.getPointId(), payload.getValue());
        payload.setLastAttemptAt(presentation.lastAttemptAt());
        payload.setLastValueAt(presentation.lastValueAt());
        if (presentation.status() == null) return;
        payload.setRealtimeStatus(presentation.status());
        payload.setFailureType(presentation.failureType());
        payload.setErrorCode(presentation.failureType());
        payload.setErrorMessage(presentation.message());
        payload.setStale(presentation.stale());
        if (presentation.quality() != null) {
            DataQuality quality = presentation.quality();
            payload.setQuality(quality.getCode());
            payload.setQualityLevel(quality.getQualityLevel());
            payload.setQualityDescription(quality.getDescription());
            payload.setQualityAvailable(true);
            payload.setQualityAcceptable(false);
        }
    }

    private long lastAttemptAt(DeviceRuntimeState state) {
        return state.points().values().stream().mapToLong(PointAcquisitionSnapshot::lastAttemptAt)
                .max().orElse(0L);
    }

    private PointPresentation present(DeviceRuntimeState state, String pointId, Object value) {
        PointAcquisitionSnapshot fact = state.points().get(pointId);
        long attemptedAt = fact == null ? 0L : fact.lastAttemptAt();
        long valueAt = fact == null ? 0L : fact.lastValueAt();
        boolean disconnected = state.runtime().running() && !state.runtime().connected();
        if (fact != null && fact.outcome() == PointAcquisitionSnapshot.Outcome.OBSERVED
                && !disconnected && value != null) {
            return new PointPresentation(null, null, null, false, null, attemptedAt, valueAt);
        }
        if (fact != null && fact.outcome() == PointAcquisitionSnapshot.Outcome.FAILED) {
            String failure = classifyFailure(fact.failureReason());
            DataQuality quality = "CONFIG_ERROR".equals(failure) ? DataQuality.CONFIG_ERROR
                    : "COMM_ERROR".equals(failure) ? DataQuality.DEVICE_ERROR : DataQuality.BAD;
            return new PointPresentation(value != null && !"CONFIG_ERROR".equals(failure) ? "STALE" : failure, failure,
                    fact.errorMessage() != null ? fact.errorMessage() : fact.failureReason(),
                    value != null, quality, attemptedAt, valueAt);
        }
        if (value != null || (fact != null && fact.outcome() == PointAcquisitionSnapshot.Outcome.STALE)) {
            return new PointPresentation("STALE", "STALE", disconnected ? "设备连接已断开" : "当前运行代次没有新鲜值",
                    true, DataQuality.UNCERTAIN, attemptedAt, valueAt);
        }
        if (attemptedAt > 0L || (state.firstValueDeadlineAt() > 0L
                && state.generatedAt() >= state.firstValueDeadlineAt())) {
            return new PointPresentation("NO_VALUE", "NO_VALUE", "采集后未收到该点有效值",
                    false, DataQuality.BAD, attemptedAt, valueAt);
        }
        if (disconnected) {
            return new PointPresentation("COMM_ERROR", "COMM_ERROR", "设备连接已断开",
                    false, DataQuality.DEVICE_ERROR, attemptedAt, valueAt);
        }
        return new PointPresentation("WAITING", null, null, false, null, attemptedAt, valueAt);
    }

    private String classifyFailure(String reason) {
        if (reason == null) return "BAD";
        String normalized = reason.toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("CONFIG") || normalized.contains("ADDRESS")) return "CONFIG_ERROR";
        if (normalized.contains("MAPPING")) return "MAPPING_ERROR";
        if (normalized.contains("DECODE") || normalized.contains("PROCESS")) return "DECODE_ERROR";
        if (normalized.contains("NO_VALUE")) return "NO_VALUE";
        if (normalized.contains("TIMEOUT") || normalized.contains("COMM") || normalized.contains("CONNECT")
                || normalized.contains("HTTP_404") || normalized.contains("EXCEPTION")) return "COMM_ERROR";
        return "BAD";
    }

    private record PointPresentation(String status, String failureType, String message,
                                     boolean stale, DataQuality quality, long lastAttemptAt, long lastValueAt) {
    }

    private record HealthState(String signature, long changedAtRevision) {
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
            rows.add(CompactRealtimePointPayload.from(point, deviceId, values.get(cacheKey)));
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
