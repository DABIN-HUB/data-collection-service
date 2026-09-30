# IEC104 wire-level proof（未提交证据）

- Capture UTC: 2026-09-25T02:21:46.023147+00:00；TCP `127.0.0.1:2404 → 127.0.0.1:50174`，独立 Python socket 接收完整 APDU，原始字节来自运行中的 ProtoForge 1.3.0（`importlib.metadata.version('protoforge')`），镜像 `suoten/protoforge:latest`（sha256:25029cbcae00650fa5685f206cb69e8204bd9a5523b185548dad03fc24506a49，镜像创建于 2026-09-16T12:10:58Z）。此次为 STARTDT 后服务器主动推送的 M_ME_NC_1，非模拟数据；VSQ SQ=0。
- Collector 持久连接配置：host=127.0.0.1, port=2404, extJson.commonAddress=1, slaveId=1，未设置 cotFieldLength/commonAddressFieldLength/ioaFieldLength/ioaEncodingMode。项目 `Iec104ConnectionAdapter` 实际默认 COT=2、CA=2、IOA=3；`AbstractIce104Collector` 默认 STANDARD。ProtoForge DB `/app/data/protoforge.db` 中 `pf_proto_iec104` 的 `protocol_config`：common_address=1、scan_interval=1、collect_interval=5；originator_address 缺省 0。ProtoForge UI schema 提供 common_address 和 originator_address，但 **COT length、CA length、IOA length NOT CONFIGURABLE**；服务源码固定 COT `<H`、单独 OA `<B`、CA `<H`、IOA 3 字节。

完整 APDU hex：`68 13 00 00 00 00 0d 01 01 00 00 01 00 a1 0f 00 a4 19 9c 44 00`

| APDU offset | hex | 标准 IEC104 / j60870 按 COT=2、CA=2、IOA=3 的含义 | 跳过多余 OA 后的含义 |
|---|---|---|---|
| 0 | 68 | Start | 同左 |
| 1 | 13 | APDU length = 19 | 同左 |
| 2–5 | 00 00 00 00 | I-format control, N(S)=0, N(R)=0 | 同左 |
| 6 | 0d | TypeId=13 M_ME_NC_1（短浮点测量） | 同左 |
| 7 | 01 | VSQ=1，SQ=0 | 同左 |
| 8 | 01 | COT=1（periodic） | 同左 |
| 9 | 00 | COT 第二字节，即 OA=0 | 同左 |
| 10 | 00 | 标准 CA 低字节；**服务端额外写入 OA=0** | 多余 OA=0 |
| 11–12 | 01 00 | 标准 CA 高字节=01，IOA 低字节=00 | CA=0001（低位优先），值 1 |
| 13–15 | a1 0f 00 | 标准 IOA 后两字节与浮点首字节，按 12–14 实际 IOA `00 a1 0f`=1024256 | IOA `a1 0f 00`=4001 |
| 16–20 | a4 19 9c 44 00 | 标准解码剩余不足一个 M_ME_NC_1 object（FLOAT32+QDS），前面一字节已被 IOA 消耗 | float32 LE `a4 19 9c 44`=1248.80126953125，QDS=00 |

**独立解码**：直接从 socket 捕获完整 frame，Python `int.from_bytes(...,'little')` 和 `struct.unpack('<f', ...)` 手工按 IEC104 固定字段宽度解析。标准布局得 CA=256、IOA=1024256 且测量元素缺一字节；跳过 offset 10 额外字节恢复 CA=1、IOA=4001、有限有效浮点值。这与持久点位 `phase_current_a → 4001` 一致。j60870 客户端既有配置 `2/2/3` 使用相同字段窗口；对 GI 请求的服务端源码 `_process_asdu` 同样按 `asdu[4]` 读取额外 OA、按 `asdu[5:7]` 读取 CA，因此会把标准 GI 的 CA 错读为 0/其它值并丢弃。

ProtoForge 服务源码 `/app/protoforge/protocols/iec104/server.py:619-626` `_asdu_header`: `h += struct.pack('<H', cot & 0x3F); h.append(self._originator_address & 0xFF); h += struct.pack('<H', ca & 0xFFFF)`，重复添加一个 OA；`server.py:467-481` 对入站包也错误地以 `asdu[4]` 当 OA、`asdu[5:7]` 当 CA。COT=2 字节时第二字节已经是 OA，标准 ASDU 头应直接在 offset 10 写 CA。没有在 Collector 增加 ProtoForge 专用兼容分支。此实际帧 SQ=0，不用于宣称真实 sequence SQ=1 的现场验证；sequence 路径只由现有 IEC104 回归覆盖。

结论：`PROTOFORGE_SERVICE` / `BLOCKED_BY_PROTOFORGE`。受影响的是服务器发出的测量帧和它对标准 GI 的 CA 解析；无法通过改变 Collector 字段长度或 IOA 模式正确补偿。仍需单独记录同配置下标准兼容 server 的对照测试或既有现成回归测试结果。
