package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.AlarmHistoryDataResponse;
import com.wangbin.collector.api.controller.dto.HistoryDataResponse;
import com.wangbin.collector.storage.service.AlarmHistoryService;
import com.wangbin.collector.storage.service.HistoryDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 控制台历史数据查询应用服务。
 *
 * <p>只负责 TDengine 点位历史和告警历史查询，并保留存储未启用时的 disabled 响应语义。</p>
 */
@Slf4j
@Service
public class DataHistoryApplicationService {

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_ERROR = "error";
    private static final String STATUS_DISABLED = "disabled";

    private final HistoryDataService historyDataService;
    private final AlarmHistoryService alarmHistoryService;

    /**
     * 创建控制台历史数据查询应用服务。
     *
     * @param historyDataServiceProvider 历史数据服务提供器
     * @param alarmHistoryServiceProvider 告警历史服务提供器
     */
    public DataHistoryApplicationService(ObjectProvider<HistoryDataService> historyDataServiceProvider,
                                         ObjectProvider<AlarmHistoryService> alarmHistoryServiceProvider) {
        this.historyDataService = historyDataServiceProvider.getIfAvailable();
        this.alarmHistoryService = alarmHistoryServiceProvider.getIfAvailable();
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
        try {
            if (historyDataService == null || !historyDataService.isEnabled()) {
                return HistoryDataResponse.builder()
                        .status(STATUS_DISABLED)
                        .message("TDengine 历史存储未启用")
                        .deviceId(deviceId)
                        .pointId(pointId)
                        .build();
            }
            List<Map<String, Object>> rows = historyDataService.queryPointHistory(
                    deviceId, pointId, startTs, endTs, limit);
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
        } catch (Exception exception) {
            log.error("查询历史数据失败，设备={}，点位={}", deviceId, pointId, exception);
            return HistoryDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + exception.getMessage())
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
    public AlarmHistoryDataResponse getRecentAlarmHistory(String deviceId,
                                                          String pointId,
                                                          String pointCode,
                                                          String level,
                                                          String ruleId,
                                                          Long startTs,
                                                          Long endTs,
                                                          Integer limit) {
        try {
            if (alarmHistoryService == null || !alarmHistoryService.isEnabled()) {
                return alarmDisabledResponse(deviceId, pointId, pointCode, level, ruleId, startTs, endTs);
            }
            List<Map<String, Object>> rows = alarmHistoryService.queryRecentAlarmHistory(
                    deviceId, pointId, pointCode, level, ruleId, startTs, endTs, limit);
            long total = alarmHistoryService.countRecentAlarmHistory(
                    deviceId, pointId, pointCode, level, ruleId, startTs, endTs);
            return alarmSuccessResponse(deviceId, pointId, pointCode, level, ruleId, startTs, endTs, rows, total);
        } catch (Exception exception) {
            log.error("查询最近告警历史失败，设备={}，点位={}，点位编码={}，级别={}，规则={}",
                    deviceId, pointId, pointCode, level, ruleId, exception);
            return AlarmHistoryDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + exception.getMessage())
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
    public AlarmHistoryDataResponse getAlarmHistory(String deviceId,
                                                    String pointId,
                                                    String pointCode,
                                                    String level,
                                                    String ruleId,
                                                    Long startTs,
                                                    Long endTs,
                                                    Integer limit) {
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
        } catch (Exception exception) {
            log.error("查询告警历史失败，设备={}，点位={}，点位编码={}，级别={}，规则={}",
                    deviceId, pointId, pointCode, level, ruleId, exception);
            return AlarmHistoryDataResponse.builder()
                    .status(STATUS_ERROR)
                    .message("查询失败: " + exception.getMessage())
                    .deviceId(deviceId)
                    .build();
        }
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
