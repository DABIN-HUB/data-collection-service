# Phase 6 四协议 Docker 仿真环境

本目录是本轮专用、与既有 ProtoForge Compose 隔离的四协议仿真环境。Compose 网络名为 `phase6-protocol-isolated`；宿主端口仅绑定 `127.0.0.1`。

## 启停

```bash
cd tools/protocol-simulators/phase6
docker compose -f compose.yml config
docker compose -f compose.yml build sim-mc sim-eip sim-bacnet
docker compose -f compose.yml up -d
docker compose -f compose.yml ps
docker compose -f compose.yml logs --no-color sim-opcua sim-mc sim-eip sim-bacnet
docker compose -f compose.yml down
```

不要用既有 `tools/protocol-simulators/docker-compose.yml` 的 `down` 操作停止本目录服务，两个 Compose 工程不是同一套服务。

## 端点与数据

| 服务 | 宿主端点 | 服务端 | 数据 |
|---|---|---|---|
| `sim-opcua` | `opc.tcp://127.0.0.1:14841`；管理页 `http://127.0.0.1:14842` | Microsoft `iot-edge/opc-plc` | 官方动态节点：4 个 Double 慢速节点、4 个 UInt 快速节点；NodeId 必须以 Browse 读回结果为准 |
| `sim-mc` | `127.0.0.1:15001/TCP` | Dreamine `Dreamine.PLC.Mitsubishi.MC` 1.0.1，Binary 3E | D0=1，D1=2；D100/D102/D200 按两个相邻 16 位寄存器保存 Float32：12.5、-3.25、88.75 |
| `sim-eip` | `127.0.0.1:14423/TCP` | `cpppo/cpppo` 固定 digest | 普通 Tag：Run BOOL、CPULoad REAL、MotorSpeed REAL、LinePressure REAL、Count DINT |
| `sim-bacnet` | `127.0.0.1:14782/UDP` | BACpypes3 0.0.110，设备实例 101 | BV1 active、BV2 inactive、AV1 22.0、AV2 101.25；BV1/AV1 每 5 秒在有界值集合内变化 |

`sim-opcua` 使用上游文档要求的 `--autoaccept`，客户端必须根据实际 Discovery 返回的安全策略连接，不能将 Anonymous/None 作为前提。当前命令使用 `--pn=14841`，容器日志和独立 Browse 结果是端点与 NodeId 的事实来源。

## 来源和可重复性

- OPC UA：<https://github.com/Azure-Samples/iot-edge-opc-plc>；镜像 tag `latest` 的本次拉取 digest 记录在 Phase 6 证据中。
- Mitsubishi MC：<https://github.com/CodeMaru-Dreamine/Dreamine.PLC.Mitsubishi.MC>；使用公开 MIT NuGet 包 `Dreamine.PLC.Mitsubishi.MC` 1.0.1，容器内是真实 `MitsubishiMcTcpSimulatorServer` 与 `MitsubishiMcBinary3ESimulatorProtocol`，不是复刻协议服务器。
- EtherNet/IP：<https://github.com/pjkundert/cpppo>；使用固定镜像 digest，先验证 ListServices、RegisterSession，再验证普通 Tag。
- BACnet/IP：<https://github.com/JoelBender/BACpypes3>；固定 `bacpypes3==0.0.110`，服务脚本基于官方 `mini-device-revisited.py` 的 `Application.from_args`、真实 BV/AV 对象和 BVLC/IP 服务。

镜像 digest、容器 ID、启动时间、独立请求/响应和 Collector 结果不得凭 `docker compose up` 推断，统一写入 `docs/protocol-live-20261009/phase6/` 的证据文件。运行日志只保存在本机，不提交到 Git。

## 本机核实的配置和边界

- OPC PLC 实际版本为 `v2.12.48.0+88e0995a2f`；`--ut` 显式开放 None/None，用户名和证书用户认证已关闭。`--autoaccept` 仅用于此隔离仿真，不作为生产安全建议。
- `--ph=0.0.0.0` 是容器监听地址，不能改为容器内127.0.0.1。Discovery 返回 `opc.tcp://0.0.0.0:14841/`，本次Windows Java17/Milo1.0.8已实际连接该返回地址读取、订阅；不保证其他操作系统客户端行为。宿主入口仍用127.0.0.1。
- Browse 已确认：`ns=3;s=SlowDouble1` 至 `SlowDouble4`、`ns=3;s=FastUInt1` 至 `FastUInt4`。不要沿用ProtoForge命名空间。
- Dreamine1.0.1的子头序列化为 `00 50 / 00 D0`。15001入口只将这两个子头字节与标准 `50 00 / D0 00` 互转，再转发给容器内真实Dreamine服务15002；命令、地址、内存及载荷由真实库处理。没有修改Collector兼容非标准帧，也不声称原库未经包装即通过标准认证。
- MC浮点为小端、低字在前：12.5=`00 00 48 41`，-3.25=`00 00 50 C0`，88.75=`00 80 B1 42`。D200低字为0x8000；错误设置0x0000会读成88.5。验证范围为Binary3E读取，不含4E、ASCII和完整PLC功能。
- MC使用 `packages.lock.json` 和 `dotnet restore --locked-mode`，锁定直接及传递依赖；.NET8 SDK/runtime和Python基础镜像均固定实际digest。
- cpppo镜像实际为cpppo3.9.7/Python2.7.9，只限本机隔离验证，不推荐部署为生产服务。每次启动经真实RegisterSession/CIP Write Tag初始化五点：true、31.25、1475.5、0.625、246810。初始化失败在20秒预算内退出；正常ListServices/PLC4X握手不禁用。普通Tag通过不替代Program helper验收。
- BACpypes3=0.0.110、packaging=26.3；四个真实BV/AV对象由BACpypes3提供BVLC、Who-Is/I-Am和属性服务。启用容器内UDP层DEBUG用于核对实际收发，不把端口映射当成协议通过。只验证定向单播，不外推跨网广播/BBMD。
- 所有容器 `restart: "no"`，不会因配置失败快速无限重启。重建单服务可执行 `docker compose -f compose.yml up -d --build sim-mc`；停止这四个容器前应先停止Collector中本轮专属设备。
- 独立客户端协议通过、当前class隔离采集通过、正式9090部署/E2E通过是独立门禁。凭据、证书、私钥、NuGet下载缓存、bin/obj和实际日志不提交Git。
