package com.wangbin.collector.core.config.protocol;

import com.wangbin.collector.core.collector.protocol.base.ProtocolCollector;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 协议描述不可变快照。
 */
public record ProtocolDescriptor(String code,
                                 String title,
                                 String description,
                                 List<String> aliases,
                                 Class<? extends ProtocolCollector> collectorClass,
                                 String connectionType,
                                 Integer defaultPort,
                                 ProtocolAddressingMode addressingMode,
                                 ProtocolCapabilityState implementationState,
                                 ProtocolCapabilityState writeCapability,
                                 ProtocolCapabilityState subscriptionCapability,
                                 ProtocolCapabilityState browseCapability,
                                 List<ProtocolFieldConfig> connectionFields,
                                 List<String> pointAddressHints,
                                 List<String> dataTypes,
                                 ProtocolTypeMode typeMode,
                                 String primaryTypeField,
                                 PlatformDataTypeMode platformDataTypeMode,
                                 boolean driverTypeEnabled,
                                 String driverTypeLabel,
                                 String driverTypeField,
                                 List<String> driverDataTypes,
                                 List<ProtocolFieldConfig> pointFields) {

    private static final List<String> COMMON_DATA_TYPES = List.of(
            "INT", "FLOAT", "DOUBLE", "BOOLEAN", "STRING", "BYTE", "SHORT", "LONG", "UINT16", "UINT32");
    private static final List<String> EXTENDED_DATA_TYPES = dataTypesWith(COMMON_DATA_TYPES,
            "INT8", "UINT8", "INT16", "INT32", "FLOAT32", "FLOAT64", "INT64", "UINT64");

    public ProtocolDescriptor {
        aliases = aliases == null ? Collections.emptyList() : List.copyOf(aliases);
        implementationState = defaultState(implementationState);
        writeCapability = defaultState(writeCapability);
        subscriptionCapability = defaultState(subscriptionCapability);
        browseCapability = defaultState(browseCapability);
        connectionFields = connectionFields == null ? Collections.emptyList() : List.copyOf(connectionFields);
        pointAddressHints = pointAddressHints == null ? Collections.emptyList() : List.copyOf(pointAddressHints);
        dataTypes = dataTypes == null ? EXTENDED_DATA_TYPES : List.copyOf(dataTypes);
        typeMode = typeMode == null ? ProtocolTypeMode.PLATFORM_ONLY : typeMode;
        primaryTypeField = primaryTypeField == null || primaryTypeField.isBlank()
                ? defaultPrimaryTypeField(typeMode)
                : primaryTypeField;
        platformDataTypeMode = platformDataTypeMode == null
                ? defaultPlatformDataTypeMode(typeMode)
                : platformDataTypeMode;
        driverDataTypes = driverDataTypes == null ? Collections.emptyList() : List.copyOf(driverDataTypes);
        pointFields = pointFields == null ? Collections.emptyList() : List.copyOf(pointFields);
    }

    /**
     * 兼容只声明连接字段的协议描述构造方式。
     */
    public ProtocolDescriptor(String code,
                              String title,
                              String description,
                              List<String> aliases,
                              Class<? extends ProtocolCollector> collectorClass,
                              String connectionType,
                              Integer defaultPort,
                              ProtocolAddressingMode addressingMode,
                              ProtocolCapabilityState implementationState,
                              ProtocolCapabilityState writeCapability,
                              ProtocolCapabilityState subscriptionCapability,
                              ProtocolCapabilityState browseCapability,
                              List<ProtocolFieldConfig> connectionFields,
                              List<String> pointAddressHints) {
        this(code, title, description, aliases, collectorClass, connectionType, defaultPort, addressingMode,
                implementationState, writeCapability, subscriptionCapability, browseCapability, connectionFields,
                pointAddressHints, EXTENDED_DATA_TYPES, ProtocolTypeMode.PLATFORM_ONLY, "dataType",
                PlatformDataTypeMode.REQUIRED, false, null, null, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * 兼容旧布尔能力标记，Provider 如需运行依赖或实验状态应使用显式能力枚举构造。
     */
    public ProtocolDescriptor(String code,
                              String title,
                              String description,
                              List<String> aliases,
                              Class<? extends ProtocolCollector> collectorClass,
                              String connectionType,
                              Integer defaultPort,
                              ProtocolAddressingMode addressingMode,
                              boolean implemented,
                              boolean writable,
                              boolean subscribable,
                              List<ProtocolFieldConfig> connectionFields,
                              List<String> pointAddressHints) {
        this(code, title, description, aliases, collectorClass, connectionType, defaultPort, addressingMode,
                stateOf(implemented), stateOf(writable), stateOf(subscribable),
                ProtocolCapabilityState.UNSUPPORTED, connectionFields, pointAddressHints);
    }

    public boolean implemented() {
        return implementationState.isAvailable();
    }

    public boolean writable() {
        return writeCapability.isAvailable();
    }

    public boolean subscribable() {
        return subscriptionCapability.isAvailable();
    }

    ProtocolDescriptor withSchema(List<String> dataTypes,
                                  ProtocolTypeMode typeMode,
                                  String primaryTypeField,
                                  PlatformDataTypeMode platformDataTypeMode,
                                  boolean driverTypeEnabled,
                                  String driverTypeLabel,
                                  String driverTypeField,
                                  List<String> driverDataTypes,
                                  List<ProtocolFieldConfig> pointFields) {
        return new ProtocolDescriptor(code, title, description, aliases, collectorClass, connectionType, defaultPort,
                addressingMode, implementationState, writeCapability, subscriptionCapability, browseCapability,
                connectionFields, pointAddressHints, dataTypes, typeMode, primaryTypeField, platformDataTypeMode,
                driverTypeEnabled, driverTypeLabel, driverTypeField, driverDataTypes, pointFields);
    }

    ProtocolDescriptor withPointFields(List<ProtocolFieldConfig> pointFields) {
        return withSchema(dataTypes, typeMode, primaryTypeField, platformDataTypeMode, driverTypeEnabled,
                driverTypeLabel, driverTypeField, driverDataTypes, pointFields);
    }

    ProtocolDescriptor withDriverPrimarySchema(String driverTypeLabel,
                                               List<String> driverDataTypes,
                                               List<ProtocolFieldConfig> pointFields) {
        return withSchema(EXTENDED_DATA_TYPES, ProtocolTypeMode.DRIVER_PRIMARY, "additionalConfig.driverDataType",
                PlatformDataTypeMode.DERIVED_EDITABLE, true, driverTypeLabel,
                "additionalConfig.driverDataType", driverDataTypes, pointFields);
    }

    ProtocolDescriptor withProtocolFieldPrimarySchema(String primaryTypeField,
                                                      List<ProtocolFieldConfig> pointFields) {
        return withSchema(EXTENDED_DATA_TYPES, ProtocolTypeMode.PROTOCOL_FIELD_PRIMARY, primaryTypeField,
                PlatformDataTypeMode.DERIVED_EDITABLE, false, null, null,
                Collections.emptyList(), pointFields);
    }

    static List<String> extendedDataTypes() {
        return EXTENDED_DATA_TYPES;
    }

    static List<String> dataTypesWithExtended(String... values) {
        return dataTypesWith(EXTENDED_DATA_TYPES, values);
    }

    private static ProtocolCapabilityState stateOf(boolean available) {
        return available ? ProtocolCapabilityState.SUPPORTED : ProtocolCapabilityState.UNSUPPORTED;
    }

    private static ProtocolCapabilityState defaultState(ProtocolCapabilityState state) {
        return state == null ? ProtocolCapabilityState.UNSUPPORTED : state;
    }

    private static String defaultPrimaryTypeField(ProtocolTypeMode typeMode) {
        return switch (typeMode) {
            case DRIVER_PRIMARY -> "additionalConfig.driverDataType";
            case PROTOCOL_FIELD_PRIMARY -> "additionalConfig.dptId";
            case PLATFORM_ONLY -> "dataType";
        };
    }

    private static PlatformDataTypeMode defaultPlatformDataTypeMode(ProtocolTypeMode typeMode) {
        return switch (typeMode) {
            case DRIVER_PRIMARY, PROTOCOL_FIELD_PRIMARY -> PlatformDataTypeMode.DERIVED_EDITABLE;
            case PLATFORM_ONLY -> PlatformDataTypeMode.REQUIRED;
        };
    }

    private static List<String> dataTypesWith(List<String> base, String... values) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(base);
        if (values != null) {
            merged.addAll(List.of(values));
        }
        return List.copyOf(merged);
    }
}
