package com.wangbin.collector.core.cloud.aggregation;


import com.wangbin.collector.common.constant.CommonMapKeys;
import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.report.model.ReportData;
import org.springframework.stereotype.Component;

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

    /**
     * 执行当前业务逻辑。
     */
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
        data.addMetadata(CommonMapKeys.RAW_DEVICE_ID, rawGatewayDeviceId);
        data.addMetadata(MessageConstant.FIELD_MESSAGE_ID, UUID.randomUUID().toString());

        Map<String, Object> pack = new LinkedHashMap<>();
        Map<String, Object> gatewayProperties = new LinkedHashMap<>();
        Map<String, Object> gatewayEvents = new LinkedHashMap<>();
        List<Map<String, Object>> subDevices = new ArrayList<>();
        for (CloudAggregateSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.identity() == null || !snapshot.identity().valid()) {
                continue;
            }
            if (sameIdentity(gatewayIdentity, snapshot.identity())) {
                gatewayProperties.putAll(snapshot.properties());
                gatewayEvents.putAll(snapshot.events());
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("identity", Map.of(
                    "productKey", snapshot.identity().productKey(),
                    "deviceName", snapshot.identity().deviceName()));
            item.put("properties", snapshot.properties());
            item.put("events", snapshot.events());
            subDevices.add(item);
        }
        pack.put("properties", gatewayProperties);
        pack.put("events", gatewayEvents);
        pack.put("subDevices", subDevices);
        data.addMetadata("propertyPack", pack);
        data.addMetadata("packSize", subDevices.size() + (gatewayProperties.isEmpty() ? 0 : 1));
        return data;
    }

    /**
     * 执行当前业务逻辑。
     */
    private boolean sameIdentity(CloudDeviceIdentity left, CloudDeviceIdentity right) {
        if (left == null || right == null || !left.valid() || !right.valid()) {
            return false;
        }
        return left.productKey().equals(right.productKey())
                && left.deviceName().equals(right.deviceName());
    }
}
