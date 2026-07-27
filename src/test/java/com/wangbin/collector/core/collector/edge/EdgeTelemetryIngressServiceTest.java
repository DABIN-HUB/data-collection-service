package com.wangbin.collector.core.collector.edge;

import com.wangbin.collector.api.controller.dto.EdgeTelemetryBatchRequest;
import com.wangbin.collector.api.controller.dto.EdgeTelemetryItem;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.ingress.TelemetryIngressService;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EdgeTelemetryIngressServiceTest {

    @Test
    void shouldAcceptOnlyIncreasingSequenceAndKnownPoint() {
        DevicePointResolver pointResolver = mock(DevicePointResolver.class);
        TelemetryIngressService telemetryIngressService = mock(TelemetryIngressService.class);
        EdgeTelemetryIngressService service = new EdgeTelemetryIngressService(pointResolver, telemetryIngressService);
        DataPoint point = new DataPoint();
        point.setPointId("p1");
        point.setDeviceId("dev-1");
        when(pointResolver.resolve("dev-1", "temperature")).thenReturn(Optional.of(point));

        EdgeTelemetryBatchRequest first = request(1L);
        EdgeTelemetryBatchRequest duplicate = request(1L);

        EdgeTelemetryIngressResult accepted = service.ingest(first);
        EdgeTelemetryIngressResult ignored = service.ingest(duplicate);

        assertEquals(1, accepted.acceptedCount());
        assertEquals(0, ignored.acceptedCount());
        assertEquals(1, ignored.duplicateCount());
        verify(telemetryIngressService).appendRaw(
                eq("dev-1"), eq(point), eq(12.5d), eq(100), any(Long.class), eq("EDGE_PROFINET"));
    }

    private EdgeTelemetryBatchRequest request(long sequence) {
        EdgeTelemetryItem item = new EdgeTelemetryItem(
                "dev-1", "temperature", 12.5d, 100, System.currentTimeMillis(), sequence);
        return new EdgeTelemetryBatchRequest(
                "gateway-1", EdgeProtocolType.PROFINET, "v1", List.of(item));
    }
}
