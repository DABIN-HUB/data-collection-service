package com.wangbin.collector.core.cache.ingress;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * 标识当前 JVM 进程内的一次运行实例，用于区分可持久遥测消息的生成来源。
 */
@Component
public class RuntimeInstanceIdentity {

    private final String runtimeId;

    /**
     * Spring 生产装配使用，每次应用启动生成新的运行实例标识。
     */
    public RuntimeInstanceIdentity() {
        this(UUID.randomUUID().toString());
    }

    RuntimeInstanceIdentity(String runtimeId) {
        this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId must not be null");
        if (this.runtimeId.isBlank()) {
            throw new IllegalArgumentException("runtimeId must not be blank");
        }
    }

    public String runtimeId() {
        return runtimeId;
    }
}
