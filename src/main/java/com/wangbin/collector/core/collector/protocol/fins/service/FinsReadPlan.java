package com.wangbin.collector.core.collector.protocol.fins.service;

import com.wangbin.collector.core.collector.protocol.fins.domain.FinsMemoryArea;
import lombok.Getter;

import java.util.List;

@Getter
public class FinsReadPlan {

    private final String segmentKey;
    private final FinsMemoryArea memoryArea;
    private final boolean bitUnit;
    private final int startWord;
    private final int endWordExclusive;
    private final List<FinsReadPlanItem> items;

    public FinsReadPlan(String segmentKey,
                        FinsMemoryArea memoryArea,
                        boolean bitUnit,
                        int startWord,
                        int endWordExclusive,
                        List<FinsReadPlanItem> items) {
        this.segmentKey = segmentKey;
        this.memoryArea = memoryArea;
        this.bitUnit = bitUnit;
        this.startWord = startWord;
        this.endWordExclusive = endWordExclusive;
        this.items = items;
    }

    public int unitCount() {
        return endWordExclusive - startWord;
    }
}