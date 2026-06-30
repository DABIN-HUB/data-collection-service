package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleRequest;

import java.io.ByteArrayOutputStream;

public final class BacnetReadPropertyMultipleCodec {

    public static final int SERVICE_CHOICE_READ_PROPERTY_MULTIPLE = 0x0E;

    private BacnetReadPropertyMultipleCodec() {
    }

    public static byte[] encode(BacnetReadPropertyMultipleRequest request) {
        ByteArrayOutputStream apdu = new ByteArrayOutputStream();
        apdu.write((BacnetReadPropertyCodec.APDU_TYPE_CONFIRMED_REQUEST << 4) | 0x02);
        apdu.write((BacnetReadPropertyCodec.MAX_SEGMENTS_UNSPECIFIED << 4)
                | BacnetReadPropertyCodec.MAX_APDU_UP_TO_480);
        apdu.write(request.getInvokeId() & 0xFF);
        apdu.write(SERVICE_CHOICE_READ_PROPERTY_MULTIPLE);

        ByteArrayOutputStream service = new ByteArrayOutputStream();
        if (request.getAccessSpecifications() != null) {
            for (BacnetReadPropertyMultipleRequest.ReadAccessSpec spec : request.getAccessSpecifications()) {
                if (spec == null) {
                    continue;
                }
                BacnetTagSupport.writeContextOpeningTag(service, 0);
                BacnetTagSupport.writeObjectIdentifier(service, spec.getObjectType().getId(), spec.getObjectInstance());
                BacnetTagSupport.writeContextClosingTag(service, 0);

                BacnetTagSupport.writeContextOpeningTag(service, 1);
                if (spec.getPropertyReferences() != null) {
                    for (BacnetReadPropertyMultipleRequest.PropertyReferenceSpec propertyReference : spec.getPropertyReferences()) {
                        if (propertyReference == null) {
                            continue;
                        }
                        BacnetTagSupport.writeContextOpeningTag(service, 0);
                        BacnetTagSupport.writeEnumerated(service, propertyReference.getPropertyIdentifier().getId());
                        if (propertyReference.getArrayIndex() != null) {
                            BacnetTagSupport.writeUnsignedInteger(service, propertyReference.getArrayIndex());
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
