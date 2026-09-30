package com.wangbin.collector.core.config.validator;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.core.collector.protocol.s7.plan.S7ReadPlanBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 新增或编辑 S7 点位时复用运行期同一地址与读取跨度规则。
 */
@Component
public class S7ProtocolPointValidator implements ProtocolPointValidator {

    private final S7ReadPlanBuilder readPlanBuilder = new S7ReadPlanBuilder();

    @Override
    public boolean supports(DeviceInfo device) {
        if (device == null || device.getProtocolType() == null) {
            return false;
        }
        String protocol = device.getProtocolType().trim().toUpperCase(Locale.ROOT);
        return "SIEMENS_S7".equals(protocol) || "S7".equals(protocol);
    }

    @Override
    public void validate(List<DataPoint> points) {
        if (points == null) {
            return;
        }
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            try {
                if (!isEventSubscription(point)) {
                    readPlanBuilder.build(List.of(point), 64);
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("S7 点位配置无效, pointId=" + point.getPointId()
                        + ", address=" + point.getAddress() + ": " + exception.getMessage(), exception);
            }
        }
    }

    private boolean isEventSubscription(DataPoint point) {
        String mode = normalizeMode(point.getAdditionalConfig("subscriptionMode"));
        if (mode == null) {
            mode = normalizeMode(point.getAdditionalConfig("s7SubscriptionMode"));
        }
        if (mode != null) {
            if (!List.of("CYCLIC", "MODE", "SYS", "USR", "ALM").contains(mode)) {
                throw new IllegalArgumentException("Unsupported S7 subscription mode: " + mode);
            }
            return !"CYCLIC".equals(mode);
        }
        if (!"EVENT".equalsIgnoreCase(point.getCollectionMode())) {
            return false;
        }
        Object address = point.getAddress();
        if (address == null || address.toString().isBlank()) {
            address = point.getAdditionalConfig("subscriptionAddress");
        }
        if (address == null || address.toString().isBlank()) {
            address = point.getAdditionalConfig("s7SubscriptionAddress");
        }
        String inferredMode = normalizeMode(address);
        return inferredMode != null && List.of("MODE", "SYS", "USR", "ALM").contains(inferredMode);
    }

    private String normalizeMode(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String mode = value.toString().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (mode) {
            case "SYSTEM" -> "SYS";
            case "USER" -> "USR";
            case "ALARM" -> "ALM";
            default -> mode;
        };
    }
}
