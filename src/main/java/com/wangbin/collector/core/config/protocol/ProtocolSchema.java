package com.wangbin.collector.core.config.protocol;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 协议 元数据 used by the 可视化控制台.
 */
@Data
@Builder
public class ProtocolSchema {

    private String protocol;
    private String title;
    private String description;
    private boolean implemented;
    private boolean writable;
    private boolean subscribable;

    @Builder.Default
    private ProtocolCapabilityState implementationState = ProtocolCapabilityState.UNSUPPORTED;

    @Builder.Default
    private ProtocolCapabilityState writeCapability = ProtocolCapabilityState.UNSUPPORTED;

    @Builder.Default
    private ProtocolCapabilityState subscriptionCapability = ProtocolCapabilityState.UNSUPPORTED;

    @Builder.Default
    private ProtocolCapabilityState browseCapability = ProtocolCapabilityState.UNSUPPORTED;

    @Builder.Default
    private ProtocolTypeMode typeMode = ProtocolTypeMode.PLATFORM_ONLY;

    @Builder.Default
    private String primaryTypeField = "dataType";

    @Builder.Default
    private PlatformDataTypeMode platformDataTypeMode = PlatformDataTypeMode.REQUIRED;

    private boolean driverTypeEnabled;
    private String driverTypeLabel;
    private String driverTypeField;

    @Builder.Default
    private List<String> aliases = Collections.emptyList();

    @Builder.Default
    private List<ProtocolFieldConfig> connectionFields = Collections.emptyList();

    @Builder.Default
    private List<String> pointAddressHints = Collections.emptyList();

    @Builder.Default
    private List<String> dataTypes = Collections.emptyList();

    @Builder.Default
    private List<String> driverDataTypes = Collections.emptyList();

    @Builder.Default
    private List<ProtocolFieldConfig> pointFields = Collections.emptyList();
}
