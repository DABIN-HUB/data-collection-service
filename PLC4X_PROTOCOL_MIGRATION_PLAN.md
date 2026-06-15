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

Suggested PLC4X replacement classes:

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
- `SIEMENS_S7` now supports PLC4X cyclic subscription for configured scalar points.
- `SIEMENS_S7` now exposes thin `executeCommand` wrappers for configured-point `read`, `write`, and `status` / `diagnostic`.
- `ETHERNET_IP` is now wired to a real PLC4X Logix adapter/collector path with scalar tag connect/read/write support.
- `ETHERNET_IP` now exposes thin `executeCommand` wrappers for configured-point `read`, `write`, and `status` / `diagnostic`.
- `ETHERNET_IP` subscription status is now explicitly closed as unsupported on the current PLC4X Logix driver path.
- `ETHERNET_IP` now supports limited whole-array read/write pass-through on the protocol edge when points do not rely on scalar-only processing settings.
- `ADS` is now wired to a real PLC4X ADS / AMS adapter/collector path and no longer remains a plan-only entry.
- `ADS` currently supports real connect/read/write plus symbolic/direct address normalization.
- `ADS` now supports PLC4X cyclic subscription for configured scalar points.
- `ADS` now exposes thin `executeCommand` wrappers for configured-point `read`, `write`, and `status` / `diagnostic`.
- `OPC_UA` is now routed to the PLC4X OPC UA collector/connection adapter path.
- `OPC_UA_PLC4X` is kept as a backward-compatible alias to the same PLC4X OPC UA implementation.
- `OPC_UA` / `OPC_UA_PLC4X` currently support real connect/read/write plus NodeId / PLC4X data-type normalization.
- `OPC_UA` / `OPC_UA_PLC4X` expose collector-side cyclic subscription registration for configured scalar points.
- `OPC_UA` / `OPC_UA_PLC4X` expose thin `executeCommand` wrappers for raw `read`, `write`, `status` / `diagnostic`, plus a metadata-gated `browse` path.
- `OPC_UA` / `OPC_UA_PLC4X` accept migration aliases for security and timeout fields such as `securityMode`, `authType`, `authParams`, `clientCertPath`, `clientCertPassword`, `requestTimeoutMs`, and `connectTimeoutMs`.
- Local embedded validation confirms anonymous connect/read/write and compatibility alias connection mapping for security and timeout fields.
- Real-server validation on the local Prosys Simulation Server confirms `opc.tcp://DESKTOP-IKHU04D:53530/OPCUA/SimulationServer` is readable through the PLC4X route.
- Real-server validation on `ns=3;i=1030` confirms write success through the PLC4X route.

In progress:

- `OPC_UA` browse behavior is still metadata-gated and currently returned unsupported on the local Prosys Simulation Server.
- `OPC_UA` subscription registration succeeds, but real-server value delivery is still not proven on the local Prosys Simulation Server.
- `OPC_UA` X509 behavior still needs real-server validation.

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

1. Array-style point access is still intentionally rejected; current implementation only targets scalar points.
2. Current validation is compile + targeted unit tests only; there is still no real PLC end-to-end verification in this repository.

### EtherNet/IP

Current code entry points:

- `src/main/java/com/wangbin/collector/core/collector/protocol/ethernetip/EtherNetIpCollector.java`
- `src/main/java/com/wangbin/collector/core/connection/adapter/EtherNetIpConnectionAdapter.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/ethernetip/util/EtherNetIpAddressParser.java`

Known remaining gaps:

1. `subscribe` remains intentionally unsupported because the current PLC4X Logix driver metadata reports no subscribe capability for the active connection path.
2. `executeCommand` now supports configured-point `read`, `write`, and `status` / `diagnostic`, but no broader protocol-specific command model has been introduced.
3. Whole-array tags now use a limited raw pass-through path and therefore must not rely on scalar-only processing settings such as scaling, precision, min/max, or alarm processing.
4. Current validation is compile + targeted unit tests only; there is still no real PLC end-to-end verification in this repository.

### ADS / AMS

Current code entry points:

- `src/main/java/com/wangbin/collector/core/collector/protocol/ads/AdsCollector.java`
- `src/main/java/com/wangbin/collector/core/connection/adapter/AdsConnectionAdapter.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/ads/util/AdsAddressParser.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/ads/util/AmsNetIdParser.java`

Known remaining gaps:

1. Array-style point access is still intentionally rejected; current implementation only targets scalar points.
2. `sourceAmsNetId` and `sourceAmsPort` are required today unless a full PLC4X connection string override is supplied.
3. Current validation is compile + targeted unit tests only; there is still no real PLC end-to-end verification in this repository.

### OPC UA

Current code entry points:

- `src/main/java/com/wangbin/collector/core/collector/protocol/opc/Plc4xOpcUaCollector.java`
- `src/main/java/com/wangbin/collector/core/connection/adapter/Plc4xOpcUaConnectionAdapter.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/opc/plc4x/domain/Plc4xOpcUaAddress.java`
- `src/main/java/com/wangbin/collector/core/collector/protocol/opc/plc4x/util/Plc4xOpcUaAddressParser.java`

Known remaining gaps:

1. `OPC_UA_PLC4X` is retained only as a backward-compatible alias; new configs should prefer `OPC_UA`.
2. Array-style point access is still intentionally rejected; the current PLC4X route only targets scalar points.
3. X509 handling now follows PLC4X-native `keyStore/trustStore` fields and still needs real-server validation.
4. `trustAllServerCert=true` is intentionally rejected on the generated PLC4X config path; if that behavior is needed, use `trustStoreFile` or provide a full `plc4xConnectionString`.
5. Local embedded validation covers anonymous connect/read/write and compatibility alias mapping, but runtime `browse` metadata still returned unsupported there.
6. Real-server validation confirms read and a single writable point (`ns=3;i=1030`) on the local Prosys Simulation Server, but end-to-end subscription value delivery is still unproven.
7. Endpoint path compatibility is partially validated by the local Prosys endpoint `/OPCUA/SimulationServer`; a broader matrix still needs confirmation before assuming arbitrary path coverage.

### Shared Closeout Notes

1. If S7, EtherNet/IP, ADS, `OPC_UA`, or `OPC_UA_PLC4X` fields change again, keep these files synchronized:
   - `ProtocolSchemaService`
   - `docs/protocols/FIELD_CONFIG_SUMMARY.md`
   - protocol-specific docs under `docs/protocols/`
2. Current targeted verification command is:
   - `mvn --% -o -Dmaven.repo.local=.m2 -Djdk.net.URLClassPath.disableClassPathURLCheck=true -Dtest=CollectorFactoryProtocolMappingTest,ConnectionFactoryProtocolAliasMappingTest,ProtocolSchemaServiceTest,ProtocolControllerTest,ProtocolBatchStrategyTest,ProtocolConnectionValidatorTest,S7AddressParserTest,S7CollectorTest,EtherNetIpAddressParserTest,EtherNetIpCollectorTest,AmsNetIdParserTest,AdsAddressParserTest,AdsCollectorTest,Plc4xOpcUaAddressParserTest,Plc4xOpcUaCollectorTest,Plc4xOpcUaConnectionAdapterTest,Plc4xOpcUaCollectorIntegrationTest,Plc4xOpcUaCollectorRealServerIT test -Dopcua.real.enabled=true`

## Next Session Order

This is the recommended execution order for the next session. Follow it directly unless requirements change.

1. Harden the cut-over `OPC_UA` PLC4X route.
   - Start from `src/main/resources/mock/opcuaPlc4xDevice.json` as the seed device config for real-server validation.
   - Re-validate browse behavior, subscription value delivery, endpoint-path compatibility, and security / certificate handling against real OPC UA servers.
   - Decide whether the compatibility alias `OPC_UA_PLC4X` can stay long-term or should be retired after configuration cleanup.
2. Keep `BACnet/IP` after `OPC_UA`.
   - `BACnet/IP` remains the highest-uncertainty protocol and should still be treated as a POC-first item.

## Restart Checklist

Use this checklist at the start of the next session:

1. Read this file first.
2. Re-open:
   - `S7Collector`
   - `EtherNetIpCollector`
   - `AdsCollector`
   - `Plc4xOpcUaCollector`
   - `ProtocolSchemaService`
3. Re-run compile:
   - `mvn --% -o -Dmaven.repo.local=.m2 -DskipTests compile`
4. Re-run targeted tests:
   - `mvn --% -o -Dmaven.repo.local=.m2 -Djdk.net.URLClassPath.disableClassPathURLCheck=true -Dtest=CollectorFactoryProtocolMappingTest,ConnectionFactoryProtocolAliasMappingTest,ProtocolSchemaServiceTest,ProtocolControllerTest,ProtocolBatchStrategyTest,ProtocolConnectionValidatorTest,S7AddressParserTest,S7CollectorTest,EtherNetIpAddressParserTest,EtherNetIpCollectorTest,AmsNetIdParserTest,AdsAddressParserTest,AdsCollectorTest,Plc4xOpcUaAddressParserTest,Plc4xOpcUaCollectorTest,Plc4xOpcUaConnectionAdapterTest,Plc4xOpcUaCollectorIntegrationTest,Plc4xOpcUaCollectorRealServerIT test -Dopcua.real.enabled=true`
5. If all green, start from `Next Session Order` step 1.

## Risk Notes

- `MODBUS_TCP` is the safest PLC4X entry point in this repository.
- `OPC_UA` has already been cut over to PLC4X, but it is still not a low-risk protocol because browse, subscription push delivery, and X509 behavior remain server-dependent.
- `BACnet/IP` should be treated as a separate POC before committing to framework integration.
