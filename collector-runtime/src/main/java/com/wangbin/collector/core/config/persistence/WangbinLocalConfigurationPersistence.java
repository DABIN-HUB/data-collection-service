package com.wangbin.collector.core.config.persistence;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.config.model.DeviceContext;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** 本地配置权威源桥接，仅依赖配置仓储契约，不泄露 JDBC 或设备生命周期。 */
public interface WangbinLocalConfigurationPersistence {
    /** 从同一数据库事务视图恢复全部本地配置及修订，支持无旧JSON或迁移记录的合法空库。 */
    List<StoredDeviceConfiguration> restore();

    /** 仅读取当前数据库中的单设备，不回退旧JSON。 */
    Optional<StoredDeviceConfiguration> findDevice(String deviceId);

    /** 设备管理列表只还原基础信息，不读取任何连接或点位负载。 */
    List<DeviceInfo> listDeviceSummaries();

    /** 设备管理按 ID 定位的数据库配置；运行态仍通过 ConfigManager 缓存。 */
    Optional<DeviceInfo> findDeviceSummary(String deviceId);
    Optional<DeviceContext> findDeviceConnectionContext(String deviceId);
    Optional<DeviceContext> findDevicePointsContext(String deviceId);

    /** 按显式白名单还原独立配置快照，不恢复在线状态、采集值或统计。 */
    DeviceContext toRuntime(DeviceConfiguration configuration);

    /** 仅导出配置白名单，保留稳定身份、点位顺序及原始告警扩展。 */
    DeviceConfiguration fromRuntime(DeviceContext context);

    /** 仅比较配置语义；忽略创建/更新时间与 JSON 键序，但保留点位顺序。 */
    boolean equivalent(DeviceContext left, DeviceContext right);

    /** 复用现有仓储事务；禁止 Writer 逃逸、嵌套事务及事务内生命周期操作。 */
    <T> T transaction(Function<ConfigurationStore.Writer, T> action);
}
