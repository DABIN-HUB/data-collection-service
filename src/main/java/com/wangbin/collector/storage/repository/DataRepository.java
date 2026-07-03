package com.wangbin.collector.storage.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface DataRepository {

    void createDatabase(@Param("database") String database, @Param("keepDays") Integer keepDays);

    void createStable(@Param("database") String database, @Param("superTable") String superTable);

    Long countStable(@Param("database") String database, @Param("stableName") String stableName);

    Long countColumn(@Param("database") String database,
                     @Param("tableName") String tableName,
                     @Param("columnName") String columnName);

    void addTelemetryUnitColumn(@Param("database") String database,
                                @Param("superTable") String superTable);

    void insertTelemetry(@Param("database") String database,
                         @Param("subTable") String subTable,
                         @Param("eventTs") long eventTs,
                         @Param("pointId") String pointId,
                         @Param("pointCode") String pointCode,
                         @Param("pointName") String pointName,
                         @Param("valueText") String valueText,
                         @Param("unit") String unit,
                         @Param("quality") Integer quality,
                         @Param("success") Boolean success,
                         @Param("message") String message,
                         @Param("rawJson") String rawJson,
                         @Param("processedJson") String processedJson,
                         @Param("metadataJson") String metadataJson);

    List<Map<String, Object>> queryPointHistory(@Param("database") String database,
                                                @Param("subTable") String subTable,
                                                @Param("pointId") String pointId,
                                                @Param("startTs") Long startTs,
                                                @Param("endTs") Long endTs,
                                                @Param("limit") int limit);
}
