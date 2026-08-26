package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.ConfigDiffResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 配置差异计算器。
 *
 * <p>只负责本地和远端设备、连接、点位配置的纯计算对比，不访问配置源或采集运行态。</p>
 */
@Component
public class ConfigDiffCalculator {

    /**
     * 构建本地与远端配置差异。
     */
    ConfigDiffResponse calculate(DeviceInfo localDevice,
                                 DeviceInfo remoteDevice,
                                 DeviceConnection localConn,
                                 DeviceConnection remoteConn,
                                 List<DataPoint> localPoints,
                                 List<DataPoint> remotePoints) {
        Map<String, DataPoint> localMap = indexPoints(localPoints);
        Map<String, DataPoint> remoteMap = indexPoints(remotePoints);

        Set<String> missing = new LinkedHashSet<>(remoteMap.keySet());
        missing.removeAll(localMap.keySet());

        Set<String> extra = new LinkedHashSet<>(localMap.keySet());
        extra.removeAll(remoteMap.keySet());

        List<String> changed = localMap.entrySet().stream()
                .filter(entry -> remoteMap.containsKey(entry.getKey())
                        && !Objects.equals(entry.getValue(), remoteMap.get(entry.getKey())))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        return ConfigDiffResponse.builder()
                .deviceChanged(!Objects.equals(localDevice, remoteDevice))
                .connectionChanged(!Objects.equals(localConn, remoteConn))
                .missingPointCodes(new ArrayList<>(missing))
                .extraPointCodes(new ArrayList<>(extra))
                .changedPointCodes(changed)
                .build();
    }

    /**
     * 按点位编码索引点位配置。
     *
     * @param points 点位配置列表
     * @return 点位编码到点位配置的映射
     */
    private Map<String, DataPoint> indexPoints(List<DataPoint> points) {
        if (CollectionUtils.isEmpty(points)) {
            return Collections.emptyMap();
        }
        return points.stream()
                .filter(Objects::nonNull)
                .filter(point -> StringUtils.hasText(point.getPointCode()))
                .collect(Collectors.toMap(DataPoint::getPointCode,
                        Function.identity(),
                        (left, right) -> left,
                        HashMap::new));
    }
}
