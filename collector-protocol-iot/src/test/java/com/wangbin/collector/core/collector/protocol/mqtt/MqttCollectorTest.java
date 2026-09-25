package com.wangbin.collector.core.collector.protocol.mqtt;

import com.wangbin.collector.common.domain.entity.DataPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttCollectorTest {

    @Test
    void batchReadSkipsPointsWithoutReceivedValues() {
        MqttCollector collector = new MqttCollector();
        DataPoint point = new DataPoint();
        point.setPointId("mqtt-current-temp");

        assertTrue(collector.doReadPoints(List.of(point)).isEmpty());
    }
}
