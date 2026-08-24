package com.wangbin.collector.core.processor;

import com.wangbin.collector.common.domain.alert.AlertNotification;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.alarm.AlarmStateTracker;
import com.wangbin.collector.core.alarm.InMemoryAlarmStateRepository;
import com.wangbin.collector.core.port.AlertPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DataQualityProcessorAlertPublisherTest {

    @Test
    void alarmTransitionShouldPublishAlertThroughPort() {
        AlertPublisher alertPublisher = mock(AlertPublisher.class);
        DataQualityProcessor processor = new DataQualityProcessor(
                alertPublisher,
                new AlarmStateTracker(new InMemoryAlarmStateRepository()));
        DataPoint point = point();
        ProcessContext context = new ProcessContext();
        context.setProcessTime(123456789L);

        processor.process(context, point, 12.5d);

        ArgumentCaptor<AlertNotification> captor = ArgumentCaptor.forClass(AlertNotification.class);
        verify(alertPublisher).notifyAlert(captor.capture(), eq(false));
        AlertNotification notification = captor.getValue();
        assertEquals("dev-1", notification.getDeviceId());
        assertEquals("device-1", notification.getDeviceName());
        assertEquals("p1", notification.getPointId());
        assertEquals("temperature", notification.getPointCode());
        assertEquals("rule-1", notification.getRuleId());
        assertEquals("high-temperature", notification.getRuleName());
        assertEquals("WARNING", notification.getLevel());
        assertEquals("ALARM", notification.getEventType());
        assertNotNull(notification.getEventId());
        assertEquals(12.5d, notification.getValue());
        assertEquals("℃", notification.getUnit());
        assertEquals(123456789L, notification.getTimestamp());
    }

    private DataPoint point() {
        DataPoint point = new DataPoint();
        point.setDeviceId("dev-1");
        point.setDeviceName("device-1");
        point.setPointId("p1");
        point.setPointCode("temperature");
        point.setPointName("温度");
        point.setUnit("℃");
        point.setAlarmEnabled(1);
        point.setAlarmRule("""
                [{
                  "ruleId": "rule-1",
                  "ruleName": "high-temperature",
                  "operator": ">",
                  "threshold": 10,
                  "duration": 0,
                  "level": "WARNING",
                  "description": "温度过高",
                  "enabled": true
                }]
                """);
        return point;
    }
}
