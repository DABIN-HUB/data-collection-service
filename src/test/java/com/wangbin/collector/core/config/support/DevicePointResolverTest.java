package com.wangbin.collector.core.config.support;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.config.manager.ConfigManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DevicePointResolverTest {

    private final DevicePointResolver resolver = new DevicePointResolver(mock(ConfigManager.class));

    @Test
    void shouldResolveByReportFieldAliasCodeIdAndName() {
        DataPoint point = point("p1", "temperature", "main temp");
        point.setPointAlias("tempAlias");
        point.setAdditionalConfig(Map.of("reportField", "temp_report"));
        List<DataPoint> points = List.of(point);

        assertEquals(point, resolver.resolve(points, "temp_report").orElseThrow());
        assertEquals(point, resolver.resolve(points, "tempAlias").orElseThrow());
        assertEquals(point, resolver.resolve(points, "temperature").orElseThrow());
        assertEquals(point, resolver.resolve(points, "p1").orElseThrow());
        assertEquals(point, resolver.resolve(points, "main temp").orElseThrow());
    }

    @Test
    void shouldReturnEmptyWhenPointCannotBeResolved() {
        assertTrue(resolver.resolve(List.of(point("p1", "temperature", "Temperature")), "humidity").isEmpty());
    }

    private DataPoint point(String pointId, String pointCode, String pointName) {
        DataPoint point = new DataPoint();
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointName);
        point.setReadWrite("RW");
        point.setStatus(1);
        return point;
    }
}
