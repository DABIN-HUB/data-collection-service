# ADS

## Current Status

- `ADS` is now a real PLC4X-backed protocol entry.
- The implementation uses PLC4X `ads:tcp://` connection semantics by default.
- Factory, connection factory, validator, schema metadata, and parser are wired.
- The current path supports real connect/read/write for configured scalar points.
- PLC4X cyclic subscription is enabled for configured scalar points when the runtime metadata reports subscribe capability.
- Protocol-level command execution now exposes thin configured-point wrappers for `read`, `write`, and `status` / `diagnostic`.

## Implementation Entry Points

- `core/collector/protocol/ads/AdsCollector`
- `core/connection/adapter/AdsConnectionAdapter`
- `core/collector/protocol/ads/domain/AdsAddress`
- `core/collector/protocol/ads/util/AdsAddressParser`
- `core/collector/protocol/ads/util/AmsNetIdParser`

## Supported Address Styles

The current implementation accepts both symbolic TwinCAT-style and direct ADS forms.

1. Symbolic addresses
   - `MAIN.temperature`
   - `GVL.deviceStatus`
   - `MAIN.arrayValue[1]`
2. Direct ADS addresses
   - `0x4020/0x0:REAL`
   - `16416/32:DINT`
   - `16416/64:STRING(80)`
   - `0x4020/0x0:DINT[4]`

## Address Parsing Rules

- Symbolic addresses are preserved as-is for PLC4X tag resolution.
- Direct addresses can infer PLC4X type from point `dataType` when the address only contains `indexGroup/indexOffset`.
- Point `additionalConfig` can override the inferred PLC4X type with:
  - `adsType`
  - `plc4xType`
  - `plcType`
- `STRING` and `WSTRING` can set length with:
  - `stringLength`
  - `adsStringLength`
- Direct array syntax is recognized, but the current collector still targets scalar point handling first.

## Connection Fields

```java
fields.add(createFieldConfig("host", "string", "Device host", true, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "TCP port", false, "48898", null));
fields.add(createFieldConfig("targetAmsNetId", "string", "Target AMS Net ID", true, "", null));
fields.add(createFieldConfig("targetAmsPort", "number", "Target AMS port", true, "851", null));
fields.add(createFieldConfig("sourceAmsNetId", "string", "Source AMS Net ID", true, "", null));
fields.add(createFieldConfig("sourceAmsPort", "number", "Source AMS port", true, "", null));
fields.add(createFieldConfig("loadSymbolAndDataTypeTables", "boolean", "Load symbol/data type tables", false, "true",
        new String[]{"true", "false"}));
fields.add(createFieldConfig("timeoutRequest", "number", "ADS request timeout (ms)", false, "4000", null));
fields.add(createFieldConfig("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null));
fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X connection string", false, "", null));
fields.add(createFieldConfig("readTimeout", "number", "Read timeout (ms)", false, "5000", null));
fields.add(createFieldConfig("timeout", "number", "Protocol timeout (ms)", false, "5000", null));
```

## Notes

- `AdsConnectionAdapter` builds a real `ads:tcp://` PLC4X connection by default.
- `AdsConnectionAdapter` requires both target and source AMS Net ID / AMS port fields unless a full PLC4X connection string override is supplied.
- `AdsCollector` supports single-point and batched read/write.
- `AdsCollector` uses PLC4X cyclic subscriptions and reuses the existing `ingestPushedValue(...)` processing path.
- Subscription interval resolves from point adaptive interval first, then point base interval, then device collection interval.
- Batched reads are chunked by `maxFieldsPerRequest`.
- Array-style point access is still intentionally rejected; the current path targets scalar points first.

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
