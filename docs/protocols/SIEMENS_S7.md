# SIEMENS S7

## Current Status

- `SIEMENS_S7` is no longer a scaffold-only protocol entry.
- The repository now contains a real PLC4X S7 connect/read/write path.
- Factory, connection factory, protocol validator, and schema metadata are all wired.
- Subscription and protocol-level command execution are still not implemented.

## Implementation Entry Points

- `core/collector/protocol/s7/S7Collector`
- `core/connection/adapter/S7ConnectionAdapter`
- `core/collector/protocol/s7/domain/S7Address`
- `core/collector/protocol/s7/util/S7AddressParser`

## Supported Address Styles

The current implementation accepts both project-friendly shorthand and native PLC4X syntax.

1. TIA-style shorthand
   - `DB1.DBX0.0`
   - `DB1.DBW0`
   - `DB1.DBD4`
   - `I0.0`
   - `Q0.0`
   - `M10.0`
2. Native PLC4X style
   - `DB1:0.0:BOOL`
   - `DB1:4:REAL`
   - `%DB56.DBW20:INT`

## Address Parsing Rules

- Bit addresses such as `DBX`, `I0.0`, `Q0.0`, and `M10.0` are normalized to `BOOL`.
- Word and dword addresses infer the PLC4X type from the point `dataType`.
- Point `additionalConfig` can override the inferred PLC4X type with:
  - `s7Type`
  - `plc4xType`
  - `plcType`
- `STRING` and `WSTRING` can set length with:
  - `stringLength`
  - `s7StringLength`

## Connection Fields

```java
fields.add(createFieldConfig("host", "string", "Device host", true, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "Port", false, "102", null));
fields.add(createFieldConfig("rack", "number", "Rack", false, "0", null));
fields.add(createFieldConfig("slot", "number", "Slot", false, "1", null));
fields.add(createFieldConfig("controllerType", "select", "Controller type", false, "S7_1200",
        new String[]{"S7_300", "S7_400", "S7_1200", "S7_1500", "LOGO"}));
fields.add(createFieldConfig("pduSize", "number", "PDU size", false, "1024", null));
fields.add(createFieldConfig("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null));
fields.add(createFieldConfig("localTsap", "number", "Local TSAP", false, "", null));
fields.add(createFieldConfig("remoteTsap", "number", "Remote TSAP", false, "", null));
fields.add(createFieldConfig("localDeviceGroup", "select", "Local device group", false, "",
        new String[]{"PG_OR_PC", "OS", "OTHERS"}));
fields.add(createFieldConfig("remoteDeviceGroup", "select", "Remote device group", false, "",
        new String[]{"PG_OR_PC", "OS", "OTHERS"}));
fields.add(createFieldConfig("ping", "boolean", "Enable PLC4X ping", false, "false",
        new String[]{"true", "false"}));
fields.add(createFieldConfig("pingTime", "number", "Ping interval (s)", false, "", null));
fields.add(createFieldConfig("retryTime", "number", "Retry time (s)", false, "", null));
fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X connection string", false, "", null));
fields.add(createFieldConfig("readTimeout", "number", "Read timeout (ms)", false, "5000", null));
fields.add(createFieldConfig("timeout", "number", "Protocol timeout (ms)", false, "5000", null));
```

## Notes

- `S7ConnectionAdapter` builds a real `s7://` PLC4X connection.
- `S7Collector` supports single-point and batched read/write.
- Batched reads are chunked by `maxFieldsPerRequest`.
- Array-style addresses are rejected for now; the current path targets scalar points first.
