package com.wangbin.collector.core.processor;

import com.wangbin.collector.core.alarm.AlarmStateTracker;
import com.wangbin.collector.core.alarm.InMemoryAlarmStateRepository;

/**
 * 数据质量处理器测试实例工厂，避免生产代码保留测试兜底构造器。
 */
public final class DataQualityProcessorTestSupport {

    private DataQualityProcessorTestSupport() {
    }

    /**
     * 创建不依赖外部告警服务的测试处理器。
     *
     * @return 数据质量处理器测试实例
     */
    public static DataQualityProcessor create() {
        return new DataQualityProcessor(null, new AlarmStateTracker(new InMemoryAlarmStateRepository()));
    }
}