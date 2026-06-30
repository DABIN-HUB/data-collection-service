package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import com.wangbin.collector.common.domain.entity.DataPoint;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BacnetReadPointPlan {
    DataPoint point;
    BacnetAddress address;
}
