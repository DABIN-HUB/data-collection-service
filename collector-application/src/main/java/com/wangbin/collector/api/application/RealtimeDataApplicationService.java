package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.AdaptiveResetResponse;
import com.wangbin.collector.api.controller.dto.AllDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.AlarmHistoryDataResponse;
import com.wangbin.collector.api.controller.dto.DeviceListResponse;
import com.wangbin.collector.api.controller.dto.DevicePointListResponse;
import com.wangbin.collector.api.controller.dto.DeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.HistoryDataResponse;
import com.wangbin.collector.api.controller.dto.PointRealtimeResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 实时数据控制台应用服务。
 *
 * <p>作为 DataController 的稳定入口，委托实时查询和历史查询组件，并保留轻量自适应重置命令。</p>
 */
@Slf4j
@Service
public class RealtimeDataApplicationService {

    private final RealtimeDataQueryApplicationService realtimeDataQueryApplicationService;
    private final DataHistoryApplicationService dataHistoryApplicationService;
    private final ConfigManager configManager;
    private final PointRuntimeStateService pointRuntimeStateService;

    /**
     * 创建实时数据控制台应用服务。
     *
     * @param realtimeDataQueryApplicationService 实时数据查询应用服务
     * @param dataHistoryApplicationService 历史数据查询应用服务
     * @param configManager 配置管理器
     * @param pointRuntimeStateService 点位运行状态服务
     */
    public RealtimeDataApplicationService(
            RealtimeDataQueryApplicationService realtimeDataQueryApplicationService,
            DataHistoryApplicationService dataHistoryApplicationService,
            ConfigManager configManager,
            PointRuntimeStateService pointRuntimeStateService) {
        this.realtimeDataQueryApplicationService = realtimeDataQueryApplicationService;
        this.dataHistoryApplicationService = dataHistoryApplicationService;
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
        return realtimeDataQueryApplicationService.getPointData(deviceId, pointId);
    }

    /**
     * 查询指定设备的实时数据。
     *
     * @param deviceId 本地设备唯一标识
     * @param pointIds 可选点位过滤条件
     * @return 设备实时数据响应
     */
    public DeviceRealtimeDataResponse getDeviceData(String deviceId, List<String> pointIds) {
        return realtimeDataQueryApplicationService.getDeviceData(deviceId, pointIds);
    }

    /**
     * 查询全部设备的实时数据。
     *
     * @return 全设备实时数据聚合响应
     */
    public AllDeviceRealtimeDataResponse getAllRealtimeData() {
        return realtimeDataQueryApplicationService.getAllRealtimeData();
    }

    /**
     * 查询所有设备的基本摘要。
     *
     * @return 设备摘要列表
     */
    public DeviceListResponse getAllDevices() {
        return realtimeDataQueryApplicationService.getAllDevices();
    }

    /**
     * 查询指定设备的点位配置摘要。
     *
     * @param deviceId 本地设备唯一标识
     * @return 点位配置摘要列表
     */
    public DevicePointListResponse getDevicePoints(String deviceId) {
        return realtimeDataQueryApplicationService.getDevicePoints(deviceId);
    }

    /**
     * 重置设备下点位的自适应采集状态。
     *
     * @param deviceId 本地设备唯一标识
     * @return 重置结果
     */
    public AdaptiveResetResponse resetAdaptiveConfig(String deviceId) {
        try {
            List<DataPoint> dataPoints = configManager.getDataPoints(deviceId);
            for (DataPoint point : dataPoints) {
                pointRuntimeStateService.reset(deviceId, point);
            }
            return AdaptiveResetResponse.builder()
                    .code(200)
                    .message("重置成功")
                    .build();
        } catch (Exception exception) {
            log.error("重置设备自适应配置失败，设备={}", deviceId, exception);
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
    public HistoryDataResponse getPointHistory(String deviceId,
                                               String pointId,
                                               Long startTs,
                                               Long endTs,
                                               Integer limit) {
        return dataHistoryApplicationService.getPointHistory(deviceId, pointId, startTs, endTs, limit);
    }

    /**
     * 查询最近告警历史。
     *
     * @return 告警历史响应
     */
    public AlarmHistoryDataResponse getRecentAlarmHistory(String deviceId,
                                                          String pointId,
                                                          String pointCode,
                                                          String level,
                                                          String ruleId,
                                                          Long startTs,
                                                          Long endTs,
                                                          Integer limit) {
        return dataHistoryApplicationService.getRecentAlarmHistory(
                deviceId, pointId, pointCode, level, ruleId, startTs, endTs, limit);
    }

    /**
     * 查询指定设备的告警历史。
     *
     * @return 告警历史响应
     */
    public AlarmHistoryDataResponse getAlarmHistory(String deviceId,
                                                    String pointId,
                                                    String pointCode,
                                                    String level,
                                                    String ruleId,
                                                    Long startTs,
                                                    Long endTs,
                                                    Integer limit) {
        return dataHistoryApplicationService.getAlarmHistory(
                deviceId, pointId, pointCode, level, ruleId, startTs, endTs, limit);
    }
}
