package com.wangbin.collector.core.cloud.aggregation;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.enums.QualityEnum;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.report.model.ReportData;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 纵向点位数据转横向云端属性快照。
 */
@Service
public class CloudAggregationService {

    public ReportData toReportData(String localDeviceId,
                                   DataPoint point,
                                   ProcessResult processResult,
                                   CloudPointBinding binding) {
        if (point == null || processResult == null || binding == null || binding.identity() == null) {
            return null;
        }
        Object value = processResult.getFinalValue();
        if (value == null) {
            value = processResult.getProcessedValue();
        }
        if (value == null) {
            value = processResult.getRawValue();
        }
        if (value == null) {
            return null;
        }

        ReportData data = new ReportData();
        data.setDeviceId(binding.identity().deviceName());
        data.setMethod(binding.messageType());
        data.setTimestamp(System.currentTimeMillis());
        ReportData.applyPointInfo(data, point);
        data.addMetadata("productKey", binding.identity().productKey());
        data.addMetadata("rawDeviceId", localDeviceId);
        data.addMetadata("aggregateTargetId", binding.targetKey());
        data.addMetadata("sourcePointId", point.getPointId());
        data.addMetadata("sourcePointCode", point.getPointCode());
        if (processResult.getMetadata() != null && !processResult.getMetadata().isEmpty()) {
            data.getMetadata().putAll(processResult.getMetadata());
        }

        QualityEnum quality = QualityEnum.fromCode(processResult.getQuality());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("localDeviceId", localDeviceId);
        source.put("pointId", point.getPointId());
        source.put("pointCode", point.getPointCode());
        data.addProperty(
                binding.field(),
                value,
                System.currentTimeMillis(),
                quality != null ? quality.getText() : QualityEnum.GOOD.getText(),
                source);
        return data;
    }

    public CloudAggregateSnapshot snapshotOf(ReportData data) {
        if (data == null) {
            return null;
        }
        String productKey = Optional.ofNullable(data.getMetadata().get("productKey"))
                .map(String::valueOf)
                .orElse("");
        String aggregateTargetId = Optional.ofNullable(data.getMetadata().get("aggregateTargetId"))
                .map(String::valueOf)
                .orElse(data.getDeviceId());
        return new CloudAggregateSnapshot(
                aggregateTargetId,
                com.wangbin.collector.core.cloud.model.CloudDeviceIdentity.of(productKey, data.getDeviceId()),
                data.getProperties(),
                data.getPropertyTs(),
                data.getPropertyQuality(),
                data.getPropertyMetadata());
    }
}
