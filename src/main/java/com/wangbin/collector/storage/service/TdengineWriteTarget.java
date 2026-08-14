package com.wangbin.collector.storage.service;

/**
 * TDengine 已解析写入目标，调用方必须已经完成数据库和子表名清洗。
 */
public record TdengineWriteTarget(String database, String subTable) {
}
