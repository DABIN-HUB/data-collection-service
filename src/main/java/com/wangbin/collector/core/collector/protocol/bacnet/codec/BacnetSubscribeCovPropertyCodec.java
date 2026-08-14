package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovPropertyRequest;

import java.io.ByteArrayOutputStream;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetSubscribeCovPropertyCodec {

    public static final int SERVICE_CHOICE_SUBSCRIBE_COV_PROPERTY = 0x1C;

    /**
     * 创建当前组件实例。
     */
    private BacnetSubscribeCovPropertyCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(BacnetSubscribeCovPropertyRequest request) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write((BacnetReadPropertyCodec.APDU_TYPE_CONFIRMED_REQUEST << 4) | 0x02);
        apdu.write((BacnetReadPropertyCodec.MAX_SEGMENTS_UNSPECIFIED << 4)
                | BacnetReadPropertyCodec.MAX_APDU_UP_TO_480);
        apdu.write(request.getInvokeId() & 0xFF);
        apdu.write(SERVICE_CHOICE_SUBSCRIBE_COV_PROPERTY);

        ByteArrayOutputStream service = new ByteArrayOutputStream();
        int processLength = BacnetTagSupport.unsignedLength(request.getSubscriberProcessIdentifier());
        BacnetTagSupport.writeTag(service, 0, true, processLength);
        BacnetTagSupport.writeUnsigned(service, request.getSubscriberProcessIdentifier(), processLength);

        BacnetTagSupport.writeTag(service, 1, true, 4);
        int objectIdentifier = ((request.getObjectType().getId() & 0x03FF) << 22)
                | (request.getObjectInstance() & 0x3FFFFF);
        BacnetTagSupport.writeUnsigned(service, objectIdentifier, 4);

        BacnetTagSupport.writeTag(service, 2, true, request.isIssueConfirmedNotifications() ? 1 : 0);

        if (request.getLifetimeSeconds() != null) {
            int lifetimeLength = BacnetTagSupport.unsignedLength(request.getLifetimeSeconds());
            BacnetTagSupport.writeTag(service, 3, true, lifetimeLength);
            BacnetTagSupport.writeUnsigned(service, request.getLifetimeSeconds(), lifetimeLength);
        }

        BacnetTagSupport.writeContextOpeningTag(service, 4);
        int propertyId = request.getPropertyIdentifier().getId();
        int propertyLength = BacnetTagSupport.unsignedLength(propertyId);
        BacnetTagSupport.writeTag(service, 0, true, propertyLength);
        BacnetTagSupport.writeUnsigned(service, propertyId, propertyLength);
        if (request.getArrayIndex() != null) {
            int indexLength = BacnetTagSupport.unsignedLength(request.getArrayIndex());
            BacnetTagSupport.writeTag(service, 1, true, indexLength);
            BacnetTagSupport.writeUnsigned(service, request.getArrayIndex(), indexLength);
        }
        BacnetTagSupport.writeContextClosingTag(service, 4);

        if (request.getCovIncrement() != null) {
            BacnetValueEncodingSupport.writeContextReal(service, 5, request.getCovIncrement());
        }

        apdu.writeBytes(service.toByteArray());
        return BacnetFrameSupport.wrapConfirmedRequest(apdu.toByteArray());
    }
}
