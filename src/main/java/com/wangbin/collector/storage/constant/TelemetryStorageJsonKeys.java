package com.wangbin.collector.storage.constant;

/**
 * 遥测历史入库时 rawJson、processedJson 和 metadataJson 使用的字段常量。
 */
public final class TelemetryStorageJsonKeys {

    /**
     * 原始报文 JSON 扩展字段，来源于处理元数据。
     */
    public static final String RAW_JSON = "rawJson";

    /**
     * 处理后 JSON 扩展字段，来源于处理元数据。
     */
    public static final String PROCESSED_JSON = "processedJson";

    /**
     * 历史元数据 JSON 扩展字段，来源于处理元数据。
     */
    public static final String METADATA_JSON = "metadataJson";

    /**
     * 点位平台数据类型字段。
     */
    public static final String DATA_TYPE = "dataType";

    /**
     * 原始采集值字段。
     */
    public static final String RAW_VALUE = "rawValue";

    /**
     * 原始字节数据字段。
     */
    public static final String RAW_BYTES = "rawBytes";

    /**
     * 协议站号或单元号字段。
     */
    public static final String UNIT_ID = "unitId";

    /**
     * 采集发生时间字段。
     */
    public static final String COLLECT_TIME = "collectTime";

    /**
     * 历史元数据中的协议类型字段。
     */
    public static final String PROTOCOL_TYPE = "protocolType";

    /**
     * 历史元数据中的采集周期字段。
     */
    public static final String COLLECTION_INTERVAL = "collectionInterval";

    /**
     * 历史元数据中的是否允许上报字段。
     */
    public static final String REPORT_ENABLED = "reportEnabled";

    /**
     * 历史元数据中的是否启用告警字段。
     */
    public static final String ALARM_ENABLED = "alarmEnabled";

    /**
     * 历史元数据中的处理器名称字段。
     */
    public static final String PROCESSOR_NAME = "processorName";

    /**
     * 工具类不允许实例化。
     */
    private TelemetryStorageJsonKeys() {
    }
}