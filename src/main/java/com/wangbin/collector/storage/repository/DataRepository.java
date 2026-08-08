package com.wangbin.collector.storage.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 定义当前模块的业务契约。
 */
@Mapper
public interface DataRepository {

    /**
     * 创建并返回业务对象。
     */
    void createDatabase(@Param("database") String database, @Param("keepDays") Integer keepDays);

    /**
     * 创建并返回业务对象。
     */
    void createStable(@Param("database") String database, @Param("superTable") String superTable);

    /**
     * 记录或统计业务状态。
     */
    Long countStable(@Param("database") String database, @Param("stableName") String stableName);

    /**
     * 记录或统计业务状态。
     */
    Long countColumn(@Param("database") String database,
                     @Param("tableName") String tableName,
                     @Param("columnName") String columnName);

    /**
     * 执行当前业务逻辑。
     */
    void addTelemetryUnitColumn(@Param("database") String database,
                                @Param("superTable") String superTable);

    /**
     * 写入或持久化业务数据。
     */
    void insertTelemetry(@Param("database") String database,
                         @Param("subTable") String subTable,
                         @Param("storageTs") long storageTs,
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

    /**
     * 查询并返回业务数据。
     */
    List<Map<String, Object>> queryPointHistory(@Param("database") String database,
                                                @Param("subTable") String subTable,
                                                @Param("pointId") String pointId,
                                                @Param("startTs") Long startTs,
                                                @Param("endTs") Long endTs,
                                                @Param("limit") int limit);
}
