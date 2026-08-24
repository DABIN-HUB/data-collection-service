package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyRequest;

import java.io.ByteArrayOutputStream;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetReadPropertyCodec {

    public static final int BVLC_TYPE_IP = 0x81;
    public static final int BVLC_RESULT = 0x00;
    public static final int BVLC_FORWARDED_NPDU = 0x04;
    public static final int BVLC_REGISTER_FOREIGN_DEVICE = 0x05;
    public static final int BVLC_DISTRIBUTE_BROADCAST_TO_NETWORK = 0x09;
    public static final int BVLC_ORIGINAL_UNICAST_NPDU = 0x0A;
    public static final int BVLC_ORIGINAL_BROADCAST_NPDU = 0x0B;
    public static final int BACNET_PROTOCOL_VERSION = 0x01;
    public static final int APDU_TYPE_CONFIRMED_REQUEST = 0x00;
    public static final int APDU_TYPE_UNCONFIRMED_REQUEST = 0x01;
    public static final int APDU_TYPE_SIMPLE_ACK = 0x02;
    public static final int APDU_TYPE_COMPLEX_ACK = 0x03;
    public static final int APDU_TYPE_SEGMENT_ACK = 0x04;
    public static final int APDU_TYPE_ERROR = 0x05;
    public static final int APDU_TYPE_REJECT = 0x06;
    public static final int APDU_TYPE_ABORT = 0x07;
    public static final int SERVICE_CHOICE_READ_PROPERTY = 0x0C;
    public static final int MAX_SEGMENTS_UNSPECIFIED = 0x00;
    public static final int MAX_APDU_UP_TO_480 = 0x03;

    /**
     * 创建当前组件实例。
     */
    private BacnetReadPropertyCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(BacnetReadPropertyRequest request) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write((APDU_TYPE_CONFIRMED_REQUEST << 4) | 0x02);
        apdu.write((MAX_SEGMENTS_UNSPECIFIED << 4) | MAX_APDU_UP_TO_480);
        apdu.write(request.getInvokeId() & 0xFF);
        apdu.write(SERVICE_CHOICE_READ_PROPERTY);

        ByteArrayOutputStream service = new ByteArrayOutputStream();
        BacnetTagSupport.writeTag(service, 0, true, 4);
        int objectIdentifier = ((request.getObjectType().getId() & 0x03FF) << 22)
                | (request.getObjectInstance() & 0x3FFFFF);
        BacnetTagSupport.writeUnsigned(service, objectIdentifier, 4);

        BacnetTagSupport.writeTag(service, 1, true,
                BacnetTagSupport.unsignedLength(request.getPropertyIdentifier().getId()));
        BacnetTagSupport.writeUnsigned(service, request.getPropertyIdentifier().getId(),
                BacnetTagSupport.unsignedLength(request.getPropertyIdentifier().getId()));

        Integer arrayIndex = request.getArrayIndex();
        if (arrayIndex != null) {
            BacnetTagSupport.writeTag(service, 2, true, BacnetTagSupport.unsignedLength(arrayIndex));
            BacnetTagSupport.writeUnsigned(service, arrayIndex, BacnetTagSupport.unsignedLength(arrayIndex));
        }
        apdu.writeBytes(service.toByteArray());

        return BacnetFrameSupport.wrapConfirmedRequest(apdu.toByteArray());
    }

    /**
     * 执行当前业务逻辑。
     */
    public static String describeProperty(BacnetPropertyIdentifier propertyIdentifier) {
        return propertyIdentifier != null ? propertyIdentifier.getName() : "unknown";
    }
}
