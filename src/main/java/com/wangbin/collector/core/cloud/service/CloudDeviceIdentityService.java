package com.wangbin.collector.core.cloud.service;

import com.wangbin.collector.common.domain.entity.DeviceInfo;
import com.wangbin.collector.common.domain.cloud.CloudDeviceIdentity;
import com.wangbin.collector.common.domain.cloud.CloudTargetConfig;
import com.wangbin.collector.core.config.manager.ConfigManager;
import com.wangbin.collector.core.config.model.ConfigUpdateEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 设备级云身份索引服务，禁止从点位配置推导云设备身份。
 */
@Service
@RequiredArgsConstructor
public class CloudDeviceIdentityService {

    private final ConfigManager configManager;
    private final ConcurrentMap<String, CloudTargetConfig> targetByLocalDevice = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> localDeviceByCloudIdentity = new ConcurrentHashMap<>();

    /**
     * 解析或转换业务数据。
     */
    public CloudTargetConfig resolveTarget(String localDeviceId) {
        if (!StringUtils.hasText(localDeviceId)) {
            return null;
        }
        CloudTargetConfig cached = targetByLocalDevice.get(localDeviceId);
        if (cached != null) {
            return cached;
        }
        DeviceInfo device = configManager.getDevice(localDeviceId);
        CloudTargetConfig target = normalize(device != null ? device.getCloudTarget() : null);
        if (target == null || !target.valid()) {
            return null;
        }
        targetByLocalDevice.put(localDeviceId, target);
        localDeviceByCloudIdentity.put(target.identity().key(), localDeviceId);
        return target;
    }

    /**
     * 校验业务条件和参数边界。
     */
    public CloudTargetConfig requireTarget(String localDeviceId) {
        CloudTargetConfig target = resolveTarget(localDeviceId);
        if (target == null || !target.valid()) {
            throw new IllegalStateException("设备未配置有效 cloudTarget: " + localDeviceId);
        }
        return target;
    }

    /**
     * 解析或转换业务数据。
     */
    public String resolveLocalDeviceId(CloudDeviceIdentity identity) {
        if (identity == null || !identity.valid()) {
            return null;
        }
        String cached = localDeviceByCloudIdentity.get(identity.key());
        if (StringUtils.hasText(cached)) {
            return cached;
        }
        rebuildIndex();
        return localDeviceByCloudIdentity.get(identity.key());
    }

    /**
     * 查询并返回业务数据。
     */
    public Map<String, String> snapshotCloudIdentityIndex() {
        rebuildIndex();
        return Map.copyOf(localDeviceByCloudIdentity);
    }

    /**
     * 处理当前业务流程。
     */
    @EventListener
    public void handleConfigUpdate(ConfigUpdateEvent event) {
        if (event == null || !StringUtils.hasText(event.getDeviceId())) {
            clear();
            return;
        }
        String localDeviceId = event.getDeviceId();
        targetByLocalDevice.remove(localDeviceId);
        localDeviceByCloudIdentity.entrySet().removeIf(entry -> localDeviceId.equals(entry.getValue()));
    }

    /**
     * 执行当前业务逻辑。
     */
    private void rebuildIndex() {
        targetByLocalDevice.clear();
        localDeviceByCloudIdentity.clear();
        List<DeviceInfo> devices = configManager.getAllDevices();
        if (devices == null || devices.isEmpty()) {
            return;
        }
        for (DeviceInfo device : devices) {
            if (device == null || !StringUtils.hasText(device.getDeviceId())) {
                continue;
            }
            CloudTargetConfig target = normalize(device.getCloudTarget());
            if (target == null || !target.valid()) {
                continue;
            }
            targetByLocalDevice.put(device.getDeviceId(), target);
            localDeviceByCloudIdentity.put(target.identity().key(), device.getDeviceId());
        }
    }

    /**
     * 清理或删除业务数据。
     */
    private void clear() {
        targetByLocalDevice.clear();
        localDeviceByCloudIdentity.clear();
    }

    /**
     * 解析或转换业务数据。
     */
    private CloudTargetConfig normalize(CloudTargetConfig source) {
        if (source == null || !source.isEnabled()) {
            return null;
        }
        CloudTargetConfig target = new CloudTargetConfig();
        target.setEnabled(source.isEnabled());
        target.setDeviceType(source.getDeviceType());
        target.setProductKey(trim(source.getProductKey()));
        target.setDeviceName(trim(source.getDeviceName()));
        target.setTopologyEnabled(source.isTopologyEnabled());
        return target;
    }

    /**
     * 执行当前业务逻辑。
     */
    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
