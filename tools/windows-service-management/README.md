# WangbinCollector Redis Windows 服务管理

本目录提供 WangbinCollector 自有 Redis 服务的受控 Windows SCM 生命周期管理入口。它只管理具有完整归属记录的 `WangbinCollector-Redis`，不接管旧 `REISUN-*` 原型、用户已有 Redis 或仅因端口相同而发现的 Redis 进程。

## 边界

- `MANAGED_REDIS`：由本产品使用受信任的本地 Redis 可执行文件和 WinSW wrapper 安装，并由受保护管理记录绑定路径、SHA-256、服务账号、Service SID、配置、数据和日志目录。
- `EXTERNAL_REDIS`：没有本产品服务注册冲突时，对用户提供的 Redis 地址执行连接检查；只允许 STATUS/连通性探测，不执行安装、启动、停止、重启或卸载。
- `NOT_INSTALLED`：没有本产品注册，也没有成功探测到外部 Redis。
- `FOREIGN_SERVICE` / `OWNERSHIP_UNKNOWN`：服务名、marker、SCM 注册、构件哈希、进程树或端口归属无法一致证明时的拒绝状态。此状态禁止所有有副作用操作。

INSTALL/START/STOP/RESTART/UNINSTALL 使用本机命名互斥锁串行化；marker schema 还绑定固定服务账号和安装 nonce，旧或不完整记录不会被自动接管。

## 目录布局

默认目录如下，安装器不会把 Redis 数据放入程序目录，也不会触碰 SQLite：

```text
%ProgramFiles%\WangbinCollector\Redis\bin\
  WangbinCollector-Redis.exe
  WangbinCollector-Redis.xml
  redis-server.exe
  redis-sentinel.exe
  redis-cli.exe
  redis-benchmark.exe
  redis-check-aof.exe
  redis-check-rdb.exe
  msys-2.0.dll
  msys-crypto-3.dll
  msys-ssl-3.dll
  msys-gcc_s-seh-1.dll
  msys-stdc++-6.dll
  redis.conf
  sentinel.conf
  README.md
  README.zh_CN.md
  start.bat

%ProgramData%\WangbinCollector\Redis\
  config\redis.conf
  data\
  logs\
  state\ownership.json
  state\redis.secret.json
```

配置、秘密和管理记录使用受保护 ACL。Redis 服务使用 `NT SERVICE\WangbinCollector-Redis` 虚拟服务账号；服务 SID 仅获得代码读取、配置读取、数据和日志写入所需权限。不会把 Program Files 或 ProgramData 的所有者权限扩大给 `Users`、`Everyone` 或其他宽泛主体。

卸载只注销本产品服务，默认保留配置、数据、日志和受保护秘密文件；本模块没有清空 Redis 数据的操作。

## 构件来源契约

仓库不包含 Redis Windows 原生可执行文件、WinSW wrapper 或未知来源下载器。INSTALL 必须由运维人员显式提供：

1. 本地 `Redis-8.10.3-Windows-x64-msys2.zip` 完整发行包路径及 SHA-256；
2. 本地 WinSW wrapper 路径及 SHA-256；
3. 两个构件的来源说明；
4. Redis 与 WinSW 对应的本地许可证/版权证据文件。

模块只校验并记录所提供文件的实际哈希，不把哈希校验伪装成上游签名、版本支持或商业再分发许可结论。正式发布仍需审核 Redis Windows 发行物、WinSW 版本、依赖、LICENSE/NOTICE 和再分发授权。禁止把 Docker、WSL 或旧 `REISUN-Prototype` 文件冒充本机托管 Redis 构件。

Redis ZIP 必须使用固定顶层目录 `Redis-8.10.3-Windows-x64-msys2/`；允许该目录自身的一个 ZIP 目录条目，目录下必须正好包含批准的 16 个文件。根目录名称、嵌套目录、目录外文件和其他目录条目都会被拒绝；ZIP 中的文件条目路径会记录为 marker 的 `EntryPath`，复制阶段按该精确路径读回。

## 命令入口

PowerShell 5.1 管理员窗口中执行。普通用户可以执行 STATUS，但无法进行 INSTALL、START、STOP、RESTART、UNINSTALL。

### 外部 Redis 只读检查

```powershell
powershell.exe -NoProfile -File .\tools\windows-service-management\Invoke-WangbinCollectorRedis.ps1 `
  -Operation STATUS `
  -ExternalHost 127.0.0.1 `
  -Port 6379
```

需要认证时使用交互式安全输入；密码不进入命令行、日志或 JSON 输出：

```powershell
powershell.exe -NoProfile -File .\tools\windows-service-management\Invoke-WangbinCollectorRedis.ps1 `
  -Operation STATUS `
  -ExternalHost 127.0.0.1 `
  -Port 6379 `
  -PromptForExternalPassword
```

外部模式只返回连通性结果，不会因为 PING 成功而获得服务生命周期权限。
如果本机已经存在完整的 `MANAGED_REDIS` 归属记录，STATUS 会忽略 `ExternalHost`，只在已验证的本地 Redis 监听者上探测；服务未就绪或监听归属未证明时不会读取或发送 managed 密码。

### 安装本产品 Redis

以下示例中的路径、SHA-256、来源说明和许可证文件必须由用户按实际批准的发行物替换；这些占位值不能直接执行。安装前会拒绝同名 SCM 服务、占用端口、未经归属证明的既有产品目录、重解析路径、哈希不匹配和不完整许可证证据；对完整归属记录、SCM 配置和构件哈希均一致的已安装服务，重复 INSTALL 返回 `AlreadyInstalled`，不会重写配置或数据。

```powershell
powershell.exe -NoProfile -File .\tools\windows-service-management\Invoke-WangbinCollectorRedis.ps1 `
  -Operation INSTALL `
  -RedisDistributionArchive C:\approved\Redis-8.10.3-Windows-x64-msys2.zip `
  -ExpectedRedisArchiveSha256 af16df340525b431e9ec8dabfd625d5e7a233f82805b5418819904ea909c447f `
  -WinSwExecutable C:\approved\WinSW-x64.exe `
  -ExpectedWinSwSha256 <64位WinSWSHA256> `
  -RedisSourceDescription '由组织批准的本地Redis发行物及版本记录' `
  -WinSwSourceDescription '由组织批准的WinSW发行物及版本记录' `
  -RedisLicenseFile C:\approved\redis-LICENSE.txt `
  -WinSwLicenseFile C:\approved\WinSW-LICENSE.txt `
  -Port 6379
```

安装过程：

1. 只读检查 Windows、管理员令牌、服务名、端口、根目录和重解析点；
2. 校验固定 Redis 8.10.3 MSYS2 x64 ZIP 的归档 SHA-256、固定顶层目录、完整允许清单、每个 PE 架构及运行时 DLL；
3. 拒绝 ZIP 路径穿越、绝对路径、反斜杠路径、重复/大小写碰撞条目、嵌套目录、额外文件、Cygwin 运行库和缺少 DLL；
4. 将完整发行目录逐文件复制到受控代码目录，并把相对路径、大小、架构和 SHA-256 写入 marker；
5. 创建隔离的代码、配置、数据、日志、状态目录；
6. 运行时生成随机 Redis 密码并仅写入受保护文件和 Redis 配置；
7. 生成固定服务名的 WinSW XML；
8. 注册服务并回读 SCM 路径、账号、Service SID、Manual 启动类型和结构化 FailureActions；
9. 为服务 SID 设置最小 ACL；
10. 原子写入并读回 ownership marker。

WinSW 将工作目录设为受控配置目录，并以相对文件名 `redis.conf` 启动 Redis，避免 Windows 服务账号下的 MSYS2 绝对路径挂载不可用；Redis 配置中的 `dir`、`logfile` 使用指向本产品专用目录的 Windows 正斜杠绝对路径。进程归属仍校验固定可执行文件、受控 wrapper/SCM、配置参数、文件哈希和监听 PID，不会用端口匹配替代进程归属。

失败时不会删除半安装目录，也不会盲目注销无法证明归属的服务；目录和 marker 会保留供人工审计。
安装器不会修改 Collector 的 `application.yml`、环境变量或其他用户配置。由于 Redis 配置使用受保护的随机密码，部署 Collector 前必须通过项目既有的 Redis 密码注入方式配置同一凭据；Redis 管理器的 `STATUS` 只证明 Redis 服务自身，不把它冒充为 Collector 数据链路就绪。

### 生命周期操作

```powershell
# 只允许操作通过 marker、SCM、wrapper、子进程、端口和哈希核验的本产品服务
powershell.exe -NoProfile -File .\tools\windows-service-management\Invoke-WangbinCollectorRedis.ps1 -Operation STATUS
powershell.exe -NoProfile -File .\tools\windows-service-management\Invoke-WangbinCollectorRedis.ps1 -Operation START
powershell.exe -NoProfile -File .\tools\windows-service-management\Invoke-WangbinCollectorRedis.ps1 -Operation STOP
powershell.exe -NoProfile -File .\tools\windows-service-management\Invoke-WangbinCollectorRedis.ps1 -Operation RESTART
powershell.exe -NoProfile -File .\tools\windows-service-management\Invoke-WangbinCollectorRedis.ps1 -Operation UNINSTALL
```

START 不把 `SCM Running` 当作就绪：它必须在有界时间内确认 wrapper/Redis 子进程、loopback listener、实际端口归属和认证 PING。STOP 先在仍处于 `Running`/`Stop Pending` 且已通过 wrapper、子进程和 loopback 监听归属核验时发送认证 `SHUTDOWN SAVE`，再请求 SCM 停止 wrapper，随后确认 SCM 停止、原子记录的子进程退出和端口释放；不会调用 `taskkill /IM redis-server.exe /F`、`Stop-Process` 或按端口杀进程。

RESTART 固定执行 STOP → 停止和端口释放确认 → START，避免旧进程尚未退出时创建第二个实例。

## 进程和服务归属

有副作用操作必须同时满足：

- 服务名为固定 `WangbinCollector-Redis`；
- SCM `PathName` 指向 marker 记录的 wrapper；
- SCM `PathName` 的完整命令行不包含未授权参数；
- 服务账号为 `NT SERVICE\WangbinCollector-Redis`；
- Service SID 类型为 unrestricted，且服务对象 DACL 只允许 SYSTEM/Administrators 控制；
- 服务对象 DACL、代码/配置/秘密/数据/日志目录 ACL 和关键文件 ACL 与安装契约一致；
- marker 的代码、wrapper、XML、配置和秘密文件路径及 SHA-256 与磁盘一致；
- marker 中的构件来源、版本、许可证路径和许可证 SHA-256 与安装时证据一致；
- wrapper PID、Redis 子进程 PID、创建时间、父子关系和实际 executable path 一致；
- 监听端口是 loopback，并由 marker 绑定的 Redis 子进程持有；
- Redis PING 使用受控密码成功。

端口、PID、进程名、服务名、PONG 任一单项都不能证明归属。服务记录缺失、marker 不一致、路径被替换、哈希改变、服务处于 Pending 或当前令牌无法确认 SCM 时，结果为 UNKNOWN/BLOCKED，不会尝试修复或接管。

## 无管理员安全回归

`Invoke-WangbinCollectorRedisSecurityTests.ps1` 是不依赖 Pester 的 PowerShell 5.1 安全回归入口，覆盖 TEST-07-S01～S06 的路径参数、marker/ACL、FailureActions、managed 密码读取、STOP/UNINSTALL 状态拒绝和 INSTALL 构件门禁。它只使用受控临时目录、模块私有函数和模拟返回值，不调用真实 SCM 修改命令、不修改系统 ACL、不启动或停止服务；输出中的 `RealScmAcceptance` 必须保持 `BLOCKED`，直到真实验收前置条件全部满足。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\windows-service-management\Invoke-WangbinCollectorRedisSecurityTests.ps1
```

`Invoke-WangbinCollectorRedisPhase07Tests.ps1` 是同样不依赖 Pester 的阶段专项入口，覆盖完整发行清单、缺失/哈希/架构拒绝、ZIP 路径穿越/重复/额外文件拒绝、MSYS2 路径、配置与命令行一致性、marker 清单漂移、INSTALL 幂等、许可证证据、外部服务归属和 WinSW 兼容性共 12 组检查。它只在临时目录生成合成 PE/ZIP fixture，不下载或安装 Redis/WinSW，不操作 SCM、系统 ACL 或服务生命周期。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\windows-service-management\Invoke-WangbinCollectorRedisPhase07Tests.ps1
```

## 当前阶段 07 验收边界

真实 INSTALL/START/STOP/RESTART/UNINSTALL 必须在明确隔离且获授权的 Windows 服务环境执行。没有受信任二进制、许可证证据、管理员/UAC 授权或安全隔离窗口时，只能执行：

- PowerShell AST/语法解析；
- 模块导入和只读 STATUS；
- 目标服务、端口、进程和路径的只读审计；
- 生产模块编译和阶段 06 已有回归。

不能用模拟对象、Docker/WSL 或退出码替代真实 SCM 服务验收，也不能操作现役 `WangbinCollector-R2A2-*`、旧 `REISUN-*`、其 Redis/SQLite 数据或端口。阶段 07 的系统级门禁在缺少这些前置条件时必须保持 `BLOCKED`，不能报告 `PHASE_07 = PASS`。

### TEST-07-02 / TEST-07-06 独立证据补采

`Invoke-WangbinCollectorRedisPhase07Evidence.ps1` 是只读辅助脚本，不是新的服务管理器；不会安装、启动、停止、卸载服务，也不提供故障注入开关。JSON 只保存在本机 Hermes scratch，切勿提交原始回执、秘密文件或个人路径。

```powershell
# 提升权限终端中的无副作用故障恢复执行前检查清单；不能证明恢复成功
pwsh -NoProfile -File .\tools\windows-service-management\Invoke-WangbinCollectorRedisPhase07Evidence.ps1 -Mode CRASH_RECOVERY

# 仅当用户对本机重新授权 INSTALL/START，且管理员 PRECHECK 已通过、目标测试服务确实运行时，
# 在提升权限的终端读回 SCM、安全描述符、磁盘 ACL、进程父子关系和目标监听者
pwsh -NoProfile -File .\tools\windows-service-management\Invoke-WangbinCollectorRedisPhase07Evidence.ps1 -Mode AUDIT
```

`AUDIT` 只在完整 ownership、服务 SID 类型/DACL、目标目录及关键文件 ACL、受保护 R2-A2 前后状态核验通过时返回 `PASS`；缺失服务、普通用户令牌或状态不一致返回 `BLOCKED`。`CRASH_RECOVERY` 计划模式也要求管理员令牌：SCM 服务 DACL 可能让非管理员 CIM 查询返回空列表，不能将其解释为服务注销。脚本记录来自 `sc sdshow` 的目标服务完整 SDDL 及 SHA-256，目录/文件 ACL 的 SHA-256、ACE SID/权限掩码，不导出秘密内容或原始 Redis 命令行。安装、运行和卸载状态分别以真实生命周期回执、运行时读回、后续受控卸载回执关联；只读审计本身**不**执行卸载。

`CRASH_RECOVERY` 仅输出 `PLAN_ONLY` / `NOT_RUN`，不能作为 TEST-07-06 的执行证据。真实故障注入需用户针对本机及已证明归属的当次 Redis PID **另行明确授权**，先重新执行提升权限的 PRECHECK，再用现有安装/启动入口建立隔离测试服务，核验 PID、父子关系、路径/哈希、创建时间、监听 PID、R2-A2 基线及认证测试数据。只允许对核验过的目标 Redis PID 做一次精确故障注入；随后观察真实 WinSW/SCM FailureActions、读回新 PID、认证 PING、持久化数据及保护基线。没有该授权或任何核验失败时不得注入、推测 `PASS` 或进入阶段 08。

### 最后两项门禁一次安装合并验收（需单独授权）

`Invoke-WangbinCollectorRedisPhase07FinalGates.ps1` 调用现有 `WangbinCollectorRedis.psm1` 完成受控生命周期，并复用既有真实 SCM PRECHECK 和上面的只读 ACL 证据脚本；它不是第二套服务管理器。先由用户在**管理员 Windows PowerShell 5.1**执行以下唯一的只读命令（从仓库根目录执行），无需配置构件路径：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\windows-service-management\Invoke-WangbinCollectorRedisPhase07FinalGates.ps1 -Mode PRECHECK
```

只读模式要求管理员令牌和完整 13/13 PRECHECK：比较两个 R2-A2 服务的 SCM/进程/端口/配置路径完整指纹及**九项**历史文件 ACL 哈希，验证目标 SCM 不存在、16379 空闲、专用目录安全、真实 ZIP/WinSW 及许可文件哈希。普通用户或任一失败均返回 `BLOCKED`；只有真实回执 `READY_FOR_AUTHORIZATION` 才可**请求用户针对本机本次安装、一次精确故障注入、停止和卸载的新一轮明确授权**。不得自动从源码读取固定授权短语代替人工确认。

只有得到该授权后，才可在管理员窗口手工运行同一脚本的 `-Mode EXECUTE`；此模式会再次 PRECHECK，在本次窗口交互接收人工授权，随后再次 PRECHECK 才可能 INSTALL。INSTALL/START 只执行一次：独立读取 SCM DACL/Service SID 和受控目录、安装文件及生成的日志/数据文件 ACL，写入唯一 Cache/Stream 探针并持久化；再次验证 PID、可执行文件 SHA-256、配置参数、wrapper 父子关系和监听者后，只对该**单一进程句柄**故障注入一次。仅观察自动恢复，绝不以手工 START 代替；wrapper 不变时记录 WinSW 子进程恢复且 SCM 未触发，wrapper 更换时还要求真实 SCM 7031 事件证明服务故障恢复。成功认证 PING 与 Cache/Stream 回读、R2-A2 完整前后基线通过后，仅删除本次探针键并受控 STOP/UNINSTALL。任一异常返回 `BLOCKED`，保留现场供人工研判，不擅自清理或重复注入。UNINSTALL 按既有接口保留专用代码、配置、数据和秘密文件；回执只保存在本地 scratch，不得提交原始回执或敏感信息。

若这次合并验收已经完成唯一的 INSTALL/START，却因只读证据采集错误停在故障注入前，不得重跑 `EXECUTE` 或再次安装。仅在原始中断回执的 SHA-256、同机管理员身份、未注入状态、唯一 INSTALL/START 和原 Redis PID 均经核验，且重新进行目标归属与 R2-A2 完整预检和独立 ACL 审计后，可由同一轮已授权的管理员手工确认 `-Mode RESUME`。此模式不调用 INSTALL/START；任一回执或实时 PID 不匹配时保持现场，不进行故障注入、STOP 或 UNINSTALL。

### 本机阶段 07 最终回执

本次经管理员手工授权的合并验收结果为 `PHASE_07 = PASS`、`REAL_SCM_ACCEPTANCE = PASS`，仅代表本次开发机真实 SCM/ACL/Redis 现场，不推断其他主机。最终原始回执仅保存在本地 scratch，其 SHA-256 为 `2552e2fa82a1576fc8697dc7e6d78c266f80a799b1d2bd5d5148f206b26beb5d`；首次执行在唯一 INSTALL/START 后因只读证据采集错误中断，原始中断回执 SHA-256 为 `dc3d32aa7db3eb7e97ee1724de2a1e4b3fec3a25cbddac63b67ac6411356de6a`。续验没有重复 INSTALL/START。

`TEST-07-02 = PASS`：独立只读回执分别覆盖故障前、持久化写入后和恢复后的真实服务 DACL、Service SID、文件/目录 ACL 与进程归属（路径数分别为 36、37、37）。`TEST-07-06 = PASS`：单一受控 Redis 子进程异常退出后，观测到 wrapper 更换及真实 SCM 7031 事件，故归类为 **SCM 服务恢复**，不是将 WinSW 子进程恢复或手工 START 冒充 SCM 恢复；新进程通过认证 PING 与 Cache/Stream 数据回读。受控 STOP/UNINSTALL 后再次读回目标服务不存在、16379 无监听、R2-A2 历史指纹及九项 ACL 未变。运行中预检的总体 `BLOCKED` 是目标服务已注册、端口正在监听的预期结果；合并脚本逐项核验该边界，不能把运行中预检当作安装前的 13/13 空闲预检。
