package com.wangbin.collector.soak;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryLiveWriteThroughputITTest {

    @Test
    void measurementDbRequestRateMustUseWindowDelta() {
        assertEquals(20D, HistoryLiveWriteThroughputIT.measurementRate(1_000L, 1_100L, 5D));
    }
}
