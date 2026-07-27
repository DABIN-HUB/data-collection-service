package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.Test;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AdaptiveCollectionUtilTest {

    @Test
    void shouldInitializeMissingAdaptiveCollectionConfigWithDefaults() {
        DataPoint point = new DataPoint();
        point.setPointId("p1");

        PointRuntimeStateSnapshot state = new PointRuntimeStateService().initialize("device-1", point);

        assertEquals(AdaptiveCollectionUtil.DEFAULT_BASE_COLLECTION_INTERVAL,
                point.getBaseCollectionInterval());
        assertEquals(AdaptiveCollectionUtil.DEFAULT_BASE_COLLECTION_INTERVAL,
                state.currentCollectionInterval());
        assertEquals(AdaptiveCollectionUtil.DEFAULT_MIN_COLLECTION_INTERVAL,
                point.getMinCollectionInterval());
        assertEquals(AdaptiveCollectionUtil.DEFAULT_MAX_COLLECTION_INTERVAL,
                point.getMaxCollectionInterval());
        assertEquals(AdaptiveCollectionUtil.DEFAULT_CHANGE_THRESHOLD,
                point.getPointChangeThreshold());
    }

    @Test
    void shouldClampBaseIntervalBetweenMinAndMax() {
        DataPoint point = new DataPoint();
        point.setPointId("p1");
        point.setBaseCollectionInterval(10_000L);
        point.setMinCollectionInterval(100L);
        point.setMaxCollectionInterval(1_000L);
        point.setPointChangeThreshold(2.0);

        PointRuntimeStateSnapshot state = new PointRuntimeStateService().initialize("device-1", point);

        assertEquals(1_000L, point.getBaseCollectionInterval());
        assertEquals(1_000L, state.currentCollectionInterval());
        assertEquals(100L, point.getMinCollectionInterval());
        assertEquals(1_000L, point.getMaxCollectionInterval());
        assertEquals(2.0, point.getPointChangeThreshold());
    }

    @Test
    void shouldKeepRuntimeValuesOutsideConfigurationEntity() {
        DataPoint point = new DataPoint();
        point.setPointId("p1");
        point.setBaseCollectionInterval(1_000L);
        point.setMinCollectionInterval(100L);
        point.setMaxCollectionInterval(10_000L);
        point.setPointChangeThreshold(1D);
        PointRuntimeStateService service = new PointRuntimeStateService();
        service.initialize("device-1", point);

        PointRuntimeStateSnapshot state = service.adjust("device-1", point, 10D, 0L);

        assertEquals(10D, state.lastValue());
        assertEquals(null, point.getLastValue());
        assertEquals(0, point.getStableCount());
        assertEquals(0L, point.getLastAdjustTime());
    }
}
