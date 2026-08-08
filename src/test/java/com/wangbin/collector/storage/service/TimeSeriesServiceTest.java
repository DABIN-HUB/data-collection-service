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
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        when(dataRepository.countColumn("wangbin_collector", "telemetry_super", "unit")).thenReturn(1L);

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
        verify(deviceRepository).createChildTable("wangbin_collector", "d_plc_001", "telemetry_super", "plc-001", "S7");
        verify(dataRepository).insertTelemetry(
                eq("wangbin_collector"),
                eq("d_plc_001"),
                eq(TimeSeriesService.resolveStorageTimestamp(eventTs, point)),
                eq(eventTs),
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
    void storageTimestampShouldBeDeterministicForSameHistoryRecord() {
        long eventTs = 1783046400123L;
        DataPoint point = point(42L, "point-42");

        long first = TimeSeriesService.resolveStorageTimestamp(eventTs, point);
        long second = TimeSeriesService.resolveStorageTimestamp(eventTs, point);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void storageTimestampShouldNotCollideForThousandPointsInSameMillisecond() {
        long eventTs = 1783046400123L;
        Set<Long> storageTimestamps = new HashSet<>();

        for (int i = 0; i < TimeSeriesService.STORAGE_TIMESTAMP_SLOTS_PER_MILLISECOND; i++) {
            assertThat(storageTimestamps.add(TimeSeriesService.resolveStorageTimestamp(eventTs, point((long) i, "point-" + i))))
                    .as("point index %s should have a unique storage timestamp", i)
                    .isTrue();
        }
    }

    @Test
    void storageTimestampShouldNotCollideAcrossAdjacentEventMilliseconds() {
        long eventTs = 1783046400123L;
        DataPoint firstPoint = point(999L, "point-999");
        DataPoint secondPoint = point(0L, "point-0");

        long first = TimeSeriesService.resolveStorageTimestamp(eventTs, firstPoint);
        long second = TimeSeriesService.resolveStorageTimestamp(eventTs + 1, secondPoint);

        assertThat(first).isNotEqualTo(second);
    }

    private DataPoint point(Long id, String pointId) {
        DataPoint point = new DataPoint();
        point.setId(id);
        point.setPointId(pointId);
        point.setPointCode(pointId);
        point.setStatus(1);
        return point;
    }

    private Map<String, Object> readMap(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }
}
