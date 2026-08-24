package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.io.ByteArrayOutputStream;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetWhoIsCodec {

    private static final int APDU_TYPE_UNCONFIRMED_REQUEST = 0x01;
    private static final int SERVICE_CHOICE_WHO_IS = 0x08;

    /**
     * 创建当前组件实例。
     */
    private BacnetWhoIsCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(Integer lowLimit, Integer highLimit) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(APDU_TYPE_UNCONFIRMED_REQUEST << 4);
        apdu.write(SERVICE_CHOICE_WHO_IS);
        if (lowLimit != null) {
            int length = BacnetTagSupport.unsignedLength(lowLimit);
            BacnetTagSupport.writeTag(apdu, 0, true, length);
            BacnetTagSupport.writeUnsigned(apdu, lowLimit, length);
        }
        if (highLimit != null) {
            int length = BacnetTagSupport.unsignedLength(highLimit);
            BacnetTagSupport.writeTag(apdu, 1, true, length);
            BacnetTagSupport.writeUnsigned(apdu, highLimit, length);
        }

        ByteArrayOutputStream npdu = new ByteArrayOutputStream();
        npdu.write(BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION);
        npdu.write(0x00);
        npdu.writeBytes(apdu.toByteArray());

        byte[] npduBytes = npdu.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(BacnetReadPropertyCodec.BVLC_TYPE_IP);
        frame.write(BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU);
        int totalLength = npduBytes.length + 4;
        frame.write((totalLength >> 8) & 0xFF);
        frame.write(totalLength & 0xFF);
        frame.writeBytes(npduBytes);
        return frame.toByteArray();
    }
}
