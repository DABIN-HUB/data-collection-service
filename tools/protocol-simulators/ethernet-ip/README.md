# EtherNet/IP Program: 标量读取边界

`collector-program-import.json` 对应独立的 cpppo 服务 `127.0.0.1:14419`，含五个 `Program:Main.*` 标量。`Count` 在模拟端定义为 `DINT`，因此导入点类型是 `INT32`，五个点均标记只读。

PLC4X 0.13.0 的 `EipTag` 正则拒绝 `Program:Main.Run`；即使手动创建 `EipTag`，`EipProtocolLogic.toAnsi` 也会忽略 `:`，将 `Program:Main` 错拆成两个符号段。Collector 只对 `Program:` 标量读使用独立、带超时的 CIP SendRRData / UCMM Read Tag 路径，首个 ANSI Extended Symbol Segment 为完整 `Program:Main`；普通标签仍走 PLC4X。响应需通过会话、长度、服务码、状态、类型和标量长度检查，失败不返回伪值。该辅助路径每次读建立并关闭独立 TCP 会话，不复用 PLC4X 会话句柄。

本地验证：cpppo 客户端读到 Run=true、CPULoad=47.5、MotorSpeed=1200.25、LinePressure=6.75、Count=42；同一正在运行的 cpppo 服务上，编译后的 `ProgramTagCipClient` 五个 Java 调用也读到上述五个值。`EtherNetIpAddressParserTest`、`ProgramTagCipClientTest`、`EtherNetIpCollectorTest` 合计 24 个测试通过。**这尚不是 Collector 设备导入、运行时质量或实机互操作通过证明。**

限制：当前独立 Program 路径仅支持单元素 BOOL/SINT/INT/DINT/LINT/REAL/LREAL 读取；Program 写入明确拒绝，数组、跨槽路由、连接式 CIP、多服务聚合尚未验证。显式 PLC4X 连接串仍需同步配置真实的 `host`/`port` 才能读取 Program 点；模拟端并不覆盖真实 ControlLogix 背板路由。需要按上级 `tools/protocol-simulators/README.md` 的 Collector-side 检查再次确认每个 pointId 的非空值、质量和新鲜时间戳。
