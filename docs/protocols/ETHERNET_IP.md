# ETHERNET_IP

## Current Status

- `ETHERNET_IP` is now a real PLC4X-backed protocol entry.
- The implementation uses PLC4X `logix` driver semantics by default.
- Factory, connection factory, validator, schema metadata, and parser are wired.
- The current path supports real connect/read/write for scalar tags.
- Protocol-level command execution now exposes thin configured-point wrappers for `read`, `write`, and `status` / `diagnostic`.
- Subscription remains intentionally unsupported on the current PLC4X Logix path because the driver metadata does not expose subscribe capability.
- Whole-array tags now support limited raw pass-through read/write on the protocol edge.

## Implementation Entry Points

- `core/collector/protocol/ethernetip/EtherNetIpCollector`
- `core/connection/adapter/EtherNetIpConnectionAdapter`
- `core/collector/protocol/ethernetip/domain/EtherNetIpTagAddress`
- `core/collector/protocol/ethernetip/util/EtherNetIpAddressParser`

## Supported Address Styles

The current implementation accepts both Logix-style and symbolic PLC4X forms.

1. Logix-style tags
   - `MainProgram.Tag1`
   - `Program:MainProgram.Tag2`
   - `Program:MainProgram.Tag2:REAL`
   - `Program:MainProgram.ArrayTag:REAL[4]`
2. Symbolic EtherNet/IP addresses
   - `%TagArray[0]:1:DINT`
   - `%Program:MainProgram.Tag2:1:REAL`

## Address Parsing Rules

- If the tag address already contains an explicit PLC4X type, the parser keeps it.
- If the type is omitted, the parser can infer it from point `dataType`.
- Point `additionalConfig` can override the inferred PLC4X type with:
  - `eipType`
  - `logixType`
  - `plc4xType`
  - `plcType`
- Indexed array elements such as `TagArray[1]` continue to behave like ordinary scalar points.
- Whole-array reads and writes are supported only as protocol-edge pass-through values.
- Whole-array points must not depend on scalar-only processing settings such as `scalingFactor`, `offset`, `precision`, `minValue`, `maxValue`, or alarm processing.
- Manual commands should resolve existing configured points by `reportField`, `pointAlias`, `pointCode`, `pointId`, `pointName`, or `address`.

## Connection Fields

```java
fields.add(createFieldConfig("host", "string", "Device host", true, "127.0.0.1", null));
fields.add(createFieldConfig("port", "number", "Port", false, "44818", null));
fields.add(createFieldConfig("communicationPath", "string", "Communication path", false, "[1,0]", null));
fields.add(createFieldConfig("backplane", "number", "Backplane", false, "1", null));
fields.add(createFieldConfig("slot", "number", "Slot", false, "0", null));
fields.add(createFieldConfig("maxFieldsPerRequest", "number", "Max fields per request", false, "64", null));
fields.add(createFieldConfig("bigEndian", "boolean", "Big-endian mode", false, "true",
        new String[]{"true", "false"}));
fields.add(createFieldConfig("forceUnconnectedOperation", "boolean", "Force unconnected operation", false, "false",
        new String[]{"true", "false"}));
fields.add(createFieldConfig("tcpKeepAlive", "boolean", "TCP keep-alive", false, "true",
        new String[]{"true", "false"}));
fields.add(createFieldConfig("tcpNoDelay", "boolean", "TCP no-delay", false, "true",
        new String[]{"true", "false"}));
fields.add(createFieldConfig("plc4xConnectionString", "string", "PLC4X connection string", false, "", null));
fields.add(createFieldConfig("readTimeout", "number", "Read timeout (ms)", false, "5000", null));
fields.add(createFieldConfig("timeout", "number", "Protocol timeout (ms)", false, "5000", null));
```

## Notes

- `EtherNetIpConnectionAdapter` builds a PLC4X `logix:tcp://` connection string by default.
- `communicationPath` has priority; if absent, the adapter falls back to `backplane` + `slot`.
- `EtherNetIpCollector` supports single-point and batched read/write.
- `EtherNetIpCollector.executeCommand(...)` reuses the normal point read/write path instead of introducing a separate protocol command model.
- Whole-array points are stored and reported as raw collection values; they do not flow through the scalar conversion and quality pipeline in `BaseCollector`.
- Batched reads are chunked by `maxFieldsPerRequest`.
