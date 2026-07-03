package com.wangbin.collector.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.AlarmRepository;
import com.wangbin.collector.storage.repository.DataRepository;
import com.wangbin.collector.storage.repository.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

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
        "telemetry.tdengine.sub-table-prefix=d_",
        "telemetry.tdengine.auto-create=true",
        "telemetry.tdengine.keep-days=30",
        "telemetry.tdengine.query-default-limit=10",
        "telemetry.tdengine.query-max-limit=20"
})
class TimeSeriesServiceTdengineIT {

    @Autowired
    private DataRepository dataRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private TdengineProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appendShouldPersistAndQueryFromRealTdengine() {
        TdengineSchemaInitializer schemaInitializer = new TdengineSchemaInitializer(
                dataRepository,
                alarmRepository,
                properties
        );
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                schemaInitializer
        );

        long eventTs = System.currentTimeMillis();
        String suffix = String.valueOf(eventTs);
        String deviceId = "tdengine-it-" + suffix;

        DataPoint point = new DataPoint();
        point.setPointId("point-" + suffix);
        point.setPointCode("temp_code_" + suffix);
        point.setPointName("TDengine Integration Point");

        ProcessResult result = ProcessResult.success(12.34, 12.34, "tdengine integration");
        result.addMetadata("source", "TimeSeriesServiceTdengineIT");

        service.append(deviceId, "MODBUS_TCP", point, result, eventTs);

        List<Map<String, Object>> rows = service.query(
                deviceId,
                point.getPointId(),
                eventTs - 1000,
                eventTs + 60_000,
                5
        );

        assertThat(rows)
                .as("expected at least one row for device %s point %s, got %s", deviceId, point.getPointId(), rows)
                .isNotEmpty();

        Map<String, Object> row = rows.get(0);
        assertThat(valueOf(row, "pointId", "point_id")).isEqualTo(point.getPointId());
        assertThat(valueOf(row, "pointCode", "point_code")).isEqualTo(point.getPointCode());
        assertThat(valueOf(row, "pointName", "point_name")).isEqualTo(point.getPointName());
        assertThat(valueOf(row, "valueText", "value_text")).isEqualTo("12.34");
        assertThat(String.valueOf(valueOf(row, "success"))).isEqualTo("true");
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
