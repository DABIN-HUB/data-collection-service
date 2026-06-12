# PLC4X Protocol Migration Plan

## Goal

This document records the PLC4X migration approach for this project.

Constraints:

- Keep the existing collection flow unchanged.
- Only modify or replace protocol-edge implementations.
- Do not change scheduling, processing, caching, reporting, or telemetry stream flow.

The stable core flow remains:

1. `CollectionScheduler`
2. `CollectionManager`
3. `CollectorFactory`
4. `BaseCollector`
5. `CollectorDataCacheAspect`
6. `TelemetryIngressService`

## Common Rules

The following classes should remain unchanged for PLC4X integration:

- `src/main/java/com/wangbin/collector/core/collector/scheduler/CollectionScheduler.java`
- `src/main/java/com/wangbin/collector/core/collector/manager/CollectionManager.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/base/BaseCollector.java`
- `src/main/java/com/wangbin/collector/core/cache/aspect/CollectorDataCacheAspect.java`

The following integration points are expected to change across protocols:

- `pom.xml`
- `CollectorFactory`
- `ConnectionFactory`
- `ProtocolSchemaService`
- `ProtocolConnectionValidator`
- `ProtocolType`
- `ProtocolBatchStrategy`
- `FIELD_CONFIG_SUMMARY.md`

## Protocol Split

### S7

Type: new protocol implementation on the existing framework.

Suggested new classes:

- `S7Collector`
- `S7ConnectionAdapter`
- `S7Address`
- `S7AddressParser`

Suggested modified classes:

- `CollectorFactory`
- `ConnectionFactory`
- `ProtocolSchemaService`
- `ProtocolConnectionValidator`
- `ProtocolType`
- `ProtocolBatchStrategy`

Suggested preserved classes:

- `CollectionScheduler`
- `CollectionManager`
- `BaseCollector`
- `CollectorDataCacheAspect`

### Modbus TCP

Type: replace current protocol-edge implementation, keep current Modbus batching and processing model.

Suggested new classes:

- `Plc4xModbusTcpCollector`
- `Plc4xModbusTcpConnectionAdapter`

Suggested replaced routing:

- `CollectorFactory` should route `MODBUS_TCP` to `Plc4xModbusTcpCollector`
- `ConnectionFactory` should route `MODBUS_TCP` to `Plc4xModbusTcpConnectionAdapter`

Suggested preserved classes:

- `AbstractModbusCollector`
- `ModbusTransport`
- `ModbusAddress`
- `RegisterType`
- `ModbusReadPlan`
- `ModbusReadPlanBuilder`
- `ModbusUtils`

Notes:

- Keep current point address parsing unchanged.
- Keep current batch plan and post-processing unchanged.
- Only replace the Modbus wire client.

### Modbus RTU

Type: replace current protocol-edge implementation, but with higher risk than TCP because of serial timing.

Suggested new classes:

- `Plc4xModbusRtuCollector`
- `Plc4xModbusRtuConnectionAdapter`

Suggested preserved classes:

- `AbstractModbusCollector`
- `ModbusAddress`
- `ModbusReadPlanBuilder`
- `ModbusUtils`

Suggested modified classes:

- `CollectorFactory`
- `ConnectionFactory`

Notes:

- Serial parameters must stay compatible with current config model.
- `interFrameDelay` likely needs compatibility handling outside PLC4X defaults.

### OPC UA

Type: replacement is possible, but not recommended as the first migration target.

Suggested new classes for parallel validation:

- `Plc4xOpcUaCollector`
- `Plc4xOpcUaConnectionAdapter`
- `Plc4xOpcUaAddress`
- `Plc4xOpcUaAddressParser`

Suggested eventual replacement targets:

- `OpcUaCollector`
- `OpcUaConnectionAdapter`
- `OpcUaAddress`
- `OpcUaAddressParser`

Suggested preserved classes:

- scheduler and processing chain
- cache/report/stream chain

Notes:

- Current implementation is deeply coupled to Milo.
- X509 auth and browse behavior must be revalidated before cutover.

### EtherNet/IP

Type: new protocol implementation on the existing framework.

Suggested new classes:

- `EtherNetIpCollector`
- `EtherNetIpConnectionAdapter`
- `EtherNetIpTagAddress`
- `EtherNetIpAddressParser`

Preferred direction:

- Prefer PLC4X `logix` driver semantics for Rockwell tag access.

### ADS / AMS

Type: new protocol implementation on the existing framework.

Suggested new classes:

- `AdsCollector`
- `AdsConnectionAdapter`
- `AdsAddress`
- `AdsAddressParser`
- `AmsNetIdParser`

Notes:

- Config schema must expand for AMS Net ID and AMS port fields.

### BACnet/IP

Type: new protocol implementation on the existing framework, highest uncertainty.

Suggested new classes:

- `BacnetIpCollector`
- `BacnetIpConnectionAdapter`
- `BacnetObjectAddress`
- `BacnetObjectAddressParser`
- `BacnetEdeCatalogService`
- `BacnetPointResolver`

Notes:

- This protocol may require EDE file lifecycle support.
- It is the least likely to fit current direct-address modeling without extra work.

## Recommended Delivery Order

1. Modbus TCP
2. Modbus RTU
3. S7
4. EtherNet/IP
5. ADS / AMS
6. OPC UA
7. BACnet/IP

## Current Implementation Phase

Completed so far:

- `MODBUS_TCP` has been switched to PLC4X collector/adapter routing.
- `MODBUS_RTU` has been switched to PLC4X collector/adapter routing.
- `MODBUS_ASCII` now reuses the same PLC4X serial collector entry and resolves to PLC4X `modbus-ascii` connection strings when the device protocol type is `MODBUS_ASCII`.
- `SIEMENS_S7` is now wired to a real PLC4X S7 adapter/collector path and no longer a scaffold-only protocol entry.
- `SIEMENS_S7` currently supports real connect/read/write plus TIA/PLC4X address normalization.
- `ETHERNET_IP` is now wired to a real PLC4X Logix adapter/collector path with scalar tag connect/read/write support.

In progress:

- Remaining gaps for S7 are subscription support and protocol command coverage.
- `ETHERNET_IP` currently supports Logix-style tag normalization plus real connect/read/write for scalar tags.
- Remaining gaps for `ETHERNET_IP` are subscription support, protocol command coverage, and array tag handling.

What stays unchanged in the current implementation:

- scheduler model
- `CollectionManager`
- `BaseCollector`
- `AbstractModbusCollector`
- cache/report/stream chain

## Current Gap List

### S7

Current code entry points:

- `src/main/java/com/wangbin/collector/core/collector/protocol/s7/S7Collector.java`
- `src/main/java/com/wangbin/collector/core/connection/adapter/S7ConnectionAdapter.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/s7/util/S7AddressParser.java`

Known remaining gaps:

1. `subscribe` is still explicitly unsupported in `S7Collector`.
2. `executeCommand` is still explicitly unsupported in `S7Collector`.
3. Array-style point access is still intentionally rejected; current implementation only targets scalar points.
4. Current validation is compile + targeted unit tests only; there is still no real PLC end-to-end verification in this repository.

### EtherNet/IP

Current code entry points:

- `src/main/java/com/wangbin/collector/core/collector/protocol/ethernetip/EtherNetIpCollector.java`
- `src/main/java/com/wangbin/collector/core/connection/adapter/EtherNetIpConnectionAdapter.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/ethernetip/util/EtherNetIpAddressParser.java`

Known remaining gaps:

1. `subscribe` is still explicitly unsupported in `EtherNetIpCollector`.
2. `executeCommand` is still explicitly unsupported in `EtherNetIpCollector`.
3. Array tag access is still intentionally blocked; current implementation only targets scalar tags.
4. Current validation is compile + targeted unit tests only; there is still no real PLC end-to-end verification in this repository.

### Shared Closeout Notes

1. If S7 or EtherNet/IP fields change again, keep these files synchronized:
   - `ProtocolSchemaService`
   - `docs/protocols/FIELD_CONFIG_SUMMARY.md`
   - protocol-specific docs under `docs/protocols/`
2. Current targeted verification command is:
   - `mvn --% -o -Dmaven.repo.local=.m2 -Dtest=CollectorFactoryProtocolMappingTest,ConnectionFactoryProtocolAliasMappingTest,ProtocolSchemaServiceTest,ProtocolControllerTest,ProtocolBatchStrategyTest,S7AddressParserTest,EtherNetIpAddressParserTest test`

## Next Session Order

This is the recommended execution order for the next session. Follow it directly unless requirements change.

1. Close out the remaining S7 gaps first.
   - First confirm whether PLC4X S7 subscription is actually available and usable in this project shape.
   - If subscription is not realistically supportable, keep it unsupported and document it as an intentional limitation instead of treating it as unfinished code.
   - Then decide whether `executeCommand` should become a thin wrapper over existing write semantics or stay intentionally unsupported.
2. Close out the remaining EtherNet/IP gaps second.
   - Use the same decision rule as S7 for subscription support.
   - Decide whether `executeCommand` should map to tag write behavior or remain unsupported by design.
   - Only handle array tags after subscription/command status is clear.
3. Start `ADS / AMS` only after the two current partially-finished protocols are closed out.
   - Add PLC4X dependency.
   - Add `ProtocolType`, `CollectorFactory`, `ConnectionFactory`, `ProtocolConnectionValidator`, `ProtocolSchemaService`, and `ProtocolBatchStrategy` entries.
   - Implement the planned protocol edge classes:
     - `AdsCollector`
     - `AdsConnectionAdapter`
     - `AdsAddress`
     - `AdsAddressParser`
     - `AmsNetIdParser`
4. Keep `OPC_UA` and `BACnet/IP` after `ADS / AMS`.
   - `OPC_UA` remains a replacement/refactor problem, not a quick swap.
   - `BACnet/IP` remains the highest-uncertainty protocol and should still be treated as a POC-first item.

## Restart Checklist

Use this checklist at the start of the next session:

1. Read this file first.
2. Re-open:
   - `S7Collector`
   - `EtherNetIpCollector`
   - `ProtocolSchemaService`
3. Re-run compile:
   - `mvn --% -o -Dmaven.repo.local=.m2 -DskipTests compile`
4. Re-run targeted tests:
   - `mvn --% -o -Dmaven.repo.local=.m2 -Dtest=CollectorFactoryProtocolMappingTest,ConnectionFactoryProtocolAliasMappingTest,ProtocolSchemaServiceTest,ProtocolControllerTest,ProtocolBatchStrategyTest,S7AddressParserTest,EtherNetIpAddressParserTest test`
5. If all green, start from `Next Session Order` step 1.

## Risk Notes

- `MODBUS_TCP` is the safest PLC4X entry point in this repository.
- `OPC_UA` is not a low-risk swap because the current implementation is not shallow.
- `BACnet/IP` should be treated as a separate POC before committing to framework integration.
