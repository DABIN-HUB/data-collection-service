package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetWritePropertyMultipleRequest;

import java.io.ByteArrayOutputStream;

public final class BacnetWritePropertyMultipleCodec {

    public static final int SERVICE_CHOICE_WRITE_PROPERTY_MULTIPLE = 0x10;

    private BacnetWritePropertyMultipleCodec() {
    }

    public static byte[] encode(BacnetWritePropertyMultipleRequest request) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write((BacnetReadPropertyCodec.APDU_TYPE_CONFIRMED_REQUEST << 4) | 0x02);
        apdu.write((BacnetReadPropertyCodec.MAX_SEGMENTS_UNSPECIFIED << 4)
                | BacnetReadPropertyCodec.MAX_APDU_UP_TO_480);
        apdu.write(request.getInvokeId() & 0xFF);
        apdu.write(SERVICE_CHOICE_WRITE_PROPERTY_MULTIPLE);

        ByteArrayOutputStream service = new ByteArrayOutputStream();
        if (request.getWriteAccessSpecifications() != null) {
            for (BacnetWritePropertyMultipleRequest.WriteAccessSpec spec : request.getWriteAccessSpecifications()) {
                if (spec == null) {
                    continue;
                }
                BacnetTagSupport.writeContextOpeningTag(service, 0);
                BacnetTagSupport.writeObjectIdentifier(service, spec.getObjectType().getId(), spec.getObjectInstance());
                BacnetTagSupport.writeContextClosingTag(service, 0);

                BacnetTagSupport.writeContextOpeningTag(service, 1);
                if (spec.getPropertyValues() != null) {
                    for (BacnetWritePropertyMultipleRequest.PropertyValueSpec propertyValue : spec.getPropertyValues()) {
                        if (propertyValue == null) {
                            continue;
                        }
                        BacnetTagSupport.writeContextOpeningTag(service, 0);
                        int propertyId = propertyValue.getPropertyIdentifier().getId();
                        int propertyLength = BacnetTagSupport.unsignedLength(propertyId);
                        BacnetTagSupport.writeTag(service, 0, true, propertyLength);
                        BacnetTagSupport.writeUnsigned(service, propertyId, propertyLength);
                        if (propertyValue.getArrayIndex() != null) {
                            int indexLength = BacnetTagSupport.unsignedLength(propertyValue.getArrayIndex());
                            BacnetTagSupport.writeTag(service, 1, true, indexLength);
                            BacnetTagSupport.writeUnsigned(service,
                                    propertyValue.getArrayIndex(),
                                    indexLength);
                        }
                        BacnetTagSupport.writeContextOpeningTag(service, 2);
                        BacnetValueEncodingSupport.writeApplicationValue(service,
                                propertyValue.getValue(),
                                propertyValue.getValueType());
                        BacnetTagSupport.writeContextClosingTag(service, 2);
                        if (propertyValue.getPriority() != null) {
                            int length = BacnetTagSupport.unsignedLength(propertyValue.getPriority());
                            BacnetTagSupport.writeTag(service, 3, true, length);
                            BacnetTagSupport.writeUnsigned(service, propertyValue.getPriority(), length);
                        }
                        BacnetTagSupport.writeContextClosingTag(service, 0);
                    }
                }
                BacnetTagSupport.writeContextClosingTag(service, 1);
            }
        }
        apdu.writeBytes(service.toByteArray());
        return BacnetFrameSupport.wrapConfirmedRequest(apdu.toByteArray());
    }
}
