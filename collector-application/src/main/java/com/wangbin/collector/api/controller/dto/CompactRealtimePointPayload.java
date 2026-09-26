package com.wangbin.collector.api.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 实时表格专用的紧凑点位快照负载。
 *
 * <p>只携带实时表格渲染、搜索和汇总需要的稳定字段，不包含点位配置详情、运行时自适应状态或处理元数据。</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompactRealtimePointPayload {

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
     * 本地设备唯一标识。
     */
    private String deviceId;

    /**
     * 平台数据类型。
     */
    private String dataType;

    /**
     * 协议点位地址。
     */
    private String address;

    /**
     * 读写类型。
     */
    private String readWrite;

    /**
     * 缩放因子。
     */
    private Double scalingFactor;

    /**
     * 工程单位。
     */
    private String unit;

    /**
     * 当前表格展示值。
     */
    private Object value;

    /**
     * 点位启用状态，作为质量字段缺失时的展示兜底。
     */
    private Integer status;

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
     * 数据质量是否来自处理结果。
     */
    private Boolean qualityAvailable;

    /**
     * 数据处理是否成功。
     */
    private Boolean processSuccess;

    /**
     * 处理耗时，单位毫秒。
     */
    private Long processingTime;

    /**
     * 最近采集时间，来自处理结果元数据。
     */
    private Object lastUpdateTime;

    /** 本次查询的有效实时状态，不改变缓存中的采集结果。 */
    private String realtimeStatus;

    /** 运行态错误摘要。 */
    private String errorMessage;

    /** 当前值是否因设备运行态异常而成为旧值。 */
    private Boolean stale;

    /** 最近一次成功采集的时间。 */
    private Long lastSuccessfulCollectionAt;

    /**
     * 根据点位配置和缓存值构建实时表格紧凑负载。
     *
     * @param point 点位配置
     * @param deviceId 本次查询上下文中的本地设备唯一标识
     * @param cachedValue 缓存中的实时值
     * @return 实时表格紧凑负载
     */
    public static CompactRealtimePointPayload from(DataPoint point, String deviceId, Object cachedValue) {
        CompactRealtimePointPayload payload = CompactRealtimePointPayload.builder()
                .pointId(point.getPointId())
                .pointCode(point.getPointCode())
                .pointName(point.getPointName())
                .deviceId(deviceId)
                .dataType(point.getDataType())
                .address(point.getAddress())
                .readWrite(point.getReadWrite())
                .scalingFactor(point.getScalingFactor())
                .unit(point.getUnit())
                .status(point.getStatus())
                .build();
        payload.applyCachedValue(cachedValue);
        return payload;
    }

    /**
     * 填充缓存值和表格需要的数据质量摘要。
     *
     * @param cachedValue 缓存中的实时值
     */
    public void applyCachedValue(Object cachedValue) {
        if (cachedValue instanceof ProcessResult processResult) {
            Map<String, Object> metadata = processResult.getMetadata();
            this.value = processResult.getFinalValue();
            this.quality = processResult.getQuality();
            this.qualityDescription = processResult.getQualityDescription();
            this.qualityLevel = processResult.getQualityLevel();
            this.qualityAcceptable = processResult.isQualityAcceptable();
            this.qualityAvailable = true;
            this.processSuccess = processResult.isSuccess();
            this.processingTime = processResult.getProcessingTime();
            this.lastUpdateTime = metadata == null ? null : metadata.get(ProcessResultMetadataKeys.COLLECT_TIME);
            this.realtimeStatus = processResult.isSuccess()
                    ? (processResult.isQualityAcceptable() ? "GOOD" : "COLLECT_ERROR") : "PROCESS_ERROR";
            this.errorMessage = processResult.getMessage();
            this.stale = false;
            Object collectTime = this.lastUpdateTime;
            if (processResult.isSuccess() && collectTime instanceof Number number) {
                this.lastSuccessfulCollectionAt = number.longValue();
            }
            return;
        }
        this.value = cachedValue;
        this.qualityAvailable = false;
        this.realtimeStatus = cachedValue == null ? "NO_VALUE" : "UNASSESSED";
        this.stale = false;
    }
}
