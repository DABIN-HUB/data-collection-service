package com.wangbin.collector.core.collector.protocol.bacnet.codec;

import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetObjectType;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetValue;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetValueKind;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BacnetValueDecoder {

    private BacnetValueDecoder() {
    }

    static BacnetValue readAnyValue(ByteBuffer buffer) {
        BacnetTagReader.TagHeader tag = BacnetTagReader.readTag(buffer);
        return readValue(buffer, tag);
    }

    static BacnetValue readValue(ByteBuffer buffer, BacnetTagReader.TagHeader tag) {
        if (tag.contextSpecific() && tag.openingTag()) {
            return readConstructedValue(buffer, tag.tagNumber());
        }
        if (tag.contextSpecific() || tag.closingTag()) {
            throw new IllegalArgumentException("Unsupported BACnet ANY tag: contextSpecific="
                    + tag.contextSpecific() + ", opening=" + tag.openingTag() + ", closing=" + tag.closingTag());
        }
        return readPrimitiveValue(buffer, tag);
    }

    static BacnetValue readPrimitiveValue(ByteBuffer buffer, BacnetTagReader.TagHeader tag) {
        int type = tag.tagNumber();
        return switch (type) {
            case 0 -> BacnetValue.builder()
                    .value(null)
                    .valueType("NULL")
                    .kind(BacnetValueKind.PRIMITIVE)
                    .build();
            case 1 -> BacnetValue.builder()
                    .value(tag.length() == 1)
                    .valueType("BOOLEAN")
                    .kind(BacnetValueKind.PRIMITIVE)
                    .build();
            case 2 -> {
                byte[] payload = readPayload(buffer, tag.length());
                yield BacnetValue.builder()
                        .value(readUnsigned(payload))
                        .valueType("UNSIGNED_INTEGER")
                        .kind(BacnetValueKind.PRIMITIVE)
                        .build();
            }
            case 3 -> {
                byte[] payload = readPayload(buffer, tag.length());
                yield BacnetValue.builder()
                        .value(readSigned(payload))
                        .valueType("SIGNED_INTEGER")
                        .kind(BacnetValueKind.PRIMITIVE)
                        .build();
            }
            case 4 -> {
                byte[] payload = readPayload(buffer, tag.length());
                yield BacnetValue.builder()
                        .value(ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getFloat())
                        .valueType("REAL")
                        .kind(BacnetValueKind.PRIMITIVE)
                        .build();
            }
            case 5 -> {
                byte[] payload = readPayload(buffer, tag.length());
                yield BacnetValue.builder()
                        .value(ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getDouble())
                        .valueType("DOUBLE")
                        .kind(BacnetValueKind.PRIMITIVE)
                        .build();
            }
            case 7 -> {
                byte[] payload = readPayload(buffer, tag.length());
                yield BacnetValue.builder()
                        .value(readCharacterString(payload))
                        .valueType("CHARACTER_STRING")
                        .kind(BacnetValueKind.PRIMITIVE)
                        .build();
            }
            case 8 -> {
                byte[] payload = readPayload(buffer, tag.length());
                boolean[] bits = readBitString(payload);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("bitCount", bits.length);
                metadata.put("bits", toBitList(bits));
                yield BacnetValue.builder()
                        .value(bits)
                        .valueType("BIT_STRING")
                        .kind(BacnetValueKind.PRIMITIVE)
                        .metadata(metadata)
                        .build();
            }
            case 9 -> {
                byte[] payload = readPayload(buffer, tag.length());
                long value = readUnsigned(payload);
                yield BacnetValue.builder()
                        .value(value)
                        .valueType("ENUMERATED")
                        .kind(BacnetValueKind.PRIMITIVE)
                        .build();
            }
            case 10 -> {
                byte[] payload = readPayload(buffer, tag.length());
                yield BacnetValue.builder()
                        .value(readDate(payload))
                        .valueType("DATE")
                        .kind(BacnetValueKind.PRIMITIVE)
                        .build();
            }
            case 11 -> {
                byte[] payload = readPayload(buffer, tag.length());
                yield BacnetValue.builder()
                        .value(readTime(payload))
                        .valueType("TIME")
                        .kind(BacnetValueKind.PRIMITIVE)
                        .build();
            }
            case 12 -> {
                byte[] payload = readPayload(buffer, tag.length());
                ObjectIdentifier identifier = readObjectIdentifier(payload);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("objectTypeId", identifier.objectType().getId());
                metadata.put("objectTypeName", identifier.objectType().getName());
                metadata.put("instanceNumber", identifier.instance());
                yield BacnetValue.builder()
                        .value(identifier.objectType().getName() + ":" + identifier.instance())
                        .valueType("OBJECT_IDENTIFIER")
                        .kind(BacnetValueKind.PRIMITIVE)
                        .metadata(metadata)
                        .build();
            }
            default -> {
                byte[] payload = readPayload(buffer, tag.length());
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("applicationTag", type);
                metadata.put("hex", toHex(payload));
                yield BacnetValue.builder()
                        .value(metadata)
                        .valueType("APPLICATION_TAG_" + type)
                        .kind(BacnetValueKind.UNKNOWN)
                        .metadata(metadata)
                        .build();
            }
        };
    }

    static BacnetValue readConstructedValue(ByteBuffer buffer, int openingContextTagNumber) {
        List<Object> items = new ArrayList<>();
        List<String> itemTypes = new ArrayList<>();
        int index = 0;
        while (buffer.hasRemaining()) {
            BacnetTagReader.TagHeader next = BacnetTagReader.readTag(buffer);
            if (next.contextSpecific() && next.closingTag() && next.tagNumber() == openingContextTagNumber) {
                break;
            }
            BacnetValue item = readValue(buffer, next);
            items.add(item.getValue());
            itemTypes.add(item.getValueType());
            index++;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contextTag", openingContextTagNumber);
        metadata.put("itemTypes", itemTypes);
        metadata.put("size", items.size());
        return BacnetValue.builder()
                .value(items)
                .valueType("CONSTRUCTED")
                .kind(BacnetValueKind.SEQUENCE)
                .metadata(metadata)
                .build();
    }

    static Object normalizeForProperty(BacnetPropertyIdentifier propertyIdentifier,
                                       Integer arrayIndex,
                                       BacnetValue decodedValue) {
        if (decodedValue == null) {
            return null;
        }
        if (decodedValue.isComplex() || decodedValue.getKind() == BacnetValueKind.UNKNOWN) {
            return projectComplexValue(propertyIdentifier, arrayIndex, decodedValue);
        }
        if (propertyIdentifier == BacnetPropertyIdentifier.STATUS_FLAGS && decodedValue.getValue() instanceof boolean[] bits) {
            return bitStringMap(bits,
                    "inAlarm",
                    "fault",
                    "overridden",
                    "outOfService");
        }
        return decodedValue.getValue();
    }

    static BacnetValue normalizeDecodedPropertyValue(BacnetPropertyIdentifier propertyIdentifier,
                                                     Integer arrayIndex,
                                                     BacnetValue decodedValue) {
        if (decodedValue == null) {
            return null;
        }
        if (propertyIdentifier == BacnetPropertyIdentifier.OBJECT_LIST) {
            return normalizeObjectListValue(arrayIndex, decodedValue);
        }
        if (propertyIdentifier == BacnetPropertyIdentifier.PRIORITY_ARRAY) {
            return normalizePriorityArrayValue(arrayIndex, decodedValue);
        }
        if (propertyIdentifier == BacnetPropertyIdentifier.STATE_TEXT
                || propertyIdentifier == BacnetPropertyIdentifier.ACTIVE_TEXT
                || propertyIdentifier == BacnetPropertyIdentifier.INACTIVE_TEXT) {
            return normalizeTextArrayValue(propertyIdentifier, arrayIndex, decodedValue);
        }
        if (propertyIdentifier == BacnetPropertyIdentifier.STATUS_FLAGS && decodedValue.getValue() instanceof boolean[] bits) {
            Map<String, Object> metadata = new LinkedHashMap<>(decodedValue.getMetadata());
            metadata.put("semantic", "statusFlags");
            return BacnetValue.builder()
                    .value(bitStringMap(bits, "inAlarm", "fault", "overridden", "outOfService"))
                    .valueType("STATUS_FLAGS")
                    .kind(BacnetValueKind.OBJECT)
                    .metadata(metadata)
                    .build();
        }
        if (decodedValue.isComplex() || decodedValue.getKind() == BacnetValueKind.UNKNOWN) {
            return projectComplexValueAsBacnetValue(propertyIdentifier, arrayIndex, decodedValue);
        }
        return decodedValue;
    }

    private static BacnetValue normalizeObjectListValue(Integer arrayIndex, BacnetValue decodedValue) {
        if (arrayIndex != null && arrayIndex > 0) {
            return decodedValue;
        }
        if (decodedValue.getValue() instanceof List<?> list) {
            Map<String, Object> metadata = new LinkedHashMap<>(decodedValue.getMetadata());
            metadata.put("semantic", "objectList");
            metadata.put("count", list.size());
            return BacnetValue.builder()
                    .value(list)
                    .valueType("OBJECT_LIST")
                    .kind(BacnetValueKind.ARRAY)
                    .metadata(metadata)
                    .build();
        }
        return decodedValue;
    }

    private static BacnetValue normalizePriorityArrayValue(Integer arrayIndex, BacnetValue decodedValue) {
        if (decodedValue.getValue() instanceof List<?> list) {
            List<Object> priorities = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("priority", i + 1);
                entry.put("value", item);
                priorities.add(entry);
            }
            Map<String, Object> metadata = new LinkedHashMap<>(decodedValue.getMetadata());
            metadata.put("semantic", "priorityArray");
            metadata.put("count", list.size());
            return BacnetValue.builder()
                    .value(priorities)
                    .valueType("PRIORITY_ARRAY")
                    .kind(BacnetValueKind.ARRAY)
                    .metadata(metadata)
                    .build();
        }
        return decodedValue;
    }

    private static BacnetValue normalizeTextArrayValue(BacnetPropertyIdentifier propertyIdentifier,
                                                       Integer arrayIndex,
                                                       BacnetValue decodedValue) {
        if (decodedValue.getValue() instanceof List<?> list) {
            Map<String, Object> metadata = new LinkedHashMap<>(decodedValue.getMetadata());
            metadata.put("semantic", propertyIdentifier.getName());
            metadata.put("count", list.size());
            return BacnetValue.builder()
                    .value(list)
                    .valueType(propertyIdentifier.getName().toUpperCase())
                    .kind(BacnetValueKind.ARRAY)
                    .metadata(metadata)
                    .build();
        }
        if (arrayIndex != null && arrayIndex > 0) {
            return decodedValue;
        }
        return decodedValue;
    }

    private static Object projectComplexValue(BacnetPropertyIdentifier propertyIdentifier,
                                              Integer arrayIndex,
                                              BacnetValue decodedValue) {
        return projectComplexValueAsBacnetValue(propertyIdentifier, arrayIndex, decodedValue).getValue();
    }

    private static BacnetValue projectComplexValueAsBacnetValue(BacnetPropertyIdentifier propertyIdentifier,
                                                                Integer arrayIndex,
                                                                BacnetValue decodedValue) {
        Map<String, Object> metadata = new LinkedHashMap<>(decodedValue.getMetadata());
        metadata.put("complex", true);
        if (propertyIdentifier != null) {
            metadata.put("propertyIdentifier", propertyIdentifier.getName());
            metadata.put("propertyIdentifierId", propertyIdentifier.getId());
        }
        if (arrayIndex != null) {
            metadata.put("arrayIndex", arrayIndex);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("kind", decodedValue.getKind() != null ? decodedValue.getKind().name() : BacnetValueKind.UNKNOWN.name());
        envelope.put("valueType", decodedValue.getValueType());
        envelope.put("value", decodedValue.getValue());
        if (!metadata.isEmpty()) {
            envelope.put("metadata", metadata);
        }
        return BacnetValue.builder()
                .value(envelope)
                .valueType(decodedValue.getValueType() != null ? decodedValue.getValueType() : "CONSTRUCTED")
                .kind(decodedValue.getKind() != null ? decodedValue.getKind() : BacnetValueKind.UNKNOWN)
                .metadata(metadata)
                .build();
    }

    private static byte[] readPayload(ByteBuffer buffer, int length) {
        byte[] payload = new byte[length];
        buffer.get(payload);
        return payload;
    }

    private static long readUnsigned(byte[] payload) {
        long value = 0;
        for (byte item : payload) {
            value = (value << 8) | Byte.toUnsignedLong(item);
        }
        return value;
    }

    private static long readSigned(byte[] payload) {
        long value = 0;
        for (byte item : payload) {
            value = (value << 8) | Byte.toUnsignedLong(item);
        }
        int bits = payload.length * 8;
        long signBit = 1L << (bits - 1);
        if ((value & signBit) != 0) {
            long mask = (-1L) << bits;
            value |= mask;
        }
        return value;
    }

    private static String readCharacterString(byte[] payload) {
        return BacnetReadPropertyResponseDecoder.readCharacterStringPayload(payload);
    }

    private static boolean[] readBitString(byte[] payload) {
        return BacnetReadPropertyResponseDecoder.readBitStringPayload(payload);
    }

    private static ObjectIdentifier readObjectIdentifier(byte[] payload) {
        if (payload.length != 4) {
            throw new IllegalArgumentException("BACnet objectIdentifier payload length must be 4");
        }
        int raw = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getInt();
        BacnetObjectType objectType = BacnetObjectType.fromId((raw >>> 22) & 0x03FF);
        int instance = raw & 0x3FFFFF;
        return new ObjectIdentifier(objectType, instance);
    }

    private static Map<String, Object> bitStringMap(boolean[] bits, String... labels) {
        Map<String, Object> map = new LinkedHashMap<>();
        List<Boolean> values = toBitList(bits);
        map.put("bits", values);
        for (int i = 0; i < labels.length; i++) {
            map.put(labels[i], i < bits.length && bits[i]);
        }
        return map;
    }

    private static List<Boolean> toBitList(boolean[] bits) {
        List<Boolean> values = new ArrayList<>(bits.length);
        for (boolean bit : bits) {
            values.add(bit);
        }
        return values;
    }

    private static String readDate(byte[] payload) {
        if (payload.length != 4) {
            return toHex(payload);
        }
        int year = payload[0] == (byte) 0xFF ? -1 : 1900 + Byte.toUnsignedInt(payload[0]);
        int month = payload[1] == (byte) 0xFF ? -1 : Byte.toUnsignedInt(payload[1]);
        int day = payload[2] == (byte) 0xFF ? -1 : Byte.toUnsignedInt(payload[2]);
        return String.format("%s-%s-%s",
                year >= 0 ? String.format("%04d", year) : "XXXX",
                month >= 0 ? String.format("%02d", month) : "XX",
                day >= 0 ? String.format("%02d", day) : "XX");
    }

    private static String readTime(byte[] payload) {
        if (payload.length != 4) {
            return toHex(payload);
        }
        int hour = payload[0] == (byte) 0xFF ? -1 : Byte.toUnsignedInt(payload[0]);
        int minute = payload[1] == (byte) 0xFF ? -1 : Byte.toUnsignedInt(payload[1]);
        int second = payload[2] == (byte) 0xFF ? -1 : Byte.toUnsignedInt(payload[2]);
        int hundredth = payload[3] == (byte) 0xFF ? -1 : Byte.toUnsignedInt(payload[3]);
        return String.format("%s:%s:%s.%s",
                hour >= 0 ? String.format("%02d", hour) : "XX",
                minute >= 0 ? String.format("%02d", minute) : "XX",
                second >= 0 ? String.format("%02d", second) : "XX",
                hundredth >= 0 ? String.format("%02d", hundredth) : "XX");
    }

    private static String toHex(byte[] payload) {
        StringBuilder builder = new StringBuilder(payload.length * 2);
        for (byte value : payload) {
            builder.append(String.format("%02X", value));
        }
        return builder.toString();
    }

    private record ObjectIdentifier(BacnetObjectType objectType, int instance) {
    }
}
