package com.wangbin.collector.core.collector.protocol.bacnet.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BacnetReadPropertyMultiplePlanBuilder {

    private static final int DEFAULT_MAX_PROPERTIES_PER_REQUEST = 16;

    private BacnetReadPropertyMultiplePlanBuilder() {
    }

    public static List<BacnetReadPropertyMultiplePlan> build(List<BacnetReadPointPlan> pointPlans,
                                                              Integer maxPropertiesPerRequest) {
        List<BacnetReadPropertyMultiplePlan> plans = new ArrayList<>();
        if (pointPlans == null || pointPlans.isEmpty()) {
            return plans;
        }
        int maxPerRequest = maxPropertiesPerRequest != null && maxPropertiesPerRequest > 0
                ? maxPropertiesPerRequest
                : DEFAULT_MAX_PROPERTIES_PER_REQUEST;

        Map<String, List<BacnetReadPointPlan>> groupedByObject = new LinkedHashMap<>();
        for (BacnetReadPointPlan pointPlan : pointPlans) {
            if (pointPlan == null || pointPlan.getAddress() == null) {
                continue;
            }
            BacnetAddress address = pointPlan.getAddress();
            String key = address.getObjectTypeId() + ":" + address.getInstanceNumber();
            groupedByObject.computeIfAbsent(key, ignored -> new ArrayList<>()).add(pointPlan);
        }

        BacnetReadPropertyMultiplePlan.BacnetReadPropertyMultiplePlanBuilder currentPlanBuilder =
                BacnetReadPropertyMultiplePlan.builder();
        int currentPropertyCount = 0;

        for (List<BacnetReadPointPlan> groupPlans : groupedByObject.values()) {
            int offset = 0;
            while (offset < groupPlans.size()) {
                int remaining = groupPlans.size() - offset;
                int allowed = Math.min(maxPerRequest, remaining);
                List<BacnetReadPointPlan> chunk = new ArrayList<>(groupPlans.subList(offset, offset + allowed));
                offset += allowed;

                if (currentPropertyCount > 0 && currentPropertyCount + chunk.size() > maxPerRequest) {
                    plans.add(currentPlanBuilder.build());
                    currentPlanBuilder = BacnetReadPropertyMultiplePlan.builder();
                    currentPropertyCount = 0;
                }

                BacnetAddress firstAddress = chunk.get(0).getAddress();
                currentPlanBuilder.group(BacnetReadPropertyMultiplePlan.ReadGroup.builder()
                        .objectType(BacnetObjectType.fromId(firstAddress.getObjectTypeId()))
                        .objectInstance(firstAddress.getInstanceNumber())
                        .pointPlans(chunk)
                        .build());
                for (BacnetReadPointPlan plan : chunk) {
                    currentPlanBuilder.pointPlan(plan);
                }
                currentPropertyCount += chunk.size();

                if (currentPropertyCount >= maxPerRequest) {
                    plans.add(currentPlanBuilder.build());
                    currentPlanBuilder = BacnetReadPropertyMultiplePlan.builder();
                    currentPropertyCount = 0;
                }
            }
        }

        if (currentPropertyCount > 0) {
            plans.add(currentPlanBuilder.build());
        }
        return plans;
    }
}
