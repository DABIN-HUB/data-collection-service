package com.wangbin.collector.storage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.DataRepository;
import com.wangbin.collector.storage.repository.DeviceRepository;
import com.wangbin.collector.storage.repository.TelemetryInsertRow;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        "telemetry.tdengine.query-max-limit=20",
        "telemetry.tdengine.write.mode=MYBATIS_REST"
})
class TimeSeriesServiceTdengineIT {

    @Autowired
    private DataRepository dataRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private TdengineProperties properties;

    @Autowired
    private DataSource dataSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void compositeKeyProofShouldKeepDifferentPointKeysWithSameTimestamp() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        long now = System.currentTimeMillis();
        String stable = "proof_composite_" + now;
        String table = "proof_composite_t_" + now;
        String database = properties.getDatabase();

        jdbcTemplate.execute("CREATE STABLE IF NOT EXISTS " + database + "." + stable
                + " (ts TIMESTAMP, point_key VARCHAR(128) PRIMARY KEY, event_ts BIGINT, value_text NCHAR(64))"
                + " TAGS (device_id NCHAR(128))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + database + "." + table
                + " USING " + database + "." + stable + " TAGS ('dev-proof')");
        jdbcTemplate.execute("INSERT INTO " + database + "." + table
                + " (ts, point_key, event_ts, value_text) VALUES (1700000000000, 'point-A', 1700000000000, 'A')");
        jdbcTemplate.execute("INSERT INTO " + database + "." + table
                + " (ts, point_key, event_ts, value_text) VALUES (1700000000000, 'point-B', 1700000000000, 'B')");
        jdbcTemplate.execute("INSERT INTO " + database + "." + table
                + " (ts, point_key, event_ts, value_text) VALUES (1700000000000, 'point-A', 1700000000000, 'A2')");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT point_key,value_text,event_ts,ts FROM "
                + database + "." + table + " WHERE ts = 1700000000000 ORDER BY point_key");

        assertThat(rows).hasSize(2);
        assertThat(valueOf(rows.get(0), "point_key", "pointKey")).isEqualTo("point-A");
        assertThat(valueOf(rows.get(0), "value_text", "valueText")).isEqualTo("A2");
        assertThat(valueOf(rows.get(1), "point_key", "pointKey")).isEqualTo("point-B");
        assertThat(valueOf(rows.get(1), "value_text", "valueText")).isEqualTo("B");
        assertThat(toMillis(valueOf(rows.get(0), "ts"))).isEqualTo(1_700_000_000_000L);
    }

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
    void directRestWriterMustPreserveV2SemanticsInRealTdengine() throws Exception {
        TdengineProperties directProperties = copyProperties();
        directProperties.getWrite().setMode(TdengineWriteMode.DIRECT_REST);
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                directProperties,
                objectMapper,
                new PointRuntimeStateService(),
                List.of(new MybatisTdengineTelemetryWriter(dataRepository),
                        new DirectJdbcTdengineTelemetryWriter(dataSource)),
                new TdengineWriteMetricRecorder());

        long eventTs = System.currentTimeMillis();
        String suffix = "direct_" + eventTs;
        String deviceId = "tdengine-direct-" + suffix;
        DataPoint pointA = point(1301L, "point-a-" + suffix, "direct_a_" + suffix, "40001");
        DataPoint pointB = point(1302L, "point-b-" + suffix, "direct_b_" + suffix, "40002");

        service.appendBatch(List.of(
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", pointA,
                        ProcessResult.success(111, 11.1, "direct point A"), eventTs),
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", pointB,
                        ProcessResult.success(222, 22.2, "direct point B"), eventTs)));

        assertThat(service.query(deviceId, pointA.getPointId(), eventTs, eventTs, 5)).hasSize(1);
        assertThat(service.query(deviceId, pointB.getPointId(), eventTs, eventTs, 5)).hasSize(1);
        assertThat(service.writeMetrics().singleTableWriteRequests()).isEqualTo(1L);
        assertThat(service.writeMetrics().writtenRows()).isEqualTo(2L);
        assertThat(service.writeMetrics().writeFailures()).isZero();
    }

    @Test
    void singleQuoteMustRoundTrip() throws Exception {
        assertDirectRestStringRoundTrip("single_quote", "O'Brien");
    }

    @Test
    void backslashMustRoundTrip() throws Exception {
        assertDirectRestStringRoundTrip("backslash", "a\\b\\c");
    }

    @Test
    void windowsPathMustRoundTrip() throws Exception {
        assertDirectRestStringRoundTrip("windows_path", "C:\\temp\\data");
    }

    @Test
    void newlineCarriageReturnTabMustRoundTrip() throws Exception {
        assertDirectRestStringRoundTrip("control_chars", "line1\nline2\r\t");
    }

    @Test
    void jsonEscapesMustRoundTrip() throws Exception {
        String json = "{\"path\":\"C:\\\\temp\",\"name\":\"O'Brien\"}";
        String value = assertDirectRestStringRoundTrip("json_escapes", json);
        assertThat(objectMapper.readTree(value)).isEqualTo(objectMapper.readTree(json));
    }

    @Test
    void unicodeMustRoundTrip() throws Exception {
        assertDirectRestStringRoundTrip("unicode", "中文 emoji 😀");
    }

    @Test
    void emptyAndNullMustPreserveSemantics() throws Exception {
        long eventTs = System.currentTimeMillis();
        String suffix = "literal_empty_null_" + eventTs;
        String database = sanitizeIdentifier(properties.getDatabase());
        String superTable = TimeSeriesService.resolveV2Name(sanitizeIdentifier(properties.getSuperTable()));
        String subTable = TimeSeriesService.resolveV2Name(sanitizeIdentifier(properties.getSubTablePrefix())
                + sanitizeIdentifier("tdengine_" + suffix));
        prepareV2Table(database, superTable, subTable, "tdengine-" + suffix);

        DirectJdbcTdengineTelemetryWriter writer = new DirectJdbcTdengineTelemetryWriter(dataSource);
        writer.writeBatch(new TdengineWriteTarget(database, subTable), List.of(
                literalRow(eventTs, "empty-" + suffix, "", "", "{}", "{}", "{}"),
                literalRow(eventTs + 1, "null-" + suffix, null, null, null, null, null)));

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Map<String, Object> emptyRow = jdbcTemplate.queryForMap("SELECT value_text,message,raw_json,processed_json,metadata_json FROM "
                + database + "." + subTable + " WHERE point_key = 'empty-" + suffix + "'");
        assertThat(valueOf(emptyRow, "value_text", "valueText")).isEqualTo("");
        assertThat(valueOf(emptyRow, "message")).isEqualTo("");
        assertThat(valueOf(emptyRow, "raw_json", "rawJson")).isEqualTo("{}");

        Map<String, Object> nullRow = jdbcTemplate.queryForMap("SELECT value_text,message,raw_json,processed_json,metadata_json FROM "
                + database + "." + subTable + " WHERE point_key = 'null-" + suffix + "'");
        assertThat(valueOf(nullRow, "value_text", "valueText")).isNull();
        assertThat(valueOf(nullRow, "message")).isNull();
        assertThat(valueOf(nullRow, "raw_json", "rawJson")).isNull();
        assertThat(valueOf(nullRow, "processed_json", "processedJson")).isNull();
        assertThat(valueOf(nullRow, "metadata_json", "metadataJson")).isNull();
    }

    @Test
    void unsupportedCharacterMustNotBeSilentlyModified() {
        DirectJdbcTdengineTelemetryWriter writer = new DirectJdbcTdengineTelemetryWriter(dataSource);
        assertThatThrownBy(() -> writer.writeBatch(
                new TdengineWriteTarget(properties.getDatabase(), "d_literal_fail_v2"),
                List.of(literalRow(System.currentTimeMillis(), "bad-nul", "bad\u0000value", "msg", "{}", "{}", "{}"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NUL");
    }

    @Test
    void directRestWriterMustPreserveAllStringColumns() throws Exception {
        long eventTs = System.currentTimeMillis();
        String suffix = "literal_all_" + eventTs;
        String database = sanitizeIdentifier(properties.getDatabase());
        String superTable = TimeSeriesService.resolveV2Name(sanitizeIdentifier(properties.getSuperTable()));
        String subTable = TimeSeriesService.resolveV2Name(sanitizeIdentifier(properties.getSubTablePrefix())
                + sanitizeIdentifier("tdengine_" + suffix));
        prepareV2Table(database, superTable, subTable, "tdengine-" + suffix);
        String rawJson = "{\"path\":\"C:\\\\temp\",\"name\":\"O'Brien\",\"text\":\"中文😀\"}";
        String processedJson = "{\"line\":\"line1\\nline2\",\"tab\":\"\\t\"}";
        String metadataJson = "{\"quote\":\"O'Brien\",\"slash\":\"a\\\\b\\\\c\"}";

        DirectJdbcTdengineTelemetryWriter writer = new DirectJdbcTdengineTelemetryWriter(dataSource);
        writer.writeBatch(new TdengineWriteTarget(database, subTable), List.of(literalRow(
                eventTs,
                "all-" + suffix,
                "C:\\temp\\data\n中文😀",
                "O'Brien\r\tmessage",
                rawJson,
                processedJson,
                metadataJson)));

        Map<String, Object> row = new JdbcTemplate(dataSource).queryForMap(
                "SELECT value_text,message,raw_json,processed_json,metadata_json FROM "
                        + database + "." + subTable + " WHERE point_key = 'all-" + suffix + "'");

        assertThat(valueOf(row, "value_text", "valueText")).isEqualTo("C:\\temp\\data\n中文😀");
        assertThat(valueOf(row, "message")).isEqualTo("O'Brien\r\tmessage");
        assertJsonRoundTrip(row, "raw_json", "rawJson", rawJson);
        assertJsonRoundTrip(row, "processed_json", "processedJson", processedJson);
        assertJsonRoundTrip(row, "metadata_json", "metadataJson", metadataJson);
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

    @Test
    void sameDeviceHundredPointsWithSameTimestampShouldNotCollideInV2() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        long eventTs = System.currentTimeMillis();
        String suffix = "hundred_points_" + eventTs;
        String deviceId = "tdengine_hundred_" + suffix;

        for (int index = 0; index < 100; index++) {
            DataPoint point = point((long) index, "point-" + index + "-" + suffix,
                    "code_" + index + "_" + suffix, "4" + String.format("%04d", index));
            service.append(deviceId, "MODBUS_TCP", point,
                    ProcessResult.success(index, index, "point " + index), eventTs);
        }

        String database = sanitizeIdentifier(properties.getDatabase());
        String subTable = TimeSeriesService.resolveV2Name(
                sanitizeIdentifier(properties.getSubTablePrefix()) + sanitizeIdentifier(deviceId));
        List<Map<String, Object>> rows = new JdbcTemplate(dataSource).queryForList(
                "SELECT ts,event_ts,point_id FROM " + database + "." + subTable
                        + " WHERE event_ts = " + eventTs + " ORDER BY point_id LIMIT 200");

        assertThat(rows).hasSize(100);
        assertThat(rows.stream().map(row -> valueOf(row, "pointId", "point_id")).distinct()).hasSize(100);
        assertThat(rows).allSatisfy(row -> {
            assertThat(numberValue(row, "eventTs", "event_ts")).isEqualTo(eventTs);
            assertThat(toMillis(valueOf(row, "ts"))).isEqualTo(eventTs);
        });
    }

    @Test
    void batchSuccessShouldPreserveDifferentPointsSameTimestampInRealTdengine() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );
        long eventTs = System.currentTimeMillis();
        String suffix = "batch_collision_" + eventTs;
        String deviceId = "tdengine_batch_collision_" + suffix;
        DataPoint pointA = point(701L, "point-a-" + suffix, "batch_a_" + suffix, "40001");
        DataPoint pointB = point(702L, "point-b-" + suffix, "batch_b_" + suffix, "40002");

        service.appendBatch(List.of(
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", pointA,
                        ProcessResult.success(111, 11.1, "point A"), eventTs),
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", pointB,
                        ProcessResult.success(222, 22.2, "point B"), eventTs)));

        assertThat(service.query(deviceId, pointA.getPointId(), eventTs, eventTs, 5)).hasSize(1);
        assertThat(service.query(deviceId, pointB.getPointId(), eventTs, eventTs, 5)).hasSize(1);
    }

    @Test
    void samePointSameTimestampBatchReplayShouldRemainIdempotentInRealTdengine() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );
        long eventTs = System.currentTimeMillis();
        String suffix = "batch_replay_" + eventTs;
        String deviceId = "tdengine_batch_replay_" + suffix;
        DataPoint point = point(801L, "point-" + suffix, "batch_replay_" + suffix, "40001");

        service.appendBatch(List.of(
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", point,
                        ProcessResult.success(1, 1.1, "first"), eventTs),
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", point,
                        ProcessResult.success(2, 2.2, "second"), eventTs)));

        List<Map<String, Object>> rows = service.query(deviceId, point.getPointId(), eventTs, eventTs, 5);
        assertThat(rows).hasSize(1);
        assertThat(valueOf(rows.get(0), "valueText", "value_text")).isEqualTo("2.2");
        assertThat(valueOf(rows.get(0), "message")).isEqualTo("second");
    }

    @Test
    void batchInsertShouldPreserveTypedAndNullValuesInRealTdengine() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );
        long eventTs = System.currentTimeMillis();
        String suffix = "batch_types_" + eventTs;
        String deviceId = "tdengine_batch_types_" + suffix;
        DataPoint longPoint = point(901L, "long-" + suffix, "long_" + suffix, "40001");
        DataPoint doublePoint = point(902L, "double-" + suffix, "double_" + suffix, "40002");
        DataPoint boolPoint = point(903L, "bool-" + suffix, "bool_" + suffix, "40003");
        DataPoint stringPoint = point(904L, "string-" + suffix, "string_" + suffix, "40004");
        DataPoint nullPoint = point(905L, "null-" + suffix, "null_" + suffix, "40005");

        service.appendBatch(List.of(
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", longPoint,
                        ProcessResult.success(1L, 1L, "long"), eventTs),
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", doublePoint,
                        ProcessResult.success(2.5D, 2.5D, "double"), eventTs),
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", boolPoint,
                        ProcessResult.success(true, true, "bool"), eventTs),
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", stringPoint,
                        ProcessResult.success("ok", "ok", "string"), eventTs),
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", nullPoint,
                        ProcessResult.success(null, null, "null"), eventTs)));

        assertThat(valueOf(service.query(deviceId, longPoint.getPointId(), eventTs, eventTs, 5).get(0),
                "valueText", "value_text")).isEqualTo("1");
        assertThat(valueOf(service.query(deviceId, doublePoint.getPointId(), eventTs, eventTs, 5).get(0),
                "valueText", "value_text")).isEqualTo("2.5");
        assertThat(valueOf(service.query(deviceId, boolPoint.getPointId(), eventTs, eventTs, 5).get(0),
                "valueText", "value_text")).isEqualTo("true");
        assertThat(valueOf(service.query(deviceId, stringPoint.getPointId(), eventTs, eventTs, 5).get(0),
                "valueText", "value_text")).isEqualTo("ok");
        assertThat(valueOf(service.query(deviceId, nullPoint.getPointId(), eventTs, eventTs, 5).get(0),
                "valueText", "value_text")).isNull();
    }

    @Test
    void multiTableBatchInsertShouldPreserveDeviceIsolationInRealTdengine() {
        properties.getWrite().setMultiTableEnabled(true);
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );
        long eventTs = System.currentTimeMillis();
        String suffix = "multi_table_" + eventTs;
        String deviceA = "tdengine_multi_a_" + suffix;
        String deviceB = "tdengine_multi_b_" + suffix;
        DataPoint pointA = point(1101L, "point-a-" + suffix, "multi_a_" + suffix, "40001");
        DataPoint pointB = point(1102L, "point-b-" + suffix, "multi_b_" + suffix, "40002");

        service.appendBatch(List.of(
                new TimeSeriesService.AppendRequest(deviceA, "MODBUS_TCP", pointA,
                        ProcessResult.success(111, 11.1, "multi device A"), eventTs),
                new TimeSeriesService.AppendRequest(deviceB, "MODBUS_TCP", pointB,
                        ProcessResult.success(222, 22.2, "multi device B"), eventTs)));

        assertThat(service.query(deviceA, pointA.getPointId(), eventTs, eventTs, 5)).hasSize(1);
        assertThat(service.query(deviceB, pointB.getPointId(), eventTs, eventTs, 5)).hasSize(1);
        assertThat(service.writeMetrics().multiTableWriteRequests()).isEqualTo(1L);
        assertThat(service.writeMetrics().writtenRows()).isEqualTo(2L);
    }

    @Test
    void multiTableBatchShouldKeepV2IdempotentReplaySemanticsInRealTdengine() {
        properties.getWrite().setMultiTableEnabled(true);
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );
        long eventTs = System.currentTimeMillis();
        String suffix = "multi_replay_" + eventTs;
        String deviceId = "tdengine_multi_replay_" + suffix;
        DataPoint point = point(1201L, "point-" + suffix, "multi_replay_" + suffix, "40001");

        service.appendBatch(List.of(
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", point,
                        ProcessResult.success(1, 1.1, "first"), eventTs),
                new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", point,
                        ProcessResult.success(2, 2.2, "second"), eventTs)));

        List<Map<String, Object>> rows = service.query(deviceId, point.getPointId(), eventTs, eventTs, 5);
        assertThat(rows).hasSize(1);
        assertThat(valueOf(rows.get(0), "valueText", "value_text")).isEqualTo("2.2");
        assertThat(valueOf(rows.get(0), "message")).isEqualTo("second");
    }

    @Test
    void v1OldHistoryShouldRemainQueryableThroughDualRead() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        long eventTs = System.currentTimeMillis();
        String suffix = "v1_only_" + eventTs;
        String deviceId = "tdengine_v1_" + suffix;
        DataPoint point = point(401L, "point-" + suffix, "temp_" + suffix, "40001");
        insertV1(deviceId, point, eventTs, "v1-old", "v1 only");

        List<Map<String, Object>> rows = service.query(deviceId, point.getPointId(), eventTs, eventTs, 5);

        assertThat(rows).hasSize(1);
        assertThat(valueOf(rows.get(0), "valueText", "value_text")).isEqualTo("v1-old");
        assertThat(numberValue(rows.get(0), "eventTs", "event_ts")).isEqualTo(eventTs);
    }

    @Test
    void dualReadShouldPreferV2WhenSameLogicalRecordExistsInV1AndV2() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        long eventTs = System.currentTimeMillis();
        String suffix = "dual_duplicate_" + eventTs;
        String deviceId = "tdengine_dual_" + suffix;
        DataPoint point = point(501L, "point-" + suffix, "temp_" + suffix, "40001");
        insertV1(deviceId, point, eventTs, "v1-old", "old before upgrade");
        service.append(deviceId, "MODBUS_TCP", point,
                ProcessResult.success(888, 88.8, "v2 after upgrade"), eventTs);

        List<Map<String, Object>> rows = service.query(deviceId, point.getPointId(), eventTs, eventTs, 5);

        assertThat(rows).hasSize(1);
        assertThat(valueOf(rows.get(0), "valueText", "value_text")).isEqualTo("88.8");
        assertThat(valueOf(rows.get(0), "message")).isEqualTo("v2 after upgrade");
    }

    @Test
    void dualReadShouldApplyUnifiedLimitAndBusinessTimestampSorting() {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        long baseTs = System.currentTimeMillis();
        String suffix = "dual_limit_" + baseTs;
        String deviceId = "tdengine_dual_limit_" + suffix;
        DataPoint oldPoint = point(601L, "old-" + suffix, "old_" + suffix, "40001");
        DataPoint newPoint = point(602L, "new-" + suffix, "new_" + suffix, "40002");
        DataPoint newestPoint = point(603L, "newest-" + suffix, "newest_" + suffix, "40003");
        insertV1(deviceId, oldPoint, baseTs, "v1-oldest", "oldest");
        service.append(deviceId, "MODBUS_TCP", newPoint,
                ProcessResult.success(20, 20.0, "middle"), baseTs + 1);
        service.append(deviceId, "MODBUS_TCP", newestPoint,
                ProcessResult.success(30, 30.0, "newest"), baseTs + 2);

        List<Map<String, Object>> rows = service.query(deviceId, null, baseTs, baseTs + 2, 2);

        assertThat(rows).hasSize(2);
        assertThat(valueOf(rows.get(0), "pointId", "point_id")).isEqualTo(newestPoint.getPointId());
        assertThat(valueOf(rows.get(1), "pointId", "point_id")).isEqualTo(newPoint.getPointId());
    }

    @Test
    void oldHistoryWriteRequestJsonShouldReplayIntoV2WithoutSchemaChange() throws Exception {
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        long eventTs = System.currentTimeMillis();
        String suffix = "legacy_json_" + eventTs;
        String json = """
                {"deviceId":"tdengine_legacy_%s","protocolType":"MODBUS_TCP","point":{"pointId":"point-%s","pointCode":"temp_%s","pointName":"Legacy JSON","address":"40001","dataType":"FLOAT","unit":"C","status":1},"processResult":{"success":true,"rawValue":12.3,"processedValue":12.3,"quality":100,"message":"legacy"},"eventTs":%d}
                """.formatted(suffix, suffix, suffix, eventTs);

        com.wangbin.collector.storage.buffer.HistoryWriteRequest request = objectMapper.readValue(
                json,
                com.wangbin.collector.storage.buffer.HistoryWriteRequest.class);
        service.append(request.getDeviceId(), request.getProtocolType(), request.getPoint(),
                request.getProcessResult(), request.getEventTs());

        List<Map<String, Object>> rows = service.query(
                request.getDeviceId(),
                request.getPoint().getPointId(),
                eventTs,
                eventTs,
                5);

        assertThat(rows).hasSize(1);
        assertThat(valueOf(rows.get(0), "valueText", "value_text")).isEqualTo("12.3");
        assertThat(numberValue(rows.get(0), "eventTs", "event_ts")).isEqualTo(eventTs);
    }

    private Map<String, Object> readMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private String assertDirectRestStringRoundTrip(String scenario, String value) throws Exception {
        long eventTs = System.currentTimeMillis();
        String suffix = scenario + "_" + eventTs;
        String database = sanitizeIdentifier(properties.getDatabase());
        String superTable = TimeSeriesService.resolveV2Name(sanitizeIdentifier(properties.getSuperTable()));
        String subTable = TimeSeriesService.resolveV2Name(sanitizeIdentifier(properties.getSubTablePrefix())
                + sanitizeIdentifier("tdengine_" + suffix));
        prepareV2Table(database, superTable, subTable, "tdengine-" + suffix);
        String json = objectMapper.writeValueAsString(Map.of("value", value));

        DirectJdbcTdengineTelemetryWriter writer = new DirectJdbcTdengineTelemetryWriter(dataSource);
        writer.writeBatch(new TdengineWriteTarget(database, subTable), List.of(literalRow(
                eventTs,
                "point-" + suffix,
                value,
                value,
                json,
                json,
                json)));

        Map<String, Object> row = new JdbcTemplate(dataSource).queryForMap(
                "SELECT value_text,message,raw_json,processed_json,metadata_json FROM "
                        + database + "." + subTable + " WHERE point_key = 'point-" + suffix + "'");
        assertThat(valueOf(row, "value_text", "valueText")).isEqualTo(value);
        assertThat(valueOf(row, "message")).isEqualTo(value);
        assertJsonRoundTrip(row, "raw_json", "rawJson", json);
        assertJsonRoundTrip(row, "processed_json", "processedJson", json);
        assertJsonRoundTrip(row, "metadata_json", "metadataJson", json);
        return String.valueOf(valueOf(row, "value_text", "valueText"));
    }

    private void assertJsonRoundTrip(Map<String, Object> row, String snakeKey, String camelKey, String expected)
            throws Exception {
        Object actual = valueOf(row, snakeKey, camelKey);
        assertThat(actual).isNotNull();
        JsonNode actualTree = objectMapper.readTree(String.valueOf(actual));
        JsonNode expectedTree = objectMapper.readTree(expected);
        assertThat(actualTree).isEqualTo(expectedTree);
    }

    private void prepareV2Table(String database, String superTable, String subTable, String deviceId) {
        dataRepository.createDatabase(database, properties.getKeepDays());
        dataRepository.createStableV2(database, superTable);
        deviceRepository.createChildTable(database, subTable, superTable, deviceId, "MODBUS_TCP");
    }

    private TelemetryInsertRow literalRow(long eventTs,
                                          String pointKey,
                                          String value,
                                          String message,
                                          String rawJson,
                                          String processedJson,
                                          String metadataJson) {
        return new TelemetryInsertRow(
                eventTs,
                pointKey,
                pointKey,
                "code-" + pointKey,
                "name-" + pointKey,
                value,
                "C",
                100,
                true,
                message,
                rawJson,
                processedJson,
                metadataJson);
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

    private void insertV1(String deviceId, DataPoint point, long eventTs, String valueText, String message) {
        String database = sanitizeIdentifier(properties.getDatabase());
        String superTable = sanitizeIdentifier(properties.getSuperTable());
        String subTable = sanitizeIdentifier(properties.getSubTablePrefix()) + sanitizeIdentifier(deviceId);
        dataRepository.createDatabase(database, properties.getKeepDays());
        dataRepository.createStable(database, superTable);
        deviceRepository.createChildTable(database, subTable, superTable, deviceId, "MODBUS_TCP");
        dataRepository.insertTelemetry(
                database,
                subTable,
                eventTs,
                point.getPointId(),
                point.getPointCode(),
                point.getPointName(),
                valueText,
                point.getUnit(),
                100,
                true,
                message,
                "{}",
                "{}",
                "{}");
    }

    private TdengineProperties copyProperties() {
        TdengineProperties copy = new TdengineProperties();
        copy.setEnabled(properties.isEnabled());
        copy.setDatabase(properties.getDatabase());
        copy.setSuperTable(properties.getSuperTable());
        copy.setSubTablePrefix(properties.getSubTablePrefix());
        copy.setAlarmSuperTable(properties.getAlarmSuperTable());
        copy.setAlarmSubTablePrefix(properties.getAlarmSubTablePrefix());
        copy.setKeepDays(properties.getKeepDays());
        copy.setAutoCreate(properties.isAutoCreate());
        copy.setQueryDefaultLimit(properties.getQueryDefaultLimit());
        copy.setQueryMaxLimit(properties.getQueryMaxLimit());
        copy.getWrite().setMultiTableEnabled(properties.getWrite().isMultiTableEnabled());
        copy.getWrite().setMaxTablesPerRequest(properties.getWrite().getMaxTablesPerRequest());
        copy.getWrite().setMaxRowsPerRequest(properties.getWrite().getMaxRowsPerRequest());
        copy.getWrite().setAggregationWaitMs(properties.getWrite().getAggregationWaitMs());
        copy.getWrite().setWebsocketUrl(properties.getWrite().getWebsocketUrl());
        copy.getWrite().setWebsocketUsername(properties.getWrite().getWebsocketUsername());
        copy.getWrite().setWebsocketPassword(properties.getWrite().getWebsocketPassword());
        return copy;
    }

    private static Object valueOf(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
        }
        return null;
    }

    private static long numberValue(Map<String, Object> row, String... keys) {
        Object value = valueOf(row, keys);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static long toMillis(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.getTime();
        }
        if (value instanceof Date date) {
            return date.getTime();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Instant.parse(String.valueOf(value)).toEpochMilli();
    }

    private static String sanitizeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String value = raw.replaceAll("[^a-zA-Z0-9_]", "_");
        if (!Character.isLetter(value.charAt(0)) && value.charAt(0) != '_') {
            value = "_" + value;
        }
        return value.toLowerCase();
    }
}
