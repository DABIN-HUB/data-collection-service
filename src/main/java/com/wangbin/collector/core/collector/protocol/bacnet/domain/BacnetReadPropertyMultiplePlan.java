package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class BacnetReadPropertyMultiplePlan {

    @Singular
    List<ReadGroup> groups;
    @Singular
    List<BacnetReadPointPlan> pointPlans;

    @Value
    @Builder
    public static class ReadGroup {
        BacnetObjectType objectType;
        int objectInstance;
        @Singular
        List<BacnetReadPointPlan> pointPlans;
    }
}
