package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * 定义当前模块的业务组件。
 */
@Value
@Builder
public class BacnetReadPropertyMultiplePlan {

    @Singular
    List<ReadGroup> groups;
    @Singular
    List<BacnetReadPointPlan> pointPlans;

    /**
     * 定义当前模块的业务组件。
     */
    @Value
    @Builder
    public static class ReadGroup {
        BacnetObjectType objectType;
        int objectInstance;
        @Singular
        List<BacnetReadPointPlan> pointPlans;
    }
}
