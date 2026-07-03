package com.wangbin.collector.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.monitor.alert.AlertNotification;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.AlarmRepository;
import com.wangbin.collector.storage.repository.DataRepository;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@EnableConfigurationProperties(TdengineProperties.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=com.taosdata.jdbc.rs.RestfulDriver",
        "spring.datasource.url=jdbc:TAOS-RS://127.0.0.1:6041/wangbin_collector",
        "spring.datasource.username=root",
        "spring.datasource.password=taosdata",
        "mybatis.mapper-locations=classpath:mapper/**/*.xml",
        "telemetry.tdengine.enabled=true",
        "telemetry.tdengine.database=wangbin_collector",
        "telemetry.tdengine.super-table=wangbin_super",
        "telemetry.tdengine.alarm-super-table=alarm_super",
        "telemetry.tdengine.alarm-sub-table-prefix=d_alarm_",
        "telemetry.tdengine.auto-create=true",
        "telemetry.tdengine.keep-days=30",
        "telemetry.tdengine.query-default-limit=10",
        "telemetry.tdengine.query-max-limit=20"
})
class AlarmHistoryServiceTdengineIT {

    @Autowired
    private DataRepository dataRepository;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private TdengineProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Executor directExecutor = Runnable::run;

    @Test
    void saveShouldPersistAndQueryFromRealTdengine() {
        TdengineSchemaInitializer schemaInitializer = new TdengineSchemaInitializer(
                dataRepository,
                alarmRepository,
                properties
        );
        AlarmHistoryService service = new AlarmHistoryService(
                alarmRepository,
                properties,
                objectMapper,
                directExecutor,
                schemaInitializer
        );

        long eventTs = System.currentTimeMillis();
        String suffix = String.valueOf(eventTs);
        String deviceId = "alarm-it-" + suffix;
        String pointId = "alarm-point-" + suffix;
        String pointCode = "alarm_code_" + suffix;
        String ruleId = "rule-" + suffix;

        AlertNotification notification = AlertNotification.builder()
                .deviceId(deviceId)
                .deviceName("Alarm Integration Device")
                .pointId(pointId)
                .pointCode(pointCode)
                .ruleId(ruleId)
                .ruleName("Alarm Rule")
                .level("WARNING")
                .eventType("QUALITY")
                .message("alarm tdengine integration")
                .value(42.5)
                .unit("C")
                .timestamp(eventTs)
                .build();

        service.save(notification);

        List<Map<String, Object>> rows = service.queryAlarmHistory(
                deviceId,
                pointId,
                pointCode,
                "WARNING",
                ruleId,
                eventTs - 1000,
                eventTs + 60_000,
                5
        );

        assertThat(rows)
                .as("expected at least one alarm row for device %s, got %s", deviceId, rows)
                .isNotEmpty();

        Map<String, Object> row = rows.get(0);
        assertThat(valueOf(row, "pointId", "point_id")).isEqualTo(pointId);
        assertThat(valueOf(row, "pointCode", "point_code")).isEqualTo(pointCode);
        assertThat(valueOf(row, "ruleId", "rule_id")).isEqualTo(ruleId);
        assertThat(valueOf(row, "alarm_event_type", "eventType", "event_type")).isEqualTo("QUALITY");
        assertThat(valueOf(row, "message")).isEqualTo("alarm tdengine integration");
        assertThat(valueOf(row, "valueText", "value_text")).isEqualTo("42.5");
    }

    private static Object valueOf(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
        }
        return null;
    }
}
