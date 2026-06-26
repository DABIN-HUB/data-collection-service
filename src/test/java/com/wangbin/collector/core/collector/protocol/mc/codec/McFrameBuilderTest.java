package com.wangbin.collector.core.collector.protocol.mc.codec;

import com.wangbin.collector.common.domain.entity.DeviceConnection;
import com.wangbin.collector.core.collector.protocol.mc.domain.McAddress;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;
import com.wangbin.collector.core.collector.protocol.mc.domain.McDriverType;
import com.wangbin.collector.core.collector.protocol.mc.util.McByteCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McFrameBuilderTest {

    @Test
    void shouldBuildBatchReadFrameForWordDevice() {
        DeviceConnection connection = new DeviceConnection();
        McAddress address = new McAddress("D100", "D100", McDeviceCode.D, 100, McDriverType.UINT32, 1, null, null);

        byte[] frame = McFrameBuilder.buildBatchRead(address, connection);

        assertEquals(0x50, frame[0] & 0xFF);
        assertEquals(0x00, frame[1] & 0xFF);
        assertEquals(0xFF, frame[3] & 0xFF);
        assertEquals(0xFF, frame[4] & 0xFF);
        assertEquals(0x03, frame[5] & 0xFF);
        assertEquals(0x0C, frame[7] & 0xFF);
        assertEquals(0x10, frame[9] & 0xFF);
        assertEquals(0x01, frame[11] & 0xFF);
        assertEquals(0x04, frame[12] & 0xFF);
        assertEquals(0x00, frame[13] & 0xFF);
        assertEquals(0x64, frame[15] & 0xFF);
        assertEquals(0xA8, frame[18] & 0xFF);
        assertEquals(0x02, frame[19] & 0xFF);
    }

    @Test
    void shouldBuildBatchWriteFrameForBitDevice() {
        DeviceConnection connection = new DeviceConnection();
        McAddress address = new McAddress("M0[3]", "M0[3]", McDeviceCode.M, 0, McDriverType.BOOL, 3, null, null);
        byte[] payload = McByteCodec.encode(address, java.util.List.of(true, false, true));

        byte[] frame = McFrameBuilder.buildBatchWrite(address, payload, connection);

        assertEquals(0x01, frame[11] & 0xFF);
        assertEquals(0x14, frame[12] & 0xFF);
        assertEquals(0x01, frame[13] & 0xFF);
        assertEquals(0x03, frame[19] & 0xFF);
        assertEquals(0x01, frame[21] & 0xFF);
        assertEquals(0x01, frame[22] & 0xFF);
    }

    @Test
    void shouldBuildRandomReadFrameForWordDevices() {
        DeviceConnection connection = new DeviceConnection();
        McAddress first = new McAddress("D100", "D100", McDeviceCode.D, 100, McDriverType.UINT16, 1, null, null);
        McAddress second = new McAddress("D200", "D200", McDeviceCode.D, 200, McDriverType.UINT16, 1, null, null);

        byte[] frame = McFrameBuilder.buildRandomRead(new McRandomReadRequest(java.util.List.of(first, second)), connection);

        assertEquals(0x03, frame[11] & 0xFF);
        assertEquals(0x04, frame[12] & 0xFF);
        assertEquals(0x02, frame[13] & 0xFF);
        assertEquals(0x64, frame[15] & 0xFF);
        assertEquals(0xA8, frame[18] & 0xFF);
        assertEquals(0xC8, frame[19] & 0xFF);
        assertEquals(0xA8, frame[22] & 0xFF);
    }

    @Test
    void shouldBuildRandomWriteFrameForWordDevices() {
        DeviceConnection connection = new DeviceConnection();
        McAddress first = new McAddress("D100", "D100", McDeviceCode.D, 100, McDriverType.UINT16, 1, null, null);
        McAddress second = new McAddress("D200", "D200", McDeviceCode.D, 200, McDriverType.UINT16, 1, null, null);

        byte[] frame = McFrameBuilder.buildRandomWrite(new McRandomWriteRequest(java.util.List.of(
                new McRandomWriteItem(first, new byte[]{0x34, 0x12}),
                new McRandomWriteItem(second, new byte[]{0x78, 0x56})
        )), connection);

        assertEquals(0x02, frame[11] & 0xFF);
        assertEquals(0x14, frame[12] & 0xFF);
        assertEquals(0x02, frame[13] & 0xFF);
        assertEquals(0x64, frame[15] & 0xFF);
        assertEquals(0x34, frame[19] & 0xFF);
        assertEquals(0x12, frame[20] & 0xFF);
        assertEquals(0xC8, frame[21] & 0xFF);
        assertEquals(0x78, frame[25] & 0xFF);
    }

    @Test
    void shouldBuildAsciiBatchReadFrame() {
        DeviceConnection connection = new DeviceConnection();
        McAddress address = new McAddress("D100", "D100", McDeviceCode.D, 100, McDriverType.UINT16, 1, null, null);

        byte[] frame = McFrameBuilder.buildAsciiBatchRead(address, connection);
        String text = new String(frame, java.nio.charset.StandardCharsets.US_ASCII);

        assertEquals("500000FF03FF000018001004010000000100D*0001", text);
    }

    @Test
    void shouldBuild4eBatchReadFrame() {
        DeviceConnection connection = new DeviceConnection();
        McAddress address = new McAddress("D100", "D100", McDeviceCode.D, 100, McDriverType.UINT16, 1, null, null);

        byte[] frame = McFrameBuilder.build4eBatchRead(address, connection, 0x1234);

        assertEquals(0x54, frame[0] & 0xFF);
        assertEquals(0x00, frame[1] & 0xFF);
        assertEquals(0x34, frame[2] & 0xFF);
        assertEquals(0x12, frame[3] & 0xFF);
        assertEquals(0x0C, frame[11] & 0xFF);
        assertEquals(0x10, frame[13] & 0xFF);
        assertEquals(0x01, frame[15] & 0xFF);
        assertEquals(0x04, frame[16] & 0xFF);
        assertEquals(0x64, frame[19] & 0xFF);
        assertEquals(0xA8, frame[22] & 0xFF);
    }
}
