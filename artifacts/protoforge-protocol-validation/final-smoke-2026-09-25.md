# Final new-JAR smoke（不提交）

- JAR: `collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar`，`--server.port=9091`；观测 API 为 `/collector/api/device/runtime` 和 `/collector/api/data/device/{id}/compact`，每 60 秒一次。
- UTC 2026-09-25 02:29:57.906574 → 02:41:58.681469，**12 分 0.775 秒**，13 次完整采样（序号 0–12），原始实时输出保留于该会话 background process `proc_9779a1204953` log；所有 13 次点数一致。

| Protocol | Configured | Every minute valid value + available quality | Runtime phase/ready | Failures | Final |
|---|---:|---:|---|---|---|
| Modbus TCP | 16 | 16/16 | ONLINE/true | consecutiveFailures=0 | PASS |
| OPC UA | 8 | 8/8 | ONLINE/true | consecutiveFailures=0 | PASS |
| HTTP | 4 | 4/4 | ONLINE/true | consecutiveFailures=0 | PASS |
| MQTT | 6 | 6/6 | ONLINE/true | consecutiveFailures=0 | PASS_WITH_LIMITATION（冷启动曾 0/6，正式设备 stop/start-local 后恢复；本 12 分钟无回归） |
| Siemens S7 | 5 | 5/5 | ONLINE/true | consecutiveFailures=0 | PASS；本 12 分钟 degraded=0 次，未丢点；前一轮偶发 degraded 的持续时间无法从本次采样量化，不能据此声称其未曾发生 |
| IEC104 | 8 | 0/8 | ONLINE/true | consecutiveFailures=0（该计数不代表点值成功） | BLOCKED_BY_PROTOFORGE，字节级证据见 `iec104-wire-proof-2026-09-25.md` |

补充：新 JAR 冷启动阶段 MQTT 曾 0/6，期间独立订阅到 broker 发布，持久配置为 SUBSCRIBE；通过正式设备 API stop、start-local 后 6/6，随后重启计时并完成上述连续 12 分钟。无证据表明冷启动一定会自行恢复，因此这个生命周期限制需要后续独立复现和评估；本轮回归冻结，不为追求纯 PASS 修改 MQTT 生产代码。

测试阻断：主工作区和独立 clean patched worktree `mvn test` 均在 `FinsAddressParserTest.shouldRejectBitAddressForNonBooleanType` 失败；两边 `collector-protocol-plc` 都没有本地 diff，因此这不是 FINS dirty diff，而是当前 HEAD 的范围外测试基线失败。clean worktree `mvn -DskipTests package` BUILD SUCCESS；四协议针对性测试全部通过。未满足 clean root-test BUILD SUCCESS，因此本轮**不提交**。
