package com.wangbin.collector.core.collector.protocol.fins.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsMemoryArea;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
public class FinsWritePlan {

    private final String segmentKey;
    private final FinsMemoryArea memoryArea;
    private final int startWord;
    private final int endWordExclusive;
    private final int totalUnitCount;
    private final int payloadByteLength;
    private final List<FinsWritePlanItem> items;
    private final List<DataPoint> points;

    public FinsWritePlan(String segmentKey,
                         FinsMemoryArea memoryArea,
                         int startWord,
                         int endWordExclusive,
                         List<FinsWritePlanItem> items) {
        this.segmentKey = segmentKey;
        this.memoryArea = memoryArea;
        this.startWord = startWord;
        this.endWordExclusive = endWordExclusive;
        this.totalUnitCount = Math.max(0, endWordExclusive - startWord);
        this.payloadByteLength = totalUnitCount * 2;

        List<FinsWritePlanItem> safeItems = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.items = safeItems;

        List<DataPoint> orderedPoints = new ArrayList<>(safeItems.size());
        for (FinsWritePlanItem item : safeItems) {
            if (item != null && item.getPoint() != null) {
                orderedPoints.add(item.getPoint());
            }
        }
        this.points = Collections.unmodifiableList(orderedPoints);
    }

    public int getPointCount() {
        return points.size();
    }
}