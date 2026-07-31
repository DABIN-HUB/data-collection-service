package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetReadPropertyMultipleResponse;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetValue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 定义当前模块的业务组件。
 */
public final class BacnetReadPropertyMultipleResponseDecoder {

    /**
     * 创建当前组件实例。
     */
    private BacnetReadPropertyMultipleResponseDecoder() {
    }

    /**
     * 解析或转换业务数据。
     */
    public static BacnetReadPropertyMultipleResponse decode(byte[] frame, int expectedInvokeId) {
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        BacnetReadPropertyResponseDecoder.BacnetFrameHeader header =
                BacnetReadPropertyResponseDecoder.readFrameHeader(buffer);

        return switch (header.pduType()) {
            case BacnetReadPropertyCodec.APDU_TYPE_COMPLEX_ACK -> decodeComplexAck(buffer, header.pduHeader(), expectedInvokeId);
            case BacnetReadPropertyCodec.APDU_TYPE_ERROR -> throw BacnetReadPropertyResponseDecoder.decodeError(buffer);
            case BacnetReadPropertyCodec.APDU_TYPE_REJECT -> throw BacnetReadPropertyResponseDecoder.decodeReject(buffer, header.pduHeader());
            case BacnetReadPropertyCodec.APDU_TYPE_ABORT -> throw BacnetReadPropertyResponseDecoder.decodeAbort(buffer, header.pduHeader());
            default -> throw new IllegalArgumentException("Unsupported BACnet APDU type: " + header.pduType());
        };
    }

    /**
     * 解析或转换业务数据。
     */
    private static BacnetReadPropertyMultipleResponse decodeComplexAck(ByteBuffer buffer,
                                                                       int pduHeader,
                                                                       int expectedInvokeId) {
        boolean segmented = (pduHeader & 0x08) != 0;
        boolean moreFollows = (pduHeader & 0x04) != 0;
        if (segmented || moreFollows) {
            throw new IllegalStateException("Segmented BACnet ComplexACK is not supported yet");
        }

        int invokeId = Byte.toUnsignedInt(buffer.get());
        if ((expectedInvokeId & 0xFF) != invokeId) {
            throw new IllegalStateException("BACnet invokeId mismatch: expected="
                    + (expectedInvokeId & 0xFF) + ", actual=" + invokeId);
        }
        int serviceChoice = Byte.toUnsignedInt(buffer.get());
        if (serviceChoice != BacnetReadPropertyMultipleCodec.SERVICE_CHOICE_READ_PROPERTY_MULTIPLE) {
            throw new IllegalStateException("Unexpected BACnet ComplexACK service choice: " + serviceChoice);
        }

        BacnetReadPropertyMultipleResponse.BacnetReadPropertyMultipleResponseBuilder responseBuilder =
                BacnetReadPropertyMultipleResponse.builder().invokeId(invokeId);

        while (buffer.hasRemaining()) {
            BacnetTagReader.TagHeader objectOpen = BacnetTagReader.readTag(buffer);
            if (!objectOpen.contextSpecific() || !objectOpen.openingTag() || objectOpen.tagNumber() != 0) {
                throw new IllegalArgumentException("BACnet RPM Ack missing result opening tag 0");
            }
            BacnetTagReader.TagHeader objectTag = BacnetTagReader.readTag(buffer);
            if (objectTag.contextSpecific() || objectTag.length() != 4 || objectTag.tagNumber() != 12) {
                throw new IllegalArgumentException("BACnet RPM Ack result object identifier must be application tag 12/4");
            }
            int objectIdRaw = buffer.getInt();
            BacnetObjectType objectType = BacnetObjectType.fromId((objectIdRaw >>> 22) & 0x03FF);
            int objectInstance = objectIdRaw & 0x3FFFFF;
            BacnetTagReader.TagHeader objectClose = BacnetTagReader.readTag(buffer);
            if (!objectClose.contextSpecific() || !objectClose.closingTag() || objectClose.tagNumber() != 0) {
                throw new IllegalArgumentException("BACnet RPM Ack missing result closing tag 0");
            }

            BacnetTagReader.TagHeader listOpen = BacnetTagReader.readTag(buffer);
            if (!listOpen.contextSpecific() || !listOpen.openingTag() || listOpen.tagNumber() != 1) {
                throw new IllegalArgumentException("BACnet RPM Ack missing result list opening tag 1");
            }

            BacnetReadPropertyMultipleResponse.ReadAccessResult.ReadAccessResultBuilder resultBuilder =
                    BacnetReadPropertyMultipleResponse.ReadAccessResult.builder()
                            .objectType(objectType)
                            .objectInstance(objectInstance);

            while (buffer.hasRemaining()) {
                BacnetTagReader.TagHeader next = BacnetTagReader.readTag(buffer);
                if (next.contextSpecific() && next.closingTag() && next.tagNumber() == 1) {
                    break;
                }
                if (!next.contextSpecific() || !next.openingTag() || next.tagNumber() != 2) {
                    throw new IllegalArgumentException("BACnet RPM Ack missing property result opening tag 2");
                }

                BacnetTagReader.TagHeader propertyTag = BacnetTagReader.readTag(buffer);
                BacnetReadPropertyResponseDecoder.requireContextTag(propertyTag, 2);
                int propertyId = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, propertyTag.length());
                BacnetPropertyIdentifier propertyIdentifier = BacnetPropertyIdentifier.fromId(propertyId);

                Integer arrayIndex = null;
                BacnetTagReader.TagHeader valueTag = BacnetTagReader.readTag(buffer);
                if (valueTag.contextSpecific() && !valueTag.openingTag() && !valueTag.closingTag() && valueTag.tagNumber() == 3) {
                    arrayIndex = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, valueTag.length());
                    valueTag = BacnetTagReader.readTag(buffer);
                }

                BacnetReadPropertyMultipleResponse.PropertyValueResult.PropertyValueResultBuilder propertyResultBuilder =
                        BacnetReadPropertyMultipleResponse.PropertyValueResult.builder()
                                .propertyIdentifier(propertyIdentifier)
                                .arrayIndex(arrayIndex);

                if (valueTag.contextSpecific() && valueTag.openingTag() && valueTag.tagNumber() == 4) {
                    BacnetValue decodedValue = BacnetValueDecoder.readAnyValue(buffer);
                    decodedValue = BacnetValueDecoder.normalizeDecodedPropertyValue(propertyIdentifier, arrayIndex, decodedValue);
                    BacnetTagReader.TagHeader closing = BacnetTagReader.readTag(buffer);
                    if (!closing.contextSpecific() || !closing.closingTag() || closing.tagNumber() != 4) {
                        throw new IllegalArgumentException("BACnet RPM Ack missing value closing tag 4");
                    }
                    propertyResultBuilder.value(decodedValue.getValue())
                            .valueType(decodedValue.getValueType())
                            .valueMetadata(decodedValue.getMetadata())
                            .error(false);
                } else if (valueTag.contextSpecific() && valueTag.openingTag() && valueTag.tagNumber() == 5) {
                    String errorMessage = decodeErrorClassAndCode(buffer);
                    BacnetTagReader.TagHeader closing = BacnetTagReader.readTag(buffer);
                    if (!closing.contextSpecific() || !closing.closingTag() || closing.tagNumber() != 5) {
                        throw new IllegalArgumentException("BACnet RPM Ack missing error closing tag 5");
                    }
                    propertyResultBuilder.error(true)
                            .errorMessage(errorMessage);
                } else {
                    throw new IllegalArgumentException("BACnet RPM Ack property result must be value[4] or error[5]");
                }

                BacnetTagReader.TagHeader propertyClose = BacnetTagReader.readTag(buffer);
                if (!propertyClose.contextSpecific() || !propertyClose.closingTag() || propertyClose.tagNumber() != 2) {
                    throw new IllegalArgumentException("BACnet RPM Ack missing property result closing tag 2");
                }
                resultBuilder.propertyResult(propertyResultBuilder.build());
            }
            responseBuilder.result(resultBuilder.build());
        }
        return responseBuilder.build();
    }

    /**
     * 解析或转换业务数据。
     */
    private static String decodeErrorClassAndCode(ByteBuffer buffer) {
        BacnetTagReader.TagHeader errorClass = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(errorClass, 0);
        int classId = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, errorClass.length());

        BacnetTagReader.TagHeader errorCode = BacnetTagReader.readTag(buffer);
        BacnetReadPropertyResponseDecoder.requireContextTag(errorCode, 1);
        int codeId = BacnetReadPropertyResponseDecoder.readUnsigned(buffer, errorCode.length());
        return "errorClass=" + classId + ", errorCode=" + codeId;
    }
}
