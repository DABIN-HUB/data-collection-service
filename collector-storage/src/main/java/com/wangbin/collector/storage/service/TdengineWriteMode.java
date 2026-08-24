package com.wangbin.collector.storage.service;

/**
 * TDengine 历史写入实现模式，保留既有 MyBatis + TAOS-RS 路径便于按配置回滚。
 */
public enum TdengineWriteMode {

    /**
     * 既有 MyBatis XML 生成 SQL，通过 TAOS-RS JDBC 执行。
     */
    MYBATIS_REST,

    /**
     * 直接 JDBC Statement 执行单条 multi-values SQL，绕过 MyBatis 参数绑定。
     */
    DIRECT_REST,

    /**
     * 通过 TDengine WebSocket STMT 列式参数绑定执行批量写入。
     */
    WS_STMT
}
