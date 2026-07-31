package com.wangbin.collector.core.cloud.protocol.alink.topic;

import com.wangbin.collector.core.cloud.model.CloudDeviceIdentity;
import com.wangbin.collector.core.cloud.protocol.alink.AlinkMethod;
import org.springframework.stereotype.Component;

/**
 * Alink topic 构造器，只生成云平台标准 /sys 主题。
 */
@Component
public class AlinkTopicBuilder {

    public static final String DEFAULT_PREFIX = "/sys";
    public static final String DEFAULT_REPLY_SUFFIX = "_reply";

    /**
     * 创建并返回业务对象。
     */
    public String build(CloudDeviceIdentity identity, String method) {
        AlinkMethod resolved = AlinkMethod.fromMethod(method)
                .orElseThrow(() -> new IllegalArgumentException("unsupported alink method: " + method));
        return build(identity, resolved, false);
    }

    /**
     * 创建并返回业务对象。
     */
    public String build(CloudDeviceIdentity identity, AlinkMethod method, boolean reply) {
        if (identity == null || !identity.valid()) {
            throw new IllegalArgumentException("cloud device identity is invalid");
        }
        if (method == null) {
            throw new IllegalArgumentException("alink method is required");
        }
        String topic = DEFAULT_PREFIX + "/" + identity.productKey() + "/" + identity.deviceName() + "/" + method.path();
        return reply ? topic + DEFAULT_REPLY_SUFFIX : topic;
    }
}
