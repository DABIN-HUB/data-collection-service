package com.wangbin.collector.core.collector.protocol.mc.support;

import com.wangbin.collector.core.collector.protocol.mc.domain.McDeviceCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FakeMcMemoryModel {

    private final Map<String, Boolean> bitValues = new ConcurrentHashMap<>();
    private final Map<String, Integer> wordValues = new ConcurrentHashMap<>();

    public void putBit(McDeviceCode deviceCode, int deviceNumber, boolean value) {
        bitValues.put(bitKey(deviceCode, deviceNumber), value);
    }

    public void putWord(McDeviceCode deviceCode, int deviceNumber, int value) {
        wordValues.put(wordKey(deviceCode, deviceNumber), value & 0xFFFF);
    }

    boolean getBit(McDeviceCode deviceCode, int deviceNumber) {
        return bitValues.getOrDefault(bitKey(deviceCode, deviceNumber), false);
    }

    int getWord(McDeviceCode deviceCode, int deviceNumber) {
        return wordValues.getOrDefault(wordKey(deviceCode, deviceNumber), 0);
    }

    byte[] readBits(McDeviceCode deviceCode, int startDeviceNumber, int count) {
        List<Boolean> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(getBit(deviceCode, startDeviceNumber + i));
        }
        return encodeBits(values);
    }

    byte[] readWords(McDeviceCode deviceCode, int startDeviceNumber, int count) {
        byte[] payload = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            int value = getWord(deviceCode, startDeviceNumber + i);
            payload[i * 2] = (byte) (value & 0xFF);
            payload[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return payload;
    }

    void writeBits(McDeviceCode deviceCode, int startDeviceNumber, int count, byte[] payload) {
        List<Boolean> values = decodeBits(payload, count);
        for (int i = 0; i < values.size(); i++) {
            putBit(deviceCode, startDeviceNumber + i, values.get(i));
        }
    }

    void writeWords(McDeviceCode deviceCode, int startDeviceNumber, int count, byte[] payload) {
        for (int i = 0; i < count; i++) {
            int low = payload[i * 2] & 0xFF;
            int high = payload[i * 2 + 1] & 0xFF;
            putWord(deviceCode, startDeviceNumber + i, low | (high << 8));
        }
    }

    public List<Integer> snapshotWords(McDeviceCode deviceCode, int startDeviceNumber, int count) {
        List<Integer> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(getWord(deviceCode, startDeviceNumber + i));
        }
        return Collections.unmodifiableList(values);
    }

    private String bitKey(McDeviceCode deviceCode, int deviceNumber) {
        return deviceCode.name() + ":" + deviceNumber;
    }

    private String wordKey(McDeviceCode deviceCode, int deviceNumber) {
        return deviceCode.name() + ":" + deviceNumber;
    }

    private byte[] encodeBits(List<Boolean> values) {
        byte[] payload = new byte[(values.size() + 1) / 2];
        for (int i = 0; i < values.size(); i++) {
            int targetIndex = i / 2;
            int encoded = values.get(i) ? 0x01 : 0x00;
            if ((i & 1) == 0) {
                payload[targetIndex] = (byte) ((payload[targetIndex] & 0xF0) | encoded);
            } else {
                payload[targetIndex] = (byte) ((payload[targetIndex] & 0x0F) | (encoded << 4));
            }
        }
        return payload;
    }

    private List<Boolean> decodeBits(byte[] payload, int count) {
        List<Boolean> values = new ArrayList<>(count);
        for (byte current : payload) {
            if (values.size() < count) {
                values.add((current & 0x0F) != 0);
            }
            if (values.size() < count) {
                values.add(((current >> 4) & 0x0F) != 0);
            }
            if (values.size() >= count) {
                break;
            }
        }
        return values;
    }
}
