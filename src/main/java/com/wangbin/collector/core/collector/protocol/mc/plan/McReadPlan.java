package com.wangbin.collector.core.collector.protocol.mc.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class McReadPlan {

    private final String segmentKey;
    private final McDeviceCode deviceCode;
    private final boolean bitUnit;
    private final int startDeviceNumber;
    private final int endDeviceNumberExclusive;
    private final int totalUnitCount;
    private final int expectedPayloadLength;
    private final List<McReadPlanItem> items;
    private final List<DataPoint> points;

    public McReadPlan(String segmentKey,
                      McDeviceCode deviceCode,
                      boolean bitUnit,
                      int startDeviceNumber,
                      int endDeviceNumberExclusive,
                      List<McReadPlanItem> items) {
        this.segmentKey = segmentKey;
        this.deviceCode = deviceCode;
        this.bitUnit = bitUnit;
        this.startDeviceNumber = startDeviceNumber;
        this.endDeviceNumberExclusive = endDeviceNumberExclusive;
        this.totalUnitCount = Math.max(0, endDeviceNumberExclusive - startDeviceNumber);
        this.expectedPayloadLength = bitUnit
                ? Math.max(1, (totalUnitCount + 1) / 2)
                : totalUnitCount * 2;

        List<McReadPlanItem> safeItems = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.items = safeItems;

        List<DataPoint> orderedPoints = new ArrayList<>(safeItems.size());
        for (McReadPlanItem item : safeItems) {
            if (item != null && item.getPoint() != null) {
                orderedPoints.add(item.getPoint());
            }
        }
        this.points = Collections.unmodifiableList(orderedPoints);
    }

    public String getSegmentKey() {
        return segmentKey;
    }

    public McDeviceCode getDeviceCode() {
        return deviceCode;
    }

    public boolean isBitUnit() {
        return bitUnit;
    }

    public int getStartDeviceNumber() {
        return startDeviceNumber;
    }

    public int getEndDeviceNumberExclusive() {
        return endDeviceNumberExclusive;
    }

    public int getTotalUnitCount() {
        return totalUnitCount;
    }

    public int getExpectedPayloadLength() {
        return expectedPayloadLength;
    }

    public List<McReadPlanItem> getItems() {
        return items;
    }

    public List<DataPoint> getPoints() {
        return points;
    }

    public int getPointCount() {
        return points.size();
    }
}