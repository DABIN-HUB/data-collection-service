# 协议文档索引

## 1. 已实现协议

- [MODBUS TCP](./MODBUS_TCP.md)
- [MODBUS RTU](./MODBUS_RTU.md)
- [SIEMENS S7](./SIEMENS_S7.md)
- [MITSUBISHI MC](./MITSUBISHI_MC.md)
- [OMRON FINS](./OMRON_FINS.md)
- [ETHERNET IP](./ETHERNET_IP.md)
- [ADS](./ADS.md)
- [KNXNET IP](./KNXNET_IP.md)
- [OPC UA](./OPC_UA.md)
- [OPC UA PLC4X](./OPC_UA_PLC4X.md)
- [OPC DA](./OPC_DA.md)
- [IEC104](./IEC104.md)
- [IEC61850](./IEC61850.md)
- [MQTT](./MQTT.md)
- [SNMP](./SNMP.md)
- [COAP](./COAP.md)
- [HTTP](./HTTP.md)
- [WEBSOCKET](./WEBSOCKET.md)
- [BACNET IP](./BACNET_IP.md)
- `BACNET_MSTP`

## 2. 实验性协议

- [DL/T 645-2007](./DLT645_2007.md)
  说明：协议栈、串口多表、读取和受控写入已完成，仍需目标电表连续运行验证。
- [IEC101](./IEC101.md)
  说明：非平衡控制站、FT1.2、ASDU、召唤和遥控已完成，仍需目标 RTU 互操作验证。
- `BACNET_SC`
  说明：已经接入统一采集链路，但标准 Hub/Node 控制帧与完整互操作尚未完成，当前不能按稳定生产协议承诺。
- `OPC_UA_MILO`
  说明：Milo 独立驱动已接入统一工厂，但尚未完成与默认 PLC4X 驱动的同服契约验收。
- [CUSTOM TCP](./CUSTOM_TCP.md) / `CUSTOM_UDP`
  说明：已具备独立 TCP/UDP 请求响应、帧边界和受控值解析，仍需按目标厂商协议实服验收。

## 3. 预研协议

- `PROFINET`
- `EtherCAT`

当前未注册为 Java 直连协议；已提供受鉴权的边缘遥测入口，但主站协议栈、实时循环和硬件在环验证仍在边缘进程侧完成。

## 4. 设计与规则文档

- [BACnet/IP 接入方案](../25-BACnet_IP接入方案.md)
- [DL/T 645 与 IEC101 接入方案](../29-DLT645与IEC101协议接入方案.md)
- [OMRON FINS 协议说明](./OMRON_FINS.md)
- [协议字段汇总](./FIELD_CONFIG_SUMMARY.md)
- [点位类型与协议原生类型最终规则](../20-点位类型与协议原生类型最终规则.md)

完整成熟度和能力边界以[采集协议支持与实现方式](../02-采集协议支持与实现方式.md)为准。
