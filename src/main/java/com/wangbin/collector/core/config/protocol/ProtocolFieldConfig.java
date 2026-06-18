package com.wangbin.collector.core.config.protocol;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * UI-friendly protocol connection field metadata.
 */
@Data
@Builder
public class ProtocolFieldConfig {

    private String name;
    private String type;
    private String label;
    private boolean required;
    private String defaultValue;
    private String description;
    private String group;
    private String requiredWhen;
    private String storage;

    @Builder.Default
    private List<String> options = Collections.emptyList();
}
