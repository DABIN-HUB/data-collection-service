package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.io.ByteArrayOutputStream;

public final class BacnetConfirmedCovNotificationCodec {

    public static final int SERVICE_CHOICE_CONFIRMED_COV_NOTIFICATION = 0x01;

    private BacnetConfirmedCovNotificationCodec() {
    }

    public static byte[] encodeAck(int invokeId) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write(BacnetReadPropertyCodec.APDU_TYPE_SIMPLE_ACK << 4);
        apdu.write(invokeId & 0xFF);
        apdu.write(SERVICE_CHOICE_CONFIRMED_COV_NOTIFICATION);
        return BacnetFrameSupport.wrapApdu(apdu.toByteArray(), 0x00, BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU);
    }
}
