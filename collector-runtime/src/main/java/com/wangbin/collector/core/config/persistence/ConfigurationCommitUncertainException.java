package com.wangbin.collector.core.config.persistence;

/** COMMIT 未收到可靠确认，数据库结果必须由调用方显式核对，禁止自动重试写入。 */
public final class ConfigurationCommitUncertainException extends IllegalStateException {
    public ConfigurationCommitUncertainException() {
        super("配置提交结果待核对，请勿重复提交");
    }
}
