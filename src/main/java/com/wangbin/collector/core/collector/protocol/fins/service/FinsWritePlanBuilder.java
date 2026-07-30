package com.wangbin.collector.core.collector.protocol.fins.service;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsAddress;
import com.wangbin.collector.core.collector.protocol.fins.domain.FinsMemoryArea;
import com.wangbin.collector.core.collector.protocol.fins.util.FinsAddressParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FinsWritePlanBuilder {

    public List<FinsWritePlan> build(Map<DataPoint, Object> pointValues,
                                     int maxWordsPerRequest) {
        if (pointValues == null || pointValues.isEmpty()) {
            return List.of();
        }

        int safeMaxWords = Math.max(1, maxWordsPerRequest);
        List<PlanCandidate> candidates = new ArrayList<>();
        for (Map.Entry<DataPoint, Object> entry : pointValues.entrySet()) {
            DataPoint point = entry.getKey();
            if (point == null) {
                continue;
            }
            FinsAddress address = FinsAddressParser.parse(point);
            if (address.isBitUnit()) {
                continue;
            }
            candidates.add(toCandidate(point, address));
        }

        candidates.sort(Comparator
                .comparing((PlanCandidate candidate) -> candidate.memoryArea().name())
                .thenComparingInt(PlanCandidate::startWord)
                .thenComparing(candidate -> candidate.point().getPointId() == null ? "" : candidate.point().getPointId()));

        List<FinsWritePlan> plans = new ArrayList<>();
        List<PlanCandidate> current = new ArrayList<>();
        FinsMemoryArea currentArea = null;
        int currentStart = 0;
        int currentEnd = 0;

        for (PlanCandidate candidate : candidates) {
            int nextEnd = current.isEmpty()
                    ? candidate.endWordExclusive()
                    : Math.max(currentEnd, candidate.endWordExclusive());
            boolean startNewPlan = current.isEmpty()
                    || candidate.memoryArea() != currentArea
                    || candidate.startWord() < currentEnd
                    || candidate.startWord() > currentEnd
                    || nextEnd - currentStart > safeMaxWords;
            if (startNewPlan) {
                if (!current.isEmpty()) {
                    plans.add(buildPlan(currentArea, currentStart, currentEnd, current));
                }
                current = new ArrayList<>();
                currentArea = candidate.memoryArea();
                currentStart = candidate.startWord();
                currentEnd = candidate.endWordExclusive();
            } else {
                currentEnd = nextEnd;
            }
            current.add(candidate);
        }

        if (!current.isEmpty()) {
            plans.add(buildPlan(currentArea, currentStart, currentEnd, current));
        }
        return plans;
    }

    private FinsWritePlan buildPlan(FinsMemoryArea memoryArea,
                                    int startWord,
                                    int endWordExclusive,
                                    List<PlanCandidate> candidates) {
        List<FinsWritePlanItem> items = new ArrayList<>(candidates.size());
        for (PlanCandidate candidate : candidates) {
            int unitOffset = candidate.startWord() - startWord;
            int payloadByteOffset = unitOffset * 2;
            int payloadByteLength = candidate.unitCount() * 2;
            items.add(new FinsWritePlanItem(
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
                + ":WORD";
        return new FinsWritePlan(key, memoryArea, startWord, endWordExclusive, items);
    }

    private PlanCandidate toCandidate(DataPoint point, FinsAddress address) {
        int startWord = address.getWordAddress();
        int unitCount = address.readUnitCount();
        return new PlanCandidate(
                point,
                address,
                address.getMemoryArea(),
                startWord,
                startWord + unitCount,
                unitCount
        );
    }

    private record PlanCandidate(DataPoint point,
                                 FinsAddress address,
                                 FinsMemoryArea memoryArea,
                                 int startWord,
                                 int endWordExclusive,
                                 int unitCount) {
    }
}