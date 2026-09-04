package com.wangbin.collector.api.controller;

import com.wangbin.collector.api.application.RealtimeDataApplicationService;
import com.wangbin.collector.api.controller.dto.AdaptiveResetResponse;
import com.wangbin.collector.api.controller.dto.AllDeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.AlarmHistoryDataResponse;
import com.wangbin.collector.api.controller.dto.DeviceListResponse;
import com.wangbin.collector.api.controller.dto.DevicePointListResponse;
import com.wangbin.collector.api.controller.dto.DeviceRealtimeDataResponse;
import com.wangbin.collector.api.controller.dto.HistoryDataResponse;
import com.wangbin.collector.api.controller.dto.PointRealtimeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据查询控制器。
 *
 * <p>只负责控制台数据查询接口路由，具体查询编排由应用服务处理。</p>
 */
@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataController {

    private final RealtimeDataApplicationService realtimeDataApplicationService;

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
        return realtimeDataApplicationService.getPointData(deviceId, pointId);
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
        return realtimeDataApplicationService.getDeviceData(deviceId, pointIds);
    }

    /**
     * 查询全部设备的实时点位数据。
     *
     * @return 全设备实时数据聚合响应
     */
    @GetMapping("/realtime")
    public AllDeviceRealtimeDataResponse getAllRealtimeData() {
        return realtimeDataApplicationService.getAllRealtimeData();
    }

    /**
     * 查询所有设备的基本摘要。
     *
     * @return 设备摘要列表
     */
    @GetMapping("/devices")
    public DeviceListResponse getAllDevices() {
        return realtimeDataApplicationService.getAllDevices();
    }

    /**
     * 查询指定设备的点位配置摘要。
     *
     * @param deviceId 本地设备唯一标识
     * @return 点位配置摘要列表
     */
    @GetMapping("/device/{deviceId}/points")
    public DevicePointListResponse getDevicePoints(@PathVariable String deviceId) {
        return realtimeDataApplicationService.getDevicePoints(deviceId);
    }

    /**
     * 重置设备下点位的自适应采集状态。
     *
     * @param deviceId 本地设备唯一标识
     * @return 重置结果
     */
    @PostMapping("/device/{deviceId}/reset-adaptive")
    public AdaptiveResetResponse resetAdaptiveConfig(@PathVariable String deviceId) {
        return realtimeDataApplicationService.resetAdaptiveConfig(deviceId);
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
        return realtimeDataApplicationService.getPointHistory(deviceId, pointId, startTs, endTs, limit);
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
        return realtimeDataApplicationService.getRecentAlarmHistory(
                deviceId, pointId, pointCode, level, ruleId, startTs, endTs, limit);
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
        return realtimeDataApplicationService.getAlarmHistory(
                deviceId, pointId, pointCode, level, ruleId, startTs, endTs, limit);
    }
}