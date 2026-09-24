package com.wangbin.collector.storage.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 定义当前模块的业务契约。
 */
@Mapper
public interface AlarmRepository {

    /**
     * 创建并返回业务对象。
     */
    void createStable(@Param("database") String database,
                      @Param("superTable") String superTable);

    /**
     * 执行当前业务逻辑。
     */
    void addAlarmEventTypeColumn(@Param("database") String database,
                                 @Param("superTable") String superTable);

    void addAlarmLifecycleColumn(@Param("database") String database,
                                 @Param("superTable") String superTable,
                                 @Param("column") String column,
                                 @Param("definition") String definition);

    /**
     * 创建并返回业务对象。
     */
    void createChildTable(@Param("database") String database,
                          @Param("subTable") String subTable,
                          @Param("superTable") String superTable,
                          @Param("deviceTag") String deviceTag);

    /**
     * 写入或持久化业务数据。
     */
    void insertAlarm(@Param("database") String database,
                     @Param("subTable") String subTable,
                     @Param("eventTs") long eventTs,
                     @Param("deviceName") String deviceName,
                     @Param("pointId") String pointId,
                     @Param("pointCode") String pointCode,
                     @Param("ruleId") String ruleId,
                     @Param("ruleName") String ruleName,
                     @Param("alarmLevel") String alarmLevel,
                     @Param("eventType") String eventType,
                     @Param("message") String message,
                     @Param("valueText") String valueText,
                     @Param("valueDouble") Double valueDouble,
                     @Param("valueLong") Long valueLong,
                     @Param("valueBool") Boolean valueBool,
                     @Param("unit") String unit,
                     @Param("payloadJson") String payloadJson,
                     @Param("alarmId") String alarmId,
                     @Param("relatedAlarmId") String relatedAlarmId,
                     @Param("alarmStartedAt") Long alarmStartedAt,
                     @Param("alarmLastOccurredAt") Long alarmLastOccurredAt,
                     @Param("alarmDurationMs") Long alarmDurationMs);

    List<Map<String, Object>> queryRecentAlarmActivations(@Param("database") String database,
                                                           @Param("superTable") String superTable,
                                                           @Param("deviceId") String deviceId,
                                                           @Param("pointId") String pointId,
                                                           @Param("pointCode") String pointCode,
                                                           @Param("ruleId") String ruleId,
                                                           @Param("alarmLevel") String alarmLevel,
                                                           @Param("startTs") Long startTs,
                                                           @Param("endTs") Long endTs,
                                                           @Param("limit") int limit,
                                                           @Param("offset") int offset);

    /** 兼容现有调用方的首屏查询入口。 */
    default List<Map<String, Object>> queryRecentAlarmActivations(String database, String superTable,
                                                                    String deviceId, String pointId,
                                                                    String pointCode, String ruleId,
                                                                    String alarmLevel, Long startTs,
                                                                    Long endTs, int limit) {
        return queryRecentAlarmActivations(database, superTable, deviceId, pointId, pointCode, ruleId,
                alarmLevel, startTs, endTs, limit, 0);
    }

    List<Map<String, Object>> queryRecoveriesByAlarmIds(@Param("database") String database,
                                                         @Param("superTable") String superTable,
                                                         @Param("alarmIds") List<String> alarmIds);

    /**
     * 查询并返回业务数据。
     */
    List<Map<String, Object>> queryAlarmHistory(@Param("database") String database,
                                                @Param("subTable") String subTable,
                                                @Param("pointId") String pointId,
                                                @Param("pointCode") String pointCode,
                                                @Param("alarmLevel") String alarmLevel,
                                                @Param("ruleId") String ruleId,
                                                @Param("startTs") Long startTs,
                                                @Param("endTs") Long endTs,
                                                @Param("limit") int limit);

    /**
     * 查询并返回业务数据。
     */
    List<Map<String, Object>> queryRecentAlarmHistory(@Param("database") String database,
                                                      @Param("superTable") String superTable,
                                                      @Param("deviceId") String deviceId,
                                                      @Param("pointId") String pointId,
                                                      @Param("pointCode") String pointCode,
                                                      @Param("alarmLevel") String alarmLevel,
                                                      @Param("ruleId") String ruleId,
                                                      @Param("startTs") Long startTs,
                                                      @Param("endTs") Long endTs,
                                                      @Param("limit") int limit);

    /**
     * 记录或统计业务状态。
     */
    long countRecentAlarmHistory(@Param("database") String database,
                                 @Param("superTable") String superTable,
                                 @Param("deviceId") String deviceId,
                                 @Param("pointId") String pointId,
                                 @Param("pointCode") String pointCode,
                                 @Param("alarmLevel") String alarmLevel,
                                 @Param("ruleId") String ruleId,
                                 @Param("startTs") Long startTs,
                                 @Param("endTs") Long endTs);
}
