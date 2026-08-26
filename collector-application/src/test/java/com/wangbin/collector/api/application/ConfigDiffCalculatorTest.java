package com.wangbin.collector.api.application;

import com.wangbin.collector.api.controller.dto.ConfigDiffResponse;
import com.wangbin.collector.common.domain.entity.DataPoint;
import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.common.domain.entity.DeviceInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDiffCalculatorTest {

    private final ConfigDiffCalculator calculator = new ConfigDiffCalculator();

    @Test
    void shouldReportCompletelyInSync() {
        ConfigDiffResponse diff = calculator.calculate(
                device("dev-1", "设备"),
                device("dev-1", "设备"),
                connection("dev-1", "127.0.0.1"),
                connection("dev-1", "127.0.0.1"),
                List.of(point("dev-1", "temperature", "40001")),
                List.of(point("dev-1", "temperature", "40001")));

        assertFalse(diff.isDeviceChanged());
        assertFalse(diff.isConnectionChanged());
        assertTrue(diff.getMissingPointCodes().isEmpty());
        assertTrue(diff.getExtraPointCodes().isEmpty());
        assertTrue(diff.getChangedPointCodes().isEmpty());
    }

    @Test
    void shouldReportDeviceMetadataChanged() {
        ConfigDiffResponse diff = calculator.calculate(
                device("dev-1", "本地"),
                device("dev-1", "远端"),
                null,
                null,
                Collections.emptyList(),
                Collections.emptyList());

        assertTrue(diff.isDeviceChanged());
        assertFalse(diff.isConnectionChanged());
    }

    @Test
    void shouldReportConnectionChanged() {
        ConfigDiffResponse diff = calculator.calculate(
                device("dev-1", "设备"),
                device("dev-1", "设备"),
                connection("dev-1", "127.0.0.1"),
                connection("dev-1", "192.168.1.10"),
                Collections.emptyList(),
                Collections.emptyList());

        assertFalse(diff.isDeviceChanged());
        assertTrue(diff.isConnectionChanged());
    }

    @Test
    void shouldReportRemoteOnlyPointAsMissingLocalPoint() {
        ConfigDiffResponse diff = calculator.calculate(
                device("dev-1", "设备"),
                device("dev-1", "设备"),
                null,
                null,
                Collections.emptyList(),
                List.of(point("dev-1", "remote-only", "40002")));

        assertEquals(List.of("remote-only"), diff.getMissingPointCodes());
        assertTrue(diff.getExtraPointCodes().isEmpty());
        assertTrue(diff.getChangedPointCodes().isEmpty());
    }

    @Test
    void shouldReportLocalOnlyPointAsExtraLocalPoint() {
        ConfigDiffResponse diff = calculator.calculate(
                device("dev-1", "设备"),
                device("dev-1", "设备"),
                null,
                null,
                List.of(point("dev-1", "local-only", "40001")),
                Collections.emptyList());

        assertTrue(diff.getMissingPointCodes().isEmpty());
        assertEquals(List.of("local-only"), diff.getExtraPointCodes());
        assertTrue(diff.getChangedPointCodes().isEmpty());
    }

    @Test
    void shouldReportChangedPointByPointCode() {
        ConfigDiffResponse diff = calculator.calculate(
                device("dev-1", "设备"),
                device("dev-1", "设备"),
                null,
                null,
                List.of(point("dev-1", "temperature", "40001")),
                List.of(point("dev-1", "temperature", "40002")));

        assertEquals(List.of("temperature"), diff.getChangedPointCodes());
        assertTrue(diff.getMissingPointCodes().isEmpty());
        assertTrue(diff.getExtraPointCodes().isEmpty());
    }

    @Test
    void shouldHandleNullEmptyAndBlankPointCodes() {
        ConfigDiffResponse diff = calculator.calculate(
                null,
                null,
                null,
                null,
                null,
                Arrays.asList(null, point("dev-1", "", "40002")));

        assertFalse(diff.isDeviceChanged());
        assertFalse(diff.isConnectionChanged());
        assertTrue(diff.getMissingPointCodes().isEmpty());
        assertTrue(diff.getExtraPointCodes().isEmpty());
        assertTrue(diff.getChangedPointCodes().isEmpty());
    }

    private DeviceInfo device(String deviceId, String deviceName) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceName);
        device.setProtocolType("MODBUS_TCP");
        return device;
    }

    private DeviceConnection connection(String deviceId, String host) {
        DeviceConnection connection = new DeviceConnection();
        connection.setDeviceId(deviceId);
        connection.setConnectionType("MODBUS_TCP");
        connection.setHost(host);
        connection.setPort(502);
        return connection;
    }

    private DataPoint point(String deviceId, String pointCode, String address) {
        DataPoint point = new DataPoint();
        point.setDeviceId(deviceId);
        point.setPointCode(pointCode);
        point.setPointName(pointCode);
        point.setAddress(address);
        point.setDataType("FLOAT");
        return point;
    }
}
