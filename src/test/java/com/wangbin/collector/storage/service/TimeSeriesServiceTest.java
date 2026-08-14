package com.wangbin.collector.storage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.core.collector.runtime.PointRuntimeStateService;
import com.wangbin.collector.core.processor.ProcessResult;
import com.wangbin.collector.core.processor.ProcessResultMetadataKeys;
import com.wangbin.collector.storage.buffer.HistoryWriteRequest;
import com.wangbin.collector.storage.config.TdengineProperties;
import com.wangbin.collector.storage.repository.DataRepository;
import com.wangbin.collector.storage.repository.DeviceRepository;
import com.wangbin.collector.storage.repository.TelemetryInsertRow;
import com.wangbin.collector.storage.repository.TdengineTableRows;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeSeriesServiceTest {

    private final DataRepository dataRepository = mock(DataRepository.class);
    private final DeviceRepository deviceRepository = mock(DeviceRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appendShouldPersistValueTextUnitAndStructuredJson() throws Exception {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        properties.setDatabase("wangbin_collector");
        properties.setSuperTable("telemetry_super");
        properties.setSubTablePrefix("d_");
        properties.getWrite().setMode(TdengineWriteMode.MYBATIS_REST);
        when(dataRepository.countColumn("wangbin_collector", "telemetry_super", "unit")).thenReturn(1L);
        when(dataRepository.showCreateStable("wangbin_collector", "telemetry_super_v2")).thenReturn(v2CreateSql());

        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        DataPoint point = new DataPoint();
        point.setPointId("1024");
        point.setPointCode("temperature");
        point.setPointName("设备温度");
        point.setDeviceId("plc-001");
        point.setDeviceName("一号PLC");
        point.setGroupId("group-1");
        point.setAddress("DB1.DBD10");
        point.setDataType("FLOAT");
        point.setUnit("C");
        point.setUnitId(1);
        point.setBaseCollectionInterval(1000L);
        point.setAlarmEnabled(1);
        Map<String, Object> additionalConfig = new LinkedHashMap<>();
        additionalConfig.put("reportEnabled", true);
        additionalConfig.put("reportField", "temperature");
        point.setAdditionalConfig(additionalConfig);

        long eventTs = 1783046400123L;
        ProcessResult result = ProcessResult.success(17142, 25.3, "quality check passed");
        result.addMetadata(ProcessResultMetadataKeys.RAW_VALUE, 17142);
        result.addMetadata(ProcessResultMetadataKeys.PROCESSED_VALUE, 25.3);
        result.addMetadata(ProcessResultMetadataKeys.RAW_BYTES, "42 48 00 00");
        result.addMetadata(ProcessResultMetadataKeys.COLLECT_TIME, eventTs);
        result.addMetadata(ProcessResultMetadataKeys.SOURCE, "POLLING");
        result.addMetadata(ProcessResultMetadataKeys.COLLECTOR_ID, "edge-gateway-01");
        result.addMetadata(ProcessResultMetadataKeys.BATCH_ID, "batch-20260703-00001");
        result.addMetadata(ProcessResultMetadataKeys.PROCESSING_VERSION, "v1.2");

        ArgumentCaptor<String> rawJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> processedJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metadataJson = ArgumentCaptor.forClass(String.class);

        service.append("plc-001", "S7", point, result, eventTs);

        verify(dataRepository).createDatabase("wangbin_collector", 30);
        verify(dataRepository).createStable("wangbin_collector", "telemetry_super");
        verify(dataRepository).createStableV2("wangbin_collector", "telemetry_super_v2");
        verify(deviceRepository).createChildTable("wangbin_collector", "d_plc_001_v2", "telemetry_super_v2", "plc-001", "S7");
        verify(dataRepository).insertTelemetryV2(
                eq("wangbin_collector"),
                eq("d_plc_001_v2"),
                eq(eventTs),
                eq("1024"),
                eq("1024"),
                eq("temperature"),
                eq("设备温度"),
                eq("25.3"),
                eq("C"),
                eq(100),
                eq(true),
                eq("quality check passed"),
                rawJson.capture(),
                processedJson.capture(),
                metadataJson.capture()
        );

        Map<String, Object> raw = readMap(rawJson.getValue());
        assertThat(raw.get("address")).isEqualTo("DB1.DBD10");
        assertThat(raw.get("dataType")).isEqualTo("FLOAT");
        assertThat(raw.get("rawValue")).isEqualTo(17142);
        assertThat(raw.get("rawBytes")).isEqualTo("42 48 00 00");
        assertThat(raw.get("protocol")).isEqualTo("S7");
        assertThat(raw.get("unitId")).isEqualTo(1);
        assertThat(raw.get("collectTime")).isEqualTo(eventTs);

        Map<String, Object> processed = readMap(processedJson.getValue());
        assertThat(processed.get("pointCode")).isEqualTo("temperature");
        assertThat(processed.get("pointName")).isEqualTo("设备温度");
        assertThat(processed.get("value")).isEqualTo(25.3);
        assertThat(processed.get("dataType")).isEqualTo("double");
        assertThat(processed.get("quality")).isEqualTo("GOOD");
        assertThat(processed.get("timestamp")).isEqualTo(eventTs);

        Map<String, Object> metadata = readMap(metadataJson.getValue());
        assertThat(metadata.get("deviceId")).isEqualTo("plc-001");
        assertThat(metadata.get("deviceName")).isEqualTo("一号PLC");
        assertThat(metadata.get("pointId")).isEqualTo("1024");
        assertThat(metadata.get("protocolType")).isEqualTo("S7");
        assertThat(metadata.get("collectorId")).isEqualTo("edge-gateway-01");
        assertThat(metadata.get("batchId")).isEqualTo("batch-20260703-00001");
        assertThat(metadata.get("groupId")).isEqualTo("group-1");
        assertThat(metadata.get("source")).isEqualTo("POLLING");
        assertThat(metadata.get("collectionInterval")).isEqualTo(1000);
        assertThat(metadata.get("processingVersion")).isEqualTo("v1.2");
        assertThat(metadata.get("reportEnabled")).isEqualTo(true);
        assertThat(metadata.get("alarmEnabled")).isEqualTo(true);
    }

    @Test
    void pointStorageKeyShouldUseStablePointIdentity() {
        assertThat(TimeSeriesService.resolvePointStorageKey(point(42L, "point-42"))).isEqualTo("point-42");

        DataPoint idOnly = point(42L, null);
        idOnly.setPointCode(null);
        assertThat(TimeSeriesService.resolvePointStorageKey(idOnly)).isEqualTo("id:42");

        DataPoint blankPointId = point(99L, " ");
        assertThat(TimeSeriesService.resolvePointStorageKey(blankPointId)).isEqualTo("id:99");
    }

    @Test
    void pointStorageKeyShouldFailFastWhenStableIdentityIsMissingOrTooLong() {
        DataPoint empty = point(null, null);
        empty.setPointCode("temperature");
        empty.setAddress("40001");

        assertThatThrownBy(() -> TimeSeriesService.resolvePointStorageKey(empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pointId/id");

        DataPoint tooLong = point(null, "p".repeat(129));
        assertThatThrownBy(() -> TimeSeriesService.resolvePointStorageKey(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
    }

    @Test
    void queryShouldMergeV2AndV1RowsPreferV2AndApplyUnifiedLimit() {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        properties.setDatabase("wangbin_collector");
        properties.setSuperTable("telemetry_super");
        properties.setSubTablePrefix("d_");
        properties.getWrite().setMode(TdengineWriteMode.MYBATIS_REST);
        properties.getWrite().setMode(TdengineWriteMode.MYBATIS_REST);
        properties.setQueryDefaultLimit(10);
        properties.setQueryMaxLimit(10);
        when(dataRepository.countColumn("wangbin_collector", "telemetry_super", "unit")).thenReturn(1L);
        when(dataRepository.showCreateStable("wangbin_collector", "telemetry_super_v2")).thenReturn(v2CreateSql());
        when(dataRepository.queryPointHistoryV2("wangbin_collector", "d_dev_1_v2", null, null, null, 2))
                .thenReturn(List.of(
                        row("point-2", 3_000L, "v2-newest"),
                        row("point-1", 2_000L, "v2-duplicate")));
        when(dataRepository.queryPointHistory("wangbin_collector", "d_dev_1", null, null, null, 2))
                .thenReturn(List.of(
                        row("point-1", 2_000L, "v1-old"),
                        row("point-3", 1_000L, "v1-oldest")));
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        List<Map<String, Object>> rows = service.query("dev-1", null, null, null, 2);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("point_id")).isEqualTo("point-2");
        assertThat(rows.get(1).get("point_id")).isEqualTo("point-1");
        assertThat(rows.get(1).get("value_text")).isEqualTo("v2-duplicate");
    }

    @Test
    void oldHistoryWriteRequestJsonShouldStillResolvePointStorageKey() throws Exception {
        String json = """
                {"deviceId":"legacy-dev","protocolType":"MODBUS_TCP","point":{"pointId":"legacy-point","pointCode":"legacy-code","status":1},"eventTs":1783046400123}
                """;

        HistoryWriteRequest request = objectMapper.copy()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .readValue(json, HistoryWriteRequest.class);

        assertThat(request.getEventTs()).isEqualTo(1783046400123L);
        assertThat(TimeSeriesService.resolvePointStorageKey(request.getPoint())).isEqualTo("legacy-point");
    }

    @Test
    void appendBatchShouldGroupByV2SubTableAndPreserveTypedAndNullRows() {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        properties.setDatabase("wangbin_collector");
        properties.setSuperTable("telemetry_super");
        properties.setSubTablePrefix("d_");
        properties.getWrite().setMode(TdengineWriteMode.MYBATIS_REST);
        when(dataRepository.countColumn("wangbin_collector", "telemetry_super", "unit")).thenReturn(1L);
        when(dataRepository.showCreateStable("wangbin_collector", "telemetry_super_v2")).thenReturn(v2CreateSql());
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );
        long eventTs = 1783046400999L;

        service.appendBatch(List.of(
                appendRequest("dev-batch-a", point(1L, "long"), ProcessResult.success(1L, 1L), eventTs),
                appendRequest("dev-batch-a", point(2L, "double"), ProcessResult.success(2.5D, 2.5D), eventTs),
                appendRequest("dev-batch-a", point(3L, "boolean"), ProcessResult.success(true, true), eventTs),
                appendRequest("dev-batch-a", point(4L, "string"), ProcessResult.success("ok", "ok"), eventTs),
                appendRequest("dev-batch-a", point(5L, "null"), ProcessResult.success(null, null), eventTs)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TelemetryInsertRow>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(dataRepository).insertTelemetryV2Batch(
                eq("wangbin_collector"),
                eq("d_dev_batch_a_v2"),
                rowsCaptor.capture());
        List<TelemetryInsertRow> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(5);
        assertThat(rows).extracting(TelemetryInsertRow::getPointKey)
                .containsExactly("long", "double", "boolean", "string", "null");
        assertThat(rows).extracting(TelemetryInsertRow::getValueText)
                .containsExactly("1", "2.5", "true", "ok", null);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getEventTs()).isEqualTo(eventTs);
            assertThat(row.getMetadataJson()).contains("\"deviceId\":\"dev-batch-a\"");
        });
    }

    @Test
    void appendBatchShouldUseMultiTableInsertWhenEnabled() {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        properties.setDatabase("wangbin_collector");
        properties.setSuperTable("telemetry_super");
        properties.setSubTablePrefix("d_");
        properties.getWrite().setMode(TdengineWriteMode.MYBATIS_REST);
        properties.getWrite().setMultiTableEnabled(true);
        when(dataRepository.countColumn("wangbin_collector", "telemetry_super", "unit")).thenReturn(1L);
        when(dataRepository.showCreateStable("wangbin_collector", "telemetry_super_v2")).thenReturn(v2CreateSql());
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        service.appendBatch(List.of(
                appendRequest("dev-multi-a", point(1L, "p-a"), ProcessResult.success(1L, 1L), 1_000L),
                appendRequest("dev-multi-b", point(2L, "p-b"), ProcessResult.success(2L, 2L), 1_001L)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TdengineTableRows>> tablesCaptor = ArgumentCaptor.forClass(List.class);
        verify(dataRepository).insertTelemetryV2MultiTableBatch(eq("wangbin_collector"), tablesCaptor.capture());
        assertThat(tablesCaptor.getValue()).hasSize(2);
        assertThat(tablesCaptor.getValue()).extracting(TdengineTableRows::subTable)
                .containsExactly("d_dev_multi_a_v2", "d_dev_multi_b_v2");
        assertThat(service.writeMetrics().multiTableWriteRequests()).isEqualTo(1L);
        assertThat(service.writeMetrics().writtenRows()).isEqualTo(2L);
    }

    @Test
    void appendBatchShouldKeepSingleTableInsertWhenMultiTableDisabled() {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        properties.setDatabase("wangbin_collector");
        properties.setSuperTable("telemetry_super");
        properties.setSubTablePrefix("d_");
        properties.getWrite().setMode(TdengineWriteMode.MYBATIS_REST);
        when(dataRepository.countColumn("wangbin_collector", "telemetry_super", "unit")).thenReturn(1L);
        when(dataRepository.showCreateStable("wangbin_collector", "telemetry_super_v2")).thenReturn(v2CreateSql());
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService()
        );

        service.appendBatch(List.of(
                appendRequest("dev-single-a", point(1L, "p-a"), ProcessResult.success(1L, 1L), 1_000L),
                appendRequest("dev-single-b", point(2L, "p-b"), ProcessResult.success(2L, 2L), 1_001L)));

        verify(dataRepository).insertTelemetryV2Batch(eq("wangbin_collector"), eq("d_dev_single_a_v2"), any());
        verify(dataRepository).insertTelemetryV2Batch(eq("wangbin_collector"), eq("d_dev_single_b_v2"), any());
        verify(dataRepository, never()).insertTelemetryV2MultiTableBatch(any(), any());
        assertThat(service.writeMetrics().singleTableWriteRequests()).isEqualTo(2L);
    }

    @Test
    void writerSwitchMustNotChangeHistorySemantics() {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        properties.setDatabase("wangbin_collector");
        properties.setSuperTable("telemetry_super");
        properties.setSubTablePrefix("d_");
        properties.getWrite().setMode(TdengineWriteMode.DIRECT_REST);
        when(dataRepository.countColumn("wangbin_collector", "telemetry_super", "unit")).thenReturn(1L);
        when(dataRepository.showCreateStable("wangbin_collector", "telemetry_super_v2")).thenReturn(v2CreateSql());
        CapturingWriter writer = new CapturingWriter(TdengineWriteMode.DIRECT_REST);
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService(),
                List.of(writer),
                new TdengineWriteMetricRecorder());

        service.appendBatch(List.of(
                appendRequest("dev-switch", point(1L, "p-a"), ProcessResult.success(1L, 1L), 1_000L)));

        assertThat(writer.targets).extracting(TdengineWriteTarget::subTable)
                .containsExactly("d_dev_switch_v2");
        assertThat(writer.rows).hasSize(1);
        assertThat(writer.rows.get(0).getPointKey()).isEqualTo("p-a");
        assertThat(service.writeMetrics().writtenRows()).isEqualTo(1L);
    }

    @Test
    void directRestFailureMustPropagateToExistingFallback() {
        TdengineProperties properties = new TdengineProperties();
        properties.setEnabled(true);
        properties.setDatabase("wangbin_collector");
        properties.setSuperTable("telemetry_super");
        properties.setSubTablePrefix("d_");
        properties.getWrite().setMode(TdengineWriteMode.DIRECT_REST);
        when(dataRepository.countColumn("wangbin_collector", "telemetry_super", "unit")).thenReturn(1L);
        when(dataRepository.showCreateStable("wangbin_collector", "telemetry_super_v2")).thenReturn(v2CreateSql());
        FailingWriter writer = new FailingWriter(TdengineWriteMode.DIRECT_REST);
        TimeSeriesService service = new TimeSeriesService(
                dataRepository,
                deviceRepository,
                properties,
                objectMapper,
                new PointRuntimeStateService(),
                List.of(writer),
                new TdengineWriteMetricRecorder());

        assertThatThrownBy(() -> service.appendBatch(List.of(
                appendRequest("dev-fail", point(1L, "p-a"), ProcessResult.success(1L, 1L), 1_000L))))
                .isInstanceOf(TdengineWriteException.class);
        assertThat(service.writeMetrics().writeFailures()).isEqualTo(1L);
    }

    private DataPoint point(Long id, String pointId) {
        DataPoint point = new DataPoint();
        point.setId(id);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        return point;
    }

    private TimeSeriesService.AppendRequest appendRequest(String deviceId,
                                                          DataPoint point,
                                                          ProcessResult processResult,
                                                          long eventTs) {
        return new TimeSeriesService.AppendRequest(deviceId, "MODBUS_TCP", point, processResult, eventTs);
    }

    private List<Map<String, Object>> v2CreateSql() {
        return List.of(Map.of(
                "stable", "telemetry_super_v2",
                "sql", "CREATE STABLE telemetry_super_v2 (ts TIMESTAMP, point_key VARCHAR(128) COMPOSITE KEY, event_ts BIGINT)"));
    }

    private Map<String, Object> row(String pointId, long eventTs, String valueText) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("point_id", pointId);
        row.put("event_ts", eventTs);
        row.put("value_text", valueText);
        return row;
    }

    private Map<String, Object> readMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private static class CapturingWriter implements TdengineTelemetryWriter {

        private final TdengineWriteMode mode;
        private final List<TdengineWriteTarget> targets = new ArrayList<>();
        private final List<TelemetryInsertRow> rows = new ArrayList<>();

        private CapturingWriter(TdengineWriteMode mode) {
            this.mode = mode;
        }

        @Override
        public TdengineWriteMode mode() {
            return mode;
        }

        @Override
        public TdengineWriteOutcome writeSingle(TdengineWriteTarget target, TelemetryInsertRow row) {
            targets.add(target);
            rows.add(row);
            return TdengineWriteOutcome.success(1, 1, false, 1L, 2L, 3L, 4L);
        }

        @Override
        public TdengineWriteOutcome writeBatch(TdengineWriteTarget target, List<TelemetryInsertRow> rows) {
            targets.add(target);
            this.rows.addAll(rows);
            return TdengineWriteOutcome.success(rows.size(), 1, false, 1L, 2L, 3L, 4L);
        }

        @Override
        public TdengineWriteOutcome writeMultiTableBatch(String database, List<TdengineTableRows> tables) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class FailingWriter extends CapturingWriter {

        private FailingWriter(TdengineWriteMode mode) {
            super(mode);
        }

        @Override
        public TdengineWriteOutcome writeBatch(TdengineWriteTarget target, List<TelemetryInsertRow> rows) {
            throw new TdengineWriteException("failed", new IllegalStateException("down"),
                    TdengineWriteOutcome.success(rows.size(), 1, false, 0L, 0L, 1L, 1L));
        }
    }
}
