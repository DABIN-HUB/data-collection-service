package com.wangbin.collector.core.collector.protocol.fins.service;

import com.wangbin.collector.core.collector.protocol.fins.domain.FinsMemoryArea;
import lombok.Getter;

import java.util.List;

/**
 * 定义当前模块的业务组件。
 */
@Getter
public class FinsReadPlan {

    private final String segmentKey;
    private final FinsMemoryArea memoryArea;
    private final boolean bitUnit;
    private final int startWord;
    private final int endWordExclusive;
    private final List<FinsReadPlanItem> items;

    /**
     * 创建当前组件实例。
     */
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

    /**
     * 执行当前业务逻辑。
     */
    public int unitCount() {
        return endWordExclusive - startWord;
    }
}