package com.wangbin.collector.core.collector.edge;

import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.ingress.TelemetryIngressService;
import com.wangbin.collector.core.config.support.DevicePointResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        EdgeTelemetryBatch first = request(1L);
        EdgeTelemetryBatch duplicate = request(1L);

        EdgeTelemetryIngressResult accepted = service.ingest(first);
        EdgeTelemetryIngressResult ignored = service.ingest(duplicate);

        assertEquals(1, accepted.acceptedCount());
        assertEquals(0, ignored.acceptedCount());
        assertEquals(1, ignored.duplicateCount());
        verify(telemetryIngressService).appendRaw(
                eq("dev-1"), eq(point), eq(12.5d), eq(100), eq(123456789L), eq("EDGE_PROFINET"));
    }

    private EdgeTelemetryBatch request(long sequence) {
        EdgeTelemetrySample item = new EdgeTelemetrySample(
                "dev-1", "temperature", 12.5d, 100, 123456789L, sequence);
        return new EdgeTelemetryBatch(
                "gateway-1", EdgeProtocolType.PROFINET, "v1", List.of(item));
    }
}
