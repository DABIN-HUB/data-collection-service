# SIEMENS S7

## Current Status

- `SIEMENS_S7` is no longer a scaffold-only protocol entry.
- The repository now contains a real PLC4X S7 connect/read/write path.
- Factory, connection factory, protocol validator, and schema metadata are all wired.
- The current path supports cyclic subscription for configured points, including one-dimensional full-array points.
- Polling reads now build S7-specific plans by DB / area / offset and attempt contiguous block reads before falling back to per-tag batch reads.
- `executeCommand` now exposes thin wrappers for configured-point `read`, `write`, and `status` / `diagnostic`.
- Retrofit task list: `../21-S7改造清单.md`.

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
   - `%DB1:0.0:BOOL`
   - `%DB1:4:REAL`
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
- One-dimensional arrays can be declared either inline with PLC4X syntax such as `%DB1:0:INT[4]` or by setting `additionalConfig.arraySize` when the address itself is scalar-like.

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
- `S7Collector` uses PLC4X cyclic subscriptions and reuses the existing `ingestPushedValue(...)` processing path.
- Subscription interval resolves from point adaptive interval first, then point base interval, then device collection interval.
- Batched reads are chunked by `maxFieldsPerRequest`.
- Eligible numeric polling plans use contiguous `BYTE[...]` block reads, then decode sub-values locally; parse or transport failures automatically fall back to the regular PLC4X multi-tag read path.
- Bit-level `BOOL` points and `STRING` / `WSTRING` points stay on the regular tag-batch path for now instead of forcing risky block slicing semantics.
- One-dimensional full-array read/write is supported for polling and cyclic subscription paths.
- Array points are passed through as list payloads with `ProcessResult.metadata.arrayValue=true` and `arraySize`.
- Array points still do not support `scalingFactor`, `offset`, `precision`, `min/max`, or `alarmEnabled`.

## Command Support

`executeCommand` currently supports:
- `read`
- `write`
- `status`
- `diagnostic`

Point resolution for `read` / `write` follows the same configured-point matching order used elsewhere in the project:
1. `reportField`
2. `pointAlias`
3. `pointCode`
4. `pointId`
5. `pointName`
6. `address`
