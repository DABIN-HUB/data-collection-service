package com.wangbin.collector.core.config.persistence;

import java.util.List;
import java.util.Objects;

/** 持久化请求结果，不携带配置负载、驱动异常或连接凭据。 */
public final class WangbinConfigurationPersistenceException extends IllegalStateException {
    public enum Outcome {
        CONFLICT, WRITE_FAILED, COMMITTED_RECONCILED, COMMITTED_RECOVERY_REQUIRED,
        COMMITTED_APPLY_FAILED, CONFIGURATION_UNAVAILABLE
    }

    private final Outcome outcome;
    private final List<String> deviceIds;

    public WangbinConfigurationPersistenceException(Outcome outcome, List<String> deviceIds) {
        super(message(Objects.requireNonNull(outcome)));
        this.outcome = outcome;
        this.deviceIds = List.copyOf(deviceIds);
    }

    private static String message(Outcome outcome) {
        return switch (outcome) {
            case CONFLICT -> "配置持久化修订冲突，请重新加载核对";
            case WRITE_FAILED -> "配置持久化失败，本次请求未提交";
            case COMMITTED_RECONCILED -> "配置已提交，并已从权威存储恢复，请重新加载核对";
            case COMMITTED_RECOVERY_REQUIRED -> "配置已提交，目标配置暂不可用，请显式刷新恢复";
            case COMMITTED_APPLY_FAILED -> "配置已提交，但变更应用失败，请刷新核对，不要重复提交";
            case CONFIGURATION_UNAVAILABLE -> "当前配置不可用，请显式刷新恢复";
        };
    }

    public Outcome getOutcome() { return outcome; }
    public List<String> getDeviceIds() { return deviceIds; }
    public boolean isCommitted() {
        return outcome == Outcome.COMMITTED_RECONCILED || outcome == Outcome.COMMITTED_RECOVERY_REQUIRED
                || outcome == Outcome.COMMITTED_APPLY_FAILED;
    }
    public boolean isReconciled() { return outcome == Outcome.COMMITTED_RECONCILED; }
}
