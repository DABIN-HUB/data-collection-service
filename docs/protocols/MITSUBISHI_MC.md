# MITSUBISHI_MC

## 1. 当前范围

当前 `MITSUBISHI_MC` 已实现并通过本地单元/集成测试的范围：

1. TCP
2. MC 3E Binary
3. MC 3E ASCII
4. MC 4E Binary
5. 轮询读
6. 基础写入
7. 连续标量点批量写
8. 位内偏移读写
9. 稀疏单字标量点随机读
10. 稀疏单字标量点随机写

当前仍不在交付稳定范围内：

1. 订阅
2. 远程控制
3. 多机型实机兼容验证
4. UDP 传输
5. 多字标量随机读写

## 2. 地址格式

支持的基础地址：

1. `M0`
2. `X1A`
3. `Y2F`
4. `D100`
5. `D100.3`
6. `D100[4]`
6. `R200`
7. `W300`
8. `ZR1000`

规则：

1. `X/Y/B/W` 使用十六进制地址。
2. `M/D/R/ZR` 使用十进制地址。
3. `BOOL` 仅允许位设备：
   `M/X/Y/B`
4. `D/R/W/ZR` 可使用 `D100.3` 这类位内偏移写法，或 `address=D100 + additionalConfig.bitIndex=3`
5. 位内偏移点位只能映射为 `BOOL`
6. 数值和字符串仅允许字设备：
   `D/R/W/ZR`
7. `STRING` 需要 `additionalConfig.stringLength`。

说明：

1. 位内偏移写入走 `read-modify-write`
2. 并发写同一字地址存在竞争窗口，当前未做专门字级锁

## 3. 原生数据类型

点位主类型字段：

`additionalConfig.driverDataType`

支持值：

1. `BOOL`
2. `INT16`
3. `UINT16`
4. `INT32`
5. `UINT32`
6. `FLOAT32`
7. `FLOAT64`
8. `STRING`

## 4. 连接字段

1. `host`
2. `port`
3. `networkNo`
4. `pcNo`
5. `ioNo`
6. `stationNo`
7. `monitoringTimer`
8. `frameType`
9. `randomReadEnabled`
10. `maxRandomReadPoints`
11. `randomWriteEnabled`
12. `maxRandomWritePoints`
13. `readTimeout`
14. `timeout`
15. `maxWordsPerRequest`
16. `maxBitsPerRequest`

默认值见 [22-MC协议P0实施清单](../22-MC协议P0实施清单.md)。

## 5. 点位扩展字段

1. `additionalConfig.driverDataType`
2. `additionalConfig.bitIndex`
3. `additionalConfig.stringLength`
4. `additionalConfig.arraySize`

`frameType` 当前可配置值：

1. `3E_BINARY`
2. `3E_ASCII`
3. `4E_BINARY`

说明：

1. 三种帧型均已完成代码实现并有本地测试覆盖
2. 当前尚未完成多系列 PLC 的实机兼容验证，不建议把 `ASCII/4E` 直接表述成“已完成现场稳定交付”

## 6. 当前策略边界

1. `randomReadEnabled/randomWriteEnabled` 当前只适用于稀疏、单字、标量、字设备点位。
2. `INT32/UINT32/FLOAT32/FLOAT64/STRING` 不走随机读写策略。
3. `D100.3` 这类位内偏移写入属于 `read-modify-write`，并发写同一字地址仍有竞争窗口。

## 7. 实现说明

当前协议实现文件位于：

1. `core/collector/protocol/mc`
2. `core/connection/adapter/MitsubishiMcConnectionAdapter.java`

详细实施拆分、阶段范围和验收标准见：

1. [22-MC协议P0实施清单](../22-MC协议P0实施清单.md)
2. [23-MC协议后续实施清单](../23-MC协议后续实施清单.md)
