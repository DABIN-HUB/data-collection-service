package com.wangbin.collector.core.collector.protocol.base;

import com.wangbin.collector.common.domain.entity.DataPoint;

import java.util.List;

public interface ReadPlanCapable {

    void rebuildReadPlans(String deviceId, List<DataPoint> points);
}
