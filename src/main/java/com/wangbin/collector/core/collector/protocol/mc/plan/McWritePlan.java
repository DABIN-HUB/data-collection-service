package com.wangbin.collector.core.collector.protocol.mc.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 定义当前模块的业务组件。
 */
public class McWritePlan {

    private final String segmentKey;
    private final McDeviceCode deviceCode;
    private final boolean bitUnit;
    private final int startDeviceNumber;
    private final int endDeviceNumberExclusive;
    private final int totalUnitCount;
    private final int payloadByteLength;
    private final List<McWritePlanItem> items;
    private final List<DataPoint> points;

    /**
     * 创建当前组件实例。
     */
    public McWritePlan(String segmentKey,
                       McDeviceCode deviceCode,
                       boolean bitUnit,
                       int startDeviceNumber,
                       int endDeviceNumberExclusive,
                       List<McWritePlanItem> items) {
        this.segmentKey = segmentKey;
        this.deviceCode = deviceCode;
        this.bitUnit = bitUnit;
        this.startDeviceNumber = startDeviceNumber;
        this.endDeviceNumberExclusive = endDeviceNumberExclusive;
        this.totalUnitCount = Math.max(0, endDeviceNumberExclusive - startDeviceNumber);
        this.payloadByteLength = bitUnit
                ? Math.max(1, (totalUnitCount + 1) / 2)
                : totalUnitCount * 2;

        List<McWritePlanItem> safeItems = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.items = safeItems;

        List<DataPoint> orderedPoints = new ArrayList<>(safeItems.size());
        for (McWritePlanItem item : safeItems) {
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

    public int getPayloadByteLength() {
        return payloadByteLength;
    }

    public List<McWritePlanItem> getItems() {
        return items;
    }

    public List<DataPoint> getPoints() {
        return points;
    }

    public int getPointCount() {
        return points.size();
    }
}
