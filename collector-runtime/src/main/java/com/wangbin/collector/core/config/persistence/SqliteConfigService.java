package com.wangbin.collector.core.config.persistence;

/**
 * SQLite 配置业务的唯一对外契约；复用现有读写及事务接口，不向调用方暴露 JDBC。
 * 所有设备、协议、网关和修订读写均经此服务及其事务 Writer 完成。
 */
public interface SqliteConfigService extends ConfigurationStore {
}
