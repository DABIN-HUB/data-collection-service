package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;

import java.util.List;

/**
 * 定义当前模块的业务契约。
 */
public interface ReadPlanCapable {

    /**
     * 执行当前业务逻辑。
     */
    void rebuildReadPlans(String deviceId, List<DataPoint> points);
}
