package com.wangbin.collector.config.sqlite.mapper;

import com.wangbin.collector.core.config.persistence.ConfigurationRevision;
import com.wangbin.collector.core.config.persistence.ConnectionConfiguration;
import com.wangbin.collector.core.config.persistence.GatewaySetting;
import com.wangbin.collector.core.config.persistence.MigrationRecord;
import com.wangbin.collector.core.config.persistence.PointConfiguration;
import com.wangbin.collector.core.config.persistence.ProtocolProfile;
import com.wangbin.collector.core.config.persistence.RemoteSnapshot;
import com.wangbin.collector.core.config.persistence.StoredDeviceSummary;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** SQLite 配置业务的 MyBatis Mapper；连接、事务和 CAS 编排由 ServiceImpl 负责。 */
public interface SqliteConfigMapper {
    long deviceRevision(@Param("id") String id);
    long profileRevision(@Param("id") String id);
    long settingRevision(@Param("key") String key);
    long snapshotRevision(@Param("source") String source, @Param("id") String id,
                          @Param("version") String version);

    int insertRevision(@Param("type") String type, @Param("id") String id,
                       @Param("operation") String operation, @Param("createdAt") Instant createdAt);
    long lastInsertRevision();

    DeviceRow findDevice(@Param("id") String id);
    List<DeviceRow> listDevices();
    StoredDeviceSummary findDeviceSummary(@Param("id") String id);
    List<StoredDeviceSummary> listDeviceSummaries();
    ConnectionConfiguration findDeviceConnection(@Param("id") String id);
    List<PointConfiguration> listDevicePoints(@Param("id") String id);
    List<PointOwner> findPointOwners(@Param("pointIds") Collection<String> pointIds);

    ProtocolProfile findProtocolProfile(@Param("id") String id);
    List<ProtocolProfile> listProtocolProfiles();
    GatewaySetting findGatewaySetting(@Param("key") String key);
    List<GatewaySetting> listGatewaySettings();
    RemoteSnapshot findRemoteSnapshot(@Param("source") String source, @Param("id") String id,
                                      @Param("version") String version);
    List<RemoteSnapshot> listRemoteSnapshots(@Param("source") String source);
    long currentRevision();
    List<ConfigurationRevision> listRevisions(@Param("after") long after, @Param("limit") int limit);
    MigrationRecord findMigration(@Param("id") String id);
    List<MigrationRecord> listMigrations();

    int insertDevice(@Param("config") com.wangbin.collector.core.config.persistence.DeviceConfiguration config,
                     @Param("revision") long revision);
    int updateDevice(@Param("config") com.wangbin.collector.core.config.persistence.DeviceConfiguration config,
                     @Param("revision") long revision);
    int deleteConnections(@Param("id") String id);
    int deletePoints(@Param("id") String id);
    int insertConnection(@Param("config") ConnectionConfiguration config);
    int insertPoint(@Param("point") PointConfiguration point, @Param("code") String code);
    int deleteDevice(@Param("id") String id);
    int insertProtocolProfile(@Param("id") String id, @Param("protocol") String protocol,
                              @Param("json") String json, @Param("revision") long revision);
    int updateProtocolProfile(@Param("id") String id, @Param("protocol") String protocol,
                              @Param("json") String json, @Param("revision") long revision);
    int deleteProtocolProfile(@Param("id") String id);
    int insertGatewaySetting(@Param("key") String key, @Param("json") String json,
                             @Param("revision") long revision);
    int updateGatewaySetting(@Param("key") String key, @Param("json") String json,
                             @Param("revision") long revision);
    int deleteGatewaySetting(@Param("key") String key);
    int insertRemoteSnapshot(@Param("source") String source, @Param("id") String id,
                             @Param("version") String version, @Param("json") String json,
                             @Param("revision") long revision);
    int updateRemoteSnapshot(@Param("source") String source, @Param("id") String id,
                             @Param("version") String version, @Param("json") String json,
                             @Param("revision") long revision);
    int deleteRemoteSnapshot(@Param("source") String source, @Param("id") String id,
                             @Param("version") String version);
    int insertMigration(@Param("record") MigrationRecord record);
}