package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyRequest;

import java.io.ByteArrayOutputStream;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetWritePropertyCodec {

    public static final int SERVICE_CHOICE_WRITE_PROPERTY = 0x0F;

    /**
     * 创建当前组件实例。
     */
    private BacnetWritePropertyCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(BacnetWritePropertyRequest request) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write((BacnetReadPropertyCodec.APDU_TYPE_CONFIRMED_REQUEST << 4) | 0x02);
        apdu.write((BacnetReadPropertyCodec.MAX_SEGMENTS_UNSPECIFIED << 4)
                | BacnetReadPropertyCodec.MAX_APDU_UP_TO_480);
        apdu.write(request.getInvokeId() & 0xFF);
        apdu.write(SERVICE_CHOICE_WRITE_PROPERTY);

        ByteArrayOutputStream service = new ByteArrayOutputStream();
        BacnetTagSupport.writeTag(service, 0, true, 4);
        int objectIdentifier = ((request.getObjectType().getId() & 0x03FF) << 22)
                | (request.getObjectInstance() & 0x3FFFFF);
        BacnetTagSupport.writeUnsigned(service, objectIdentifier, 4);

        int propertyId = request.getPropertyIdentifier().getId();
        int propertyLength = BacnetTagSupport.unsignedLength(propertyId);
        BacnetTagSupport.writeTag(service, 1, true, propertyLength);
        BacnetTagSupport.writeUnsigned(service, propertyId, propertyLength);

        if (request.getArrayIndex() != null) {
            int length = BacnetTagSupport.unsignedLength(request.getArrayIndex());
            BacnetTagSupport.writeTag(service, 2, true, length);
            BacnetTagSupport.writeUnsigned(service, request.getArrayIndex(), length);
        }

        BacnetTagSupport.writeContextOpeningTag(service, 3);
        BacnetValueEncodingSupport.writeApplicationValue(service, request.getValue(), request.getValueType());
        BacnetTagSupport.writeContextClosingTag(service, 3);

        if (request.getPriority() != null) {
            int length = BacnetTagSupport.unsignedLength(request.getPriority());
            BacnetTagSupport.writeTag(service, 4, true, length);
            BacnetTagSupport.writeUnsigned(service, request.getPriority(), length);
        }

        apdu.writeBytes(service.toByteArray());
        return BacnetFrameSupport.wrapConfirmedRequest(apdu.toByteArray());
    }
}
