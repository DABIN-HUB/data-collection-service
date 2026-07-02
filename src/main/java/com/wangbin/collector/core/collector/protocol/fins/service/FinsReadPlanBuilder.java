package com.wangbin.collector.core.collector.protocol.fins.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsMemoryArea;
import com.wangbin.collector.core.collector.protocol.fins.util.FinsAddressParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class FinsReadPlanBuilder {

    public List<FinsReadPlan> build(List<DataPoint> points,
                                    int maxWordsPerRequest,
                                    int maxBitsPerRequest) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }

        int safeMaxWords = Math.max(1, maxWordsPerRequest);
        int safeMaxBits = Math.max(1, maxBitsPerRequest);
        List<PlanCandidate> candidates = new ArrayList<>();
        for (DataPoint point : points) {
            if (point == null || !point.isEnabled()) {
                continue;
            }
            FinsAddress address = FinsAddressParser.parse(point);
            candidates.add(toCandidate(point, address));
        }
        candidates.sort(Comparator
                .comparing((PlanCandidate candidate) -> candidate.memoryArea().name())
                .thenComparing(PlanCandidate::bitUnit)
                .thenComparingInt(PlanCandidate::startWord)
                .thenComparing(candidate -> candidate.point().getPointId() == null ? "" : candidate.point().getPointId()));

        List<FinsReadPlan> plans = new ArrayList<>();
        List<PlanCandidate> current = new ArrayList<>();
        FinsMemoryArea currentArea = null;
        boolean currentBitUnit = false;
        int currentStart = 0;
        int currentEnd = 0;

        for (PlanCandidate candidate : candidates) {
            int maxUnits = candidate.bitUnit() ? safeMaxBits : safeMaxWords;
            int nextEnd = current.isEmpty() ? candidate.endWordExclusive() : Math.max(currentEnd, candidate.endWordExclusive());
            boolean startNew = current.isEmpty()
                    || candidate.memoryArea() != currentArea
                    || candidate.bitUnit() != currentBitUnit
                    || candidate.startWord() > currentEnd
                    || nextEnd - currentStart > maxUnits;
            if (startNew) {
                if (!current.isEmpty()) {
                    plans.add(buildPlan(currentArea, currentBitUnit, currentStart, currentEnd, current));
                }
                current = new ArrayList<>();
                currentArea = candidate.memoryArea();
                currentBitUnit = candidate.bitUnit();
                currentStart = candidate.startWord();
                currentEnd = candidate.endWordExclusive();
            } else {
                currentEnd = nextEnd;
            }
            current.add(candidate);
        }

        if (!current.isEmpty()) {
            plans.add(buildPlan(currentArea, currentBitUnit, currentStart, currentEnd, current));
        }
        return plans;
    }

    private FinsReadPlan buildPlan(FinsMemoryArea memoryArea,
                                   boolean bitUnit,
                                   int startWord,
                                   int endWordExclusive,
                                   List<PlanCandidate> candidates) {
        List<FinsReadPlanItem> items = new ArrayList<>(candidates.size());
        for (PlanCandidate candidate : candidates) {
            int unitOffset = candidate.startWord() - startWord;
            int payloadByteOffset = bitUnit ? unitOffset : unitOffset * 2;
            int payloadByteLength = bitUnit ? candidate.unitCount() : candidate.unitCount() * 2;
            items.add(new FinsReadPlanItem(
                    candidate.point(),
                    candidate.address(),
                    unitOffset,
                    candidate.unitCount(),
                    payloadByteOffset,
                    payloadByteLength
            ));
        }
        String key = memoryArea.name() + ":" + Integer.toString(startWord, 10).toUpperCase(Locale.ROOT)
                + "-" + Integer.toString(endWordExclusive, 10).toUpperCase(Locale.ROOT)
                + (bitUnit ? ":BIT" : ":WORD");
        return new FinsReadPlan(key, memoryArea, bitUnit, startWord, endWordExclusive, List.copyOf(items));
    }

    private PlanCandidate toCandidate(DataPoint point, FinsAddress address) {
        int startWord = address.getWordAddress();
        int unitCount = address.readUnitCount();
        return new PlanCandidate(
                point,
                address,
                address.getMemoryArea(),
                address.isBitUnit(),
                startWord,
                startWord + unitCount,
                unitCount
        );
    }

    private record PlanCandidate(DataPoint point,
                                 FinsAddress address,
                                 FinsMemoryArea memoryArea,
                                 boolean bitUnit,
                                 int startWord,
                                 int endWordExclusive,
                                 int unitCount) {
    }
}