package com.wangbin.collector.core.config.protocol;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * Protocol metadata used by the visual admin console.
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