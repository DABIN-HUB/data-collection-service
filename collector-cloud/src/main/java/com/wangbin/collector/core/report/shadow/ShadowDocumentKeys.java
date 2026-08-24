package com.wangbin.collector.core.report.shadow;

/**
 * 设备影子文档、影子历史和影子事件元数据使用的 Map 字段常量。
 */
public final class ShadowDocumentKeys {

    /**
     * 设备影子版本字段，用于乐观锁和上报增量判断。
     */
    public static final String VERSION = "version";

    /**
     * 设备影子创建时间字段。
     */
    public static final String CREATED_AT = "createdAt";

    /**
     * 设备影子最后上报时间字段。
     */
    public static final String LAST_REPORT_AT = "lastReportAt";

    /**
     * 设备影子最近上报窗口开始时间字段。
     */
    public static final String LAST_WINDOW_START = "lastWindowStart";

    /**
     * 设备影子最近上报窗口结束时间字段。
     */
    public static final String LAST_WINDOW_END = "lastWindowEnd";

    /**
     * 设备影子状态根节点字段。
     */
    public static final String STATE = "state";

    /**
     * 已上报属性集合字段。
     */
    public static final String REPORTED = "reported";

    /**
     * 期望属性集合字段。
     */
    public static final String DESIRED = "desired";

    /**
     * 增量属性集合字段。
     */
    public static final String DELTA = "delta";

    /**
     * 上一次成功上报值集合字段。
     */
    public static final String LAST_REPORTED = "lastReported";

    /**
     * 单个属性元数据更新时间字段。
     */
    public static final String UPDATED_AT = "updatedAt";

    /**
     * 单个属性附加元数据字段。
     */
    public static final String VALUE_METADATA = "valueMetadata";

    /**
     * 影子历史动作字段。
     */
    public static final String ACTION = "action";

    /**
     * 影子历史基础版本字段。
     */
    public static final String BASE_VERSION = "baseVersion";

    /**
     * 影子历史完整文档字段。
     */
    public static final String DOCUMENT = "document";

    /**
     * 采集结果元数据中的事件触发标记字段。
     */
    public static final String EVENT_TRIGGERED = "eventTriggered";

    /**
     * 采集结果元数据中的事件等级字段。
     */
    public static final String EVENT_LEVEL = "eventLevel";

    /**
     * 采集结果元数据中的事件说明字段。
     */
    public static final String EVENT_MESSAGE = "eventMessage";

    /**
     * 期望属性更新历史动作值。
     */
    public static final String DESIRED_UPDATE_ACTION = "desired_update";

    /**
     * 期望属性清空历史动作值。
     */
    public static final String DESIRED_CLEAR_ACTION = "desired_clear";

    /**
     * 已上报属性更新历史动作值。
     */
    public static final String REPORTED_UPDATE_ACTION = "reported_update";

    /**
     * 点位值元数据中的地址字段。
     */
    public static final String ADDRESS = "address";

    /**
     * 点位值元数据中的 BACnet 对象类型字段。
     */
    public static final String OBJECT_TYPE = "objectType";

    /**
     * 点位值元数据中的 BACnet 实例号字段。
     */
    public static final String INSTANCE_NUMBER = "instanceNumber";

    /**
     * 点位值元数据中的 BACnet 属性标识字段。
     */
    public static final String PROPERTY_IDENTIFIER = "propertyIdentifier";

    /**
     * 点位值元数据中的处理模式字段。
     */
    public static final String PROCESSING_MODE = "processingMode";

    /**
     * 点位值元数据中的 BACnet 值类型字段。
     */
    public static final String BACNET_VALUE_TYPE = "bacnetValueType";

    /**
     * 点位值元数据中的 BACnet 复杂值字段。
     */
    public static final String BACNET_COMPLEX_VALUE = "bacnetComplexValue";

    /**
     * 点位值元数据中的 BACnet 附加元数据字段。
     */
    public static final String BACNET_VALUE_METADATA = "bacnetValueMetadata";
    /**
     * 工具类不允许实例化。
     */
    /**
     * 工具类不允许实例化。
     */
    private ShadowDocumentKeys() {
    }
}
