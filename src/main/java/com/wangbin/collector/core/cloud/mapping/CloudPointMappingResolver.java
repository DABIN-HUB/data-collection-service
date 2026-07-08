package com.wangbin.collector.core.cloud.mapping;

import com.wangbin.collector.common.constant.MessageConstant;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.cloud.aggregation.CloudAggregationPolicy;
import com.wangbin.collector.core.cloud.aggregation.CloudPointBinding;
import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 解析点位的 cloudBindings 配置。
 */
@Component
public class CloudPointMappingResolver {

    public List<CloudPointBinding> resolve(DataPoint point, String defaultProductKey, String defaultDeviceName) {
        if (point == null) {
            return Collections.emptyList();
        }
        Object rawBindings = point.getAdditionalConfig("cloudBindings");
        List<Map<String, Object>> bindingMaps = toMaps(rawBindings);
        if (bindingMaps.isEmpty()) {
            return fallback(point, defaultProductKey, defaultDeviceName);
        }
        List<CloudPointBinding> result = new ArrayList<>();
        for (Map<String, Object> item : bindingMaps) {
            String productKey = firstText(item, "productKey", "pk");
            String deviceName = firstText(item, "deviceName", "dn");
            String field = firstText(item, "field", "identifier");
            if (!StringUtils.hasText(field)) {
                field = point.getReportField() != null ? point.getReportField() : point.getPointCode();
            }
            if (!StringUtils.hasText(productKey)) {
                productKey = defaultProductKey;
            }
            if (!StringUtils.hasText(deviceName)) {
                deviceName = defaultDeviceName;
            }
            if (!StringUtils.hasText(productKey) || !StringUtils.hasText(deviceName) || !StringUtils.hasText(field)) {
                continue;
            }
            result.add(new CloudPointBinding(
                    firstText(item, "aggregateTargetId", "targetId"),
                    CloudDeviceIdentity.of(productKey, deviceName),
                    field,
                    normalizeMessageType(firstText(item, "messageType", "method", "type")),
                    CloudAggregationPolicy.defaults()));
        }
        return result;
    }

    private List<CloudPointBinding> fallback(DataPoint point, String defaultProductKey, String defaultDeviceName) {
        String field = point.getReportField() != null ? point.getReportField() : point.getPointCode();
        if (!StringUtils.hasText(defaultProductKey) || !StringUtils.hasText(defaultDeviceName) || !StringUtils.hasText(field)) {
            return Collections.emptyList();
        }
        return List.of(new CloudPointBinding(
                defaultProductKey + "/" + defaultDeviceName,
                CloudDeviceIdentity.of(defaultProductKey, defaultDeviceName),
                field,
                MessageConstant.MESSAGE_TYPE_PROPERTY_POST,
                CloudAggregationPolicy.defaults()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMaps(Object raw) {
        if (raw instanceof Collection<?> collection) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : collection) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
            return result;
        }
        if (raw instanceof Map<?, ?> map) {
            return List.of((Map<String, Object>) map);
        }
        return Collections.emptyList();
    }

    private String firstText(Map<String, Object> map, String... fields) {
        if (map == null) {
            return null;
        }
        for (String field : fields) {
            Object value = map.get(field);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private String firstTextOrDefault(Map<String, Object> map, String field, String defaultValue) {
        String value = firstText(map, field);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String normalizeMessageType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return MessageConstant.MESSAGE_TYPE_PROPERTY_POST;
        }
        String normalized = raw.trim().toLowerCase().replace('-', '_');
        return switch (normalized) {
            case "property", "property_post", "thing.property.post" -> MessageConstant.MESSAGE_TYPE_PROPERTY_POST;
            case "event", "event_post", "thing.event.post" -> MessageConstant.MESSAGE_TYPE_EVENT_POST;
            case "state", "state_update", "thing.state.update" -> MessageConstant.MESSAGE_TYPE_STATE_UPDATE;
            case "ota_progress", "thing.ota.progress" -> MessageConstant.MESSAGE_TYPE_OTA_PROGRESS;
            case "pack", "property_pack", "thing.event.property.pack.post" -> MessageConstant.MESSAGE_TYPE_PROPERTY_PACK_POST;
            default -> raw.trim();
        };
    }
}
