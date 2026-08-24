package com.wangbin.collector.monitor.metrics;

/**
 * 云上报监控 Map 字段名常量。
 */
public final class CloudReportMetricKeys {

    /**
     * 云上报状态中文说明字段。
     */
    public static final String STATUS_TEXT = "statusText";

    /**
     * 云平台厂商标识字段。
     */
    public static final String CLOUD_PROVIDER = "cloudProvider";

    /**
     * 当前可用上报协议列表字段。
     */
    public static final String SUPPORTED_PROTOCOLS = "supportedProtocols";

    /**
     * 上报处理器状态集合字段。
     */
    public static final String HANDLERS_STATUS = "handlersStatus";

    /**
     * 上报处理器统计集合字段。
     */
    public static final String HANDLERS_STATISTICS = "handlersStatistics";

    /**
     * 云上报配置覆盖情况字段。
     */
    public static final String CONFIGURED = "configured";

    /**
     * 云上报线程池指标字段。
     */
    public static final String EXECUTOR = "executor";

    /**
     * 云上报批量策略字段。
     */
    public static final String BATCH = "batch";

    /**
     * 云上报 ACK 策略字段。
     */
    public static final String ACK = "ack";

    /**
     * 云上报 Outbox 指标字段。
     */
    public static final String OUTBOX = "outbox";

    /**
     * 云上报负载配置字段。
     */
    public static final String PAYLOAD = "payload";

    /**
     * 云上报风险说明列表字段。
     */
    public static final String RISKS = "risks";

    /**
     * 指标生成时间戳字段。
     */
    public static final String GENERATED_AT = "generatedAt";

    /**
     * 设备总数字段。
     */
    public static final String DEVICE_COUNT = "deviceCount";

    /**
     * 启用上报点位数字段。
     */
    public static final String REPORT_ENABLED_POINT_COUNT = "reportEnabledPointCount";

    /**
     * 启用事件上报点位数字段。
     */
    public static final String EVENT_ENABLED_POINT_COUNT = "eventEnabledPointCount";

    /**
     * 启用变化触发点位数字段。
     */
    public static final String CHANGE_TRIGGER_POINT_COUNT = "changeTriggerPointCount";

    /**
     * 配置上报字段的点位数字段。
     */
    public static final String REPORT_FIELD_POINT_COUNT = "reportFieldPointCount";

    /**
     * 可实际上报点位数字段。
     */
    public static final String REPORTABLE_POINT_COUNT = "reportablePointCount";

    /**
     * 配置有效云目标设备数字段。
     */
    public static final String CLOUD_TARGET_DEVICE_COUNT = "cloudTargetDeviceCount";

    /**
     * 配置无效云目标设备数字段。
     */
    public static final String INVALID_CLOUD_TARGET_DEVICE_COUNT = "invalidCloudTargetDeviceCount";

    /**
     * 云目标总数字段。
     */
    public static final String CLOUD_TARGET_COUNT = "cloudTargetCount";

    /**
     * 云目标标识集合字段。
     */
    public static final String CLOUD_TARGET_KEYS = "cloudTargetKeys";

    /**
     * 云目标覆盖率字段。
     */
    public static final String CLOUD_TARGET_COVERAGE = "cloudTargetCoverage";

    /**
     * 线程池核心线程数字段。
     */
    public static final String CORE_POOL_SIZE = "corePoolSize";

    /**
     * 线程池最大线程数字段。
     */
    public static final String MAX_POOL_SIZE = "maxPoolSize";

    /**
     * 线程池当前线程数字段。
     */
    public static final String POOL_SIZE = "poolSize";

    /**
     * 线程池活跃线程数字段。
     */
    public static final String ACTIVE_COUNT = "activeCount";

    /**
     * 线程池队列当前长度字段。
     */
    public static final String QUEUE_SIZE = "queueSize";

    /**
     * 线程池队列剩余容量字段。
     */
    public static final String QUEUE_REMAINING_CAPACITY = "queueRemainingCapacity";

    /**
     * 线程池队列总容量字段。
     */
    public static final String QUEUE_CAPACITY = "queueCapacity";

    /**
     * 线程池队列使用率字段。
     */
    public static final String QUEUE_USAGE = "queueUsage";

    /**
     * 线程池已完成任务数字段。
     */
    public static final String COMPLETED_TASK_COUNT = "completedTaskCount";

    /**
     * 线程池累计任务数字段。
     */
    public static final String TASK_COUNT = "taskCount";

    /**
     * 线程池拒绝任务数字段。
     */
    public static final String REJECTED_COUNT = "rejectedCount";

    /**
     * MQTT 客户端管理器指标字段。
     */
    public static final String CLIENT_MANAGER = "clientManager";

    /**
     * MQTT 已连接客户端数字段。
     */
    public static final String CONNECTED_CLIENTS = "connectedClients";

    /**
     * TCP 活跃连接数字段。
     */
    public static final String ACTIVE_CONNECTIONS = "activeConnections";

    /**
     * 单包最大设备数字段。
     */
    public static final String MAX_DEVICES_PER_PACK = "maxDevicesPerPack";

    /**
     * 单包最大属性数字段。
     */
    public static final String MAX_PROPERTIES_PER_PACK = "maxPropertiesPerPack";

    /**
     * 单包最大负载字节数字段。
     */
    public static final String MAX_PAYLOAD_BYTES = "maxPayloadBytes";

    /**
     * 批量最大延迟毫秒数字段。
     */
    public static final String MAX_DELAY_MS = "maxDelayMs";

    /**
     * 高优先级消息绕过批量策略字段。
     */
    public static final String HIGH_PRIORITY_BYPASS = "highPriorityBypass";

    /**
     * ACK 超时毫秒数字段。
     */
    public static final String TIMEOUT_MS = "timeoutMs";

    /**
     * ACK 最大等待消息数字段。
     */
    public static final String MAX_PENDING = "maxPending";

    /**
     * ACK 超时扫描周期毫秒数字段。
     */
    public static final String TIMEOUT_SCAN_MS = "timeoutScanMs";

    /**
     * 设备影子提交策略字段。
     */
    public static final String COMMIT_ON = "commitOn";

    /**
     * Outbox 待处理消息数字段。
     */
    public static final String PENDING_COUNT = "pendingCount";

    /**
     * Outbox 隔离消息数字段。
     */
    public static final String ISOLATED_COUNT = "isolatedCount";

    /**
     * Outbox 最老消息滞留毫秒数字段。
     */
    public static final String OLDEST_MESSAGE_AGE_MS = "oldestMessageAgeMs";

    /**
     * 云上报负载格式字段。
     */
    public static final String PROFILE = "profile";

    /**
     * 云上报负载是否包含质量字段。
     */
    public static final String INCLUDE_QUALITY = "includeQuality";

    /**
     * 云上报负载是否包含属性时间戳字段。
     */
    public static final String INCLUDE_PROPERTY_TS = "includePropertyTs";

    /**
     * 云上报负载是否包含元数据字段。
     */
    public static final String INCLUDE_METADATA = "includeMetadata";

    /**
     * 云上报负载是否包含消息 ID 字段。
     */
    public static final String INCLUDE_MESSAGE_ID = "includeMessageId";

    /**
     * 云上报配置快照是否可用字段。
     */
    public static final String CONFIG_SNAPSHOT_AVAILABLE = "configSnapshotAvailable";

    /**
     * 云上报配置快照读取错误字段。
     */
    public static final String CONFIG_SNAPSHOT_ERROR = "configSnapshotError";
    /**
     * 创建工具类实例没有业务意义。
     */
    private CloudReportMetricKeys() {
    }
}
