package com.wangbin.collector.core.config.persistence;

import java.util.function.Function;

/**
 * 配置持久化 Port，不暴露 JDBC 或运行态实体。
 * 同一事务的读取包含尚未提交的写入；回调或提交失败必须整批回滚。
 * 数据及无负载修订审计必须原子提交，持久化修订与现有 JVM 配置版本独立。
 */
public interface ConfigurationStore extends ConfigurationReader, AutoCloseable {
    /** 禁止嵌套事务；Writer 不得跨线程或逃逸回调，失效后任何读写必须报错。 */
    <T> T transaction(Function<Writer, T> action);

    /** 幂等关闭；关闭后所有读写操作必须拒绝，不允许隐式重开或创建库。 */
    @Override
    void close();

    /**
     * 仅限当前回调有效期的事务读写入口。
     * 所有保存的 expectedRevision=0 表示仅创建；大于零表示必须匹配已有记录修订。
     * 所有删除必须提供正数且匹配的修订，缺失或冲突抛出 ConfigurationConflictException。
     * 仅创建遇到已存在记录、更新时记录缺失或修订不匹配，也抛出同一冲突异常。
     * 非法输入抛出 IllegalArgumentException，资源或事务失败抛出不含敏感负载的 IllegalStateException。
     * 每次成功变更分配全局递增修订，同时返回该实体新修订。
     * 所有身份禁止 trim、归一化或自动生成，pointId 必须跨设备全局唯一。
     * 实现必须重新校验设备 JSON 白名单、本地临时来源、稳定身份、配置状态和排序的冗余一致性。
     * 协议模板、网关设置及快照 JSON 必须语法合法且保留精确数值，禁止包含运行态实体。
     */
    interface Writer extends ConfigurationReader {
        StoredDeviceConfiguration saveDevice(DeviceConfiguration configuration, long expectedRevision);
        void deleteDevice(String deviceId, long expectedRevision);
        ProtocolProfile saveProtocolProfile(String profileId, String protocolType, String configJson, long expectedRevision);
        void deleteProtocolProfile(String profileId, long expectedRevision);
        GatewaySetting saveGatewaySetting(String settingKey, String configJson, long expectedRevision);
        void deleteGatewaySetting(String settingKey, long expectedRevision);
        RemoteSnapshot saveRemoteSnapshot(String source, String snapshotId, String sourceVersion,
                String configJson, long expectedRevision);
        void deleteRemoteSnapshot(String source, String snapshotId, String sourceVersion, long expectedRevision);
        /** 仅插入成功记录，重复 migrationId 必须拒绝；迁移器先回读校验，再同事务标记。 */
        void recordMigration(MigrationRecord record);
    }
}
