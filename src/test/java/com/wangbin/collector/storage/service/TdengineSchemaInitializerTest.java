package com.wangbin.collector.storage.service;

import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.AlarmRepository;
import com.wangbin.collector.storage.repository.DataRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TdengineSchemaInitializerTest {

    private final DataRepository dataRepository = mock(DataRepository.class);
    private final AlarmRepository alarmRepository = mock(AlarmRepository.class);

    @Test
    void runShouldCreateMissingSuperTablesOnStartup() throws Exception {
        TdengineProperties properties = createProperties();
        when(dataRepository.countStable("wangbin_collector", "wangbin_super")).thenReturn(0L);
        when(dataRepository.countStable("wangbin_collector", "alarm_super")).thenReturn(0L);
        when(dataRepository.countColumn("wangbin_collector", "wangbin_super", "unit")).thenReturn(1L);
        when(dataRepository.countColumn("wangbin_collector", "alarm_super", "alarm_event_type")).thenReturn(1L);
        when(dataRepository.showCreateStable("wangbin_collector", "wangbin_super_v2")).thenReturn(v2CreateSql());

        TdengineSchemaInitializer initializer = new TdengineSchemaInitializer(dataRepository, alarmRepository, properties);

        initializer.run(null);

        verify(dataRepository).createDatabase("wangbin_collector", 30);
        verify(dataRepository).createStable("wangbin_collector", "wangbin_super");
        verify(dataRepository).createStableV2("wangbin_collector", "wangbin_super_v2");
        verify(alarmRepository).createStable("wangbin_collector", "alarm_super");
        verify(dataRepository, never()).addTelemetryUnitColumn("wangbin_collector", "wangbin_super");
        verify(alarmRepository, never()).addAlarmEventTypeColumn("wangbin_collector", "alarm_super");
    }

    @Test
    void runShouldSkipExistingStableCreationButUpgradeSchemas() throws Exception {
        TdengineProperties properties = createProperties();
        when(dataRepository.countStable("wangbin_collector", "wangbin_super")).thenReturn(1L);
        when(dataRepository.countStable("wangbin_collector", "alarm_super")).thenReturn(1L);
        when(dataRepository.countColumn("wangbin_collector", "wangbin_super", "unit")).thenReturn(0L);
        when(dataRepository.countColumn("wangbin_collector", "alarm_super", "alarm_event_type")).thenReturn(0L);
        when(dataRepository.showCreateStable("wangbin_collector", "wangbin_super_v2")).thenReturn(v2CreateSql());

        TdengineSchemaInitializer initializer = new TdengineSchemaInitializer(dataRepository, alarmRepository, properties);

        initializer.run(null);

        verify(dataRepository).createDatabase("wangbin_collector", 30);
        verify(dataRepository, never()).createStable("wangbin_collector", "wangbin_super");
        verify(dataRepository).createStableV2("wangbin_collector", "wangbin_super_v2");
        verify(alarmRepository, never()).createStable("wangbin_collector", "alarm_super");
        verify(dataRepository).addTelemetryUnitColumn("wangbin_collector", "wangbin_super");
        verify(alarmRepository).addAlarmEventTypeColumn("wangbin_collector", "alarm_super");
    }

    private TdengineProperties createProperties() {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        properties.setAutoCreate(true);
        properties.setDatabase("wangbin_collector");
        properties.setSuperTable("wangbin_super");
        properties.setAlarmSuperTable("alarm_super");
        properties.setKeepDays(30);
        return properties;
    }

    private List<Map<String, Object>> v2CreateSql() {
        return List.of(Map.of(
                "stable", "wangbin_super_v2",
                "sql", "CREATE STABLE wangbin_super_v2 (ts TIMESTAMP, point_key VARCHAR(128) COMPOSITE KEY, event_ts BIGINT)"));
    }
}
