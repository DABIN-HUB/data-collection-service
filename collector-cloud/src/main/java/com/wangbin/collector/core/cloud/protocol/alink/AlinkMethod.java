package com.wangbin.collector.core.cloud.protocol.alink;

import com.wangbin.collector.common.constant.MessageConstant;

import java.util.Arrays;
import java.util.Optional;

/**
 * Alink 方法枚举，避免业务代码散落字符串。
 */
public enum AlinkMethod {
    STATE_UPDATE(MessageConstant.MESSAGE_TYPE_STATE_UPDATE, true),
    PROPERTY_POST(MessageConstant.MESSAGE_TYPE_PROPERTY_POST, true),
    PROPERTY_SET(MessageConstant.MESSAGE_TYPE_PROPERTY_SET, false),
    EVENT_POST(MessageConstant.MESSAGE_TYPE_EVENT_POST, true),
    SERVICE_INVOKE(MessageConstant.MESSAGE_TYPE_SERVICE_INVOKE, false),
    CONFIG_PUSH(MessageConstant.MESSAGE_TYPE_CONFIG_PUSH, false),
    OTA_UPGRADE(MessageConstant.MESSAGE_TYPE_OTA_UPGRADE, false),
    OTA_PROGRESS(MessageConstant.MESSAGE_TYPE_OTA_PROGRESS, true),
    TOPO_ADD(MessageConstant.MESSAGE_TYPE_TOPO_ADD, true),
    TOPO_DELETE(MessageConstant.MESSAGE_TYPE_TOPO_DELETE, true),
    TOPO_GET(MessageConstant.MESSAGE_TYPE_TOPO_GET, true),
    TOPO_CHANGE(MessageConstant.MESSAGE_TYPE_TOPO_CHANGE, false),
    AUTH_REGISTER(MessageConstant.MESSAGE_TYPE_AUTH_REGISTER, true),
    AUTH_REGISTER_SUB(MessageConstant.MESSAGE_TYPE_AUTH_REGISTER_SUB, true),
    PROPERTY_PACK_POST(MessageConstant.MESSAGE_TYPE_PROPERTY_PACK_POST, true);

    private final String method;
    private final boolean upstream;

    /**
     * 创建当前组件实例。
     */
    AlinkMethod(String method, boolean upstream) {
        this.method = method;
        this.upstream = upstream;
    }

    /**
     * 执行当前业务逻辑。
     */
    public String method() {
        return method;
    }

    /**
     * 执行当前业务逻辑。
     */
    public boolean upstream() {
        return upstream;
    }

    /**
     * 执行当前业务逻辑。
     */
    public String path() {
        return method.replace('.', '/');
    }

    /**
     * 创建并返回业务对象。
     */
    public static Optional<AlinkMethod> fromMethod(String method) {
        if (method == null || method.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(item -> item.method.equals(method.trim()))
                .findFirst();
    }

    /**
     * 创建并返回业务对象。
     */
    public static Optional<AlinkMethod> fromPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String normalized = path.replace('\\', '/');
        return Arrays.stream(values())
                .filter(item -> item.path().equals(normalized))
                .findFirst();
    }
}
