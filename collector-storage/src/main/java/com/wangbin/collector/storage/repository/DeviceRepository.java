package com.wangbin.collector.storage.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 定义当前模块的业务契约。
 */
@Mapper
public interface DeviceRepository {

    /**
     * 创建并返回业务对象。
     */
    void createChildTable(@Param("database") String database,
                          @Param("subTable") String subTable,
                          @Param("superTable") String superTable,
                          @Param("deviceTag") String deviceTag,
                          @Param("protocolTag") String protocolTag);
}
