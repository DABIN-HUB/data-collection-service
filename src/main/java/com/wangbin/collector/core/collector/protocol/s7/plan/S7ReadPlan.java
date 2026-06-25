package com.wangbin.collector.core.collector.protocol.s7.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class S7ReadPlan {

    private final String segmentKey;
    private final String area;
    private final Integer dbNumber;
    private final int startOffset;
    private final int endOffsetExclusive;
    private final int estimatedByteSpan;
    private final boolean blockOptimizable;
    private final String blockReadAddress;
    private final List<S7ReadPlanItem> items;
    private final List<DataPoint> points;

    public S7ReadPlan(String segmentKey,
                      String area,
                      Integer dbNumber,
                      int startOffset,
                      int endOffsetExclusive,
                      boolean blockOptimizable,
                      String blockReadAddress,
                      List<S7ReadPlanItem> items) {
        this.segmentKey = segmentKey;
        this.area = area;
        this.dbNumber = dbNumber;
        this.startOffset = startOffset;
        this.endOffsetExclusive = endOffsetExclusive;
        this.estimatedByteSpan = Math.max(0, endOffsetExclusive - startOffset);
        this.blockOptimizable = blockOptimizable;
        this.blockReadAddress = blockReadAddress;

        List<S7ReadPlanItem> safeItems = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.items = safeItems;

        List<DataPoint> orderedPoints = new ArrayList<>(safeItems.size());
        for (S7ReadPlanItem item : safeItems) {
            if (item != null && item.getPoint() != null) {
                orderedPoints.add(item.getPoint());
            }
        }
        this.points = Collections.unmodifiableList(orderedPoints);
    }

    public String getSegmentKey() {
        return segmentKey;
    }

    public String getArea() {
        return area;
    }

    public Integer getDbNumber() {
        return dbNumber;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffsetExclusive() {
        return endOffsetExclusive;
    }

    public int getEstimatedByteSpan() {
        return estimatedByteSpan;
    }

    public boolean isBlockOptimizable() {
        return blockOptimizable;
    }

    public String getBlockReadAddress() {
        return blockReadAddress;
    }

    public List<S7ReadPlanItem> getItems() {
        return items;
    }

    public List<DataPoint> getPoints() {
        return points;
    }

    public int getPointCount() {
        return points.size();
    }

    public boolean canUseBlockRead() {
        return blockOptimizable && blockReadAddress != null && points.size() > 1;
    }
}

