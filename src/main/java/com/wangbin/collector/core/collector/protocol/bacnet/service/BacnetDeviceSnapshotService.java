package com.wangbin.collector.core.collector.protocol.bacnet.service;

import com.wangbin.collector.core.collector.protocol.bacnet.BacnetIpCollector;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetDeviceSnapshot;
import com.wangbin.collector.core.collector.protocol.bacnet.domain.BacnetPropertyIdentifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理当前模块的业务服务。
 */
public class BacnetDeviceSnapshotService {

    private final Map<String, Object> propertyCache = new ConcurrentHashMap<>();

    /**
     * 执行当前业务逻辑。
     */
    public BacnetDeviceSnapshot capture(BacnetIpCollector collector) {
        Map<String, Object> deviceInfo = new LinkedHashMap<>();
        deviceInfo.put("remoteDeviceInstance", collector.requireRemoteDeviceInstanceForSnapshot());
        deviceInfo.put("objectName", readAndCache(collector, BacnetPropertyIdentifier.OBJECT_NAME, null));
        deviceInfo.put("description", readAndCache(collector, BacnetPropertyIdentifier.DESCRIPTION, null));
        deviceInfo.put("modelName", readAndCache(collector, BacnetPropertyIdentifier.MODEL_NAME, null));
        deviceInfo.put("vendorIdentifier", readAndCache(collector, BacnetPropertyIdentifier.VENDOR_IDENTIFIER, null));
        deviceInfo.put("protocolVersion", readAndCache(collector, BacnetPropertyIdentifier.PROTOCOL_VERSION, null));
        deviceInfo.put("protocolRevision", readAndCache(collector, BacnetPropertyIdentifier.PROTOCOL_REVISION, null));
        deviceInfo.put("maxApduLengthAccepted", readAndCache(collector, BacnetPropertyIdentifier.MAX_APDU_LENGTH_ACCEPTED, null));
        deviceInfo.put("segmentationSupported", readAndCache(collector, BacnetPropertyIdentifier.SEGMENTATION_SUPPORTED, null));

        List<String> objectList = new ArrayList<>();
        Object countValue = readAndCache(collector, BacnetPropertyIdentifier.OBJECT_LIST, 0);
        int count = countValue instanceof Number number ? number.intValue() : 0;
        for (int index = 1; index <= count; index++) {
            Object objectValue = readAndCache(collector, BacnetPropertyIdentifier.OBJECT_LIST, index);
            if (objectValue != null) {
                objectList.add(String.valueOf(objectValue));
            }
        }
        deviceInfo.put("objectCount", count);

        return BacnetDeviceSnapshot.builder()
                .remoteDeviceInstance(collector.requireRemoteDeviceInstanceForSnapshot())
                .deviceInfo(deviceInfo)
                .objectList(objectList)
                .propertyCache(new LinkedHashMap<>(propertyCache))
                .snapshotAt(System.currentTimeMillis())
                .build();
    }

    /**
     * 查询并返回业务数据。
     */
    public Object readAndCache(BacnetIpCollector collector,
                               BacnetPropertyIdentifier propertyIdentifier,
                               Integer arrayIndex) {
        String key = cacheKey(collector.requireRemoteDeviceInstanceForSnapshot(), propertyIdentifier, arrayIndex);
        Object value = collector.safeReadDevicePropertyForSnapshot(propertyIdentifier, arrayIndex);
        propertyCache.put(key, value);
        return value;
    }

    /**
     * 执行当前业务逻辑。
     */
    public Map<String, Object> currentPropertyCache() {
        return new LinkedHashMap<>(propertyCache);
    }

    /**
     * 清理或删除业务数据。
     */
    public void clear() {
        propertyCache.clear();
    }

    /**
     * 执行当前业务逻辑。
     */
    private String cacheKey(int deviceInstance,
                            BacnetPropertyIdentifier propertyIdentifier,
                            Integer arrayIndex) {
        return "device:" + deviceInstance + "." + propertyIdentifier.getName()
                + (arrayIndex != null ? "[" + arrayIndex + "]" : "");
    }
}
