package com.wangbin.collector.core.collector.protocol.mc.plan;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;
import com.wangbin.collector.core.collector.protocol.mc.util.McAddressParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class McWritePlanBuilder {

    public List<McWritePlan> build(Map<DataPoint, Object> pointValues,
                                   int maxWordsPerRequest,
                                   int maxBitsPerRequest) {
        if (pointValues == null || pointValues.isEmpty()) {
            return List.of();
        }

        int safeMaxWords = Math.max(1, maxWordsPerRequest);
        int safeMaxBits = Math.max(1, maxBitsPerRequest);
        List<PlanCandidate> candidates = new ArrayList<>();
        for (Map.Entry<DataPoint, Object> entry : pointValues.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null) {
                continue;
            }
            McAddress address = McAddressParser.parse(point);
            candidates.add(toCandidate(point, address));
        }

        candidates.sort(Comparator
                .comparing((PlanCandidate candidate) -> candidate.deviceCode().getSymbol())
                .thenComparingInt(PlanCandidate::startDeviceNumber)
                .thenComparing(candidate -> candidate.point().getPointId() == null ? "" : candidate.point().getPointId()));

        List<McWritePlan> plans = new ArrayList<>();
        List<PlanCandidate> currentCandidates = new ArrayList<>();
        McDeviceCode currentDeviceCode = null;
        boolean currentBitUnit = false;
        int currentStart = 0;
        int currentEnd = 0;

        for (PlanCandidate candidate : candidates) {
            int maxUnits = candidate.bitUnit() ? safeMaxBits : safeMaxWords;
            int nextEnd = currentCandidates.isEmpty()
                    ? candidate.endDeviceNumberExclusive()
                    : Math.max(currentEnd, candidate.endDeviceNumberExclusive());
            boolean startNewPlan = currentCandidates.isEmpty()
                    || candidate.deviceCode() != currentDeviceCode
                    || candidate.startDeviceNumber() < currentEnd
                    || candidate.startDeviceNumber() > currentEnd
                    || nextEnd - currentStart > maxUnits;

            if (startNewPlan) {
                if (!currentCandidates.isEmpty()) {
                    plans.add(buildPlan(currentDeviceCode, currentBitUnit, currentStart, currentEnd, currentCandidates));
                }
                currentCandidates = new ArrayList<>();
                currentDeviceCode = candidate.deviceCode();
                currentBitUnit = candidate.bitUnit();
                currentStart = candidate.startDeviceNumber();
                currentEnd = candidate.endDeviceNumberExclusive();
            } else {
                currentEnd = nextEnd;
            }
            currentCandidates.add(candidate);
        }

        if (!currentCandidates.isEmpty()) {
            plans.add(buildPlan(currentDeviceCode, currentBitUnit, currentStart, currentEnd, currentCandidates));
        }
        return plans;
    }

    private McWritePlan buildPlan(McDeviceCode deviceCode,
                                  boolean bitUnit,
                                  int startDeviceNumber,
                                  int endDeviceNumberExclusive,
                                  List<PlanCandidate> candidates) {
        List<McWritePlanItem> items = new ArrayList<>(candidates.size());
        for (PlanCandidate candidate : candidates) {
            int unitOffset = candidate.startDeviceNumber() - startDeviceNumber;
            int payloadByteOffset = bitUnit ? unitOffset / 2 : unitOffset * 2;
            int payloadByteLength = bitUnit
                    ? Math.max(1, (candidate.unitCount() + 1) / 2)
                    : candidate.unitCount() * 2;
            items.add(new McWritePlanItem(
                    candidate.point(),
                    candidate.address(),
                    unitOffset,
                    candidate.unitCount(),
                    payloadByteOffset,
                    payloadByteLength
            ));
        }
        return new McWritePlan(
                buildSegmentKey(deviceCode, startDeviceNumber, endDeviceNumberExclusive),
                deviceCode,
                bitUnit,
                startDeviceNumber,
                endDeviceNumberExclusive,
                items
        );
    }

    private String buildSegmentKey(McDeviceCode deviceCode,
                                   int startDeviceNumber,
                                   int endDeviceNumberExclusive) {
        int radix = deviceCode.getRadix();
        String start = Integer.toString(startDeviceNumber, radix).toUpperCase(Locale.ROOT);
        String endExclusive = Integer.toString(endDeviceNumberExclusive, radix).toUpperCase(Locale.ROOT);
        return deviceCode.getSymbol() + ":" + start + "-" + endExclusive;
    }

    private PlanCandidate toCandidate(DataPoint point, McAddress address) {
        int startDeviceNumber = address.getDeviceNumber();
        int unitCount = address.getReadUnitCount();
        return new PlanCandidate(
                point,
                address,
                address.getDeviceCode(),
                address.isBitDevice(),
                startDeviceNumber,
                startDeviceNumber + unitCount,
                unitCount
        );
    }

    private record PlanCandidate(DataPoint point,
                                 McAddress address,
                                 McDeviceCode deviceCode,
                                 boolean bitUnit,
                                 int startDeviceNumber,
                                 int endDeviceNumberExclusive,
                                 int unitCount) {
    }
}
