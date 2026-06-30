package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import java.io.ByteArrayOutputStream;

final class BacnetFrameSupport {

    private BacnetFrameSupport() {
    }

    static byte[] wrapConfirmedRequest(byte[] apdu) {
        return wrapApdu(apdu, 0x04, BacnetReadPropertyCodec.BVLC_ORIGINAL_UNICAST_NPDU);
    }

    static byte[] wrapApdu(byte[] apdu, int npduControl, int bvlcFunction) {
        ByteArrayOutputStream npdu = new ByteArrayOutputStream();
        npdu.write(BacnetReadPropertyCodec.BACNET_PROTOCOL_VERSION);
        npdu.write(npduControl & 0xFF);
        npdu.writeBytes(apdu);

        byte[] npduBytes = npdu.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(BacnetReadPropertyCodec.BVLC_TYPE_IP);
        frame.write(bvlcFunction & 0xFF);
        int totalLength = npduBytes.length + 4;
        frame.write((totalLength >> 8) & 0xFF);
        frame.write(totalLength & 0xFF);
        frame.writeBytes(npduBytes);
        return frame.toByteArray();
    }
}
