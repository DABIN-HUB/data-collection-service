package com.wangbin.collector.core.processor;

/**
 * Metadata keys 共享 by 采集器 and 下游 persistence builders.
 */
public final class ProcessResultMetadataKeys {

    public static final String RAW_VALUE = "collectorRawValue";
    public static final String PROCESSED_VALUE = "collectorProcessedValue";
    public static final String RAW_BYTES = "rawBytes";
    public static final String COLLECT_TIME = "collectTime";
    public static final String SOURCE = "source";
    public static final String COLLECTOR_ID = "collectorId";
    public static final String BATCH_ID = "batchId";
    public static final String GROUP_ID = "groupId";
    public static final String PROCESSING_VERSION = "processingVersion";

    /** 实时数据所属设备运行代次。 */
    public static final String SOURCE_GENERATION = "sourceGeneration";
    /** 实时数据所属进程实例，不能跨进程以相同代号认领。 */
    public static final String RUNTIME_ID = "runtimeId";
    /** 产生样本时的设备配置版本。 */
    public static final String CONFIG_VERSION = "configVersion";
    /** 点位的最小稳定身份，不包含完整配置或连接秘密。 */
    public static final String POINT_IDENTITY = "pointIdentity";
    /** 点位身份中的平台数据类型字段。 */
    public static final String POINT_DATA_TYPE = "dataType";

    /**
     * 创建当前组件实例。
     */
    private ProcessResultMetadataKeys() {
    }
}
