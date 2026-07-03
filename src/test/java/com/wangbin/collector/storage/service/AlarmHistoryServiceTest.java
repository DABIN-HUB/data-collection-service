package com.wangbin.collector.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.monitor.alert.AlertNotification;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.AlarmRepository;
import com.wangbin.collector.storage.repository.DataRepository;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AlarmHistoryServiceTest {

    private final AlarmRepository alarmRepository = mock(AlarmRepository.class);
    private final DataRepository dataRepository = mock(DataRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Executor directExecutor = Runnable::run;

    @Test
    void saveAsyncShouldCreateSchemaAndInsertAlarmWhenEnabled() {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        AlarmHistoryService service = new AlarmHistoryService(
                alarmRepository,
                dataRepository,
                properties,
                objectMapper,
                directExecutor
        );

        AlertNotification notification = AlertNotification.builder()
                .deviceId("Dev-1")
                .deviceName("Device 1")
                .pointId("p1")
                .pointCode("temperature")
                .ruleId("r1")
                .ruleName("high temperature")
                .level("WARNING")
                .eventType("ALARM")
                .message("temperature high")
                .value(12.5)
                .unit("C")
                .timestamp(1234L)
                .build();

        service.saveAsync(notification);

        verify(dataRepository).createDatabase("wangbin_collector", 30);
        verify(alarmRepository).createStable("wangbin_collector", "alarm_super");
        verify(alarmRepository).createChildTable("wangbin_collector", "d_alarm_dev_1", "alarm_super", "Dev-1");
        verify(alarmRepository).insertAlarm(
                eq("wangbin_collector"),
                eq("d_alarm_dev_1"),
                eq(1234L),
                eq("Device 1"),
                eq("p1"),
                eq("temperature"),
                eq("r1"),
                eq("high temperature"),
                eq("WARNING"),
                eq("ALARM"),
                eq("temperature high"),
                eq("12.5"),
                eq(12.5),
                eq(12L),
                eq(null),
                eq("C"),
                anyString()
        );
    }

    @Test
    void saveAsyncShouldSkipWhenDisabled() {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(false);
        AlarmHistoryService service = new AlarmHistoryService(
                alarmRepository,
                dataRepository,
                properties,
                objectMapper,
                directExecutor
        );

        service.saveAsync(AlertNotification.builder()
                .deviceId("dev-1")
                .eventType("ALARM")
                .timestamp(1234L)
                .build());

        verifyNoInteractions(dataRepository);
        verifyNoInteractions(alarmRepository);
    }
}

