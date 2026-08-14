package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 实时点位查询响应负载。
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PointRealtimePayload {
    /**
     * 点位数据库主键。
     */
    private Long id;

    /**
     * 从站地址。
     */
    private Integer unitId;

    /**
     * 协议公共地址。
     */
    private Integer commonAddress;

    /**
     * 稳定点位唯一标识。
     */
    private String pointId;

    /**
     * 点位业务编码。
     */
    private String pointCode;

    /**
     * 点位名称。
     */
    private String pointName;

    /**
     * 点位别名。
     */
    private String pointAlias;

    /**
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 设备名称。
     */
    private String deviceName;

    /**
     * 设备分组标识。
     */
    private String groupId;

    /**
     * 协议点位地址。
     */
    private String address;

    /**
     * 平台数据类型。
     */
    private String dataType;

    /**
     * 读写类型。
     */
    private String readWrite;

    /**
     * 缩放因子。
     */
    private Double scalingFactor;

    /**
     * 偏移量。
     */
    private Double offset;

    /**
     * 死区范围。
     */
    private Double deadband;

    /**
     * 工程单位。
     */
    private String unit;

    /**
     * 最小有效值。
     */
    private Double minValue;

    /**
     * 最大有效值。
     */
    private Double maxValue;

    /**
     * 采集模式。
     */
    private String collectionMode;

    /**
     * 采集优先级。
     */
    private Integer priority;

    /**
     * 是否启用缓存。
     */
    private Integer cacheEnabled;

    /**
     * 缓存持续时间。
     */
    private Integer cacheDuration;

    /**
     * 是否启用告警。
     */
    private Integer alarmEnabled;

    /**
     * 点位状态。
     */
    private Integer status;

    /**
     * 创建时间。
     */
    private Date createTime;

    /**
     * 更新时间。
     */
    private Date updateTime;

    /**
     * 数值精度。
     */
    private Integer precision;

    /**
     * 备注信息。
     */
    private String remark;

    /**
     * 协议或点位扩展配置，字段由协议 Schema 定义。
     */
    private Map<String, Object> additionalConfig;

    /**
     * 基础采集间隔。
     */
    private Long baseCollectionInterval;

    /**
     * 当前采集间隔。
     */
    private Long currentCollectionInterval;

    /**
     * 最小采集间隔。
     */
    private Long minCollectionInterval;

    /**
     * 最大采集间隔。
     */
    private Long maxCollectionInterval;

    /**
     * 点位变化阈值。
     */
    private Double pointChangeThreshold;

    /**
     * 连续稳定次数。
     */
    private Integer stableCount;

    /**
     * 上一次采集值。
     */
    private Object lastValue;

    /**
     * 最近变化率。
     */
    private Double changeRate;

    /**
     * 上一次调整时间。
     */
    private Long lastAdjustTime;

    /**
     * 当前最终展示值。
     */
    private Object value;

    /**
     * 原始采集值。
     */
    private Object rawValue;

    /**
     * 处理后数值。
     */
    private Object processedValue;

    /**
     * 是否存在缓存值。
     */
    private Boolean hasCachedValue;

    /**
     * 数据质量分值。
     */
    private Integer quality;

    /**
     * 数据质量描述。
     */
    private String qualityDescription;

    /**
     * 数据质量等级。
     */
    private String qualityLevel;

    /**
     * 数据质量是否可接受。
     */
    private Boolean qualityAcceptable;

    /**
     * 数据质量是否可用。
     */
    private Boolean qualityAvailable;

    /**
     * 数据处理提示信息。
     */
    private String processMessage;

    /**
     * 数据处理是否成功。
     */
    private Boolean processSuccess;

    /**
     * 是否跳过处理。
     */
    private Boolean skipped;

    /**
     * 处理器名称。
     */
    private String processorName;

    /**
     * 处理耗时，单位毫秒。
     */
    private Long processingTime;

    /**
     * 处理耗时是否可用。
     */
    private Boolean processingTimeAvailable;

    /**
     * 处理元数据。
     */
    private Map<String, Object> metadata;

    /**
     * 最近更新时间。
     */
    private Object lastUpdateTime;

    /**
     * 响应生成时间戳。
     */
    private Long timestamp;

    /**
     * 根据点位配置和运行状态构建响应负载。
     *
     * @param point 点位配置
     * @param runtimeState 点位运行状态
     * @return 实时点位响应负载
     */
    public static PointRealtimePayload fromPoint(DataPoint point, PointRuntimeStateSnapshot runtimeState) {
        return PointRealtimePayload.builder()
                .id(point.getId())
                .unitId(point.getUnitId())
                .commonAddress(point.getCommonAddress())
                .pointId(point.getPointId())
                .pointCode(point.getPointCode())
                .pointName(point.getPointName())
                .pointAlias(point.getPointAlias())
                .deviceId(point.getDeviceId())
                .deviceName(point.getDeviceName())
                .groupId(point.getGroupId())
                .address(point.getAddress())
                .dataType(point.getDataType())
                .readWrite(point.getReadWrite())
                .scalingFactor(point.getScalingFactor())
                .offset(point.getOffset())
                .deadband(point.getDeadband())
                .unit(point.getUnit())
                .minValue(point.getMinValue())
                .maxValue(point.getMaxValue())
                .collectionMode(point.getCollectionMode())
                .priority(point.getPriority())
                .cacheEnabled(point.getCacheEnabled())
                .cacheDuration(point.getCacheDuration())
                .alarmEnabled(point.getAlarmEnabled())
                .status(point.getStatus())
                .createTime(point.getCreateTime())
                .updateTime(point.getUpdateTime())
                .precision(point.getPrecision())
                .remark(point.getRemark())
                .additionalConfig(point.getAdditionalConfig())
                .baseCollectionInterval(point.getBaseCollectionInterval())
                .currentCollectionInterval(runtimeState.currentCollectionInterval())
                .minCollectionInterval(point.getMinCollectionInterval())
                .maxCollectionInterval(point.getMaxCollectionInterval())
                .pointChangeThreshold(point.getPointChangeThreshold())
                .stableCount(runtimeState.stableCount())
                .lastValue(runtimeState.lastValue())
                .changeRate(runtimeState.changeRate())
                .lastAdjustTime(runtimeState.lastAdjustTime())
                .build();
    }

    /**
     * 填充缓存值和处理质量信息。
     *
     * @param cachedValue 缓存中的实时值
     */
    public void applyCachedValue(Object cachedValue) {
        if (cachedValue instanceof ProcessResult processResult) {
            Map<String, Object> payload = processResult.getMetadata() == null
                    ? new HashMap<>()
                    : new HashMap<>(processResult.getMetadata());
            this.value = processResult.getFinalValue();
            this.rawValue = processResult.getRawValue();
            this.processedValue = processResult.getProcessedValue();
            this.hasCachedValue = true;
            this.quality = processResult.getQuality();
            this.qualityDescription = processResult.getQualityDescription();
            this.qualityLevel = processResult.getQualityLevel();
            this.qualityAcceptable = processResult.isQualityAcceptable();
            this.qualityAvailable = true;
            this.processMessage = processResult.getMessage();
            this.processSuccess = processResult.isSuccess();
            this.skipped = processResult.isSkipped();
            this.processorName = processResult.getProcessorName();
            this.processingTime = processResult.getProcessingTime();
            this.processingTimeAvailable = true;
            this.metadata = payload;
            this.lastUpdateTime = payload.get(ProcessResultMetadataKeys.COLLECT_TIME);
            return;
        }
        this.value = cachedValue;
        this.rawValue = cachedValue;
        this.hasCachedValue = cachedValue != null;
        this.qualityAvailable = false;
        this.processingTimeAvailable = false;
        this.metadata = new HashMap<>();
    }
}
