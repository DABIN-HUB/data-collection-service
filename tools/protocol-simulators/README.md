# Independent protocol reference environment

These services are **independent of ProtoForge**. A PASS for a `ref_*` device does **not** mean its `pf_proto_*` counterpart has recovered. Never infer collection success from an open port, ONLINE, or an old cached value. A PASS requires a running Collector and simulator, a real request/subscription, the configured `pointId`/`pointCode`, non-null value, acceptable quality, `stale=false`, and a current timestamp matching the simulator or advancing on subsequent samples.

## Prerequisites and configuration

Run the commands from this directory in Git Bash on Windows. Docker Desktop (Linux containers), Docker Compose, Python 3, Java (WireMock fallback), `curl` and `uv` (for BACnet/cpppo) are used. The Collector runs on the host; container ports are published to host loopback. Docker Desktop host networking is not equivalent to a native Linux broadcast interface: run BACnet on the host for reliable local UDP discovery. Check port ownership before starting. Keep any Collector API credentials in your local shell/secret store; **do not add them to this repository**. The `*/collector-import.json` files are separate reference-device bundles for `POST /collector/api/config/import`; inspect target identifiers before importing into a shared database. Existing ProtoForge devices must not be overwritten. If credentials are required, use the authenticated API flow configured for this deployment; no credential is supplied here.

Start Compose services independently (the IEC 61850 model is experimental; see below):

```sh
docker compose up -d mqtt mqtt-publisher iec104 s7
python s7/configure.py
# Optional; Docker Hub access for WireMock may fail on some networks:
docker compose up -d http
curl -f http://127.0.0.1:18080/api/v1
```

If the WireMock image cannot be pulled, obtain `wiremock-standalone-3.13.2.jar` from the official WireMock Maven artifact, then run `java -jar /path/to/wiremock-standalone-3.13.2.jar --port 18080 --root-dir ./http` from this directory. `./http/mappings` contains the same mappings for either mode.

| Protocol | Reference endpoint | Simulator data/model | Collector import file | Simulator-side check |
|---|---|---|---|---|
| MQTT | 127.0.0.1:18830 | retained `protoforge/device001/hvac/{current_temp,set_temp,mode,fan,power,humidity}` = 25.6, 24, 1, 2, true, 58.2, published every five seconds | `mqtt/collector-import.json` (SUBSCRIBE, six points) | `docker compose exec mqtt mosquitto_sub -h localhost -p 1883 -t 'protoforge/device001/hvac/#' -C 6 -W 10 -v` |
| HTTP | 127.0.0.1:18080 | `/api/v1`: temperature 26.5, humidity 61.2, pressure 101.3, status 1; also `/api/v1/sensor/{temperature,humidity,pressure,status}` | `http/collector-import.json` (four points); `http/collector-single-endpoint-import.json` (one point) | `curl -f http://127.0.0.1:18080/api/v1` and `curl -f http://127.0.0.1:18080/api/v1/sensor/temperature` |
| IEC 104 | 127.0.0.1:12404 | IOA 4001–4008, configured in `iec104/SimulationOptions.json` | `iec104/collector-import.json` (eight points) | inspect `docker compose logs iec104` and actual ASDU/IOA exchange |
| Siemens S7 | 127.0.0.1:11020; management 18082 | DB1: DBX0.0=1, DBX0.1=0, DBX1.0=1, DBD4≈12.34, DBD8≈56.78 | `s7/collector-import.json` (five points) | `python s7/configure.py` writes and reads back DB1; this is not a substitute for PLC4X reads |
| Omron FINS | 127.0.0.1:19600/UDP | CIO0=1; DM0=75; DM1=23.5; DM3=101.25; DM5=14.75; DM7=9 | `fins/collector-import.json` (six points) | inspect `FINS read` log for real memory-area-read frames and end code `0000` |
| BACnet/IP | 127.0.0.1:14780/UDP; wiretap 14781/UDP | Device 101, BV:1/BV:2, AV:1/AV:2 in `bacnet/devices/ref101.yaml` | `bacnet/collector-import.json` (four points, routed through 14781) | inspect wiretap datagrams for Who-Is/I-Am and ReadProperty request/response |
| EtherNet/IP | 127.0.0.1:14418; Program tags 14419 | Run, CPULoad, MotorSpeed, LinePressure, Count, with `Program:Main.*` equivalents | `ethernet-ip/collector-import.json` and `ethernet-ip/collector-program-import.json` (five each) | use `python -m cpppo.server.enip.client --address 127.0.0.1:14418 --print Run CPULoad MotorSpeed LinePressure Count` (and Program equivalents on 14419) |
| IEC 61850 | 127.0.0.1:11030 (host mapping to MMS 102) | `iec61850/model.cid` and `iec61850/config.xml` | see IEC 61850 section | require MMS association and eight data reads; TCP alone is insufficient |

The HTTP aggregate request is configured at device level; per-point extraction is `DIRECT`/`JSON_PATH`/`$.sensor`. The single-point route instead extracts `$.value`. Incorrect routes returning HTTP 404 must be treated as request failures, not cache errors. FINS `CIO0.00` is a **bit** address: use BOOLEAN; a `UINT16` point must use `CIO0` (word). Do not relax the parser to admit bit+word configurations.

## Host-process services

FINS runs with the Python standard library: `python fins/server.py --port 19600` (keep the process running; Ctrl-C to stop). It implements only FINS/UDP memory-area-read `01 01` for the configured CIO/DM areas: it is an independent packet-level simulator, **not a complete third-party FINS stack**.

BACnet runs using upstream `https://github.com/quentinnippert/bacnet-simulator` and its BACpypes3 dependencies. Clone outside this repository, run `uv sync --frozen` in that checkout, then follow its documented CLI using the absolute path to this directory's `bacnet/devices/ref101.yaml`; bind host loopback UDP 14780 and management HTTP 18083. From another terminal run `python bacnet/wiretap.py` (UDP 14781). Configure the Collector to contact the wiretap on 14781. Confirm the upstream simulator's launch arguments against the checked-out version before running; its management HTTP alone does not prove BACnet reads. Stop both Python processes with Ctrl-C.

EtherNet/IP runs via upstream `https://github.com/pjkundert/cpppo` (`uv pip install cpppo` or an isolated venv). Start `python -m cpppo.server.enip --address 0.0.0.0:14418 Run=BOOL CPULoad=REAL MotorSpeed=REAL LinePressure=REAL Count=DINT` for simple tags, and an independent server at 14419 with `Program:Main.Run=BOOL Program:Main.CPULoad=REAL Program:Main.MotorSpeed=REAL Program:Main.LinePressure=REAL Program:Main.Count=DINT`. Initialize values using cpppo's CLI as described upstream, then read back with its client before importing Collector bundles. cpppo implements a subset of explicit unconnected CIP messaging; it is not a full PLC or implicit-I/O implementation. Stop both servers with Ctrl-C. A cpppo client read does not prove PLC4X accepts the same Program-scoped address syntax.

## IEC 61850

The pinned `stinging/61850-sim:1.0` image uses model/config files in `iec61850/`. Start with `docker compose up -d iec61850`; check `docker compose logs iec61850`. The MMS service must associate and expose eight configured model objects before importing an IEC 61850 reference device. The simulator's example PM/ION ICD objects are **not** interchangeable with the original ProtoForge paths. Until COTP, MMS association, eight non-null point reads, good quality and fresh API timestamps have been checked, report **BLOCKED/FAIL, never PASS**. The image's privileged internal port 102 is published at host 11030; don't claim host port 102 is listening.

## Collector-side check and shutdown

可先在本目录执行只读导入清单检查：`node ops/check-reference.mjs --list mqtt/collector-import.json`。启动模拟器、Collector 并导入设备后，将令牌仅放入环境变量 `COLLECTOR_TOKEN`；按需设置 `COLLECTOR_BASE_URL`（默认 `http://127.0.0.1:9090/collector`），然后运行 `node ops/check-reference.mjs mqtt/collector-import.json`。脚本只连接本机 HTTP 服务，不打印令牌或完整响应，不写文件；针对导入文件中的每个 `pointId` 检查原始设备 DTO 两次采样的 `pointCode`、非空值、`qualityAvailable/qualityAcceptable`、`stale=false`、`realtimeStatus=GOOD` 以及递增且未过期的采集时间。检查失败退出码为 1。此检查**不能**替代对模拟器请求/订阅日志与实际值的人工交叉核验；常量值或只缓存旧值不能直接判 PASS。

Import only the relevant `collector-import.json` through `POST /collector/api/config/import`, then start the resulting reference device through the existing Collection API. Fetch `GET /collector/api/data/device/<ref_device_id>` twice, separated by at least one sampling interval, and inspect each payload under `data[pointId]`: `pointCode`, `value`, `quality`, `stale`, `lastUpdateTime`, and `realtimeStatus`. Check device runtime (`/api/collector/...` according to the current controller routes) for transport/protocol/acquisition/health and cross-check with simulator logs. A cached historical value, an unknown quality, or a stale point is **not a PASS**. For negative checks, disconnect the broker without publishing, return HTTP 404 or an unmatched JSONPath, and use invalid FINS/Tag configurations on separate disposable reference devices; restore the configuration after evidence collection.

Stop the Collection devices first, then `docker compose down` to stop Compose services; Ctrl-C host WireMock, FINS, BACnet, wiretap and cpppo processes separately. `docker compose down` does not stop host processes. Do not remove ProtoForge devices or production caches as a shutdown shortcut.

## Known limitations

- Docker Hub may fail to supply WireMock; the standalone JAR fallback is a real HTTP server serving the same mappings.
- FINS server is intentionally a limited, independently implemented FINS/UDP subset. For interoperability beyond its configured read frames, verify against a full vendor or third-party implementation.
- BACnet/IP UDP broadcasts through Docker Desktop on Windows are not reliably equivalent to a native host interface; prefer the host BACpypes3 process plus recorded loopback forwarding.
- cpppo simple-tag success does not establish Program-scoped Tag support in a different PLC4X driver. IEC 61850 requires an actual model and MMS-level evidence; port 11030 availability alone is insufficient.
- Simulator-only checks prove known values, not Collector data path success. Protect all real secrets outside the reference imports and README.
