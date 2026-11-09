package com.wangbin.collector.core.config.persistence;

import java.util.List;
import java.util.Optional;

/** 每次方法调用读取一致快照，返回不可变集合；批量读写一致性请使用事务 Writer。 */
public interface ConfigurationReader {
    Optional<StoredDeviceConfiguration> findDevice(String deviceId);
    List<StoredDeviceConfiguration> listDevices();
    /** 只读设备基础 JSON 和持久修订，不读取连接或点位。 */
    Optional<StoredDeviceSummary> findDeviceSummary(String deviceId);
    List<StoredDeviceSummary> listDeviceSummaries();
    Optional<ConnectionConfiguration> findDeviceConnection(String deviceId);
    List<PointConfiguration> listDevicePoints(String deviceId);
    Optional<ProtocolProfile> findProtocolProfile(String profileId);
    List<ProtocolProfile> listProtocolProfiles();
    Optional<GatewaySetting> findGatewaySetting(String settingKey);
    List<GatewaySetting> listGatewaySettings();
    /** 按来源、快照身份和远端版本联合定位，历史版本互不覆盖。 */
    Optional<RemoteSnapshot> findRemoteSnapshot(String source, String snapshotId, String sourceVersion);
    List<RemoteSnapshot> listRemoteSnapshots(String source);
    /** 未发生变更时为零；普通读取返回已提交最大修订，事务读取还包含本事务待提交修订。 */
    long currentRevision();
    /** 按修订升序返回严格大于 afterRevision 的记录；游标非负，limit 必须为正数。 */
    List<ConfigurationRevision> listRevisions(long afterRevision, int limit);
    Optional<MigrationRecord> findMigration(String migrationId);
    List<MigrationRecord> listMigrations();
}
