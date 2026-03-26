package com.wangbin.collector.storage.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeviceRepository {

    void createChildTable(@Param("database") String database,
                          @Param("subTable") String subTable,
                          @Param("superTable") String superTable,
                          @Param("deviceTag") String deviceTag,
                          @Param("protocolTag") String protocolTag);
}
