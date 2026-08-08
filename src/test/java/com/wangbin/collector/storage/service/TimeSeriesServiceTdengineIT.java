package com.wangbin.collector.storage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import com.wangbin.collector.storage.config.TdengineProperties;
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
    private TdengineProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appendShouldPersistAndQueryFromRealTdengine() throws Exception {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        long eventTs = System.currentTimeMillis();
        String suffix = String.valueOf(eventTs);
        String deviceId = "tdengine-it-" + suffix;

        DataPoint point = new DataPoint();
        point.setPointId("point-" + suffix);
        point.setPointCode("temp_code_" + suffix);
        point.setPointName("TDengine Integration Point");
        point.setAddress("40001");
        point.setDataType("FLOAT");
        point.setUnit("C");
        point.setUnitId(1);

        ProcessResult result = ProcessResult.success(1234, 12.34, "tdengine integration");
        result.addMetadata(ProcessResultMetadataKeys.RAW_BYTES, "04 D2");
        result.addMetadata(ProcessResultMetadataKeys.SOURCE, "TimeSeriesServiceTdengineIT");

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
        assertThat(valueOf(row, "unit")).isEqualTo("C");
        assertThat(String.valueOf(valueOf(row, "success"))).isEqualTo("true");

        Map<String, Object> raw = readMap(String.valueOf(valueOf(row, "rawJson", "raw_json")));
        assertThat(raw.get("address")).isEqualTo("40001");
        assertThat(raw.get("rawValue")).isEqualTo(1234);
        assertThat(raw.get("rawBytes")).isEqualTo("04 D2");
        assertThat(raw.get("protocol")).isEqualTo("MODBUS_TCP");

        Map<String, Object> processed = readMap(String.valueOf(valueOf(row, "processedJson", "processed_json")));
        assertThat(processed.get("value")).isEqualTo(12.34);
        assertThat(processed.get("quality")).isEqualTo("GOOD");
    }

    @Test
    void duplicateTimestampReplayShouldKeepSingleLatestRowInRealTdengine() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        long eventTs = System.currentTimeMillis();
        String suffix = "dup_" + eventTs;
        String deviceId = "tdengine-dup-" + suffix;

        DataPoint point = new DataPoint();
        point.setPointId("point-" + suffix);
        point.setPointCode("temp_code_" + suffix);
        point.setPointName("TDengine Duplicate Timestamp Point");
        point.setAddress("40002");
        point.setDataType("FLOAT");
        point.setUnit("C");

        service.append(deviceId, "MODBUS_TCP", point,
                ProcessResult.success(1234, 12.34, "first write"), eventTs);
        service.append(deviceId, "MODBUS_TCP", point,
                ProcessResult.success(5678, 56.78, "replay write"), eventTs);

        List<Map<String, Object>> rows = service.query(
                deviceId,
                point.getPointId(),
                eventTs - 1000,
                eventTs + 60_000,
                5
        );

        assertThat(rows).hasSize(1);
        assertThat(valueOf(rows.get(0), "valueText", "value_text")).isEqualTo("56.78");
        assertThat(valueOf(rows.get(0), "message")).isEqualTo("replay write");
    }

    @Test
    void sameDeviceDifferentPointsWithSameTimestampShouldNotOverwriteHistory() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        long eventTs = System.currentTimeMillis();
        String suffix = "collision_" + eventTs;
        String deviceId = "tdengine-collision-" + suffix;
        DataPoint pointA = point(101L, "point-a-" + suffix, "temp_a_" + suffix, "40001");
        DataPoint pointB = point(102L, "point-b-" + suffix, "temp_b_" + suffix, "40002");

        service.append(deviceId, "MODBUS_TCP", pointA,
                ProcessResult.success(111, 11.1, "point A"), eventTs);
        service.append(deviceId, "MODBUS_TCP", pointB,
                ProcessResult.success(222, 22.2, "point B"), eventTs);

        List<Map<String, Object>> pointARows = service.query(
                deviceId,
                pointA.getPointId(),
                eventTs,
                eventTs,
                5
        );
        List<Map<String, Object>> pointBRows = service.query(
                deviceId,
                pointB.getPointId(),
                eventTs,
                eventTs,
                5
        );

        assertThat(pointARows)
                .as("same device/different point/same timestamp must keep point A")
                .hasSize(1);
        assertThat(pointBRows)
                .as("same device/different point/same timestamp must keep point B")
                .hasSize(1);
        assertThat(valueOf(pointARows.get(0), "valueText", "value_text")).isEqualTo("11.1");
        assertThat(valueOf(pointBRows.get(0), "valueText", "value_text")).isEqualTo("22.2");
    }

    @Test
    void sameDeviceDifferentPointsWithDifferentTimestampShouldRemainIndependent() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        long eventTs = System.currentTimeMillis();
        String suffix = "different_ts_" + eventTs;
        String deviceId = "tdengine-different-ts-" + suffix;
        DataPoint pointA = point(201L, "point-a-" + suffix, "temp_a_" + suffix, "40001");
        DataPoint pointB = point(202L, "point-b-" + suffix, "temp_b_" + suffix, "40002");

        service.append(deviceId, "MODBUS_TCP", pointA,
                ProcessResult.success(111, 11.1, "point A"), eventTs);
        service.append(deviceId, "MODBUS_TCP", pointB,
                ProcessResult.success(222, 22.2, "point B"), eventTs + 1);

        assertThat(service.query(deviceId, pointA.getPointId(), eventTs, eventTs, 5)).hasSize(1);
        assertThat(service.query(deviceId, pointB.getPointId(), eventTs + 1, eventTs + 1, 5)).hasSize(1);
    }

    @Test
    void differentDevicesSamePointWithSameTimestampShouldRemainIndependent() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        long eventTs = System.currentTimeMillis();
        String suffix = "different_device_" + eventTs;
        DataPoint pointA = point(301L, "point-" + suffix, "temp_" + suffix, "40001");
        DataPoint pointB = point(301L, "point-" + suffix, "temp_" + suffix, "40001");
        String deviceA = "tdengine-device-a-" + suffix;
        String deviceB = "tdengine-device-b-" + suffix;

        service.append(deviceA, "MODBUS_TCP", pointA,
                ProcessResult.success(111, 11.1, "device A"), eventTs);
        service.append(deviceB, "MODBUS_TCP", pointB,
                ProcessResult.success(222, 22.2, "device B"), eventTs);

        List<Map<String, Object>> deviceARows = service.query(deviceA, pointA.getPointId(), eventTs, eventTs, 5);
        List<Map<String, Object>> deviceBRows = service.query(deviceB, pointB.getPointId(), eventTs, eventTs, 5);

        assertThat(deviceARows).hasSize(1);
        assertThat(deviceBRows).hasSize(1);
        assertThat(valueOf(deviceARows.get(0), "valueText", "value_text")).isEqualTo("11.1");
        assertThat(valueOf(deviceBRows.get(0), "valueText", "value_text")).isEqualTo("22.2");
    }

    private Map<String, Object> readMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private DataPoint point(Long id, String pointId, String pointCode, String address) {
        DataPoint point = new DataPoint();
        point.setId(id);
        point.setPointId(pointId);
        point.setPointCode(pointCode);
        point.setPointName(pointId);
        point.setAddress(address);
        point.setDataType("FLOAT");
        point.setUnit("C");
        return point;
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
