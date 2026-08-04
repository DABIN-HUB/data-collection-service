package com.wangbin.collector.monitor.metrics;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.Map;

/**
 * 控制台运行状态中的单个组件状态。
 */
@Data
@Builder
public class RuntimeComponentStatus {

    /**
     * 组件编码。
     */
    private final String code;

    /**
     * 组件中文名称。
     */
    private final String name;

    /**
     * 组件健康级别。
     */
    private final RuntimeHealthLevel level;

    /**
     * 组件状态说明。
     */
    private final String message;

    /**
     * 组件关键明细。
     */
    @Builder.Default
    private final Map<String, Object> details = Collections.emptyMap();
}
