# wangbin SQLite 配置存储 Phase 1 验证与迁移报告

## 当前结论

Phase 1 生产代码、独立数据库、事务仓储、白名单编解码、显式初始化/迁移及默认关闭的 Boot 装配已实现。最终定向 JUnit **163 项，失败 0、错误 0、跳过 0**；18 模块生产编译成功。真实旧 JSON 在独立数据库中迁移、回读、关闭重开及重复迁移通过。

这不是“全仓测试通过”：全 reactor 定向 test 在既有 OPC 测试编译阶段被阻断，详见下文。没有部署、重启现有采集服务，没有更改 ConfigManager 的来源和生产读写流程。

## 1. 构建身份与范围

- 分支：feature_2.0；实施基线 HEAD：d64189a5，未 add/commit/push。
- 实际 Maven：3.6.3；构建 Java：17.0.15；项目目标 Java 17。
- SQLite JDBC：org.xerial:sqlite-jdbc:3.53.4.0；实际运行 SQLite 由该构件 Windows native 提供。
- SQLite 默认关闭，显式开启仅 openExisting；init/migrate 仅独立 API/CLI 显式调用。
- 全部验证数据库位于临时专用目录，不创建 ProgramData 下生产库；CLI 实际迁移后已删除本次拥有的敏感临时库/附属文件。
- 精确文件清单见 CHANGESET.md；表、索引和字段白名单见 README.md；完整 Port 与下一阶段接点见 PHASE2-INTERFACES.md。

最终独立模块 JAR：`target/collector-config-sqlite-0.0.1-SNAPSHOT.jar`。

- 大小：69,592 bytes。
- SHA-256：ea84c81b72d3c6ceccf65b20168a1b4199f50d50a9cd979009094b4f7c3e44a8。
- 已逐字节比较 JAR 与最终 target/classes 中的类、Schema 和许可证条目，共20项一致。
- 这是库/独立工具模块，不是替换生产服务的全依赖可执行 Boot JAR。

## 2. 最终真实执行命令

本机 Git Bash 的 mvn shell 启动器无法加载 Launcher，使用已验证的原生 `D:/Program Files/Java/apache-maven-3.6.3/bin/mvn.cmd`。

最终环境仅对验证进程设置：

```bash
export MAVEN_OPTS='-Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Users/wangbin/AppData/Local/hermes/cache/scratch/wangbin-maven-temp'
```

### 新模块全部定向测试与真实来源迁移

```bash
'D:/Program Files/Java/apache-maven-3.6.3/bin/mvn.cmd' -B -ntp \
  -pl collector-config-sqlite -am \
  '-Dtest=ConfigurationJsonCodecTest,Sqlite*Test,LegacyJson*Test,WangbinConfigSqliteToolTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dwangbin.migration.sample=C:/Users/wangbin/.data-collection-service/local-device-configs.json' \
  '-Dwangbin.migration.report=C:/Users/wangbin/AppData/Local/hermes/cache/scratch/wangbin-config-sqlite-evidence/real-migration-verification.json' test
```

最终退出0，78项通过；完成时间2026-10-22 12:07:09 UTC+08:00。

### 当前上游构件与配置回归

首次全 reactor 定向 test 被 OPC 既有测试编译阻断后，先对**当前本地生产源码**执行上游 install，明确排除 collector-boot，使用 `-Dmaven.test.skip=true`。没有 package/repackage 现有 Boot 服务 JAR，也没有用陈旧依赖替代当前源码。

```bash
'D:/Program Files/Java/apache-maven-3.6.3/bin/mvn.cmd' -B -ntp \
  -pl '!collector-boot' -Dmaven.test.skip=true install
```

上游17模块构件构建/安装成功。最终配置模块修正后另执行 `-pl collector-config-sqlite -am -Dmaven.test.skip=true install`，退出0；这是本地构件准备，不是测试通过的证明或远端发布。

随后只执行目标模块的测试，避免无关 OPC testCompile 阻断：

```bash
'D:/Program Files/Java/apache-maven-3.6.3/bin/mvn.cmd' -B -ntp \
  -pl collector-runtime,collector-application,collector-boot \
  '-Dtest=WangbinConfigSqliteConfigurationTest,ConfigManagerLocalDeviceTest,ConfigSyncServiceTest,FileConfigLoaderTest,SensitiveConfigSanitizerTest,ConfigConsoleApplicationServiceTest,ConfigDiffCalculatorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终退出0，42+38+5项通过；完成时间2026-10-22 12:07:29 UTC+08:00。未使用 `-am` 的本轮以刚构建安装的本地当前上游构件为输入，非旧版本依赖。

### 全部生产模块编译

```bash
'D:/Program Files/Java/apache-maven-3.6.3/bin/mvn.cmd' -B -ntp \
  -pl collector-boot -am -Dmaven.test.skip=true compile
```

最终退出0，18模块成功；完成时间2026-10-22 12:07:34 UTC+08:00。此项只证明生产编译，不冒充测试、服务启动或生产切源验收。

## 3. JUnit 精确结果

按最后的 `target/surefire-reports/TEST-*.xml` 每类只计一次，未把历史轮次、每类行和模块汇总重复相加。

| 模块/测试 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| ConfigurationJsonCodecTest | 20 | 0 | 0 | 0 |
| SqliteDatabaseTest | 13 | 0 | 0 | 0 |
| SqlitePathsTest | 4 | 0 | 0 | 0 |
| SqliteOwnershipTest | 3 | 0 | 0 | 0 |
| SqliteConfigurationStoreTest | 18 | 0 | 0 | 0 |
| SqliteConfigurationPayloadBoundaryTest | 2 | 0 | 0 | 0 |
| LegacyJsonMigrationIntegrationTest | 12 | 0 | 0 | 0 |
| LegacyJsonMigrationTest | 1 | 0 | 0 | 0 |
| WangbinConfigSqliteToolTest | 5 | 0 | 0 | 0 |
| runtime配置回归（4类） | 42 | 0 | 0 | 0 |
| application导入导出/配置版本回归（2类） | 38 | 0 | 0 | 0 |
| WangbinConfigSqliteConfigurationTest | 5 | 0 | 0 | 0 |
| **合计** | **163** | **0** | **0** | **0** |

覆盖：创建/关闭/重开；通信与不同协议扩展、告警及精确数值往返；运行态排除与点位配置status保留；事务异常与捕获异常后rollback-only；外键、全局pointId、设备内排序、一设备一连接、CAS；并发修订及聚合一致性；每个连接PRAGMA；数据库锁恢复、只读/拒绝访问/损坏/空/未知和未来版本；主库丢失不重建；真实Windows ACL与活跃WAL/SHM；跨平台路径策略与URI字符；首次/重复/无身份/坏来源/部分数据库失败迁移；默认关闭、仅打开已有库、TDengine/MyBatis绑定不被劫持。

## 4. 实际45/45/749迁移校验

来源：原有 local-device-configs.json，只读；没有把样本、身份明细、连接参数或含秘密的数据库放入报告。

| 校验 | 实际结果 |
|---|---|
| 设备数量 | 45 |
| 连接数量 | 45 |
| 点位数量 | 749 |
| 缺失/重复deviceId | 0 / 0 |
| 缺失/全局重复pointId | 0 / 0 |
| 回读稳定设备身份 | 45，一致 |
| 回读稳定点位身份 | 749，一致 |
| 与原始JSON独立逐字段比较 | 29,365个白名单字段，一致 |
| alarmRule/additionalConfig/extJson/authParams | 原始配置形态及未知扩展保持；没有走typed规则列表重编码 |
| 关闭重开 | 数量、身份、来源摘要恢复一致 |
| 重复迁移 | ALREADY_MIGRATED；修订不增加；成功标记仅一条 |
| 部分失败 | 真实SQLite触发器拒绝第二设备，数据/修订/标记全部回滚，移除故障后重试成功 |
| 原文件 | 从阶段开始及每轮迁移前后逐字节/摘要不变，未改名、删除或回写 |

来源SHA-256：`c1f3bb429a9fc030611bdbf29eac4ea8a7582c72f7370cb75799cc45e13172ef`。

此外使用**最终模块JAR**和实际运行时依赖，独立执行了 init、inspect、migrate、再次migrate，四个进程退出码均0：INITIALIZED → VALID → IMPORTED → ALREADY_MIGRATED。首次迁移返回 identityAndConfigurationVerified=true；重复跳过返回false，表示没有把“跳过”冒充再次内容校验。CLI临时库已清理，来源未变。

## 5. 审查修复与过程异常

独立只读复核对R1/R2/R3分别返回ADDRESSED，未发现范围内重大新问题；它没有运行测试，也不构成Linux跨UID验收。复核后的sidecar并发检查修正由父代理复查并真实重测，不冒充独立代理重新审查了最终每个字节。复核回执随交付包保存。

- 首轮独立静态审查发现既有owner信任问题，已改为操作系统服务身份核对；主目录、库、sidecar在JDBC触碰前核验，不自动接管异主文件。所有权拒绝边界、真实当前身份不受user.name伪造影响、残留WAL拒绝初始化的3项测试通过。
- 模板/设置/远端快照曾只校验JSON语法，现已拒绝明确运行态字段；新增2项回归先RED后GREEN，合法协议扩展和配置status不受损。
- Schema摘要改为仅规范化换行；版本表允许正数版本记录，但当前只定义并接受V001，未知版本继续拒绝。
- 新的JDBC前安全检查实际捕获了并发连接关闭删除sidecar的竞态，已修复为仅在 `Files.notExists` 明确确认可选文件消失时跳过；主库和仍存在的权限/owner错误不忽略。原并发一致性测试连续3轮通过，最后全部78项再通过，没有删除或削弱断言。
- 曾出现Surefire测试进程启动“拒绝访问”、0项执行。未改测试配置、未当通过；把本次验证JVM临时目录移到Hermes scratch后恢复正常，最终命令如上。保留原失败日志。

## 6. 未通过/未执行的边界与剩余风险

1. **全 reactor test没有通过**：未修改的 `collector-protocol-opc/src/test/java/com/wangbin/collector/core/collector/protocol/opc/OpcUaCollectorReadStatusTest.java:110,112,114` 引用了不存在的client字段，testCompile报3处错误。未越界修改协议生产/测试代码，也未禁用该测试。配置范围定向测试与全部生产编译成功不能替代这一阻断。
2. **Linux原生与跨UID特权场景未实测**：本机为Windows；Linux默认路径/URI策略已测，POSIX/UnixSystem分支编译成功，异可信主体拒绝边界已测，但未创建第二OS账号或声称完成Linux root→其他UID实验。上线前需在目标Linux服务账户验证所有权、权限及native加载；定制JRE需保留jdk.security.auth。
3. SQLite不是加密库，秘密仍是配置事实；本期仅OS权限保护，不提供伪加密。没有在线备份、断电/掉盘或网络文件系统验收；不得复制运行中WAL库的单个主文件作为完整备份。
4. Xerial商业分发按Apache/BSD/ISC及SQLite公有领域分别处理，精确tag的NOTICE/ISC已补入模块资源并核对进入JAR。extension-functions.c历史作者单独许可与GPG身份验签仍需正式发布审核，不宣称法务全部清零。
5. 未部署/重启现有服务，未作生产整应用新版本启动验收；通过的是隔离装配测试，现有生产配置主链路8个受保护文件字节一致。
6. 原未跟踪 `tools/windows-service-prototype/Prototype.psm1` 在阶段期间出现外部并发内容漂移，本任务没有写入或回滚该路径，未纳入交付。`.hermes.md`保持原样。不能把整个工作区描述为“全部字节不变”或“干净”。
7. 全仓语言审计已有46项历史问题；新增Java范围审计单独执行，不顺带改历史代码。正常测试日志含故障注入的WARN/ERROR，不代表日志零警告。

## 7. 交付与停止点

- README.md：表设计/索引、字段白名单、路径、安全、命令与JSON保真边界。
- CHANGESET.md：真实新增/修改文件清单。
- PHASE2-INTERFACES.md：准确Port/模型/codec/API和未来生产方法接点。
- verification/phase1-evidence.json：最终JUnit、迁移、CLI、JAR与受保护文件的无负载证据。
- 设计、实施计划、任务报告和审查过程在Git忽略的 `.hermes/plans/`；未改忽略规则或强制加入索引。

**严格停止在Phase 1；没有生产来源切换、自动迁移、设备启停、云同步语义修改或Phase 2实施。所有Git提交及推送由用户手动执行。**
