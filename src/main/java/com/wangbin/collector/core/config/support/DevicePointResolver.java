package com.wangbin.collector.core.config.support;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.config.manager.ConfigManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves point references consistently for manual control and downlink use cases.
 */
@Component
@RequiredArgsConstructor
public class DevicePointResolver {

    private final ConfigManager configManager;

    public Optional<DataPoint> resolve(String deviceId, String pointRef) {
        return resolve(configManager.getDataPoints(deviceId), pointRef);
    }

    public Optional<DataPoint> resolve(List<DataPoint> points, String pointRef) {
        if (points == null || points.isEmpty() || !StringUtils.hasText(pointRef)) {
            return Optional.empty();
        }
        String normalized = normalize(pointRef);
        return points.stream()
                .filter(point -> matches(point, normalized))
                .findFirst();
    }

    private boolean matches(DataPoint point, String normalizedRef) {
        return point != null
                && (normalizedRef.equals(normalize(point.getReportField()))
                || normalizedRef.equals(normalize(point.getPointAlias()))
                || normalizedRef.equals(normalize(point.getPointCode()))
                || normalizedRef.equals(normalize(point.getPointId()))
                || normalizedRef.equals(normalize(point.getPointName())));
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
