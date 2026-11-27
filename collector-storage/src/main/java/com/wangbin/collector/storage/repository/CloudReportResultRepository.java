package com.wangbin.collector.storage.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 云上报终态的独立 TDengine 普通表；所有动态标识符由服务端严格校验。 */
@Mapper
public interface CloudReportResultRepository {

    void createTable(@Param("database") String database);

    List<Map<String, Object>> showCreateTable(@Param("database") String database);

    void insert(@Param("database") String database,
                @Param("ts") java.sql.Timestamp ts,
                @Param("messageId") String messageId,
                @Param("deviceIdsJson") String deviceIdsJson,
                @Param("deviceHashesJson") String deviceHashesJson,
                @Param("cloudDeviceId") String cloudDeviceId,
                @Param("gatewayDeviceId") String gatewayDeviceId,
                @Param("status") String status,
                @Param("publishedAt") long publishedAt,
                @Param("ackAt") long ackAt,
                @Param("createdAt") long createdAt,
                @Param("completedAt") long completedAt,
                @Param("attempts") int attempts);

    List<Map<String, Object>> query(@Param("database") String database,
                                    @Param("devicePattern") String devicePattern,
                                    @Param("startTs") long startTs,
                                    @Param("endTs") long endTs,
                                    @Param("limit") int limit,
                                    @Param("offset") long offset);

    Long count(@Param("database") String database,
               @Param("devicePattern") String devicePattern,
               @Param("startTs") long startTs,
               @Param("endTs") long endTs,
               @Param("ackOnly") boolean ackOnly);
}
