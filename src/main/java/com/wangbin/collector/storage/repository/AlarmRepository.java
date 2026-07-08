package com.wangbin.collector.storage.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface AlarmRepository {

    void createStable(@Param("database") String database,
                      @Param("superTable") String superTable);

    void addAlarmEventTypeColumn(@Param("database") String database,
                                 @Param("superTable") String superTable);

    void createChildTable(@Param("database") String database,
                          @Param("subTable") String subTable,
                          @Param("superTable") String superTable,
                          @Param("deviceTag") String deviceTag);

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
                     @Param("payloadJson") String payloadJson);

    List<Map<String, Object>> queryAlarmHistory(@Param("database") String database,
                                                @Param("subTable") String subTable,
                                                @Param("pointId") String pointId,
                                                @Param("pointCode") String pointCode,
                                                @Param("alarmLevel") String alarmLevel,
                                                @Param("ruleId") String ruleId,
                                                @Param("startTs") Long startTs,
                                                @Param("endTs") Long endTs,
                                                @Param("limit") int limit);

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
}
