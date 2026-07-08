package com.wangbin.collector.core.cloud.aggregation;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.report.model.ReportData;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 批量属性包组装器，用于网关一次上报多个子设备的横向属性快照。
 */
@Component
public class CloudPackReportAssembler {

    public ReportData assemble(CloudDeviceIdentity gatewayIdentity,
                               String rawGatewayDeviceId,
                               List<CloudAggregateSnapshot> snapshots) {
        if (gatewayIdentity == null || !gatewayIdentity.valid() || snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        ReportData data = new ReportData();
        data.setDeviceId(gatewayIdentity.deviceName());
        data.setPointCode("property-pack");
        data.setPointId("property-pack");
        data.setPointName("property-pack");
        data.setTimestamp(System.currentTimeMillis());
        data.setMethod(MessageConstant.MESSAGE_TYPE_PROPERTY_PACK_POST);
        data.addMetadata("productKey", gatewayIdentity.productKey());
        data.addMetadata("rawDeviceId", rawGatewayDeviceId);
        data.addMetadata(MessageConstant.FIELD_MESSAGE_ID, UUID.randomUUID().toString());

        List<Map<String, Object>> pack = new ArrayList<>();
        for (CloudAggregateSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.identity() == null || !snapshot.identity().valid()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productKey", snapshot.identity().productKey());
            item.put("deviceName", snapshot.identity().deviceName());
            item.put("properties", snapshot.properties());
            item.put("timestamp", System.currentTimeMillis());
            if (!snapshot.propertyTs().isEmpty()) {
                item.put("propertyTs", snapshot.propertyTs());
            }
            if (!snapshot.propertyQuality().isEmpty()) {
                item.put("quality", snapshot.propertyQuality());
            }
            if (StringUtils.hasText(snapshot.aggregateTargetId())) {
                item.put("aggregateTargetId", snapshot.aggregateTargetId());
            }
            pack.add(item);
        }
        data.addMetadata("propertyPack", pack);
        data.addMetadata("packSize", pack.size());
        return data;
    }
}
