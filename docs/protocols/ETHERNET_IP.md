# ETHERNET_IP

## Current Status

- `ETHERNET_IP` is now a real PLC4X-backed protocol entry.
- The implementation uses PLC4X `logix` driver semantics by default.
- Factory, connection factory, validator, schema metadata, and parser are wired.
- The current path supports real connect/read/write for scalar tags.
- Subscription, protocol-level command execution, and array tag access are still not implemented.

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
- Array reads and writes are intentionally blocked for now; the current collector targets scalar tags first.

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
- Batched reads are chunked by `maxFieldsPerRequest`.
