package com.wangbin.collector.core.report.validator;

import com.wangbin.collector.common.domain.entity.DataPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验同一设备下启用上报的 reportField 唯一性。
 */
@Slf4j
@Component
public class FieldUniquenessValidator {

    /**
     * 校验业务条件和参数边界。
     */
    public void validate(String deviceId, List<DataPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        Map<String, List<DataPoint>> grouped = new HashMap<>();
        for (DataPoint point : points) {
            if (point == null) {
                continue;
            }
            String field = point.getReportField();
            if (field == null) {
                continue;
            }
            grouped.computeIfAbsent(field, k -> new java.util.ArrayList<>()).add(point);
        }
        for (Map.Entry<String, List<DataPoint>> entry : grouped.entrySet()) {
            List<DataPoint> list = entry.getValue();
            if (list.size() <= 1) {
                continue;
            }
            String field = entry.getKey();
            StringBuilder sb = new StringBuilder();
            for (DataPoint point : list) {
                point.setReportFieldConflict(true);
                sb.append(String.format("[点位=%s, 点位编码=%s, 上报字段=%s] ",
                        point.getPointId(), point.getPointCode(), point.getReportField()));
            }
            log.error("设备 {} 的报告字段 '{}' 存在冲突，以下点位被降级为原始字段上报：{}", deviceId, field, sb);
        }
    }
}
