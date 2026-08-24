package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetSubscribeCovRequest;

import java.io.ByteArrayOutputStream;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetSubscribeCovCodec {

    public static final int SERVICE_CHOICE_SUBSCRIBE_COV = 0x05;

    /**
     * 创建当前组件实例。
     */
    private BacnetSubscribeCovCodec() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static byte[] encode(BacnetSubscribeCovRequest request) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write((BacnetReadPropertyCodec.APDU_TYPE_CONFIRMED_REQUEST << 4) | 0x02);
        apdu.write((BacnetReadPropertyCodec.MAX_SEGMENTS_UNSPECIFIED << 4)
                | BacnetReadPropertyCodec.MAX_APDU_UP_TO_480);
        apdu.write(request.getInvokeId() & 0xFF);
        apdu.write(SERVICE_CHOICE_SUBSCRIBE_COV);

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

        apdu.writeBytes(service.toByteArray());
        return BacnetFrameSupport.wrapConfirmedRequest(apdu.toByteArray());
    }
}
