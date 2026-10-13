# collector-desktop 前端架构重构进度

## 最新范围：批准稿精密工业工作台实施与真实 renderer 验收（2026-10-13）

登录/连接页、共享浅色壳、14 个路由呈现、设备五分区编辑器、点位六分区编辑器及相关弹层已完成生产实施。用户已回复“接受，继续”，接受已披露的 8 个 script 差异及既有 `.gitignore` 边界；随后明确要求“执行完成后提交一下代码，然后吧本地运行的服务重新编译启动”。最新范围增加的本地限定提交、精确提交隔离无测试打包及 9090 服务替换已实际完成，不包括推送、修改认证/依赖配置或业务写入。当前为 `IMPLEMENTED / USER_ACCEPTED_PROTECTION_SCOPE / LOCAL_DEPLOYED_WITH_HEALTH_DOWN`；新服务启动及完整静态资源读回通过，但整体健康仍为 DOWN，不是完整业务验收或最终像素签收。

用户接受后的 lint、Stylelint、typecheck、build、build:web 五项门禁均重新实际 exit 0；renderer 与 Boot 静态目录的完整 60 项路径及 SHA-256 一致，入口 `index-C8DUMKg8.js`。Vite 仍有第三方 PURE 注释与 vendor chunk 体积 warning，未更改依赖或放宽规则。正式构建独立预览入口 `http://127.0.0.1:5175/`，原开发入口 5174 保留；9090 另经下述独立部署核证，已运行本次新 JAR，不能以预览服务代替部署证据。

最新真实正式构建观察按清单精确使用 1600×1000、1280×800、390×844，14 路由共 42 个组合，页面级横向溢出 0、Runtime exception 0；原先 1440×900/1024×768 的观察仅保留为历史，不能替代要求尺寸。入口 HTML 与 dist 原字节相同，全部页面实际加载 `index-C8DUMKg8.js`；本轮全部 265 个请求均为 GET，388 条浏览器日志均为 CORS 分类，真实失败/未知不补假数据。三宽度设备卡片/表格切换、五个编辑分区及上下端点、Escape 关闭、日志“更多筛选”有实际观察，共 76 张新 PNG；pre/textarea 正文仅在截图时隐藏，保留几何，不签其内容可读性。

窄屏导航原生 Tab/Shift+Tab 共 28 次均未越出抽屉；Escape、真实路由点击和 900→1024→390 断点的背景锁/回焦有实际记录。观察工具的遮罩中心点击实际命中了覆盖其上的导航链接，不能当作遮罩关闭证据。设备编辑器 Escape 关闭并清除 modal-active，但从分区按钮关闭后焦点实际落到 BODY，不能标记 Dialog 焦点恢复通过；该既有 UI 限制未在本轮新增脚本修复。截图存在/几何通过不等于独立最终像素审查或全成功态验收。

### 本轮保护核对与剩余边界

- 改前 manifest 为 1530 个 tracked 文件、41 个 Vue script、146 个源快照和 69 个批准稿文件；允许呈现范围内源文件按批准稿变化。
- renderer 与 Boot 静态同步校验：60/60 common 文件一致，0 mismatch，0 missing，0 extra。
- 按 manifest 的 Vue `<script>` 字节核对仍发现 8 个差异：`AlarmView.vue`、`CloudView.vue`、`DeviceListView.vue`、`LogView.vue`、`NetworkView.vue`、`RealtimeView.vue`、`DeviceOperationShell.vue`、`ConfigOpsPanel.vue`。其中设备列表属于批准的页面本地 UI 例外；其余差异为当前工作区既有生命周期/请求归属/最后成功快照保护等行为改动，本轮未回滚，未将其冒称为 script 保护通过。
- 全仓库 `git diff --check` 仅因既有 `.gitignore:42` EOF 空行返回 exit 2；前端源与 Boot 静态同步路径限定核对返回 exit 0。未修改 `.gitignore`，以免越过当前工作区边界。
- 用户已接受上述保护例外；其源差异保持原范围，不据此宣称全部 script 字节一致。本次提交只纳入前端生产源码、完整 Boot 同步静态资源和本进度文档；既有 `.gitignore`、`.hermes.md`、`.workbuddy-ai/`、私有审计/设计/运行证据不纳入。

### 真实 renderer 证据

- 机器可读记录：`C:/Users/wangbin/AppData/Local/hermes/cache/scratch/runtime-ui-results-with-events.json`。
- PNG 目录：`C:/Users/wangbin/AppData/Local/hermes/cache/scratch/runtime-ui/`；文件按 `{wide|medium|narrow}-{route}.png` 命名，另有设备新增弹层交互截图。
- 代表性访问方式：浏览器打开 `http://127.0.0.1:5174/#/login`；其他页面使用 hash 路由，例如 `#/dashboard`、`#/device`、`#/device/workbench`、`#/shadow`。
- 最新正式构建证据：`designs/collector-workbench-implementation/evidence/candidate-C8DUMKg8/RUNTIME.json`、`IDENTITY.json`、`screenshots/`，仅保留本地，不提交截图及运行记录。

### 最新提交与重启执行边界

从本次生产提交的精确 HEAD 创建隔离 worktree，以 `-Dmaven.test.skip=true clean package` 编译生产模块，不编译或运行测试；旧 9090 保持运行直到新可执行包及全部嵌入静态资源核验通过。替换前保留旧 JAR，按真实监听 PID/创建时间/可执行文件核对后只终止该服务，沿用原 Java、工作目录和启动参数。生产提交、JAR SHA-256、旧/新 PID、完整 HTTP 静态资源读回、健康组件状态及回滚包另存忽略目录 `collector-boot/target/releases/` 下安全回执；部署后的文档收尾不改变生产构建身份。服务启动与依赖健康、视觉验收分别记录，不改历史 PASS/FAIL/BLOCKED。

### 本轮本地提交与部署结果（2026-10-13）

- 生产提交：`d1578fd4`（`feat(desktop): implement precision industrial workbench design`），分支 `feature_2.0`。包含 44 个前端生产源码文件的改动、完整同步静态资源和本进度文档；没有推送，没有提交私有设计/证据、测试或无关工作区文件。
- 从该精确提交创建全新 detached worktree；实际 cwd 和 Maven 各 `Building jar:` 路径均位于隔离目录。生产 `clean package` 的 17 个 reactor 项目全部 SUCCESS，exit 0；实际日志明确不复制测试资源、不编译测试、跳过测试运行。
- 新可执行 JAR 为 100178571 bytes，包含 192 个嵌入依赖，应用字节码 major 61（Java 17）。SHA-256：`689cbc72e83edbc0ccea4957eb96931263061a9ecc20d4e29d92ffff8893aa3f`。源码静态目录、正式 renderer 与 JAR 的 60 项完整路径及字节一致，无旧 hash 文件残留。
- 旧运行包先备份并复核 SHA-256；只终止原 9090 实际监听 PID `25552`，确认进程退出和端口释放后才安装新包。沿用原 Java 17、仓库根工作目录及原启动参数，新实际监听 PID `50456`，进程创建时间 `2026-10-13T22:23:26.221664+08:00`。
- 页面入口 `http://127.0.0.1:9090/collector/desktop/index.html#/login`；60/60 静态 HTTP GET 均返回 200，响应原始字节 SHA-256 均匹配新 JAR，入口 `index-C8DUMKg8.js`。这是静态部署读回，不升级此前视觉或业务验收结论。
- 切换前后 `/collector/health` 均 HTTP 200、整体 DOWN；分项同为 `collectionService: DOWN`、`cache: UP`、`connections: UNKNOWN`、`application: UP`。新包已启动不等于采集服务健康；本次未改认证/依赖配置或发起设备启停等业务修复。
- 发布产物与安全回执目录：`collector-boot/target/releases/20261013-220823-d1578fd4/`，包括 `ARTIFACT-READY.json`、`DEPLOYMENT-RECEIPT.json`、`data-collection-service-d1578fd4.jar` 和 `previous-data-collection-service.jar`。回滚包保留本地；服务继续运行，隔离构建目录在持久化产物后清理。

### 以下为此前发布与专项验收历史（不是本轮部署结果）

提交之后从该精确 HEAD 创建隔离 worktree，采用 `-Dmaven.test.skip=true clean package`，不新增、修改、编译或运行测试。旧 9090 服务保持运行直到新可执行 JAR 核证成功；替换前保留旧包备份，替换后核实新 PID、9090 页面及全部嵌入静态资源字节，健康状态与启动结果分别记录。打包、部署结果及回滚路径以忽略目录 `collector-boot/target/releases/` 下本次安全部署回执为准，不预先宣称成功；以下更新时间和未部署说明均为历史窗口。

### 发布请求执行记录

- 2026-10-13 13:14（中国标准时间）：新构建/同步完成，完整 60 项资源一致；原监听 PID、Java 路径、工作目录及运行 JAR 路径已限定读取核对，未输出秘密或原始运行日志。
- 原全量验收执行窗口停止，临时认证输入进程已取消；历史证据、盘点和未完成验收保持原范围。
- 本次仅执行授权的提交、无测试编译和 9090 服务替换；精确提交和最终运行核证另存安全部署回执，不开展业务写入或依赖配置修复。

最新全部授权执行窗口（2026-10-13）：用户要求完成此前解析的剩余四类任务，执行计划另存 `full-closure-authorized/EXECUTION-PLAN.md`，不改旧合同和历史结论。五项无测试门禁重新实际exit0，最新隔离renderer的完整60项path/SHA/bytes与前序焦点候选完全一致，158个源hash保持；HEAD仍 `7c1778dd27542be92049deb59b16233b471e48e4`，diff-check通过，既有工作树改动保留、不宣clean。独立原条款盘点 `deleg_3738a697`已完成并逐条父核：云诊断42、日志网络42、共享/采集36，共120条；原id/priority/全文/顺序和suite保持，不合并28个同名shared ID，9输入manifest与9安全源snapshot全部匹配。10条高价值动作及208个证据路由分支不是新PASS或新合同。已准备真实浏览器安全观察工具但尚未初始化或生成新PNG：上一轮临时认证值没有保留，当前内存只具 `[REDACTED]`标记，禁止猜值、逆脱敏或从配置/storage读取。任务自有本地终端已进入 `x-collector-token`不回显输入提示，`CONTROLLER-STATE.json`读回 `WAITING_FOR_LOCAL_EPHEMERAL_CREDENTIAL`；认证只在用户输入后由stdin进入浏览器内存。完整五页、新像素终审、最终效果图ZIP仍PENDING；不是授权未获准或门禁失败。新窗口未执行业务写入/测试/部署/服务替换。

最新认证补充（2026-10-13）：用户明确提供`x-collector-token`供只读验收使用，值仅由stdin进入任务自有headless context内存，不写源码/配置/任务日志/证据。相同认证头在9346既有入口的config summary仍401，直连已批准9090后端返回200；原因未核实，不推断代理丢头、不改代理或服务。新auth候选仅在浏览器映射原scratch真实index-CUrdtFzV.js，不替换9090运行包：五页5条route、36静态资源及27原页面GET均200，console/pageErrors均0，0非GET/0越界guard。首认证轮的两个自动设备runtime/running GET被旧观察器guard误挡已另保留；核生产API及Collection默认选首设备调用链后，新版本只精确放行此前两个自然GET，不手选设备/带入、改query或扩大业务权限。父核`AUTHENTICATED-REQUEST-READBACK.json`另存durable补充；158当前源及原renderer/Boot各60资源仍一致，旧401记录/原专项安全ZIP不改。MF-FOCUS-01专项PASS保持；HTTP200不等于accepted-source/last-good/业务、完整84或新像素通过，完整五页/最终效果图ZIP仍PENDING。下列“25GET401”为修复专项原观察窗口历史，不再作为新直连认证窗口状态。）

最新更新时间：2026-10-13（本轮MF-FOCUS-01最小修复/无测试构建/隔离有限键盘复核/Frontend Design三阶段监督专项闭环完成。deleg_9ed39160最终裁决SCOPED_MF_FOCUS_01_PASS、专项must-fix0，三个报告SHA/bytes及13唯一直接引用已父核，并逐项重算221保存记录、201开态记录、182原生键、7组14方向/14首尾单键见证、6Escape和16末链接滚入见证；60新真实资源与36加载身份、158当前源集合/bytes及原renderer/Boot各60完整集合均再次核证。只签所录闭合details共享根因与顺序边界，不是独立新交互/rawtool取回、新像素、Electron IPC或原84整条通过。五门禁exit0及diff-check保持，源码只有AppShell三处改变，157其它源从改前基线不变。原两个suite S-NAV-04历史FAIL/原84/旧PNG裁决保留，完整五页/新效果图终审/最终ZIP PENDING；25原GET全部401/25consoleErrors如实保留，不签业务成功或读取秘密修认证。专项报告及安全证据另存durable artifacts；未提交push/部署或启停服务，不扩Q3/Q4。下列在途/未运行checkpoint为历史。）

最新更新时间：2026-10-13（MF-FOCUS-01新候选有限键盘观察已真实exit0并父核：index-CUrdtFzV.js，221安全结构记录/182原生Tab或Shift+Tab，五页640×480加900×600及短高复回共7组/14完整方向，均遍历12实际候选，SUMMARY→关闭BUTTON及反向同次按键闭环，BODY/抽屉外/隐藏后代焦点0。6次Escape回触发器/解锁，当前路由关闭、其它四页导航、1024→900→1024→640断点及末链接内部滚入有实际端点。Frontend Design实施中deleg_d2416592报告已SHA父核，源级无must-fix；行尾误说明以SOURCE-SCOPE-LINE-ENDINGS-CORRECTION.json补充更正为317CRLF/0孤立LF，旧scope/candidate记录不改。修复后独立证据终审deleg_9ed39160在途，状态RUNTIME_OBSERVED_PENDING_INDEPENDENT_REVIEW，不预签其PASS。36新真实静态资源/25原GET全部401，0pageerror但25console error，不证明源成功/业务/last-good；原renderer/Boot/运行包未替换，未修认证。旧84/两套历史FAIL/旧PNG签署保留，新像素/五页完整验收/最终ZIP PENDING；未扩Q3/Q4、测试/秘密/业务/前台/部署或服务动作。以下checkpoint为历史。）

最新更新时间：2026-10-13（MF-FOCUS-01最小修复已获用户明确“授权”，Frontend Design修复前监督deleg_0624f852已父核支持实施。仅改AppShell的可聚焦过滤、同集合初焦和Tab成员/首尾边界；CSS/template/关闭/resize/watchers/lifecycle保持原字节，157其它前端源文件不变。lint、Stylelint、typecheck、scratch renderer build及scratch Electron编译五门禁均exit0，git diff --check通过；新候选index-CUrdtFzV.js的60资源单独冻结于scratch，原renderer/Boot各60资源与既有9346入口index-CW3V7nPt.js未替换。实施中监督deleg_d2416592已派发，真实新候选键盘复核尚未执行；修复状态IMPLEMENTED_PENDING_RUNTIME_REVIEW，不签局部或完整PASS。原两套S-NAV-04历史FAIL、84全文/priority/来源/顺序及原PNG像素结论保持原build范围；新候选不能沿用旧PNG签新像素，五页/最终ZIP PENDING。无测试、秘密/业务写入、提交push、部署/服务启停，不扩大Q3/Q4。下面的旧更新时间与旧监督为历史时点。）

更新时间：2026-10-13（本轮已授权只读DOM与有限交互检查、两份Frontend Design独立监督及父级核证已完成，无待返回本轮监督报告。index-CW3V7nPt.js确证1个P0共享焦点环缺陷MF-FOCUS-01：640×480闭合details，SUMMARY→BODY→BUTTON；候选14误含2隐藏后代/实际可见12，反向环也不能通过。两套S-NAV-04补充FAIL，另外82完整BLOCKED、完整PASS0；原84全文/priority/顺序/历史状态保持不改，五页/最终ZIP PENDING。主轮92UI/5页computed/11matched/3listener，定点9UI/8节点，独立报告14输入/10父引用/76有界证据引用全部父核。21生产输入和renderer/Boot各60完整资源集合hash再次一致；没有本次新HTTP/health、截图或像素分析。旧限定像素PASS与全部PNG覆盖保原范围，不证明键盘。只读阶段报告已另存durable artifact，本轮未修源码、业务/秘密/前台/测试/提交push/部署/服务动作，修复需另行窄授权，Q3/Q4未获准。）

## 当前状态

### 当前MF-FOCUS-01专项闭环：SCOPED_MF_FOCUS_01_PASS

- 认证后的最新安全交通补充：用户供给认证头已临时实际应用，`authenticated-read-status-2`真实观察exit0；直连9090、同一新真实scratch artifact，5页原导航自然读取27GET全200、36静态200、0console/pageErrors、0非GET/越界阻断。未读响应正文/请求头/storage/文本值，details保持闭合，不改源码/运行资源；两个默认设备状态GET仅按已核生产GET定义及原页面自动默认选择链精确加入观察器allowlist。父安全投影/hash已存`AUTHENTICATED-REQUEST-READBACK.json`，认证值不落盘；不以200升级source-success/accepted提交/last-good或整页验收。原专项记录/证据ZIP保持原窗口字节，新的认证结果为另存补充而非覆写历史。
- 本轮授权任务已完成：最小生产补丁、无测试lint/Stylelint/typecheck/scratch renderer及Electron编译、真实隔离有限交互、Frontend Design修复前/实施中/修复后监督及父核。最终独立报告`RUNTIME-REPAIR-REVIEW.md` SHA `83e95d70428d42b755b633ecb140c223c5be5e56b12663d02a0ab60d9cd735a0`，机器状态SHA `d30060a7aa462b8d954839b836272a93469e7c309ac5f8e36084bd0dcebe45b5`，引用表SHA `8f50b14600e929487561c7576bccfbd8807492d613940ab75b98968acec21ed0`；均精确SHA/bytes父核，不再待返回监督。
- `PARENT-FINAL-REPAIR-REVIEW-READBACK.json`重新验证13唯一直接引用、60完整新artifact集合/36实际加载、158当前源path集合及hash、原renderer/Boot各60资源；重算7组/14完整方向/182键逐项ordinal和14邻接边界、6Escape、resize/route端点及16真实末链接内部scroll见证。221记录中201开态均无BODY/抽屉外/隐藏后代焦点；首条闭态BODY不算逃逸。新entry `index-CUrdtFzV.js`独立归属，不混签旧`index-CW3V7nPt.js`。
- 审阅来源准确限定为“保存真实观察记录的独立审阅＋父逐项核证”；新专项PASS只关闭MF-FOCUS-01共享根因及所录顺序边界。不能升级`cloud-diagnostic/S-NAV-04`、`log-network/S-NAV-04`全部合取条件，更不能原84/五页全量/新像素/业务/部署或用户接受PASS。原历史两FAIL及其它完整BLOCKED不擦除，后续证据需新候选独立补充。
- 317CRLF/0孤立LF说明更正已单独冻结，旧scope/candidate/中期报告输入字节保持；未因记录说明错误修改或重建正确源码。最后生产编辑后的五门禁exit0仍绑定当前源，Vite第三方PURE/大chunk警告保留。只有三处焦点逻辑改变，CSS/template/其它handlers/watchers/lifecycle按精确逆向原字节核证保持，157其它源从改前基线不变。
- 25原页面GET全401、0pageError/25consoleError；静态36×200不等于后端健康/成功。未读取正文/值/headers/storage/秘密、未提交业务或修认证；不存在新像素签署，reduced-motion/query/root卸载、未自然出现兼容/空集/失效成员分支、成功/last-good/accepted提交、检测ingest/隐藏全文仍未审。完整五页及最终真实效果图ZIP PENDING；专项durable报告/安全证据交付不能冒充该最终包。

### 下列为专项最终独审返回前的真实键盘checkpoint历史

- `keyboard-observation-1/OBSERVATION-RECEIPT.json`：fresh task-owned headless Chromium，scratch编译产物精确path/SHA/bytes映射，不是部署或API Mock；加载entry `index-CUrdtFzV.js`/SHA `ab1b29224fcb456f53b417cb9b5087902de9c930a35b1aa73ea70b3e3a3405d0`，36唯一真实静态资源均与60项冻结集合对应。全部原GET继续原后端，method/精确path/默认参数allowlist，未读取response正文/headers/storage/值，未添加凭据。
- `PARENT-KEYBOARD-OBSERVATION-READBACK.json`从221原记录重算182原生按键：cloud/diagnostic/log/network/collection五页640×480、900×600及重新640×480的7组，每方向13次，均实际走全12候选并按±1mod12连续；初焦都在当前link，闭合details全程不展开，旧算法14/2隐藏后代仅诊断。7次正向SUMMARY11→关闭BUTTON0、7次反向BUTTON0→SUMMARY11均一次原生键完成，无BODY/外部/隐藏焦点；不把诊断候选列表冒充通用浏览器顺序oracle。
- 五页及短高复回6次Escape均等实际hidden后保存回焦/expanded=false/main解锁/overflow恢复；当前route点击一次闭合、另四route真实RouterLink导航。desktop当前link真实获焦→900隐藏后trigger；900开抽屉→1024恢复220px常驻nav/currentlink并解锁→640隐藏后trigger。短高section-nav `scrollHeight=533/clientHeight=255`，原生末链接获焦后`scrollTop=278`且整段rect在局部视口；无强制focus/状态注入/checkVisibility改写/人工history-query/实际root卸载。
- 新观察36静态200、25原GET全部401，0非GET/0越界guard阻断、0pageErrors/25consoleErrors。401是实际未认证状态，不签source-success/accepted-commit/业务/last-good，不读取或修复秘密。观察结束关闭仅task-owned headless browser，既有服务/窗口未动。
- 实施中报告`REPAIR-MIDPOINT.md` SHA `9728cebe1d8b9797ccd8fd9e1a9cf72904ae82e36f22f2e01e9241f3c448e432`父核：三处符合、源级must-fix无；说明更正以版本化补充保留其冻结输入。观察后158源保存hash/旧renderer与Boot各60资源集合再次一致，无新源码编辑；前五gate依然归当前源。修复后Frontend Design独立保存证据审阅`deleg_9ed39160`在途，不冒称已返回或独立新交互。
- 当前仅RUNTIME_OBSERVED_PENDING_INDEPENDENT_REVIEW；旧两套S-NAV-04 FAIL是历史、不能擦除，原84整条仍无新完整PASS。缺失checkVisibility/嵌套details/disabled fieldset/空集合/panel失效等未自然分支只源级；Electron IPC/OS焦点、新像素、reduced-motion/query/root卸载、成功业务/隐藏全文及完整五页/最终ZIP均不在本次通过声明内。

### 下列为授权修复实施/构建历史checkpoint

- 授权范围：只修`collector-desktop/src/app/AppShell.vue`三处焦点逻辑、无测试静态构建及隔离同范围有限键盘复核；不改业务/API/Router/Store，不展开令牌details或读取值/正文，不部署或启停服务。
- `focus-boundary-repair-authorized/PRE-EDIT-BASELINE.json`保存改前158个生产源/静态资源集合。AppShell原始CRLF与归档LF的SHA分列；经核原SHA重构原字节，仅反向恢复两函数跨度和Tab成员条件一行，即与完整改前原字节完全相等。CSS/template、其余handlers/watchers/lifecycle未改，不将绑定相同冒称全部script未改。
- Frontend Design修复前报告SHA `09776b1e70a45e71e31489ace9469a1c702363b8fe934964fda71d43c98537bd`已父核；实现保留第一直接summary及其子树、检查所有闭合details祖先、hidden/inert/:disabled/负tabIndex及checkVisibility兼容过滤，不排除滚动视口外可Tab的链接。初焦与Tab共用集合，非成员按方向回首尾，保留空集合panel兜底；未注入状态或原生函数伪造分支覆盖。
- 五项真实无测试门禁exit0；Vite仍有第三方PURE注解及大chunk警告，未改依赖/配置消警。新entry `index-CUrdtFzV.js`、60完整资源在scratch独立冻结；没有执行`build:web`的原目录同步、Maven打包或服务替换。原renderer/Boot各60集合字节和9346公共index保持；隔离复核拟仅在task-owned headless browser映射真实新静态artifact，后端原页面精确允许GET继续，不伪造API响应。
- 实施中监督`deleg_d2416592`在途；五页640×480完整双向环、真实Escape、900断点/resize/navigation和原短高复现尚待执行并独立复核。新补充结论另存，不改原84历史、旧failure或旧PNG记录；不将此专项等同S-NAV-04全部合取条件、完整五页/像素/业务/cleanup通过。

### 下列为修复前检查/监督历史

最新运行监督结论（2026-10-13）：`deleg_7fdc3589`已完整返回并父核，MF-FOCUS-01为CONFIRMED P0 MUST-FIX，非新增像素终审。`cloud-diagnostic/S-NAV-04`、`log-network/S-NAV-04`补充FAIL对应同一个共享缺陷，不是两个生产缺陷；其它82条完整BLOCKED，不将局部CSS/关闭/节点复用签全条通过。独立报告、机器状态、回执精确SHA/bytes，14输入、10父引用及76 scoped引用/JSON pointer/源码行范围均核证；原84历史层不改。`PARENT-RUNTIME-DESIGN-READBACK.json`保存，durable `READONLY-DOM-UI-INSPECTION-HANDOFF.md`与原独立报告已生成。本轮检查/复核阶段完成，但源修复仍未授权未实施，最终PASS/ZIP PENDING；下两段是此前范围回读与监督待回时点。

范围监督补充（2026-10-13）：`deleg_77ba2df7`独立范围报告与矩阵已精确SHA父核；16行监督矩阵覆盖Q1/Q2关联并集50条，不是50条通过。原84正文/顺序/priority/历史状态及Q1–Q4映射与安全计划完全相同，2输入和8源码hash一致。本轮不扩展筛选/模式/设备填写、details展开、复制、人工query/history或root卸载；现有history证据仅已有路由点击后真实goBack。实际computed保存15个安全native disabled布尔，观察器也读取该布尔等待settle，不冒称只读ARIA；范围监督指出字段未在投影清单显名，保守不据此签L4/N1完整禁用语义，不新增扩读。独立运行证据监督的最终报告尚未消费，焦点问题仍未修、整体PENDING；下段“在途”是上一回读时点。

最新实际回读：Q1/Q2授权已收到并落实于任务自有headless Chromium和既有9346候选；只保存允许的几何/CSS/ARIA/焦点/监听器摘要。Escape、遮罩、×、当前/其他路由、history关闭及焦点回退、1024→900→1024→640尺寸变化有新实际记录；query-change和真实root unmount未执行。主轮与定点轮均发现闭合details下隐藏后代被焦点列表误算，正向Tab出现BODY越界，不能签键盘通过；独立Frontend Design证据监督在途，不提前签其结论。旧PNG覆盖已收口，无待返回PNG批，本轮0新增像素分析；两套42原条件及旧证据保持不可变，五页/ZIP PENDING。实际5页h1为22px/650/31.9px，采集textarea184px/vertical及具体matched规则有证，但不认证风险全文/全部字号/真实成功态。Sidebar实际BackendNodeId14八次相同只证明本轮记录节点复用，不证明秘密值、所有state或unmount清理。授权后不再重复请求Q1/Q2；禁止业务、秘密、前台、源码编辑、部署/服务动作的边界不变。以下较早“已问未答”等段落仅为当时历史。

- 当前目标分支：`feature_2.0`
- 最近提交：
  - `00c7fe1` 修改
  - `20e360d` 修改
  - `857360a` 修改
- Phase 1 ~ Phase 17：全部完成并通过验证。
- `collector-desktop` 前端架构重构：已完成。
- 不创建 Phase 18；后续优化作为独立任务重新确认范围。
- 2026-10-09 独立优化：设备配置页 Frontend Design 实现、静态检查、构建、真实后端只读交互及最终截图复核已完成，状态为 `READY FOR USER VISUAL REVIEW`；最终效果待用户确认。范围与验证边界见文末。
- 2026-10-09 新增独立优化：按已展示 Frontend Design 方案实现批量和协议命令、设备影子；首次监工指出的四项样式问题已修正并通过真实渲染尺寸检查，五项门禁重新通过。二次监工已确认代码 / DOM 修正，但当时截图读取能力受限，独立像素复核尚未完成，不能标记操作页视觉通过；本轮不重新验收操作页。
- 2026-10-09 协议连接独立实施：已按用户确认的 EtherNet/IP 完整目标图完成生产修改，新增工作台专用 Schema 表单和110字段展示尺寸，旧本地设备编辑表单不变。前期、实施中和最终 Frontend Design 均实际读取截图像素；必须修正项已闭环，最终独立监工为 `PASS`（所附截图像素复核 + 五协议三宽度运行记录核验），六项静态/构建门禁通过。不是整页逐像素完全相同、业务写入验证或用户最终验收；未部署运行中旧JAR，不新增 Phase 18。
- 2026-10-10 本地临时设备弹窗独立实施：已按批准方案落实五个 Tab，业务脚本与 Git 基线一致，62处模板业务绑定保留，六项允许门禁通过。Frontend Design 全程监工发现的问题已闭环，最终18张主/滚动图及4张局部图获得附图范围 `PASS`；“双套箭头”疑点经高清像素核实是计数单位“个”，原缺陷已撤回。最新62张有效截图/65条记录来自 `index-DBqXg9DV.js`，不是62张全部逐图签收或业务验收；当前 `READY FOR USER VISUAL REVIEW`。不新增 Phase 18，不重启或部署旧JAR。

- 设备管理独立优化最新状态：2026-10-10按批准稿完成生产实施；Frontend Design 前期、实施中和末次构建持续实际像素监工，空态重复标题及确认窗比例/危险按钮/遮罩问题已闭环。末次14图全部获得所附范围PASS，当前为 `READY FOR USER VISUAL REVIEW`，不是全状态业务验收或用户最终确认。未提交/推送/部署，不新增Phase18；详细记录见文末。

- 2026-10-10 概览/实时数据/历史趋势独立优化：六个生产呈现文件及原三个业务区、40处控件绑定和56个保护文件保持；六项允许门禁通过。原离线四附图PASS之后，用户授权启动原9090，补采27张真实原图；Frontend Design独立审查deleg_a1783768实际看4张派生图，当前有后端状态/尺寸PASS，无must-fix。历史存储仍不可用，成功历史曲线/记录及真实采集长精度数值未覆盖；前序三个CSS换行问题未闭环，不等于全状态或用户最终验收。未提交/推送/部署新JAR，不新增Phase18；见文末独立记录及恢复补审。

- 本轮独立运维交付（前序）：五页综合源码分析及优化建议已保存；前端允许门禁、隔离后端无测试打包、9090新包部署及完整57资源HTTP字节核对完成，入口 `index-BM7lzMzh.js`。该前序候选不作为本轮视觉签收。
- 2026-10-11 五页建议实施已完成：真实renderer入口 `index-DsbgyuIk.js`，五View与五份scoped CSS已按建议调整；末次 `git diff --check`、lint、Stylelint、typecheck、Electron build、Web build均exit0。Frontend Design独立复核 `deleg_2b4ea181` 首轮40张 + `deleg_d06f90d1` 补审8张，48/48在声明视口/滚动/状态范围内PASS，无当前图片支持的must-fix。当前状态为 `SCOPED VISUAL PASS / READY FOR USER ACCEPTANCE`，不等于106张全部逐图、敏感正文/未执行业务态或用户最终验收。

- 2026-10-11 最新提交 `1984343221bf8373d026219c9e0992583f304c45` 已使用最新无测试打包产物部署并重启9090，运行入口为 `index-DsbgyuIk.js`，页面/health HTTP均为200；真实运行状态仍如实为 `application=UP`、`cache=UP`、`collectionService=DOWN`、`health=DOWN`、`connections=UNKNOWN`。随后对9090真实renderer采集40张五页候选图，Frontend Design独立实际查看5张1600宽主图及3张640宽窄图，形成扫描报告。新一轮只记录建议，未修改生产代码、未执行业务写操作、未写测试；首要建议为<=900px收起侧栏/单列化，其次为统一未知/未执行/不可用/零值/失败语义、拉开读写操作权重、增强异常恢复与数据新鲜度表达。报告见发布产物 `POST-RELEASE-FRONTEND-DESIGN-SCAN.md`，不将401、未知数据、health DOWN误判为视觉缺陷。

- 2026-10-12 五页及共享壳二次精修最新状态：前序6项及新增3类独立源码must-fix已RESOLVED。旧 `index-BLe3PJnp.js` 授权终审发现P1 MF-V1抽屉背景穿透，历史FAIL保留；父级核真实关键工具结果后，仅把AppShell/AppSidebar两处CSS背景引用改为既有不透明 `--app-color-sidebar`，反向恢复完整改前文件hash，业务script/template及焦点/Router不改。六允许门禁重新exit0，新 `index-CW3V7nPt.js` 已105主图+173补图重采，共278图、265不同PNG hash。新 `deleg_33038f3e` 已实际分析39/39native＋3sidebar＋1detail，共43次真实分析，56/56本组来源可见矩形联合覆盖；MF-V1及MF-E1/E2/E3已按新像素闭环，原36项为26限定PASS、10BLOCKED、0FAIL，没有新生产视觉must-fix。父级重新计算43handle对应成功tool-result、全部原矩形覆盖、21源/60renderer/Boot/278PNG/100前端/840Java保护零漂移；compact transcript仅作实际工具handle佐证，不冒称完整vision正文已读回。当前为 `SHARED/COLLECTION SCOPED CURRENT-VISIBLE PIXEL PASS / OVERALL PENDING`；云31＋诊断126来源及110native已串行派 `deleg_a70fb030`，之后才审日志/网络，不沿用旧PASS。本轮未测试、提交、推送或部署；9090/9346公共HTML最后读回分别为旧 `index-DsbgyuIk.js`/新候选，本次未重新读取health正文或执行业务操作。

- 2026-10-12 第二组首轮终审（历史）：`deleg_a70fb030`已返回 `BLOCKED_INCOMPLETE_PIXEL_REVIEW`，不是云/诊断通过。110必审native仅6完整＋1部分真实分析，native06缺右45px，103板未见；157来源full3/partial4/unseen150，42条原要求全部BLOCKED，2侧栏补板仅准备未分析。三个自有查看器连续正常退出，最后记录 `WM_DELETE_WINDOW`，当时触发者未知，未出现新工具同意拒绝。父级当时核7实际成功tool-result及capture副本/hash/尺寸，按返回几何独立重算覆盖，21源/60renderer/Boot/100前端/840Java当时零漂移，精确三个HWND均消失。当时暂停反复重开及日志/网络同路径审查等待关闭来源和后台保持确认；后续用户确认与恢复见下条，不降低原110板矩阵，不发布总体PASS或最终冻结包。

- 2026-10-12 第二组首次恢复（历史）：用户原文“我刚才手动关闭了截图查看器，后续不再关闭  你继续”，已解除关闭来源及后台保持等待；只继承任务专用已脱敏截图后台打开/切换/翻页授权，不扩大业务、凭据、测试、前台、提交或部署范围。当时派 `deleg_697539e5`保留原6完整native证据，补native06与103未见native、2侧栏；其后实际因上游超时失败，增量证据与续审见下条。42原要求及未审状态保留，日志/网络48native仍待本组结束后串行。恢复前21源、60renderer/Boot集合字节、112板及157来源PNG、两捕获报告一致，100前端保护无漂移；既有ConnectionManager.java与前序840Java基线有1处非纯CRLF漂移，本轮未碰、不回滚，不宣当前后端合同或840Java零漂移。证据 `PIXEL-REVIEW-RESUME-AUTHORIZATION.json`、`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-RESUME-PRECHECK.json`。

- 2026-10-12 第二组第一次分批续审（历史）：`deleg_697539e5`返回failed，上游连续6次空/坏响应、slow response359s；native08并发失败/unchanged-screen不计seen。这不是新同意拒绝或关闭来源等待。父级核native06/07两成功handle及完整原板/cap像素，累计9分析/8完整native；旧PID26512/HWND800040已不存在。父级持有无激活PID41380/HWND598426，native08一次60秒延时重试真实成功后累计10分析/9完整native、101未见，source full6/partial3/unseen148；当时派30板 `deleg_38289ad8`，其后增量及再次失败见下条，原110/157/42矩阵保留。证据 `DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-RESUMED-PARENT-READBACK.json`、`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-RECOVERY-CHECKPOINT.json`。

- 2026-10-12 第二组最新增量：`deleg_38289ad8`再次上游6次空/坏响应、slow response398s而failed，原30板未完成，5个真实成功native09/10/11/12/13已核trace成功分析前缀/handle、capture副本SHA/尺寸、整板与source crop像素完全相等；native11首试并发失败排除。父级同窗口正常capture补native15/16/17三板，直接取得完整真实分析；最新累计18分析、17完整native/93未见，来源full15/partial4/unseen138，2sidebar未审。640云抽屉MF-V1仅该图可见不透字PASS，诊断库存未知/失败及默认密度仅已见范围可接受，不预签展开details或整页。已派小批 `deleg_c237391b`续native18–22，另88native及2sidebar保留；42完整条件、日志/网络48板、总体及最终冻结包仍PENDING。证据 `BATCH-1-RECOVERY-DESIGN-NOTE.md`、`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-BATCH-1-PARENT-CHECKS.json`及`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-BATCH-1-RECOVERY-CHECKPOINT.json`。

- 2026-10-12 第二组第2微批（历史）：`deleg_c237391b`已真实完成native18–22五板，full5/partial0/unseen0、无capture/vision失败；父级独立核tool成功前缀/handle、cap副本SHA及整板/来源crop像素，原42条文案完全相同且完整状态42BLOCKED。累计23分析/22完整native/88未见，source full21/partial4/unseen132，2sidebar未审；失败库存未知非0与来源失败/业务未知有新可见证据，但逐行颜色、混合排序、全展开details及drawer未被本批认证。后续23–27五板已完成，见下条；证据 `MICROBATCH-2-DESIGN-REVIEW.md`及`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-2-PARENT-READBACK.json`。

- 2026-10-12 第二组最新微批：`deleg_739e97c3`实际完成native23–27五板，full5/partial0/unseen0，五次vision成功无重试；父级核整板/来源crop RGB、frozen hash/transform、cap原副本/尺寸、实际tool handle及可读42–49字符分析前缀，完整子saved tool与逐图记录一致，但不冒称独立取回完整raw正文。累计28分析/27完整native/83未见，source full28/partial4/unseen125，2sidebar未审；390/1280×720上下和640诊断drawer/表左右端结构实际可见，库存未知非0及失败/未知并列继续支持，但各行精确色相/密度/展开全部元数据/诊断drawer不透字仍证据不足。已派`deleg_4510b619`只续28–32云连续五板，另78native和2sidebar保留，原42全BLOCKED、日志/网络和总体PENDING。独立`MICROBATCH-3-DESIGN-REVIEW.md`及父级`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-3-PARENT-READBACK.json`已存；窗口41380/598426后台NOACTIVATE留运行，21源/60renderer/Boot再次一致。

- 2026-10-12 第二组第4微批：`deleg_4510b619`真实完成28–32五native，full5/partial0/unseen0；native28首次gateway_concurrency_limit失败及PNG真实留存、不计seen，有限同窗重试成功。父级核五成功tool handle/可用分析前缀、完整子saved tool内部对应、整板/来源crop RGB、冻结hash/transform及42原文；累计33分析/32完整native/78未见，source full31/partial6/unseen120，2sidebar待审。1600连续两来源未含左220px故partial，不用整板full冒称来源full；1280连续上下和1024首板未知/未评估/恢复路径/Replay和Flush风险说明限定支持，未签成功态或细字/端点/生命周期。已派`deleg_179cd9fe`有界10板33–42、每5板即时检查点，另68native和2sidebar待，原110/157/42不变。证据`MICROBATCH-4-DESIGN-REVIEW.md`与`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-4-PARENT-READBACK.json`；21源/60renderer/Boot一致，自有41380/598426后台native32/index31留运行，总体PENDING。

- 2026-10-12 第二组第5批与父级41补审：`deleg_179cd9fe`原10板只33–40八板实际成功，父级核成功tool handle/前缀、完整子saved tool文件及逐图正文/原像素/来源crop，native38首次写文件权限错误后的补存现有效；41 gateway及无分析screen_unchanged两返回不计seen，42未试，原8/10 BLOCKED_INCOMPLETE不改为完成。查明retry marker只记commentary/JSON，实际native caption未变、两PNG hash相同；父级核自有PID/HWND、原PNG/NOACTIVATE/非前台后，真正SetWindowTextW并GetWindowTextW读回新title，单次正常capture取得41完整实际vision正文，cap整板/source crop完全相等。此为单独PARENT_FOLLOWUP，不冒称独立终审。累计42分析/41完整native/69未见，source full42/partial8/unseen107，两sidebar待、原42完整BLOCKED；已派`deleg_9ebdb3f2`仅42–46五板，另64native留原范围。证据`BATCH-5-PARENT-READBACK`、`parent-caption-recovery`及`AFTER-PARENT-41-CHECKPOINT`，总体PENDING。

- 2026-10-12 第二组第6微批：`deleg_9ebdb3f2`实际完成42–46五板且父级核证，full5/partial0/unseen0、分析成功5/失败0/重试0；实际handle及42–47字符解码前缀佐证、完整child saved tool内部对应、冻结hash/transform、整板与source crop RGB及42原文均核。累计47分析/46完整native/64未见，source full44/partial11/unseen102，2sidebar仍待；1600三来源只含x220以右，仍partial。库存失败未知、来源失败与业务未知、最后尝试/尚无成功读取和恢复/折叠入口有局部证据；红色明确对象仅45错误横幅，不能外推八行标签或字号/密度/排序/展开全文。原42完整BLOCKED、五页总体PENDING。已派`deleg_0914a7dc`仅47–51，另59native及2sidebar留原矩阵；证据`MICROBATCH-6-DESIGN-REVIEW.md`及`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-6-PARENT-READBACK.json`。

- 2026-10-12 第二组第7微批：`deleg_0914a7dc`47–51五板完整实际成功，父级核实际handle/44–47字符解码前缀、完整子saved tool/aggregate/逐图对应、冻结hash/transform/capture原副本/整板与source crop RGB，以及42原全文。累计52分析/51完整native/59未见，source full49/partial11/unseen97，两sidebar待；当前库存/运行未知而非0、来源失败与业务未知、优先关注层级/管线背压中文名/尝试时间/恢复折叠入口限定支持，颜色/字号密度/完整每源时间与展开全文仍缺证，无新确切must-fix不等于无缺陷或PASS。已派`deleg_6483344a`有界52–61十板、每5板检查点，另49native留原110/157/42，原42完整BLOCKED、整体PENDING。证据第7报告及`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-7-PARENT-READBACK.json`；父级窗后台留运行，21源/60renderer/Boot一致。

- 2026-10-12 第二组第8批：`deleg_6483344a`52–61十板真实成功，父级核10实际handle/43–49字符解码前缀、完整child saved tool/逐图/汇总对应、冻结hash/transform、原capture与留存副本SHA/尺寸、1:1整板及source crop RGB，42原全文保持；第5/10图checkpoint完成与剩余板名单精确核证。累计62分析/61完整native/49未见，source full59/partial11/unseen87，两sidebar待。库存未知/来源失败与业务未知、尝试无成功时刻/整轮结束分列及恢复入口有证，未认证行颜色/字号密度/展开/真实成功/混合排序；原42完整BLOCKED、整体PENDING。已派`deleg_aa4d95ad`62–71十板（900尾段、640与390连续），另39native保持原110/157/42；第8报告/父级READBACK和CHECKPOINT-PARENT-CHECK保存，21源/60renderer/Boot一致，owned窗后台留运行。

- 2026-10-12 第二组第9批：`deleg_aa4d95ad`62–71十板真实成功，父级核10实际handle/42–49字符可用解码前缀、child完整saved tool/逐图/汇总内部一致、冻结hash/transform、原capture与副本SHA/尺寸、1:1整板及22来源tile RGB；首图gateway失败的实际handle/前缀与PNG保留、不计seen，单次caption readback及不同capture SHA核证。第5/10图checkpoint完成/剩余名单及records精确核证，42原全文及完整BLOCKED保留。累计72分析/71完整native/39未见，source full81/partial11/unseen65，两sidebar待；多panel局部/组级观察不外推小字/逐行颜色或精确密度。已派`deleg_fe64943d`仅72–81十板（390尾段、640表左右及1600/1280/1024表左纵向），另29native/2sidebar保留；第9报告和父级READBACK已存，21源/60renderer/Boot一致，owned窗后台留运行。五页及原42完整PENDING/BLOCKED，不是总体PASS。

- 2026-10-12 第二组第10批：`deleg_fe64943d`72–81十板真实成功，父级核12实际tool-result handle及可用解码前缀（十成功42–50字符）、child完整saved tool/aggregate/record内部一致、冻结hash/transform、原capture/副本SHA与尺寸、1:1整板及13来源tile RGB；75/76各一次gateway失败不计seen，逐目标单次caption更新/readback/原PNG不变和capture SHA不同核证。第5/10图checkpoint有序完成/剩余名单与record/tool/capture引用/hash精确核证、42原全文完整BLOCKED。累计82分析/81完整native/29未见，source full91/partial14/unseen52，两sidebar待；三个1600纵向来源裁左220px仍partial。77管线背压中文主名/英文辅助获局部支持，80英文误译不当作中文缺失；原生details折叠/安全脱敏不判缺陷。已派`deleg_1720e133`仅82–91十板（1024/900表左及640/390表左右纵向），另19native/2sidebar留原矩阵；第10报告/父级READBACK已存，21源/60renderer/Boot一致，owned窗后台留运行。原42完整BLOCKED/五页总体PENDING，非总体PASS。

- 2026-10-12 第二组第11批：`deleg_1720e133`82–91十板真实成功，父级核10实际handle/42–49字符可用解码前缀、完整child saved tool与aggregate refs/hash/analysis及逐图内部一致、冻结hash/transform、原capture与副本SHA/尺寸、1:1整板和17来源crop RGB；第5/10图checkpoint有序名单、record/tool/capture refs/hash及config/manifest/brief hash精确核证，原42全文/完整BLOCKED保留。累计92分析/91完整native/19未见，source full108/partial14/unseen35，两sidebar待；native91逐panel建议“查看来源详情，再重试完整诊断。”有限支持，组级内容不外推小字或独立时间。已派`deleg_5408deed`仅92–101十板（1600/640已展开行来源与来源时间账本连续段），另9native/2sidebar留原矩阵；第11报告及父级READBACK保存，21源/60renderer/Boot一致，owned窗后台留运行。全42完整BLOCKED/总体PENDING，不是总体PASS。

- 2026-10-12 第二组第12批：`deleg_5408deed`92–101十板真实成功，父级核10实际handle/42–45字符可用解码前缀、完整child saved工具/逐图/aggregate refs/hash/analysis、冻结hash/transform、capture原副本SHA/尺寸、1:1整板和13来源crop RGB，第5/10图checkpoint有序名单/refs/hash/config-manifest-brief及原42全文精确保持。累计102分析/101完整native/9未见，source full114/partial21/unseen22，两sidebar待；本批七1600来源仅x220以右，原图仍partial。92/94/95具名1600最后尝试09:21:44/尚无成功局部支持，100的64009:21:46仅跨panel具名组级，不补齐其他源/细字/成功提交。已派`deleg_da14d72e`102–110九required及sidebar01/02两辅助，成功5/10/最终11检查点；原42完整BLOCKED/五页总体PENDING，不提前记seen或PASS。

- 2026-10-12 第二组第13批收尾：`deleg_da14d72e`审native102–110九required及sidebar01/02两辅助，11次真实分析成功、失败0/重试0。父级按实际schema核11个actual handle/可用解码前缀、完整child saved tool与record/aggregate refs/hash/analysis内部一致、原capture副本SHA/尺寸、1:1整板RGB和43来源join的source SHA/crop RGB，5/10/最终11检查点有序required/auxiliary完成与剩余名单、record/tool/capture refs/hash、config/manifest/brief hash及原42全文均核证。最终文件实际名`checkpoint-final-11.json`，初查文件名不适配是准备问题，不是capture失败。完整raw子tool未独立回取限定保持。
- 云/诊断累计113实际成功分析（含历史partial06）、110/110完整required、0当前partial/0unseen、2/2辅助已审。父级按原source path/hash与矩形联合重算157source为full157/partial0/unseen0；21侧栏来源join逐一核原字节，补齐左220px仅证明导航像素，不认证业务细字/来源时间。第13没有识别具名来源的具体最后尝试值；09:21:46/49仅按实际panel或组级记轮结束，不补为来源成功。原42完整条件仍BLOCKED；来源成功/last-good/混合排序/隐藏正文/精确色字号/640诊断drawer不透字/键盘生命周期未签。第5原8/10与parent41非独立终审历史保持。证据`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-BATCH-13-PARENT-READBACK.json`；21源/60renderer/Boot完整集合及hash一致，不是当前后端零漂移认证。
- 2026-10-12 第三组启动：日志27+网络38原图、48required native及原17辅助overview的冻结hash/尺寸和1:1source crop再次核证。`LOG-NETWORK-FROZEN-REVIEW-BRIEF.json`在本组首capture前保留原L1–L7/N1–N7全文及28共享条件，共42项；即使48native全审也有9桌面原图左220px缺口，不能据48/48推65来源full。新父级专用查看器PID39864/HWND6359704已读回native01/index0、原路径/hash/transform、NOACTIVATE/非前台；旧PID41380/HWND598426留运行，不停止9346/9090。`deleg_d46d9e9c`一条子任务仅审01–10，先核01/02不同图真实分析再继续，第5/10成功检查点，其余38明确未审；另条子任务只读收敛云/诊断42项剩余证据，不重复泛扫157source，不操作viewer或改生产。没有提前计seen/签PASS，无新增生产/测试/业务请求/秘密/提交push部署或服务启停。

- 2026-10-12 第三组首批实际核证：`deleg_d46d9e9c/task-0` native01–10真实完成，success10/failure2/retry2/captureCalls12。01首gateway失败，固定60秒同caption重试unchanged且无分析、不计seen，随后一次独立material native-caption纠正恢复；父级核12实际handle、十成功41–46字符可用解码前缀、完整child saved tool与per-image/aggregate/checkpoint refs/hash/analysis内部一致、1:1整板及12来源crop RGB/hash、原42全文/完整BLOCKED、5/10检查点的有序48required/本批及批外余项/17辅助overview未审名单、冻结config/manifest/brief hash。原capture两个失败SHA相同、恢复SHA不同但原板/来源不变，完整raw子tool未独立回取限定保持。实际冻结来源纠正：09/10仍日志640/390双panel，网络始于native17，先前派单“09起网络”描述不作证据。累计10/48完整required、0partial/38unseen；65source union full10/partial2/unseen53，日志full10/partial2/unseen15，网络38全部未审。报告及`DRAWER-CORRECTED-LOG-NETWORK-MICROBATCH-1-PARENT-READBACK.json`保存，无新确证must-fix不等于无缺陷或PASS。
- 2026-10-12 云/诊断42项证据收敛：`deleg_d46d9e9c/task-1`原42id/priority/criterion全文保留，父级核原文、authorities/previous-readback refs/hash、112catalog board/record/capture/tool/source hash及criterion witness引用。完整状态0PASS/42BLOCKED/0FAIL；限定可见建议29PASS/13BLOCKED/0FAIL只是既存localized/group观察复核，不是新看图或独立终审。已退休“六宽度/后组/底部/展开未审”覆盖理由，剩余精确颜色/640诊断drawer不透字/未归属source时间/字号几何/键盘生命周期/真实成功与last-good/混合排序/合法全文仍分列。最小已有PNG细读首轮<=12、必要delta<=12，不再泛扫157source；新DOM/键盘/业务GET或危险操作未获本子权限。报告line16关于identity仍batch12为并行派单后的陈旧旁注，当前父级identity已指batch13，父级`CLOUD-DIAGNOSTIC-42-CRITERION-AUDIT-PARENT-READBACK.json`明确纠正，原子报告不改。
- 2026-10-12 后续有界执行：父级核viewer39864/6359704仍存活、NOACTIVATE、非前台、native10/index9，最小化只用同ownedwindow的ShowWindow4恢复并读回iconic=false/不激活/原PNG与transform不变；未close/restart旧云诊断窗或服务。`deleg_15f3078c/task-0`只审11–20十板，11–16日志短高/drawer左右端/更多筛选，17–20网络1600/1280上下，第5/10成功检查点，其余28required保未审，不能提前计seen；task-1仅解析已有冻结safe capture-report中14代表几何/至多2条已有UI序列，核身份匹配、缺口与复用，不操作桌面、不发业务请求、不写测试或改生产。21源/60renderer/Boot完整集合/hash再次一致；本次无生产/测试/业务/秘密/提交push部署或服务启停，整体及最终效果图ZIP仍PENDING。

- 2026-10-12 第三组第2批核证：`deleg_15f3078c/task-0`真实完成native11–20，11–16日志、17–20网络，success10/failure0/retry0/captureCalls10。父级核10实际成功handle及41–49字符可用解码前缀、完整child saved tool/record/aggregate内部refs/hash/analysis、原capture副本SHA/尺寸、1:1整板及12source crop RGB、checkpoint-05/10有序完成/剩余/批外required和17辅助名单、冻结config/manifest/brief及原42全文/priority/完整BLOCKED；50个索引文件hash/尺寸逐项核证。完整raw子tool未独立回取限定保留。累计20/48完整required、0partial/28unseen；65source按exact path/hash矩形联合full19/partial5/unseen41，日志17/3/7、网络2/2/34；相同hash但不同source仍各自审查，1600裁左220px仍partial。第1批10success/2failure/2retry/12capture历史不变。日志401恢复/未知空态及native15弱化导出、网络未执行/本地会话历史真实0及native18蓝色开始检测获得限定支持，不将未知伪0、不认证真实响应/业务handler/精确尺寸/390每字段/全部来源时间。报告和`DRAWER-CORRECTED-LOG-NETWORK-MICROBATCH-2-PARENT-READBACK.json`保存，无新确证must-fix不等于无缺陷或总体通过。
- 2026-10-12 云/诊断存量runtime复用核证：`deleg_15f3078c/task-1`只读解析当前identity引用的105/173条安全capture-report，完整SHA/entry合同一致；157云/诊断source路径/hash及几何记录全部精确join冻结batch13，父级核53个JSON指针和保留标量、14代表layout、2段已有连续UI序列（14终态）、40导航安全记录及表端点/15连续摘要。已存桌面导航220px/顶栏40px、窄闭态导航0px/顶栏52px、两页640开/Escape后的inert/滚动锁/焦点回退、resize读回、诊断表横滚0/140与末header.right=panel.right=615可复用；12项只减少E2/E3补证，原42完整BLOCKED不变。query-change三条hash无问号，只认证命名终态、不认证真实参数改变；shared collection抽屉底部只作共享partial、不冒充云/诊断逐页或keyboard验收。原viewport与native crop PNG尺寸、layout/metadata及安全字段投影按真实schema分开核证，准备错误只修父级解析，不改子证据。winning CSS/控件bounds/全键盘事件归因/listener-unmount，以及E1像素/E4-E5真实成功与全文缺口仍保留；报告和`CLOUD-DIAGNOSTIC-EXISTING-RUNTIME-EVIDENCE-PARENT-READBACK.json`保存，不是新capture/handlerverification或新PASS。
- 2026-10-12 第三组第3批已派：父级核owned39864/6359704仍native20/index19、NOACTIVATE/非前台/非最小化，21源及60renderer/Boot完整路径集和hash一致；`deleg_53f9ee94`只审21–30十required（网络1024/900/640/390上下、1280×720上下、640drawer及表左右端），第5/10成功检查点，其余18required及17overview保未审。原48/65/42范围不缩，48全审仍须9来源左220px原像素补证；旧云/诊断窗及9346/9090不动。只更新文档/scratch证据，无本次生产/测试/业务请求/秘密/提交push部署或服务启停，五页总体与最终冻结交付仍PENDING。

- 2026-10-12 第三组第3批核证：`deleg_53f9ee94`真实完成native21–30十网络required，success10/failure1/retry1/capture11。首图app-only定位失败，真实返回width/height均0、无analysis/PNG/handle，不计seen；记录60秒有界等待后公共capture以同owned HWND6359704/PID39864精确定位恢复。父级核十一actual返回、十成功handle及23–30字符可用解码前缀，完整child saved tool/逐图/critical aggregate refs/hash/逐字analysis内部一致、原PNG副本及lossless base64解码字节完全相同、1:1整板和13个source crop RGB/hash、checkpoint-05/10有序完成/剩余与17辅助分列、冻结config/manifest/brief hash和原42全文/priority/完整BLOCKED；索引54成员hash/尺寸精确核证。native21真正完整工具引用为`tool-results/native-21-actual.json`，不将另存minimal记录当完整实际返回。完整raw子tool未独立回取限定保持。累计30/48required full、0partial/18unseen；65source按exact path/hash矩形联合full32/partial5/unseen28，日志17/3/7、网络15/2/21，17overview未审；未审required为31–48。报告及`DRAWER-CORRECTED-LOG-NETWORK-MICROBATCH-3-PARENT-READBACK.json`保存；无足够定位证据新增must-fix不代表无缺陷或通过，原42完整BLOCKED、总体PENDING。
- 2026-10-12 第三组第4批已派：父级交接前核owned39864/6359704为native30/index29、原path/hash/transform精确、NOACTIVATE/非前台/非最小化，21源码及60renderer/Boot完整路径集/hash一致。`deleg_bd388011/task-0`只审31–40十required，第5/10成功检查点；31–34网络telemetry，35混网络390底部与日志390连续源、36日志连续尾段、37–40网络1600/900连续段，逐panel按冻结transform.source归页，不误称全网络。41–48八required和17overview保持未审；48全审仍9桌面来源左220px缺口。`task-1`只读把云/诊断42审计与已核runtime缺口收敛为第一轮<=12当前冻结PNG细读计划，不操作任何viewer、不新capture/DOM/键盘/业务或改生产；计划不是像素审图、不能新增PASS。旧云诊断窗及9346/9090不动，本次只文档/scratch证据维护，无测试/秘密/提交push部署，五页和最终冻结交付仍PENDING。

- 2026-10-12 第三组第4批核证：`deleg_bd388011/task-0`真实完成native31–40十required，10success/0failure/0retry/10capture，按冻结transform共网络10源、日志3源，35混页/36日志，不按派单统称网络。父级核十actual成功handle及23–32字符可用解码前缀、完整child tool/record/critical refs/hash/逐字analysis、原capture与lossless base64解码字节、1:1整板及13source crop RGB/hash、pre/post身份、checkpoint-05/10的有序required/辅助分列、冻结config/manifest/brief hash及原42全文/priority/完整BLOCKED；索引63本批文件与237外部引用共300项bytes/hash精确核证。累计40/48required full、0partial/8unseen；65source按exact path/hash矩形union full41/partial9/unseen15，日志20/3/4、网络21/6/11，17overview未审。日志390 continuous01具名最后尝试09:21:14/尚无成功只按该source局部沿用，网络表单0/质量100/sequence和本地时钟不是业务响应或源时间。父级准备解析`counts.successfulAnalyses`及nested `successfulRefs.record/tool/capture`纠正，不改子证据。报告与`DRAWER-CORRECTED-LOG-NETWORK-MICROBATCH-4-PARENT-READBACK.json`已存；完整raw子消息未独立回取限定保持，无新具体must-fix不等于无缺陷或总体PASS。
- 2026-10-12 云/诊断定点细读计划核证：`deleg_bd388011/task-1`只读准备12个单来源请求（10既存小source PNG+2既存单panel native板），未capture/操作viewer/新裁图/DOM/键盘/业务。父级核计划JSON/MD及全部input refs/hash、当前冻结source/renderer/capture合同、12source PNG尺寸与safe capture record/原viewport/state/retained geometry、native/context hash/transform/source crop RGB、旧actual证据引用、原42全文priority及14项可见子条件映射。具体行/控件rect无法从safe整体几何取得，均保持null，不猜位置；S-P2-06全文边界单独保留。PNG-only可保证完整解决的原criterion仍0，字号token/键盘/成功0falseDisabled/last-good/排序/隐藏全文缺口不变。报告`cloud-diagnostic-focused-detail-plan/FOCUSED-DETAIL-PLAN.json/.md`及父级`CLOUD-DIAGNOSTIC-FOCUSED-DETAIL-PLAN-PARENT-READBACK.json`保存。计划不授予也不撤销权限；既有任务专用脱敏PNG后台查看授权已存在，不重复询问同一授权，新DOM/键盘/业务/秘密/前台/部署仍范围外，遇新拒绝/owned退出立即停。
- 2026-10-12 第三组required收尾已派与后续只读准备：父级核owned39864/6359704为native40/index39、path/hash/transform、NOACTIVATE非前台/iconicfalse，21源/60renderer/Boot全路径集hash一致；`deleg_62e690f5`仅审41–48八required，成功5及最终8checkpoint，不能造CP10或提前签48full。17overview和九1600 source左220px保持未审/partial。父级按九原左条的真实RGB而不是品牌或页名分出3个220×1000不同组（日志三源、网络普通两源、网络遥测四源），逐成员RGB精确核；未发生新分析，不增加coverage。公共writer已准备`focused-png-parent-review/viewer.py/config/selection`及`FOCUSED-PNG-VIEWER-PREPARATION.json`：32项允许名单为3额外左条、12云诊断细读、17原overview；左条仅Tk内存显示原PNG裁片，不生成/改原PNG，不改两个既有查看器或冻结manifest。新查看器未启动，只有语法/身份准备，不声称运行或PASS。旧云诊断窗/9346/9090不动，无本次生产/测试/业务/秘密/提交push部署或服务启停，原矩阵及五页总体/最终ZIP仍PENDING。

- 2026-10-12 第三组required收尾核证：`deleg_62e690f5`按冻结序真实完成41–48八required，8success/0failure/0retry/8capture；父级核八真实工具handle及23–32字符实际解码前缀、所有实际saved public返回字段/逐字analysis/per-image/critical一致、原capture/副本/base64解码字节、pre/post owned身份、1:1整板及15个source crop/target RGB（日志4/网络11）、success5与final8检查点有序48required/17aux分列及原42全文priority/BLOCKED，71本批+339外部共410引用的hash/bytes。累计required48full/0partial/0unseen，actual48success/3failure/3retry/51capture；source65按exactpath/hash矩形union56full/9partial/0unseen（日志24/3/0、网络32/6/0），17overview全未审。native45/46日志native crop600×281、native47/48网络596×42不是640完整viewport，裁边/隐藏正文不认生产缺陷，47 loose“empty log table”依冻结source明确为network历史表头，不借此替换来源。现存15安全source records的精确pointer/hash/PNG尺寸/原viewport/metadata已父核：exact-table原viewport640×1000，日志实offset0→360（请求370但实clamp360）、末headerRight601<=panelRight615，网络0→404、末headerRight613=panelRight613；这是存量metadata，不是新scroll/DOM/GET。native44/45右panel的09:21:28/no-success只局部不外推。父级parser对flat index、历史ref size/bytes增量、base64字段originalBytes/pngBytesBase64的schema纠正已记，不改子证据；历史21–25和31–33共8个cache PNG从既存原bytes备份恢复，父核每backup/ref/SHA/size/PNG signature/解码字节，不重抓、不新增计数。`DRAWER-CORRECTED-LOG-NETWORK-MICROBATCH-5-PARENT-READBACK.json`保存；未独立完整raw子tool回取限定保持，原完整42仍全部BLOCKED，无具体新mustfix不是无缺陷或总体PASS。
- 2026-10-12 定点查看器真实首两图试读：父级核21source/60renderer/Boot完整pathset/hash及旧owned39864/6359704在native48/index47后台运行，不关闭旧云诊断或服务。启动此前已准备的任务专属`focused-png-parent-review/viewer.py`，session `proc_08b8285c2a8c`，actual PID40748/HWND606906（57700是terminal wrapper PID），ownership/原PNG hash/220×1000 client/NOACTIVATE非前台非iconic核实。父级两次真实公共capture审LN-SIDEBAR01（日志三源）→02（网络普通两源），分别存全部实际返回、逐字analysis、handle、原PNG与公共writer的lossless原byte backup、pre/post身份及显示rect，原图只Tk内存copy左220、不生成derivedPNG；实返回220×1030，除30px原生chrome后与五个原source crop逐一RGB完全相同、effectiveScale1，两个不同RGB的允许图真实切换已验证。界面中文组/日志与网络active/客户端就绪可见，标题Fronte...属于原生窄窗chrome，不判产品缺陷；未读/保令牌值。仅父直接跟进，不归为独立终审或整条criterion PASS。`focused-png-parent-pilot/PARENT-PILOT-CHECKPOINT-02.json`保存，合并矩形union后source61full/4partial/0unseen（日志27full/0partial，网络34full/4partial），剩余networktelemetry四源左条LN-SIDEBAR03未审；48required不重复计，17overview和云诊断FD01–12仍未审。
- 2026-10-12 后续小批与证据冻结：child5的safe metadata/410索引现引用候选identity元数据SHA，因此该文件保持原bytes不再更新其历史第4批进度；新真实状态写到版本化`DRAWER-CORRECTED-CANDIDATE-REVIEW-STATUS-AFTER-LN5-PILOT2.json`，与PROGRESS/ledger共同作为最新checkpoint，避免进度更新破坏已核引用。`deleg_a75ae436/task0`仅在同owned40748/606906续LN-SIDEBAR03及云诊断FD01–05共六图，禁止提前将source65full/FD5seen/原42PASS；单列supplement与Cloud首轮12 actual请求预算，原48/65/42、110/157/42及17辅助不缩。task1只读归并两套原42的已具证/缺口/最小额外授权或用户准备合法真实状态，不能以在途审图当通过、不能重新索要已有PNG授权、不执行DOM/键盘/业务/秘密/前台。计划/就绪不是验收，新明确拒绝或owned退出仍停，不替代路径。本轮只有scratch证据/只读viewer和进度维护，无生产/测试/业务GET或写/秘密读取/提交push部署/服务启停，旧两窗与9346/9090不动，五页总体验收/最终ZIP保持PENDING。

- 2026-10-12 定点批1真实回读：`deleg_a75ae436/task0`完整返回LN-SIDEBAR03及FD01–05六图。父级核8份实际返回/原PNG与base64备份、6份逐字analysis与完整savedtool/per-image/critical内部对应、8个真实computer_use结果handle及6个20字符可用解码前缀、pre/post HWND→PID身份、client/capture/chrome/display/source尺寸与1:1 RGB、9个精确source join。两次失败为gateway_concurrency_limit/无分析screen_unchanged，不计seen；同窗caption物理更改/readback和一次有界等待后恢复，不把失败描述当业务读取失败或凭文件存在签通过。CP03/06有序记录、61本批+287外部共348引用SHA/size及两套42原全文/priority已核；完整raw子工具正文独立回取限制保留。来源union从已核pilot61/4叠加telemetry四源左条，实得65full/0partial/0unseen，日志27/0/0、网络38/0/0；48required不重复计，累计51success/5failure/5retry/56capture。原17overview全部未审，两套42完整条件仍BLOCKED。云诊断首轮12actual预算消耗5，FD06–12七请求仍未审；9个mapped可见条件仅partial、5个未识别/延后，不能称已解决14项。FD01为左侧覆盖/外部变暗非明确零穿透，FD02/03红色横幅非逐失败行红或中文主名/row rect，FD04仅Application Service尝试09:21:46及无成功的局部语义，FD05时间组级不派发各源。无确证新must-fix不等于无缺陷。`FOCUSED-PNG-BOUNDED-BATCH-1-PARENT-READBACK.json`保存，下一串行批只续FD06–12、总首轮预算不超12；不重审已完成required、不扩大业务权限。
- 2026-10-12 真正剩余授权/数据条件只读回读：task1保留两套各42、共84原条件的独立来源/priority/全文，157合取子门禁全部完整BLOCKED。父级核23安全输入、162逐图记录SHA、84 suite-qualified原文及UTF-8 hash、157子门禁、priority/非互斥分类统计与4个尚未执行草案；不读原始capture-report正文，父递归遍历核1213安全path/hash/pointer目标并排除12继承raw pointer。子自报1094unique与父递归口径未统一，记录限定而不把1094当父独立总数。计划固定于其读取时pilot61/4与FD12未审，未消费在途兄弟结果；最新像素增量只能来自另份新父回读，不倒改历史计划。`MINIMAL-AUTHORIZATION-PLAN-PARENT-READBACK.json`保存，4草案包括只读DOM/CSS/bounds、有限键盘/UI/lifecycle、用户合法真实状态、隐藏全文与禁读秘密边界；已有PNG grant不重问，不制造成功0/false/disabled、last-good或混合排序。identity和旧overlay/index保持原bytes，最新`DRAWER-CORRECTED-CANDIDATE-REVIEW-STATUS-AFTER-FOCUSED-BATCH-1.json`关联原identity、上一overlay和两份父回读。新DOM/键盘/业务/秘密/前台/部署均未执行；21源/60renderer/Boot集合字节及frozen canonical contract再核。原state原生Windows斜杠与config正斜杠的同文件表示已通过Path身份+SHA核证，不把旧helper纯字符串断言失败当退出或拒绝；Win32实核40748/606906/index7/FD05、640×1000 client、NOACTIVATE非前台非iconic。旧窗/服务不动，本次仅证据/台账维护，五页与最终ZIP仍PENDING。

- 2026-10-12 定点批2派发：原42不缩、FD12完整矩阵不变；`deleg_e3b9430b/task0`只续FD06–12，scope由已核config index8–14导出，前序FD01–05不重复。单列本批actual≤7/首轮5+本批≤12，失败也占预算不加重试；逐图即时保完整actual/analysis/原bytes/几何及RGB，有效成功3/6/最终7保存检查点，不足则partial final。原48required/65source已核与17overview未审保留，新结果未返回前不推进覆盖或条件。限定下一步DOM/CSS/bounds与有限键盘/UI/lifecycle需新明确授权；真实业务成功/last-good/混合状态及隐藏全文不伪造、不读秘密。父级不轮询子artifact/live transcript等待，保已核版本overlay/三台账，收到异步结果再回读。

- 2026-10-12 定点批2真实回读：`deleg_e3b9430b`FD06–12七request/七actual/七success/零失败重试cooldown；父级核七真实computer_use结果handle与20字符解码前缀、完整child savedtool/record/critical逐字analysis及全部实际字段、七原PNG/base64/签名hash/size/尺寸、pre/post owned40748/606906与原source/index/client、chrome-excluded RGB1:1与七source crop→target RGB。46本批+7工具原PNG+26继承共79直接refs/hash/bytes、报告/receipt SHA已核，不冒称回读历史千pointer。CP03/06/final7原12有序名单与每record/actual/backup/post refs、原两套42全文priority及CP副本保持；完整12-request matrix关联前五父核与新七实际，累计12actual12success0failure0retry、failed/unseen空，原17status原字面UNSEEN_DO_NOT_CONSUME仍保未审，父parser最初简写UNSEEN已纠正而未改证据。FD06/07接口组级及轮结束时间不派给每行，FD08异常统计source summary具名局部/其余五来源时间组级，FD09Ingress/Stream/History具名但目标主名未识别非生产缺失，FD10实际中文遥测线程池压力及可见表头/管线消息不能认证遥测来源成功，FD11轮结束09:20:19非指标/队列成功或attempt，FD12具名Outbox数量未知/未成功读取及最后成功尚未成功读取局部，50是读取上限而非实际数量。原14mapped不等于14已解决，无新增确证must-fix非无缺陷、两套42完整BLOCKED/五页PENDING；完整raw子结果未独立回取限定保持。`FOCUSED-PNG-BOUNDED-BATCH-2-PARENT-READBACK.json`及版本化`DRAWER-CORRECTED-CANDIDATE-REVIEW-STATUS-AFTER-FOCUSED-BATCH-2.json`保存，旧identity/overlay/index不改bytes。
- 2026-10-12 原17概览只读预核与授权边界：同owned40748/606906实核index14/FD12、390×844、NOACTIVATE/nonForeground/noniconic，21source及60renderer/Boot完整pathset/hash、frozen canonical contract一致。原17overview/index15–31的原PNG/source/hash及65个source→target像素均静态核：所有panel原源缩放0.4、LANCZOS与冻结board匹配，target采用round（1024×0.4→410，不是截断409）；每板viewer/capture仍须新实际analysis+全板RGB才计seen。预核不计审图/不补65source full，不以0.4panel认证细字/字号或全row颜色；source与board/capture/effectiveScale分列，multi-panel分别具名/组级/未识别。`LOG-NETWORK-ORIGINAL17-OVERVIEW-PIXEL-PRECHECK.json`保存，下一只审01–10、11–17保未审。前一父回复已请求Q1只读DOM/CSS/bounds/事件信息与Q2有限Tab/ShiftTab/Escape/导航/抽屉/缩放，尚无用户授权回复，未执行且不因异步自动消息扩大；Q3合法真实状态、Q4隐藏全文仍草案。仅继续已有PNG grant，旧两窗与9346/9090不动，无生产/测试/业务/秘密/提交push部署/服务动作。

- 2026-10-12 原17概览第1串行批派发：`deleg_5460eec1/task0`只审config index15–24的LN-overview01–10（原auxiliary），不同于原48required，不加157/65 source覆盖信用；index25–31的11–17保原矩阵未审。每图保存实际完整tool返回/逐字analysis/handle/原PNG-byte backup/pre-post/RGB，成功5/最终10检查点，技术失败最多每目标单次重试与同owned caption物理更改/readback，有界foreground cooldown不是抢GUI焦点；新拒绝/退出即停不重开替代路径。父预核65个sourcePanel的0.4倍LANCZOS/round作为geometry，实际判图必须逐panel定位或保group/unidentified，概览不能原尺寸字体/逐行色PASS。两套42全文priority/完整BLOCKED、原17完整名单保持，完整raw二次回取限定续保。该批在途不提前算seen/PASS，父级不poll子artifact/trace等待；未答复的DOM/键盘/业务/秘密/前台/部署不执行，旧窗/服务不动。

- 2026-10-13 原17辅助概览第1批父回读：`deleg_5460eec1/task0`01–10十actual十成功/零失败重试；逐图核真实computer_use handle及20字符解码前缀、complete child savedtool/record/critical逐字analysis/全部字段一致，十original PNG/base64/签名SHAbytes/尺寸、pre/post owned路径hashindex/source/client/NOACTIVATE/nonForeground/noniconic、排30chrome完整boardRGB1:1、40source crop→target LANCZOS/round/RGB精确。源panel各0.4仍只结构，不以board1:1认证原12px细字。CP5/final10原17有序completed/remaining及record/actual/capture/backup refs、84原条件全文priority/CP副本完整BLOCKED；索引60本批+10工具原PNG+11继承+10board+40source=131唯一直接refs/hashbytes，63本批文件=60索引+index/finalreceipt/DIRECT-REFS三独立文件，报告/receipt/index/CP SHA核，不宣递归千pointer或完整rawtool独立回取。父parser先把orderedActuals当results、localizedStructuralSupport/groupOnlyNotPanelCertification当supportScope、captureRGB字典当scalar，已按真实schema修正，不改旧证据或把父parser错误当capture失败。raw capture-report只hashbytes，不新增正文指针读取；originViewport/route/state作为按sourcepath/hash关联的saved safe projection记录，不冒称新DOM测量。新`LOG-NETWORK-ORIGINAL-OVERVIEW-BATCH-1-PARENT-READBACK.json`与`DRAWER-CORRECTED-CANDIDATE-REVIEW-STATUS-AFTER-ORIGINAL-OVERVIEW-BATCH-1.json`保存，旧identity/index/overlay保bytes。
- 2026-10-13 概览观察边界及续审目标：40panel中6 localized结构/34group，零具名source+metadata label+精确time局部证据；日志01–05总体失败/恢复/unknown/空表/筛选，LN04 lower-left drawer及LN05 upper-right desktop局部；网络06–10总体尚未执行/空history/展开遥测，LN09 lower-left drawer/right窄版、LN10 upper-left检查结构及lower-right纵向堆叠局部。100/5秒为筛选/自动刷新参数，127.0.0.1/PING/9090/3000ms为输入；analysis failure/successfulchecks0未定位原label/source，既非实际接口零、也非确证生产虚零。低右遥测延伸冻结view下方非意外裁切/末端可达证明；loose英文转述不是中文copy缺陷，真实401非vision失败。未新确证must-fix不等无缺陷，原两套42完整仍BLOCKED。原17overview现在10full/7unseen，其余11–17/index25–31/25source panels待最后串行批；源panel/native信用+0，原48/65与Cloud首轮12预算分别保持，不混合计数。21source/60renderer/Boot完整pathset/hash及canonical合同再核，同40748/606906 index24LN10、1296×872、NOACTIVATE非前台非最小化；仅同冻结PNG grant，新DOM/键盘问题已问未答未执行，旧两窗/9346/9090不动，无本次production/tests/business/secrets/commit/push/deploy/services动作。

- 2026-10-13 原概览最后串行批派发：`deleg_7283fa96/task0`仅LN-overview11–17/config25–31，前十verified保留不重审，七board/25source混page按exact transform定位而非ordinal。最大每目标一次技术retry/本批capture≤14，失败耗预算，不因model/vision失败反复重派，owned退出/新拒绝即保存partial并停，不重开绕路。每actual即时完整return/analysis/handle/原PNGbase64/SHA/身份/RGB，成功3/6/final7checkpoint及原17有序matrix/完整两套42。仅已有安全projection按sourcepath/hash解origincontext，明确禁止新读raw capture-report.json内容，hashbytes引用不是正文解引用；缺来源viewport/route字段保未知，不用board/sourcecrop dimensions或filename脑补。原17父核仍10full7unseen，原48/65和Cloud12calls保持分列，未返回不提前seen/PASS。父不poll子trace/artifact等待，Q1/Q2无答复不执行新DOM/keyboard/business/secret/foreground/deploy/service，旧窗/服务不动，五页及最终ZIP PENDING。

- 2026-10-13 原17概览最后批父回读：`deleg_7283fa96/task0`仅11–17，7actual/7success/0failure/0retry；父独立读七条live trace实际成功handle、尺寸和20字符解码analysis前缀，并核完整saved actual/record/critical逐字analysis一致、原PNG/base64签名SHAbytes/尺寸、pre/post同owned身份、排30px chrome的完整board RGB1:1及25个exclusive source crop→round尺寸→0.4 LANCZOS→board/capture target RGB。该核验不是另一路完整rawtool独立取回。CP3/6/final7原17有序completed/remaining与逐record/actual/capture/backup refs/hash核证；106scoped directrefs、108receipt targets、51本批文件精确成员一致。两套原42的id/priority/criterion全文及继承数组/CP副本保持完整BLOCKED；日志brief附加sourceReference仍保存在原不可变brief，未删除或改写。父parser按真实vision_analysis/screenshot_path、panel记录与派生pageMembership差异、批1localizedStructuralSupport/groupOnlyNotPanelCertification修正；这些是核验schema问题，不计capture失败或重试。
- 2026-10-13 原概览累计与许可门：17/17辅助概览full、0partial/0unseen，17actual/17success/0failure/0retry；最后25来源panel为log7/network18、23localized结构/2group-only（LN-overview13下方两图），合并前十为29localized/36group。每个本批panel仍有细字/freshness/业务或交互unidentified，零具名来源+metadata label+精确time的新局部证据。日志401/未知/恢复入口与网络尚未执行/空history/表单/输出容器只有相应可见支持；0/100/自动刷新参数不外推真实接口成功，右侧“partly clipped”转述不推翻完整板RGB或直接确证生产clip。15源由已批准安全投影精确关联path/hash/尺寸与originViewport/route/state，另10未独立解析；不新读候选raw report、不借邻源context。原48required/65source和Cloud12actual信用增量0。新`LOG-NETWORK-ORIGINAL-OVERVIEW-BATCH-2-PARENT-READBACK.json`与`DRAWER-CORRECTED-CANDIDATE-REVIEW-STATUS-AFTER-ORIGINAL-OVERVIEW-BATCH-2.json`保存，旧证据不改bytes。21源/60renderer/60Boot完整pathset/hash再核；同40748/606906 index31/LN-overview17、492×106、NOACTIVATE非前台非最小化保留运行。没有待返回PNG批，不再重复派同图；Q1/Q2已问未明确答复未执行，Q3/Q4仍草案，五页/最终ZIP仍PENDING，无本次生产/测试/业务/秘密/前台/提交push部署/服务动作。无新确证must-fix不代表无缺陷或完整验收通过。

### 2026-10-13 独立运行监督父回读与阶段交付

- `deleg_7fdc3589`报告`runtime-design-supervision/RUNTIME-DESIGN-SUPERVISION.md`的SHA为`cbebc47681b1705fe82eb1924f56bf9876cc75879c1c05767e3d61d5b3a14acd`；机器状态和核证回执均已实际读取、精确SHA/bytes核证。父级重算14允许输入、10可比父引用、76 scoped evidence refs及JSON pointer/源码行范围；独立最初代码转交SyntaxError与后续exit0核证分列，不归生产缺陷、不改原证据。
- 原84的suite/id/priority/criterion全文/hash/来源引用/历史status/scoped结论有序投影与原安全计划逐项完全相等，历史BLOCKED84不倒改。只在新supplement判S-NAV-04两条FAIL、其余82BLOCKED、完整PASS0，原优先级统计及两套42范围保持；同共享壳缺陷只计1个MF-FOCUS-01。
- 确证缺口：640×480闭合details时主轮云`/10–12`、采集短高`/81–83`及定点`/4–6`都SUMMARY→BODY→BUTTON；BODY时抽屉expanded/main inert仍真。两个隐藏后代误算使真正可见SUMMARY不是逻辑last；主轮reverse-tab-9至-14停首项支持同类反向失败，不签逆环。下一Tab回焦不能抵销越界；不推断秘密获焦/读取、原生地址栏或每route完整复测。
- 局部有效CSS、ARIA/打开、关闭/resize、同Sidebar节点及listener有限结论已独立审阅；5页h1 22/650/31.9、所采primary蓝/34px、textarea184/vertical不能证明所有角色/宽度/禁用/风险全文或真实业务成功。真实query变化、root卸载清理、每页每关闭路径、有效滚动、另一reduced-motion环境、accepted commit/last-good/混排序/隐藏全文依然缺证；401不变成成功态。
- 异步返回后再次检查21候选源码与renderer/Boot各60完整路径集合/hash一致，HEAD/branch保持，`git diff --check`exit0；未做新HTTP/health、PNG、浏览器、业务、秘密、源码、测试、前台、部署或服务动作。父回读`PARENT-RUNTIME-DESIGN-READBACK.json`与durable检查交付`READONLY-DOM-UI-INSPECTION-HANDOFF.md`、原独立报告保存；本轮两份独立报告均已消费，不轮询或再派重复审阅。
- 最小下一授权：只修改AppShell可见可聚焦集合与首尾边界，排除闭合details/hidden/inert后代、保留summary及兼容回退，不改业务/API/Router/Store/凭据/依赖；获准后运行无测试静态/构建门禁，并在任务隔离候选用同安全范围复核SUMMARY一次Tab→关闭BUTTON及关闭BUTTON一次Shift+Tab→SUMMARY，补必要短高/导航/resize/Escape与Frontend Design监督。现授权明确只读，未自动修源、部署或扩Q3/Q4；新像素/最终ZIP仍独立门槛。

### 2026-10-13 Q1/Q2授权后真实DOM与有限交互补证（监督返回前历史）

- 授权与身份：用户原文“授权只读 DOM 与有限交互检查”，对应上一请求的任务专属候选/只读DOM/CSS/bounds/listener、有限Tab/Shift+Tab/Escape/导航/抽屉/缩放及页面原GET；不包含修改源码、业务执行、秘密读取、抢焦点、部署或启停服务。`readonly-dom-ui-authorized/AUTHORIZATION-AND-BASELINE.json`记录权限。21生产输入及renderer/Boot各60资源完整路径/hash检查前后均一致；9346公共index HTTP200且SHA匹配冻结身份，HEAD仍7c1778dd27542be92049deb59b16233b471e48e4，未宣工作区干净或后端全文件零漂移。
- 实际主轮：`run-2/OBSERVATION-RECEIPT.json` completed=true，真实工具exit0；五页computed样本、11组CDP matched CSS、3份document/window监听器摘要及92条有限UI记录。101次允许的原只读请求，200×36/401×65，0非读、0越界、0执行请求、0pageError/65consoleError；不读保存原响应、console正文、DOM文本、输入值、storage、headers/cookies或凭据，不因console数量相同断言全部错误归因。字号子证据与隐藏全文/风险正文、业务成功或reduced-motion完整验收分开。
- 新实际问题：640×480、details真实闭合，`cloud-forward-tab-4`为SUMMARY、`-5`为BODY；`short-tab-6`同样BODY越界，导航expanded=true、背景main仍inert=true。`focus-detail-2/FOCUS-DETAIL-TRACE.json`再现SUMMARY→BODY→关闭BUTTON，14焦点候选中2个在闭合details内，native checkVisibility仅12；AppShell.vue:53–56仅getClientRects/visibility过滤，因而末候选不是实际可见末项。只读结构定位未展开或读取运维令牌；不推断原生浏览器地址栏实际获焦，不把新增证据包装为旧图像素发现。
- 其余实际支持：Escape/遮罩/关闭按钮/当前路由/其他路由/history后导航hidden/inert、主区解锁及可见trigger焦点均有记录；desktop→900窄与开抽屉→desktop→640再次窄有明确focus回退。定点成功轮9记录、8次Sidebar BackendNodeId均14，包含五页路由和尺寸变化，只支持同DOM节点复用，不证明所有state、秘密记忆或真实unmount。监听器keydown1→3→1、focusin1的快照包含组件/工具监听器，既不误判泄漏，也不等于生命周期完整通过。
- 工具失败独立保留：初轮CDP DOM根尚未请求导致matched阶段未完成，仅采集页computed和1摘要留存，未计为五页成功；自有observer的宽泛sync路径分类误阻GET `/api/config/sync/status`，源码证明其为原只读状态GET后修观察器，不是执行同步业务。首次定点在Escape后空候选数组的观察器访问失败，7条已收记录保留，不判生产Escape失败；定点第二轮增加空数组guard后真实exit0。未改业务代码、制造成功数据、加Mock或运行测试。
- 当前父回读：`readonly-dom-ui-authorized/PARENT-OBSERVATION-READBACK.json`独立解析成功轮记录并核19精确direct refs/SHA/bytes、5页22px有效scoped字体候选与computed、焦点越界及Sidebar节点。旧原84全文/priority和原PNG全部不改；当前新键盘问题交`deleg_7fdc3589`只读独立监督，其结论未返回不预签PASS/FAIL报告。有限检查不是Electron Main/Preload、query变化、root卸载、真实成功/last-good/混合排序或隐藏全文证明。本轮源码修复需要另行明确许可，五页最终PASS和最终ZIP仍PENDING。

## 2026-10-11 五页及共享壳二次精修：实施中记录

### 2026-10-13 独立范围监督返回与本轮动作对照

- `deleg_77ba2df7`只读范围监督已完成，未执行浏览器/端点/像素审图；新报告`design-supervision/FRONTEND-DESIGN-INDEPENDENT-SCOPE-CHECKLIST.md`及配套矩阵父级精确hash核证。16行对应Q1关联37/Q2关联29/交集16/并集50；Q3关联49、Q4关联14为非互斥数量。原84独立suite-qualified条件的顺序、全文、priority、历史BLOCKED与原计划逐项相等；全部输入及8源码目标hash一致。范围监督不是运行裁决或全条PASS，父级回执`PARENT-INDEPENDENT-SCOPE-READBACK.json`独立保存，不改原报告/矩阵。
- 本轮真实动作对照：已有cloud→diagnostic链接后`page.goBack`支持自然history关闭，不注入History API、query或人工事件。filters/mode/device、native details展开、clipboard和root生命周期注入均未执行；本轮不新补这些范围外分支。旧Q2草案更宽，不是授权本身；Q3/Q4及本轮源码修复未获许可。Sidebar数字UID及BackendNodeId只是身份支持，不外推值/state或cleanup。
- 投影/请求限定：DOM-CSS实际含15个native disabled安全布尔，无字段值；不能把回执简写理解为只有ARIA，亦不以该辅助样本签完整禁用合同。五页computed CSS没有URL值。主轮19精确refs再核不变；origin/method/path防护不是逐endpoint方法/参数语义白名单证明，0执行计数不外推后端零副作用或accepted-success。未增加浏览器、业务、秘密、源码、测试、部署或服务动作；`git diff --check`仍exit0，工作区已有其它修改保留。

- 范围：`AppShell/AppSidebar/AppTopbar`、运维状态token/纯props状态组件，以及采集、云平台、诊断、日志、网络五页限定呈现文件；`<=900px`使用同一导航的抽屉，桌面220px侧栏及40px顶栏不改。不创建Phase18，不改领域Store、API/DTO、Router定义、Electron Main/Preload或后端，不新增依赖。
- 前期监督：`DESIGN-PREFLIGHT.md`结合真实前序截图及当前源码纠正过时建议；已正确项保留，适用项记录在三份页面实施台账和共享壳台账中。实施中三组Frontend Design只审对应候选图片，尚未签新版最终PASS。
- 独立源码审计：`deleg_68aa59d7`核证100个前端保护文件、840条既有Java文件及16个改前副本hash一致；77个原ref默认值及61个原控件绑定保留。提出MF-C1/C2/D1/D2/D3/L1；该核证属于修正前审计，不自动证明修正后全部合同。
- 本次六项修正：采集点位采用严格数字类型/原字段顺序，缺值未知且不覆盖真实0；连接数量不再以设备数回退，各项计数标明自己的来源；诊断库存已知guard与原Store resolver一致；队列和累计拒绝分别跟踪共享摘要实际来源；来源读取失败优先显示但业务状态仍未知、不改风险分布数学；日志正在读取时展示当前查询本次尝试时间，完成/失败提交与cleanup仍服从原owner。`deleg_fbe9a9ad` 独立补审已全部标为RESOLVED（源码/静态范围），61个原绑定和26组API/Store/owner调用序列及参数保持；明确记录获准展示差异，不声称全部script identical。
- 父级在前序源码补审阶段读回报告及19源hash冻结，核证当时源码、100前端保护与840既有Java、全部60渲染资源及同步资源身份；记录为 `SOURCE-CORRECTION-REVIEW-VERIFICATION.json`。这是 `index-ffGQdTDD.js` 的历史checkpoint，不将其19源零漂移结论冒称新增中期整改后的21源独审；诊断排序说明已在下述整改中显式包含“读取失败”。
- 末次门禁：`git diff --check`、lint、Stylelint、typecheck、Electron build、Web build均exit0；命令与真实输出存于 `operations-polish-v2/SOURCE-CORRECTION-GATES.json`。Web同步60文件是源码资源同步，不是运行9090部署；保留既有PURE注释及vendor chunk提示。不创建、修改或运行测试。
- 新版捕获：9346隔离候选renderer只读访问真实9090，`source-corrected-reviewed/capture-report.json`为completed=true，入口 `index-ffGQdTDD.js`；105张图覆盖1600/1280/1024/900/640/390宽度、局部滚动、默认折叠及安全展开；40条抽屉操作记录。真实pageError为0，consoleError为52，响应状态包含51次401；没有非读请求或网络检测请求。原始JSON、日志正文及可能含敏感值的文本在截图时遮蔽，未读其值。
- 实施中反馈现已汇总：`MIDPOINT-SHARED-COLLECTION-REVIEW.md`要求补足细字、真实最大表端点及抽屉自身底部证据；`MIDPOINT-CLOUD-DIAGNOSTIC-REVIEW.md`旧图云页所见范围局部PASS、诊断库存失败误零/失败主标签/重复长行需修；`MIDPOINT-LOG-NETWORK-REVIEW.md`旧图遥测未提交计数误零需修，并补日志真实最大端点和390中段。三个报告均只签旧 `index-LPY1pd0N.js`，不是当前终审。
- 本次新增极小呈现差异：DeviceRuntimePanel的可选inventoryCount由诊断页传入真实成功库存count或null，未传时保留原默认，明确0仍显示0；EdgeTelemetryPanel仅resultText对没有归属响应的分支不拼接normalizer默认零，保留本地历史真实0及原提交/请求/归一化/owner；诊断失败主标签显式红色，业务状态仍未知，源名保留、各源时间/详情进入真实native details，恢复建议不再重复整段元数据；状态列140px，badge原12px与tbody原13px规则保留，不声称全部computed字号均12px。两个现有组件不属于原100保护清单，已另外保存真实中期编辑前字节 `MIDPOINT-UI-COMPONENT-BASELINE.json`，不冒称初始pre-edit副本。
- 末次完整重跑：`FINAL-CANDIDATE-GATES.json`中六命令均exit0，随后重新载入当前 `index-BLe3PJnp.js` 并捕获 `final-candidate/`105图、40导航记录，及 `final-supplement/`173图。补图22组真实连续滚动覆盖到body底部且记录零gap，包括390日志恢复来源、390遥测中段及1600/900/390本地空草稿校验/风险/来源；900/640/390抽屉及640x480导航内滚动到底均另有原像素补图。未执行遥测接入或网络检测。
- 端点补证而非改CSS：采集640设置理论350后实际clamp340，再赋极大scrollLeft仍340，最后表头右边与panel右边重合；日志理论370、实际最大360，均保留scrollbar-gutter、offset/client/scrollWidth及原像素端点图。不能拿理论差值猜测列截断；待独立像素复核确认可见右端。所有补图来自真实当前渲染，没有伪造滚动数据。
- 父级当前身份核证：`FINAL-CANDIDATE-IDENTITY.json`冻结21源hash；278图的PNG签名、尺寸、SHA、入口及外层横溢核验无问题，100前端保护/840既有Java零漂移，60资源集合与Boot源码同步一致；当前source差异补审已获独立RESOLVED并由父级核验，像素终审仍待完成。`FINAL-CANDIDATE-HTTP-IDENTITY.json`读回9090/9346公共HTML均200，分别为旧发布 `index-DsbgyuIk.js` 与当前 `index-BLe3PJnp.js`。Web同步不等于部署。
- 新差异源码补审已完成：`MIDPOINT-PIXEL-CORRECTION-SOURCE-REVIEW.md`及 FINAL机器JSON/21源freeze读回核证，MP-D1/MP-D2/MP-N1均RESOLVED，无OPEN源码项；两个组件全部原函数/ref/watch/lifecycle字节不变，反向移除获准改动后整份文件恢复真实中期原字节。父级另外重复当前/独审/捕获21源hash、100/840保护、60资源集合/字节，并独立做两个整文件反向字节比对，全通过，记录 `MIDPOINT-PIXEL-CORRECTION-PARENT-VERIFICATION.json`。本子审计第一轮整组hash已含140px，未留下更早82px整份快照，因此不宣称该历史整文件仅一行变更；不是像素、运行或业务PASS。
- 最终监工受阻：共享壳/采集 `deleg_e6965329` 对当前56原图及14结构/39原像素板形成逐项ledger，但只成功分析native-01的1600主区1:1裁剪；其余38原像素板和1600侧栏全上下文未见。翻页工具明确返回 `NOT consented / Do NOT retry / no different path`，已停止，没有通过换文件、另开查看器或输入路径绕过。末次报告 `FINAL-SHARED-COLLECTION-DESIGN-REVIEW.md` 为BLOCKED；MF-E1风险/来源/校验细字、MF-E2采集640真实右端、MF-E3抽屉导航自身底部全部仍BLOCKED。没有足够像素证据提出新生产CSS问题，不以盲改代替看图。
- 23:11父级读回核证：独立核对报告hash、8个审查JSON文件hash/格式、39原像素板hash/尺寸、56来源hash，并从ledger重新统计1成功板/1部分原图/0全审原图/55未见原图；真实live transcript存在22:58翻页超时无同意的原始工具拒绝。当前21源、全部60渲染资源/Boot同步、278原PNG与100前端/840既有Java保护全部无漂移，记录 `FINAL-SHARED-COLLECTION-PARENT-READBACK.json`。不把文件核验升级为像素PASS。云/诊断和日志/网络的终审暂挂，等待只读看图/翻页明确授权；不降低必审矩阵，不发最终冻结ZIP。
- 验收边界：实际PNG存在、DOM/交互记录和构建通过不等于像素通过；`index-LPY1pd0N.js`的实施中图与最新入口分开，不混签终审。未授权的导入/导出、同步、设备写入/启停、Replay/Flush、云测试、网络检测及敏感正文/成功业务态均未执行或未审，不制造数据补态。
- 运行边界：9090最后公共页面读回为HTTP200及旧入口 `index-DsbgyuIk.js`；health HTTP200/body DOWN为前序记录，本次未再读取health正文。不为改善健康显示执行控制操作；本次仅读回审查证据和更新文档，未改生产、运行测试、提交/部署或执行业务写入。最终逐项设计闭环、最新图片像素终审、冻结交付包及用户确认仍待完成。
- 新看图授权与恢复：父级明确请求只在任务专用截图查看器打开、切换及翻页查看已脱敏截图，用户回复“授权”。`PIXEL-REVIEW-AUTHORIZATION.json`记录该授权及仍禁止的业务操作/凭据/提交/部署/抢焦点边界，当前21源及60资源重新计算一致。已派 `deleg_c6dd7085`恢复共享/采集39原像素板及1600侧栏上下文；先核首图和第二张不同图真正分析，再扩展既定矩阵。新审查保存独立AUTHORIZED报告，不覆盖历史BLOCKED记录；如有新的同意拒绝仍停止不绕过，未返回不签PASS。其余两组保持串行，不降低未审要求。

### 2026-10-12 授权后完整终审反馈与MF-V1修正

- 真实终审反馈：`FINAL-SHARED-COLLECTION-AUTHORIZED-DESIGN-REVIEW.md`为旧 `index-BLe3PJnp.js` 的限定FAIL，39/39必审native板与3侧栏补板/3细节板共45次成功分析，56本组来源可见矩形联合覆盖；36项保留为22限定PASS、4FAIL（同一MF-V1）、10BLOCKED/SOURCE_ONLY/NOT EXERCISED，不签脱敏正文或五页总体。MF-E1风险/来源/校验细字、MF-E2真实340端点已补证；MF-E3因背景穿透不能整体PASS。父级核45独特handle basename均存在实际live结果，并通过子会话130063/130071读回native37/native39完整真实vision结果，明确看到业务文字与导航叠读，不以自报代替关键发现。
- 最小生产修正：`src/app/AppShell.vue:215`与`src/app/AppSidebar.vue:88`各一处 `background: var(--console-sidebar)`改 `var(--app-color-sidebar)`，直接复用tokens.css既有 `#151d2b`，不新增alias或fallback覆盖层。`DRAWER-OPACITY-STATIC-VERIFICATION.json`证明21源只有这两文件变化，两处反向替换整文件恢复原冻结hash；原script/template、导航handler、Tab/Escape、inert、Router及其它19源保持。未改API/DTO/Store/后端/依赖/测试。
- 门禁与新证据：`DRAWER-OPACITY-GATES.json`记录diff-check、lint、Stylelint、typecheck、Electron build、Web build六项真实exit0，保留既有vendor/PURE提示。重新捕获 `drawer-corrected-candidate/`105图、40导航记录及 `drawer-corrected-supplement/`173图；pageError/non-read请求/network执行请求均0，22连续组无gap到底，真实401保持。`DRAWER-CORRECTED-CANDIDATE-IDENTITY.json`冻结 `index-CW3V7nPt.js`、21源、60资源、278PNG（265不同hash）、Boot同字节、100前端/840Java零漂移；旧原图与FAIL报告不覆盖。新三组审查板保留原矩阵39/110/48 native，不缩小scope。
- 当时复审：新共享/采集完整矩阵已派 `deleg_33038f3e`，其结果见下述补记；公共HTML当时读回9090/9346均HTTP200，分别旧 `index-DsbgyuIk.js`/新 `index-CW3V7nPt.js`。Web资源同步不是运行部署，未重启服务、未读凭据、未执行业务写入或测试。

### 2026-10-12 MF-V1修正后共享/采集限定复审与第二组终审

- 新版独立结论：`DRAWER-CORRECTED-SHARED-COLLECTION-DESIGN-REVIEW.md`签 `SCOPED_CURRENT_VISIBLE_PIXEL_PASS`，仅当前实际可见范围。39必审native全部分析，额外3侧栏板补齐9个1600原图左220px及1个明确倍率抽屉细节，共43次成功视觉分析；56原图可见矩形联合完整，partial/unseen均0。MF-V1背景穿透已RESOLVED，MF-E1风险/校验细字、MF-E2真实340表端点、MF-E3抽屉导航底部按新图闭环，无新生产视觉must-fix。
- 原验收要求不降级：36项完整保留，26限定可见PASS、10BLOCKED、0FAIL；成功数据/明确0与false/last-good、有值JSON、实际写入或下载、脱敏正文细字、键盘焦点/inert/关闭/resize/unmount/motion未由本组图片签收，保留既有源码及40条运行记录与像素结论的区别，不把这10项删除或改为PASS。未签五页总体或用户最终确认。
- 父级证据核验：从逐图ledger重算39＋3＋1及43独特handle，逐一对应live transcript中真实成功computer_use的vision_analysis结果行；重新按exclusive-corner矩形计算56原图覆盖。重新核21源/全部60renderer与Boot资源/278PNG（265不同hash）及100前端/840Java保护均无漂移，110/48下游native每块与来源像素完全相等。证据 `DRAWER-CORRECTED-SHARED-COLLECTION-PARENT-READBACK.json`。compact transcript截断完整分析，session_search按handle及更宽标题未返回完整记录，因此目前是handle-result佐证，不冒称父级读回了全部或关键完整vision正文；独立报告和逐图分析记录仍保留该限定。
- 当时串行派单：`deleg_a70fb030`只审当前 `drawer-corrected-cloud-diagnostic-plates` 的110native和云31/诊断126来源，并补齐1600侧栏上下文；要求原CL1–CL7、D1–D7及适用共享P0/P2逐项保留，重点审库存未知、来源失败优先、展开元数据/密度。其后阻塞结果见下述补记；日志27/网络38来源及48native仍未审，没有签其PASS，没有最终冻结ZIP。
- 清理与限制：子审查两个自有查看器已记录exit0和HWND消失；父级独立读回退出记录，未操作9346/9090、未修改生产或执行业务操作，仅更新过程文档和证据。不读取或保存秘密、不创建/修改/运行测试，不提交、推送或部署。

### 2026-10-12 云/诊断终审查看器连续关闭：暂停恢复

- 独立报告：`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-DESIGN-REVIEW.md`严格为BLOCKED，不签本组或五页PASS。实际7次成功auxiliary.vision，native01/02/03/04/05/14完整，native06部分；另2次0×0未计成功。110native仍104块未完整（其中103未见），157原来源full3/partial4/unseen150；原42项0PASS/0FAIL/42BLOCKED，11项仅有局部可接受观察，不用其缩减矩阵。PIX-D1/D2/D3及本组MF-V1未完整闭环，新增已证实视觉must-fix为0不代表未见范围无问题。
- 阻塞依据：run01/run02正常exit0；run03精确PID59560记录2026-10-12T10:39:57.441500的 `WM_DELETE_WINDOW` 后exit0，触发者未暴露。源码专属selection的CLOSE分支不会写该原因日志，因此可确认末次为窗口关闭回调，但不能判断是用户、工具或其它发送者。没有新的工具权限拒绝；不将未知关闭归因用户，也不通过隐藏窗口、拒绝关闭事件或无限重开抵抗可能主动关闭。
- 父级独立核证：7个精确handle均对应实际成功vision_analysis工具结果行；7份真实capture副本hash/尺寸一致，native06返回979px对声明1024px缺45px。根据每份实际capture尺寸、30px查看器标题区和exclusive source矩形重算157来源覆盖，与子ledger一致。21源/60renderer和Boot集合字节/100前端/840Java无漂移；三个精确自有HWND当前均不存在。证据 `DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-PARENT-READBACK.json`，完整工具正文未全部读回，保留handle佐证限定。
- 当时动作：暂停云/诊断恢复及日志/网络同路径派单，先确认查看器关闭是否为用户主动操作、是否可保持后台打开。原授权和原110native/157来源要求继续保留；后续确认已解除该等待，恢复见下节。不重启设计、不操作业务、不读秘密、不新增或运行测试、不提交/推送/部署。总体仍未通过，最终效果图及冻结ZIP不伪签。

### 2026-10-12 用户确认主动关闭后：恢复原云/诊断矩阵

- 用户明确确认此前手动关闭查看器、后续不关闭并要求继续。`PIXEL-REVIEW-RESUME-AUTHORIZATION.json`保存原话、既有只读授权边界、保留6完整native及104待完整native清单；不逐次推定三个历史关闭事件的发送者，不把关闭事件混同工具拒绝。
- 父级恢复前重新核当前21源码、全部60renderer与Boot资源集合/字节、112allowlist板、157来源PNG及两份捕获报告，均与冻结候选 `index-CW3V7nPt.js`一致；100前端保护无漂移。既有 `collector-runtime/src/main/java/com/wangbin/collector/core/connection/manager/ConnectionManager.java`与前序Java基线有1处差异，当前也不同于HEAD且非纯CRLF差异。本轮未编辑后端，不恢复该用户工作、不重采或打包部署，不再宣当前840Java零漂移；冻结图的像素审查不能推定当前后端合同/健康。记录 `DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-RESUME-PRECHECK.json`。
- 已实际派 `deleg_697539e5`，新目录 `drawer-corrected-cloud-diagnostic-resumed-review/`及独立恢复报告与历史BLOCKED记录分开。保留前7条实际工具结果及其几何限定，不重审6完整图凑数量；先完整实看native06、核切换到不同native07，再补其余原矩阵和2侧栏。110required native、157来源及42原criteria不减少；PIX-D1/D2/D3、相邻页MF-V1必须凭当前真实像素闭环。恢复派单不是审完或PASS。
- 查看器只在后台无激活显示，精确自有PID/HWND捕获，不抓其它窗口、不抢焦点；出现新的明确工具同意拒绝仍停止、不绕过。仅在本组结束后关闭自有查看器并核退出，9090/9346保持。本组完成并读回真实证据后才串行日志/网络48native；共享/采集限定PASS保留，五页总体仍未通过，无测试、业务操作、提交或部署。

### 2026-10-12 上游超时后增量核证与有界分批续审

- 中断原因：`deleg_697539e5`真实终止为上游6次空/坏响应及slow response 359s，而不是新的同意拒绝。它留下native06/07两份有效逐图JSON和cap副本；native08的并发错误和无成功分析的unchanged-screen不计seen。其完整报告未生成，不能把failed子任务签成完成。旧自有PID26512/HWND800040已由父级核不存在。
- 增量父级核证：真实trace存在两个成功vision_analysis-bearing handle，cap副本SHA/尺寸与记录一致，cap从y=30起的整个原板RGB像素与原PNG完全相同。native06的旧右45px缺口已实际补齐，native07为新的完整900top观察；累计9真实分析、8完整native/102未见，157来源full5/partial3/unseen149。工具read_file会截断长分析行的展示，不代表保存JSON已截断；JSON机读没有truncation marker，仍区别子记录文本与父级独立取回raw tool正文。证据 `DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-RESUMED-PARENT-READBACK.json`。
- 有界实际恢复：父级持有新只读无激活窗口PID41380/HWND598426，allowlist及原PNG不变；native08首试辅助视觉并发限制，保存failure-01且不计seen。60秒固定前台等待后仅更新精确自有窗口caption使新cap可再分析，GetWindowText读回、原PNG hash及非前台窗口核证后，正常capture实际返回native08完整具体分析；900×1058 cap包含900×1028原板，y=30复制像素完全一致。没有换provider、读配置/秘密、伪造响应或通过permission替代路径。
- 当前核证checkpoint：累计10成功分析（含旧native06部分历史）、9个完整required native、0当前partial、101未见；157来源full6/partial3/unseen148，2sidebar仍未审。42原criteria及成功/留存/真实0与false/disabled/隐藏正文/业务写入/键盘生命周期未审条件保留，本组及五页未PASS；父级native08只签其实际可见范围。记录 `DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-RECOVERY-CHECKPOINT.json`。
- 分批不缩减矩阵：已派 `deleg_38289ad8`仅执行下一30板native09–39（跳过已有完整证据native14），逐成功图即保存真实工具文本/handle、cap副本、几何及限定观察；另71required native与2sidebar继续留原矩阵，不因执行批次缩小验收范围。父级持有查看器避免子代理上游失败一并销毁，不由本批关闭或重开；辅助视觉继续串行，新的真实权限拒绝仍停止，技术并发错误仅有界等待重试。日志/网络48板待原第二组完成后串行。
- 范围与静态核证：本次再次核21源码与60renderer/Boot字节仍同冻结候选，未改生产或介入既有ConnectionManager漂移；`git diff --check`通过。本轮仅scratch查看器/证据、进度与技能流程记录，未新增修改运行测试、未执行业务、未读秘密、未提交推送部署，也未停9346/9090。

### 2026-10-12 30板子批再次超时：保留五图并直接补三图

- `deleg_38289ad8`真实状态failed：上游6次空/坏响应及slow response398s。没有完成其30板目标、没有完整独立批次报告；不以成功5板冒称30板完成。实际native09–13五个成功vision分析结果及逐图JSON/PNG副本均存在，native11初次gateway并发错误不计seen，其一次60秒重试确实成功。没有新的工具同意拒绝，也没有窗口退出，无需再次询问用户关闭来源。
- 父级逐一机械核5个成功trace前缀/handle、原板SHA、真实capture与副本SHA/尺寸、完整board像素与capture y=30后画布完全相等，各source exclusive crop与板target位置也完全相等。子逐图正文可机读，但session_search精确handle及扩大delegation均返回0sessions，所以保留handle/result-prefix佐证限定，不冒称独立读回子完整raw tool正文。5板范围内云未知/未评估、队列未读取/恢复入口与640抽屉不透字可接受，无该已见增量证实的新must-fix；其它未见范围仍不足证据。
- 父级直接续审native15/16/17，三次正常`computer_use`确实返回完整具体auxiliary.vision，未换provider或读取配置。原1380/1280画布与capture尺寸/像素完全相等；诊断库存失败显示未知，运行计数未知/过期、独立失败源元数据/恢复入口、默认折叠行保留业务未知与失败，可见范围接受。1600原sidebar仍缺，1280顶图只见部分默认表行，JSON正文被明确脱敏；未以提示文案认证真实留存/成功数据，也未签展开details/全部行密度或MF-V1其它宽度。
- 新checkpoint机械重算为18真实成功分析（含旧native06部分历史）、17完整required native/0当前partial/93未见，157来源full15/partial4/unseen138；`sidebar-01.png`/`sidebar-02.png`均未审。原110native/157来源/42完整criteria不削减，成功/last-good/真实0与false/disabled、敏感正文、写入/下载、键盘/生命周期未审条件保留。
- 当前精确父级PID41380/HWND598426仍存在、非前台，留给后续串行小批；本次不重开、不关闭、不抢焦点。已派 `deleg_c237391b`只执行native18/19/20/21/22五板，另88required native及2sidebar明确待续；拆执行批次不是缩验收。日志/网络48native继续原顺序待本组完成，整体及最终冻结效果图/ZIP未完成。
- 21生产源、60renderer完整路径集合/hash及Boot资源路径集合/hash重新核一致，`git diff --check`exit0；未编辑生产、既有Java工作或测试，未执行业务/导出、读取秘密、提交/推送/部署或停止9090/9346。证据 `BATCH-1-RECOVERY-DESIGN-NOTE.md`、`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-BATCH-1-PARENT-CHECKS.json`、`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-BATCH-1-RECOVERY-CHECKPOINT.json`及`drawer-corrected-cloud-diagnostic-parent-continuation/per-image/`。

### 2026-10-12 第2微批完成与第3微批续审

- `deleg_c237391b`实际完成native18/19/20/21/22，五次具体auxiliary.vision成功、没有错误/unchanged-screen或重试充数；报告只签本批观察，原42完整条件全BLOCKED。父级逐一核实际live成功分析前缀/handle、每个record及capture原副本SHA/尺寸、整板RGB与cap y=30后完全相等、来源hash/crop/scale/target相等，核四份汇总记录hash及42条id/priority/criterion原文完全一致。子`observedBoardRectangle`用capture XYWH而非board exclusive corners，父级明确转换后统计，不误算coverage。完整raw子工具正文未独立读回，仍限定成功前缀/handle佐证，不签争议must-fix。
- 新覆盖机械重算：23实际成功分析（含历史部分native06）、22完整required native/0当前partial/88未见；157来源full21/partial4/unseen132，两sidebar未见。1024/900/640失败库存未知、源失败与业务未知、风险分布与恢复入口有可见证据；每行红色主标签、混合来源稳定排序、完整展开details/字号/密度及动态操作尚未被这五图完整认证，MF-V1本批无drawer。没有足够新must-fix证据不等于未审无缺陷。
- 已派 `deleg_739e97c3`下一5板native23/24/25/26/27，按原矩阵补390上下、1280×720上下、640诊断drawer及表左右端；另83required native及sidebar01/02仍待，不缩减110/157/42原验收范围。父级持有窗口PID41380/HWND598426、WS_EX_NOACTIVATE、非前台仍存在，最终state native22/index21；本次不关闭/重开/抢焦点，日志/网络48native继续原串行顺序待后。
- 再核当前21源、全部60renderer与Boot资源路径集合/hash一致，`git diff --check`exit0；仅更新证据与进度，无生产修改、测试新增修改执行、业务动作、secret读取、提交push部署或服务停止。独立报告 `drawer-corrected-cloud-diagnostic-microbatch-2/MICROBATCH-2-DESIGN-REVIEW.md`及父级 `DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-2-PARENT-READBACK.json`已保存；总体和最终冻结效果图/ZIP未完成。

### 2026-10-12 第3微批完成与第4微批续审

- `deleg_739e97c3`实际完整分析native23/24/25/26/27：390上下、1280×720上下、640诊断打开drawer及表左右端。父级逐图核冻结board hash/transform、原capture及留存副本SHA/尺寸、capture去30px标题后整板RGB相等、source hash/crop/target/scale相等，五次正常vision无失败/重试；42条id/priority/criterion原文完全保留、完整状态42BLOCKED。
- 累计28实际成功分析（含历史部分native06）、27完整required native/0当前partial/83未见；157来源full28/partial4/unseen125，2sidebar仍未见。640诊断导航当前项、关闭及底部实际识别，不能据整体caption认证背板不透字；每行精确红色色相、原字号/密度、全展开元数据和混合状态稳定排序仍未认证，没有足够新增具体must-fix不等于未审无缺陷。
- 独立raw完整工具正文未取回：父级仅核真实成功handle及live可用42–49字符前缀，子saved完整tool与per-image内部一致。首次固定100字符匹配因live省略失败，改为解码实际可用前缀，不编造余文；路径集合search返回MSYS路径及目录，改用native路径枚举，21源/60renderer/Boot完整集合/hash真实一致。冻结原序派下一28–32，不用词典排序误先选native100。
- 已派`deleg_4510b619`仅续云1600/1280连续上下及1024连续首板共5；另78required native和2sidebar留原110/157/42矩阵。父级PID41380/HWND598426已核存在/非前台/WS_EX_NOACTIVATE，末状态native27/index26；不关闭/重开/抢焦点。日志/网络48native及最终效果图/ZIP仍按原顺序待续，无本次生产修改、测试、业务/秘密读取、提交/推送/部署/服务停止。

### 2026-10-12 第4微批完成与第5有界批续审

- 云1600/1280连续上下及1024连续首板native28–32五板真实成功。父级核实际工具handle/可用分析前缀、整板与cap去30px标题后RGB完全相同、来源crop与target相同、frozen hash/transform、cap原副本SHA/尺寸、子saved完整tool和逐图正文对应、原42id/priority/criterion完全保留；完整raw工具正文未独立回取，限定继续保留。native28首次实际gateway失败与PNG已核anchor/hash并排除seen，后一次有限同窗重试成功。
- 机械累计33成功分析（含历史部分native06）、32完整native/0当前partial/78未见；157来源full31/partial6/unseen120，两独立sidebar未见。1600连续来源裁片从x220开始，仅主区partial；板全审不等于来源全审。native29/30/31工具明确读到红色监控能力未知提示，不能移签其它细字、字号/opacity或诊断各行颜色；未知/未读取/风险未评估及只读恢复/Replay/Flush风险说明是本批可见观察，非业务成功或无缺陷认证。
- 已派`deleg_179cd9fe`有界10板native33–42：云1024尾段/900三段/640与390连续以及诊断1600前三段。每图即时存证，每5图落检查点；其它68required native和2sidebar原范围待续，不缩原110/157/42，原42完整BLOCKED、日志/网络48板、最终效果图/ZIP和五页总体仍PENDING。21源/60renderer/Boot完整集合/hash真实一致；自有PID41380/HWND598426已核存在/非前台/NOACTIVATE，停native32/index31，不关闭/重开/抢焦点；本次仅证据/文档，无生产、测试、业务、秘密、提交/推送/部署或服务停止。

### 2026-10-12 第5批增量恢复、真实标题纠正与第6微批

- 第5批`deleg_179cd9fe`未完成原10板；仅33–40八成功，父级全部核冻结board hash/transform、原capture/留存副本SHA/尺寸、cap去30px标题后整板RGB/source crop完全相等、真实成功handle/可用前缀和完整子saved tool与per-image一致，42原id/priority/criterion全文精确保留。native38的单独tool-result首次写文件Permission denied，补存文件现已实际解析/hash/内容核对，不丢真实成功增量。两个41非成功返回和42未试仍留原未审，原8/10 BLOCKED历史不改为完成。
- 重试步骤根因：marker只写commentary/JSON，未改native caption，gateway失败和screen_unchanged的PNG hash完全相同，无成功analysis不可复用。父级读viewer producer确认title只在selection切换时更新，核PID41380/HWND598426、非前台/NOACTIVATE及同原PNG/hash后，用实际Win32 SetWindowTextW改title并GetWindowTextW读回，未改viewer源码/生产/业务PNG、未抢焦点。一次材料上已纠正的正常capture真实分析native41成功，原像素完全相等；不是重置重试次数反复撞相同失败路径。完整实际父级正文/cap/record立即保存到`drawer-corrected-cloud-diagnostic-parent-caption-recovery`，明确parent followup而非独立终审。
- 41所见库存/六摘要未知非0、回压来源读取失败/最后尝试/未成功读取/重试入口及每源时间折叠入口有新可见证据，不认证原字号/每行精确红色/全部展开/混合排序/本组640drawer不透字。新累计42成功分析（含历史partial06）、41完整required native/0当前partial/69未见；157source full42/partial8/unseen107，两sidebar未审；42完整条件全BLOCKED，整体PENDING。核21源/60renderer/Boot完整路径集合/hash真实一致，不据此签当前后端健康或合同。
- 已派`deleg_9ebdb3f2`第6微批只42/43/44/45/46，补1600诊断中底及1280上中；另64required native和2sidebar留原110/157/42矩阵。父级窗留运行，下一selection会正常更新title；新明确同意拒绝仍停，不重开/抢焦点/换provider。保存`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-BATCH-5-PARENT-READBACK.json`及`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-AFTER-PARENT-41-CHECKPOINT.json`，日志/网络48native与最终效果图/ZIP待续；本次无生产/测试/业务/秘密/提交推送部署或服务停止。

### 2026-10-12 第6微批父级核证与第7微批

- `deleg_9ebdb3f2`42–46五板真实成功，父级逐图核实际trace成功handle/可用JSON解码前缀（42–47字符，只作限定佐证，不宣完整raw结果独立取回）、独立保存完整tool与aggregate/逐图正文对应、capture原副本hash/尺寸、整板去30px标题后RGB相等、sourceRect exclusivecorners与target XYWH/scale1来源crop相等；42原id/priority/criterion全文精确保留且全BLOCKED。长行省略标记需从trace展示后缀剥离后才解码，初次核证把展示标记误算分析尾部，已纠正，不是原分析正文漂移。
- 累计47成功分析（含历史partial06）、46完整required native/0当前partial/64未见；157来源full44/partial11/unseen102，两sidebar未见。本批1600三来源x220主区partial，1280两来源full；44/46库存与摘要未知、43来源名/最后尝试/尚无成功读取、45来源失败与业务未知分列及恢复入口限定可见。45红色只认证错误横幅，八行主标签、混合稳定排序、精确原字号/默认密度、完整来源明细、成功0/false/disabled/last-good及交互生命周期尚未认证，无新确切must-fix不等于无缺陷或PASS。
- 21源/60renderer/Boot路径集合和hash再次一致，不推论后端健康；父级viewer41380/598426后台NOACTIVATE留运行，核停native46/index45。已派`deleg_0914a7dc`47–51五板（1280诊断中底及1024上中），其余59native/2sidebar保持原110/157/42；历史第5批8/10未完成及父级41单独跟进不改。原42完整BLOCKED、日志/网络48板及最终效果图/ZIP和五页整体PENDING。本次仅证据/文档，无生产、测试、业务、秘密、提交推送部署或服务停止；限定`git diff --check`exit0。

### 2026-10-12 第7微批核证与第8有界批

- `deleg_0914a7dc`完整47–51五板，capture成功5/失败0/重试0；准备阶段误用config.path已按真实file/width/height纠正，不是图像分析失败。父级核五实际tool成功handle/44–47字符可用解码前缀（限定佐证非完整raw独立取回）、独立saved完整tool与aggregate/逐图对应、冻结hash/transform/capture原副本SHA/尺寸、1:1整板去标题30px及来源crop RGB完全相等；42原id/priority/criterion全文精确保留且全BLOCKED。
- 累计52成功分析（含历史partial06）、51完整native/0当前partial/59未见，157source full49/partial11/unseen97，两sidebar未审。1280诊断中底及1024上中支持库存/运行未知非0、来源失败与业务未知、来源未成功说明、恢复/详情折叠入口、优先关注先于摘要及管线背压中文名/最后尝试时间；没有逐行颜色、精确字号密度/间距、完整每源尝试/成功时间与展开全文认证。真实混合排序、成功0/false/disabled/last-good、诊断drawer不透字/键盘生命周期仍待，未出现确切must-fix不等于无缺陷或总体PASS。
- 21源/60renderer/Boot集合/hash再次一致，父级owned41380/598426非前台NOACTIVATE核停native51/index50且留运行；第5批8/10未完成及parent41跟进历史保持。已派`deleg_6483344a`52–61有界10板（1024连续中底与900上中）、每5图checkpoint，其余49native/2sidebar保原110/157/42范围；原42完整BLOCKED、日志网络48板及最终效果图/ZIP和五页总体PENDING。本次仅证据/文档，无生产/测试/业务/秘密/提交推送部署或服务停止；`git diff --check`exit0。

### 2026-10-12 第8有界批核证与第9批

- `deleg_6483344a`52–61十板全部真实vision成功，full10/partial0/unseen0、失败0/重试0。父级核实际trace handle/43–49字符可用解码前缀（限定佐证非完整raw独立取回）、独立保存完整tool/aggregate/逐图一致、frozen hash/transform/原capture副本SHA与尺寸、1:1整板去30px标题与每来源crop放板RGB完全相等；42原id/priority/criterion全文精确保留、完整状态全BLOCKED。第5/10图checkpoint JSON真实解析、完成和剩余板名单精确核证、hash另存`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-8-CHECKPOINT-PARENT-CHECK.json`。
- 累计62分析（含历史partial06）、61完整native/0当前partial/49未见，157source full59/partial11/unseen87，两sidebar仍未审。1024中底与900上中库存未知而非0、源失败和业务未知、来源名/尝试/无成功读取、整轮结束与每源时间分开、中文主名/恢复/折叠入口获得限定支持；未确认行红色主标签/原字号密度、全文展开、成功0/false/disabled/last-good/混合稳定排序及生命周期。未新增确切must-fix不等于无视觉缺陷或PASS，原42完整BLOCKED、五页总体PENDING。
- 21源/60renderer/Boot集合/hash再核一致；owned41380/598426后台NOACTIVATE核停native61/index60、留运行。已派`deleg_aa4d95ad`仅62–71十板、每5图checkpoint，900尾段及640/390多panel连续按实际manifest源矩形核证，另39native/2sidebar保原110/157/42；日志网络48板及最终效果图/ZIP尚未闭环。历史第5批8/10未完成与parent41非独立终审保持，本次无生产/测试/业务/秘密/提交推送部署或服务停止；`git diff --check`exit0。

### 2026-10-12 第9有界批核证与第10批

- `deleg_aa4d95ad`62–71十板full10/partial0/unseen0，实际vision成功10、失败1、重试1；首62 gateway失败保留且不计seen，60秒单次有界恢复记录、实际native caption更新/readback、原PNG不变及恢复capture SHA不同已核。父级独立核11实际tool-result handle/可用前缀，十成功42–49字符前缀与child完整saved tool/aggregate/per-image一致；限定为实际anchor/前缀佐证加saved-evidence内部一致，完整raw tool正文未独立取回。十板整板capture去30px标题后与冻结原板RGB完全相等、22来源tile hash/crop放板像素相等、effectiveScale=1。第5/10图checkpoint完成及剩余有序名单、完整records、counts和hash精确核证，42原id/priority/criterion全文保持且完整条件全BLOCKED。保存`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-9-PARENT-READBACK.json`。
- 累计72成功分析（含历史partial06）、71完整native/0当前partial/39未见；157source矩形联合重算full81/partial11/unseen65，两sidebar待。900尾段及640/390连续可见库存未知非0、来源失败与业务未知、恢复入口/局部details获得限定支持。多panel逐源记录可定位内容与未识别内容，70组级云/线程池/缓存等未定位文字不推广到每panel，71中右组级来源失败不冒逐源认证。红色整页横幅不证逐行主标签；字号密度、全部中文主名/来源时间、全文展开、成功0/false/disabled/last-good/混合排序及生命周期仍不足，未确认新must-fix不等于无缺陷或PASS。
- 21源/60renderer/Boot完整集合/hash一致；父级owned41380/598426存在、NOACTIVATE/非前台，核停native71/index70/selection70，留运行。已派`deleg_fe64943d`仅72–81十板，每5图checkpoint，补390尾段、640表左右端和1600/1280/1024表左纵向；另29required native及2sidebar留原110/157/42，日志网络48板随后串行。第5批原8/10未完成与parent41非独立终审保持，既有后端/用户修改不碰。本次无生产/测试/业务/秘密/提交推送部署或服务停止；`git diff --check`exit0；原42完整BLOCKED、五页总体及最终效果图/ZIP仍PENDING。

### 2026-10-12 第10有界批核证与第11批

- `deleg_fe64943d`72–81十板full10/partial0/unseen0，具体成功analysis10、失败2、重试2，12实际公共capture返回。75/76各一次gateway失败的handle/可用前缀、独立saved工具/失败记录/aggregate对应和正常PNG核证，均不计seen；每目标单次60秒固定等待与native caption更新/readback记录、source hash不变/NOACTIVATE/非前台及不同恢复capture SHA核证。十成功42–50字符可解码前缀与saved完整tool/aggregate/per-image一致；仍限定实际anchor/前缀佐证及child保存证据内部一致，不冒称父级独立回取全部raw工具正文。冻结板及13来源crop/target hash与RGB全部相等，十capture去30px标题后整板原像素相等、effectiveScale=1。第5/10图checkpoint完成/剩余有序名单、原剩余native与辅助sidebar分列、record/tool/capture引用与hash、原42全文/完整BLOCKED均精确核证，保存`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-10-PARENT-READBACK.json`。
- 累计82分析含历史partial06、81完整native/0当前partial/29未见；原157来源矩形联合full91/partial14/unseen52，两sidebar待。本批13来源full10/partial3，1600三来源仅x220以右、不算full原图。390尾段库存未知非0、640表左右与1600/1280/1024表纵向失败/业务未知、风险摘要及查看/恢复/details入口有局部支持。77明确管线背压中文主名与Pipeline Backpressure辅助，80英文Alarm Backpressure译法不据此猜中文错误。73/74多panel分源定位/不足分列，原tool所称enlarged不改变实际1:1，不能外推全部小字或色相/字号；折叠元数据未展开、不把轮结束当源成功。独立时间/精确密度/全文、真实成功0/false/disabled/last-good/混合排序/业务键盘生命周期及脱敏正文仍不足；无确切新must-fix不等于无缺陷或PASS。
- 21源/60renderer/Boot完整集合/hash一致；父级owned41380/598426存在、非前台/NOACTIVATE，核停native81/index80/selection80，留运行。已派`deleg_1720e133`仅82–91十板，每5图checkpoint，补1024/900表左及640/390表左右纵向；另19native/2sidebar保原110/157/42，日志网络48板随后串行。第5批原8/10未完成及parent41非独立followup保持，用户/既有后端修改不碰。本次无生产/测试/业务/秘密/提交推送部署或服务停止；`git diff --check`exit0；原42完整BLOCKED、五页总体及最终效果图/ZIP仍PENDING。

### 2026-10-12 第11有界批核证与第12批

- `deleg_1720e133`82–91十板full10/partial0/unseen0，成功10、失败0、重试0。父级核10实际工具结果handle/42–49字符可用JSON解码前缀、完整child saved工具与逐图/aggregate引用/hash/analysis一致，冻结整板/transform/hash、capture原副本SHA/尺寸、去30px标题后整板及17来源crop放板RGB完全相等、effectiveScale=1。第5/10图checkpoint有序完成/剩余本批及当前累计剩余native名单、批外19native与2sidebar分列、record/tool/capture引用/hash、config/manifest/brief SHA与原42全文/完整BLOCKED精确核证。父级准备时按实际successful/failed键及额外briefSha256修正解析，未改证据、不计capture失败；完整raw子tool正文仍未独立取回，仅实际anchor/前缀佐证及saved-evidence内部一致。保存`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-11-PARENT-READBACK.json`。
- 累计92成功分析含历史partial06、91完整native/0当前partial/19未见；原157来源矩形联合full108/partial14/unseen35，两sidebar待。1024/900表左纵向与640/390表左右有局部可见支持，失败主标签与业务未知分开、0/0/8/0是分布不冒业务成功零；native88左右端明确对应，native91每panel处理建议“查看来源详情，再重试完整诊断。”实际描述保留。87/89/90组级文字不推广到每panel小字/红色或精确字号，中文名不由英文倒推。每源尝试/成功时间、折叠长值/元数据、成功0/false/disabled/last-good/混合稳定排序、业务键盘生命周期与脱敏正文仍未审；无确切新生产must-fix不等于无缺陷或PASS。
- 21源/60renderer/Boot完整集合/hash一致，父级owned41380/598426存在、非前台/NOACTIVATE，核停native91/index90/selection90并留运行。已派`deleg_5408deed`仅92–101十板，每5图checkpoint，审1600/640已展开行来源详情与来源时间账本连续段；另9required native及2sidebar保原110/157/42，日志网络48板随后串行。第5批原8/10未完成与parent41非独立followup保持，用户/既有后端修改不碰。本次无生产/测试/业务/秘密/提交推送部署或服务停止；`git diff --check`exit0；原42完整BLOCKED、五页总体和最终效果图/ZIP仍PENDING。

### 2026-10-12 第12有界批核证与云诊断冻结图收尾批

- `deleg_5408deed`92–101十板full10/partial0/unseen0，成功10/失败0/重试0。父级核10实际tool-result handle与42–45字符可用JSON解码前缀、child完整saved tool/逐图/aggregate refs/hash/analysis内部一致、冻结整板/transform/source SHA、capture原副本SHA及尺寸，去30px原生标题后整板与13来源crop放板RGB完全相等、effectiveScale=1。第5/10图checkpoint原有序完成/剩余名单、record/tool/capture refs/hash、config/manifest/brief SHA及原42全文/完整BLOCKED精确核证。完整raw子tool正文未由父级独立取回，保实际anchor/前缀佐证与保存件内部一致的限定。保存`DRAWER-CORRECTED-CLOUD-DIAGNOSTIC-MICROBATCH-12-PARENT-READBACK.json`。
- 累计102分析含历史partial06另一次，101完整required native/0当前partial/9未见；原157来源矩形联合full114/partial21/unseen22，两sidebar未审。本批13原来源full6/partial7，七1600截图裁左220px不算原图full。92应用服务展开，94运行/资源/云/配置/缓存/连接/性能/异常具名账本，95异常/存储/性能/背压四具名接口，原分析明确最后尝试`2026/10/12 09:21:44`及尚无成功；94底部存储部分可见不认证完整元数据。100的640`09:21:46`是跨panel具名账本组级支持，未定位每源到左右，不外推每panel细字/色相/时间；其他未识别保留，不拿整轮结束或邻源补齐。99后段来源信息仍board/group描述；101左右明确存储/性能/背压与四通道未知局部支持。中文主名/技术辅助、失败未知/恢复路径获限定观察，不认证成功0/false/disabled、last-good、混合稳定排序、业务键盘生命周期或隐藏脱敏正文。无确切新生产must-fix不等于无视觉缺陷或PASS。
- 21源/60renderer/Boot完整集合/hash一致，owned41380/598426存在/非前台/NOACTIVATE，核停native101/index100/selection100并留运行。已派`deleg_da14d72e`仅最后102–110九required及sidebar01/02两辅助，按冻结index101–111顺序，成功第5/10及最终11检查点；侧栏21个source join逐一核像素，仍由父级重算157联合覆盖。即便冻结图全部完成，原42完整条件/云诊断/五页仍不得预签PASS；日志网络48板随后串行。第5批原8/10 incomplete、parent41非独立followup保持，用户/既有后端修改不碰。本次无生产/测试/业务/秘密/提交推送部署或服务停止；`git diff --check`exit0；最终效果图/ZIP仍待完整闭环。

## Baseline 验证结果

执行时间：2026-08-28 10:46 左右，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 29 个测试文件、144 个测试通过 |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 7 |

Baseline 已存在的提示：Vite/Rollup 对 `@vueuse/core` 的 `/* #__PURE__ */` 注释给出 warning；Element Plus vendor chunk 超过 500 kB。两者为 baseline warning，本阶段未处理。

## Phase 1 完成内容

- 新增 `src/router/route-names.ts`，集中路由名称和 Legacy 过渡期 route -> module 映射。
- 从 `LegacyConsoleView.vue` 提取左侧导航数据到 `src/app/navigation.ts`。
- 新增 `src/app/AppSidebar.vue`，左侧菜单改为 `RouterLink`，选中态由 `route.path` 单向判断。
- 新增 `src/app/AppTopbar.vue`，承载节点、服务状态和时间等全局顶部信息。
- 新增 `src/app/AppShell.vue`，形成 `AppSidebar + main(AppTopbar + RouterView)` 外壳。
- 修改 Router：根路由组件改为 `AppShell`；业务子路由 Phase 1 暂时仍渲染 `LegacyConsoleView.vue`。
- 新增过渡路由 `/device/workbench`，使设备工作台不再借用 `/device + activeModule.value = "workbench"`。
- 从 `LegacyConsoleView.vue` 删除 Sidebar、App Shell、Topbar 模板和相关图标/nav/token/clock 状态。
- 将旧宿主中的 `activeModule` 改为由 `route.path` 推导的 `computed`，不再手动赋值。
- 旧宿主内部跳转只执行 `router.push()`；`/control`、`/shadow`、`/device/workbench` 只单向同步页面本地 `workbenchTab`。
- 新增 `src/styles/tokens.css`，定义深色工业控制台颜色、spacing、radius、control height 等 token，并在 `main.ts` 中引入。
- 更新 Router 测试，覆盖 `/device/workbench` 和 route -> Legacy module 过渡映射。
- 为了让本次新增长期文档可被 git 跟踪，调整 `.gitignore` 仅放开 `collector-desktop/AGENTS.md` 与 `collector-desktop/docs/frontend-refactor/**`。

## Phase 1 验证结果

执行时间：2026-08-28 10:54 左右，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 29 个测试文件、145 个测试通过 |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 13 |

Phase 1 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 2：Dashboard 迁移完成内容

- 新增 `src/views/dashboard/DashboardView.vue`，承载原 `activeModule === "overview"` 的概览页面。
- `/dashboard` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `DashboardView.vue`。
- `/realtime`、`/history`、`/alarm`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/control`、`/shadow` 当时仍保持 `LegacyConsoleView.vue` 过渡状态。
- Dashboard 新增独立 `loadDashboard()`，进入 `/dashboard` 只加载首页需要的数据，不再依赖 `LegacyConsoleView.onMounted()` 或 `LegacyConsoleView.refreshAll()`。
- Dashboard 设备列表、在线数、离线数、异常数、点位总数和运行状态来源切到 `useDeviceStore()`，不再在 Dashboard 内长期维护 `devices` / `runtimeMap` 副本。
- Dashboard 页面级展示状态保留在 View 内：最近告警、上报指标、运行状态、系统资源、缓存指标、存储指标、性能明细、刷新时间、本地设备编辑弹窗状态。
- 从 `LegacyConsoleView.vue` 删除原 Dashboard template，消除“复制一份 DashboardView 后旧实现仍存在”的双实现风险。
- 从 `LegacyConsoleView.vue` 删除 Dashboard 专属计算/辅助逻辑：`overviewCards`、`runtimeState`、`lastRefreshText`、拓扑 tone/detail、资源仪表盘 `resourceGauges`、Dashboard 风险文案辅助等。
- `LegacyConsoleView.onMounted()` 不再调用整站 `refreshAll()`；改为先加载旧页面共同需要的协议/设备基础数据，再按当前 Legacy route 加载对应页面数据。
- `loadOverview()` 去掉 Dashboard 专属的 `getAllDeviceStatistics()` / `deviceStats`，保留 Collection / Cloud / Diagnostic 仍需要的监控、配置摘要和上报链路数据。
- Dashboard 专属样式从 `legacy-console.css` 迁入 `DashboardView.vue` scoped style；`legacy-console.css` 中已删除 `overview-*`、`home-dashboard-*`、`home-panel*`、`home-event-*`、`home-risk-*`、`topology-*`、`resource-dashboard/resource-gauge/resource-ring/resource-runtime/resource-load`、`cache-ring`、`heading-note` 等 Dashboard 专属规则。
- `workbench.css` 未发现 Dashboard 专属选择器，本阶段未修改。

## Phase 2 验证结果

执行时间：2026-08-28 11:47 左右，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 29 个测试文件、147 个测试通过；`router.test.ts` 覆盖 `/dashboard -> DashboardView` 和其它过渡页面仍走 `LegacyConsoleView` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `DashboardView` chunk |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 16 |
| `git diff --check` | 通过 | 无空白错误；Git 仅提示 Web 构建产物 LF/CRLF 工作区换行提示 |

Phase 2 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 3：Realtime 迁移完成内容

- 新增 `src/views/realtime/RealtimeView.vue`，承载原 `activeModule === "realtime"` 的主菜单“实时数据查询”页面。
- `/realtime` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `RealtimeView.vue`；当前 `/dashboard` 仍直接指向 `DashboardView.vue`。
- `/history`、`/alarm`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/control`、`/shadow` 当时继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- Realtime 新增独立 `loadRealtime()`，进入 `/realtime` 后自行加载实时数据，不再依赖 `LegacyConsoleView.onMounted()`、`LegacyConsoleView.refreshAll()` 或 Legacy 内部 `loadRealtime()`。
- Realtime 设备列表来源切到 `useDeviceStore()` 的 `devices`；必要时调用 `deviceStore.refresh()`，不再从 Legacy 传入设备列表，也不在 RealtimeView 维护长期设备领域副本。
- Realtime 页面本地状态保留在 View 内：`realtimeAuto`、`realtimeDeviceId`、`realtimeKeyword`、`realtimeRows`、`realtimeSingleDeviceId`、`realtimeSinglePointId`、`realtimeSingleResult`、`loading`、`singleLoading`。
- 全部设备模式保留：先调用 `getAllDeviceDataSummaries()` 得到设备摘要，再合并 `deviceStore.devices` 的设备 ID，使用 `Promise.allSettled()` 分别调用 `getDeviceRealtimeData(deviceId)`，失败或空结果时回退到对应 summary 行。
- 单设备模式保留：选择设备后直接调用 `getDeviceRealtimeData(realtimeDeviceId)` 并通过 `normalizeRealtimeRows(response, realtimeDeviceId)` 归一化。
- 单点查询保留：使用 `getPointRealtimeData(deviceId, pointId)`，结果继续通过 `normalizeSinglePointRealtimeRow()` 优先解析；表格“查单点”会填充设备和 pointId / pointCode / address，并立即执行单点查询。
- 5 秒自动刷新 Timer 迁入 `RealtimeView.vue` 生命周期：`onMounted()` 初次加载后 `syncTimer()`，`onBeforeUnmount()` 清理；Legacy 中主实时页面的 `realtimeTimer` 已删除。
- `src/views/legacy/realtime-utils.ts` 与 `src/views/legacy/realtime-utils.test.ts` 已迁移到 `src/features/realtime/utils/`。
- `RealtimeDataPanel.vue` 已改为 import `features/realtime/utils/realtime-utils` 中的 `normalizeRealtimeRows()`，删除其内部重复 normalization 实现，继续保留单设备 WebSocket + HTTP fallback 场景。
- `DeviceConfigPanel.vue` 的工作台点位运行数据也复用新的 `normalizeRealtimeRows()`，避免继续保留另一套同名归一化函数。
- 从 `LegacyConsoleView.vue` 删除主 Realtime template、页面级 Realtime state、`filteredRealtimeRows`、`realtimeSummary`、`loadRealtime()`、`loadSingleRealtime()`、`pickRealtimePoint()`、Realtime 格式化 helper 与主 Realtime 自动刷新 Timer。
- `LegacyConsoleView.vue` 仍保留 `selectedRealtimeRows` 与 `loadSelectedRealtime()`，仅服务 `DeviceWorkbench` 当前选中设备工作台，不属于主 `/realtime` 页面。
- 搜索 `legacy-console.css` 与 `workbench.css` 后未发现 `realtime-summary-cards` / `realtime-single-panel` 对应专属样式规则；`quality-badge`、`exact-toolbar`、`exact-table-card`、`section-heading` 等仍为多页面共享样式，按阶段边界暂不复制到 `RealtimeView.vue` scoped style。

## Phase 3 验证结果

执行时间：2026-08-28 13:46-13:50，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 29 个测试文件、149 个测试通过；新增 `src/features/realtime/utils/realtime-utils.test.ts`，`router.test.ts` 覆盖 `/dashboard -> DashboardView`、`/realtime -> RealtimeView` 和其它过渡页面仍走 `LegacyConsoleView` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `RealtimeView` 与 `realtime-utils` chunks |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 19 |
| `git diff --check` | 通过 | 无输出，exit code 0 |

Phase 3 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 4：Log 迁移完成内容

- 新增 `src/views/log/LogView.vue`，承载原 `activeModule === "log"` 的主菜单“日志”页面。
- `/log` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `LogView.vue`；当前 `/dashboard`、`/realtime` 继续分别直接指向 `DashboardView.vue`、`RealtimeView.vue`。
- `/history`、`/alarm`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/network`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- Log 新增独立 `loadLogs()`，进入 `/log` 后自行调用 `getOpsLogs(buildLogQueryParams(...))` 并通过 `normalizeLogRows()` 归一化，不再依赖 `LegacyConsoleView.onMounted()`、`LegacyConsoleView.refreshAll()` 或 Legacy 内部 `loadLogs()`。
- Log 设备过滤下拉来源切到 `useDeviceStore()` 的 `devices`，进入页面时调用 `deviceStore.refresh()`，不从 Legacy 传入设备列表。
- Log 页面本地状态保留在 View 内：`logs`、`logLevel`、`logDeviceId`、`logLogger`、`logThread`、`logKeyword`、`logLimit`、`logAutoRefresh`、`loading`、`error`、`exceptionLoading`。
- 查询参数继续通过 `buildLogQueryParams()` 构造，只向后端发送既有 `level`、`logger`、聚合后的 `keyword`、`limit`，不新增后端不存在的 `deviceId` / `thread` 参数。
- 前端精筛继续使用 `filterLogRows()`，摘要继续使用 `summarizeLogRows()`。
- TXT / JSON 导出继续使用 `exportLogRowsAsText()`、`exportLogRowsAsJson()` 和 `buildLogExportFilename()`。
- 错误日志快速定位保留：设置 `logLevel = "ERROR"` 后重新加载日志。
- 最近异常定位保留：LogView 点击时单独请求 `getExceptionStats()`，使用 `buildLogSearchFromException()` 填充关键词后加载日志；不调用 `loadOverview()`、`runDiagnostic()` 或 `refreshAll()`。
- Alarm 页“定位日志”交互已调整为跳转 `/log?deviceId=...&keyword=...`，由新 `LogView.vue` 读取 route query 并独立加载日志。
- 5 秒自动刷新 Timer 迁入 `LogView.vue` 生命周期：`watch(logAutoRefresh)` 启停，`onBeforeUnmount()` 清理；Legacy 中主 Log 页的 `logTimer` 已删除。
- `src/views/legacy/log-utils.ts` 与 `src/views/legacy/log-utils.test.ts` 已迁移到 `src/features/log/utils/`。
- `LogPanel.vue` 已改为复用 `features/log/utils/log-utils.ts` 中的 `buildLogQueryParams()`、`filterLogRows()`、`exportLogRowsAsText()`、`buildLogExportFilename()`，继续服务 DeviceWorkbench 单设备日志场景。
- 搜索确认 `exportLogRows()` 只剩 ops-utils 与 LogPanel 引用后，将它从 `src/views/ops/ops-utils.ts` 删除，并删除 `ops-utils.test.ts` 中对应测试；Log TXT 导出统一由 feature log utils 承担。
- 从 `LegacyConsoleView.vue` 删除主 Log template、页面级 Log state、`filteredLogs`、`logSummary`、`loadLogs()`、`showErrorLogs()`、`searchLatestExceptionLogs()`、`downloadLogs()`、`shortLoggerName()` 与主 Log 自动刷新 Timer。
- 为保留 Diagnostic 诊断包里的日志样本能力，Legacy 未保留主 Log 页面状态，而是在导出诊断包时通过 `loadDiagnosticLogSample()` 最小化请求 `getOpsLogs({ limit: 50 })`；这不影响 `/log` 独立页面。
- Log 专属样式从 `legacy-console.css` 迁入 `LogView.vue` scoped style，包括 `modao-log-*` 与 `log-toolbar` 布局；公共 `section-heading`、`exact-page`、`exact-toolbar`、`exact-table-card`、`exact-diagnostic-card` 等仍保留公共 legacy 样式。

## Phase 4 验证结果

执行时间：2026-08-28 14:16-14:22，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 29 个测试文件、149 个测试通过；`src/features/log/utils/log-utils.test.ts` 保留并通过，`router.test.ts` 覆盖 `/dashboard -> DashboardView`、`/realtime -> RealtimeView`、`/log -> LogView` 和其它过渡页面仍走 `LegacyConsoleView` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `LogView` 与 `log-utils` chunks |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 24 |
| `git diff --check` | 通过 | exit code 0；仅有 Git 对部分 Web 构建产物 LF/CRLF 的提示，无空白错误 |

Phase 4 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 4 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5174/#/dashboard` 能打开，页面文本显示“控制台总览”，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5174/#/realtime` 能打开，页面文本显示“实时数据查询”，仍由 `RealtimeView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5174/#/log` 能打开，页面文本显示“日志”“自动刷新”“刷新日志”“全部级别”“全部设备”“记录器名称 logger”“线程名 thread”“错误日志快速定位”“最近异常定位”“导出文本”“导出 JSON”和日志摘要卡片。
- `/device`：dev server `http://127.0.0.1:5174/#/device` 能打开，仍显示 Legacy 设备管理页面。
- `/alarm`：dev server `http://127.0.0.1:5174/#/alarm` 能打开，仍显示 Legacy 告警历史中心。
- 搜索确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'log'`、主 Log 页面 state、主 Log `loadLogs()` / `downloadLogs()` / 快速定位函数、`logTimer`。
- 搜索确认 `src/views/legacy/` 下已无 `log-utils.ts` / `log-utils.test.ts`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 已无主 Log 页面 `modao-log-*` / `log-toolbar` 专属选择器。

## Phase 4 新增文件

- `collector-desktop/src/views/log/LogView.vue`
- `collector-desktop/src/features/log/utils/log-utils.ts`
- `collector-desktop/src/features/log/utils/log-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/LogView-Bhpwtpjx.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/LogView-DSQ3DSbd.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/log-utils-BEPZH2al.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/monitor.api-DGr9dOcd.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/data.api-BuLcx8kQ.js`（`build:web` 生成）

## Phase 4 修改文件

- `collector-desktop/src/components/log/LogPanel.vue`
- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/ops/ops-utils.ts`
- `collector-desktop/src/views/ops/ops-utils.test.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 4 删除文件

- `collector-desktop/src/views/legacy/log-utils.ts`
- `collector-desktop/src/views/legacy/log-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 5：Alarm 迁移完成内容

- 新增 `src/views/alarm/AlarmView.vue`，承载原 `activeModule === "alarm"` 的主菜单“告警历史中心”页面。
- `/alarm` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `AlarmView.vue`；当前 `/dashboard`、`/realtime`、`/log` 继续分别直接指向 `DashboardView.vue`、`RealtimeView.vue`、`LogView.vue`。
- `/history`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/network`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- Alarm 新增独立 `loadAlarms()`，进入 `/alarm` 后自行加载告警历史，不再依赖 `LegacyConsoleView.onMounted()`、`LegacyConsoleView.refreshAll()` 或 Legacy 内部 `loadAlarms()`。
- Alarm 设备过滤下拉来源切到 `useDeviceStore()` 的 `devices`，进入页面时调用 `deviceStore.refresh()`，不从 Legacy 传入设备列表。
- Alarm 页面本地状态保留在 View 内：`alarms`、`alarmDeviceId`、`alarmLevelFilter`、`alarmHours`、`alarmKeyword`、`alarmLimit`、`alarmAcknowledgements`、`alarmAckDialogVisible`、`selectedAlarmForAck`、`alarmAckNote`、`acknowledgingAlarmId`、`loading`、`ackStatusLoading`、`error`。
- 查询语义保持不变：有 `alarmDeviceId` 时调用 `getDeviceAlarmHistory(deviceId, query)`；无设备时调用 `getRecentAlarms(query)`。
- 查询参数继续通过 `buildAlarmHistoryQuery()` 构造，保持 `level`、`pointCode/keyword`、`ruleId`、`startTs`、`endTs`、`limit` 等既有后端契约，不修改后端接口。
- 告警历史响应继续通过 `normalizeAlarmHistoryRows()` 兼容 `alarms/records/rows/items/data` 以及 snake_case 字段。
- 确认状态批量查询保留：根据 `buildAlarmIdentity()` 生成告警 ID，调用 `queryAlarmAcknowledgements()`，通过 `normalizeAlarmAcknowledgementMap()` 与 `mergeAlarmAcknowledgementStates()` 合并 acknowledgement 明细。
- 单条告警确认保留：打开确认 Dialog，填写处理说明，使用 `buildAlarmAckPayload(note, alarmId)` 生成幂等 key，调用 `acknowledgeAlarm()`，成功后通过 `applyAlarmAcknowledgement()` 局部回写当前列表。
- acknowledgement 展示保留：`describeAlarmAcknowledgement()` 继续展示确认人、确认时间、确认说明；Dialog 继续展示 idempotency key。
- Alarm -> Log 联动保留 Router Query：`AlarmView` 使用 `buildAlarmTroubleshootTarget()` 后跳转 `/log?deviceId=...&keyword=...`，由 `LogView.vue` 独立接收并查询日志。
- Alarm -> Network 联动改为 Router Query：`AlarmView` 使用 `buildAlarmTroubleshootTarget()` 后跳转 `/network?target=...&port=...`；Network 仍在 Legacy 阶段，本 Phase 只做最小 query 兼容，不迁移 `NetworkView`。
- `LegacyConsoleView.vue` 的 Network 旧页面新增最小 `target/port` query 读取逻辑，支持 AlarmView 通过路由交接检测目标。
- `src/views/legacy/alarm-history-utils.ts` 与 `src/views/legacy/alarm-history-utils.test.ts` 已迁移到 `src/features/alarm/utils/`，测试保留并通过。
- `src/views/ops/ops-utils.ts` 中 Alarm 专属纯函数已迁移到 `src/features/alarm/utils/alarm-utils.ts`，包括 `buildAlarmAckPayload()`、`buildAlarmIdentity()`、`mergeAlarmAcknowledgementStates()`、`normalizeAlarmAcknowledgementMap()`、`describeAlarmAcknowledgement()`、`applyAlarmAcknowledgement()`、`buildAlarmTroubleshootTarget()`、`summarizeAlarms()`，并补充 `alarmCurrentValue()`、`alarmLevelText()` 的主 Alarm 页面展示测试。
- `ops-utils.ts` 继续保留 Report / Diagnostic / Network 相关函数，未提前拆出其它领域。
- `AlarmTablePanel.vue` 已改为复用 `features/alarm/utils/alarm-utils.ts` 中的 `buildAlarmAckPayload()` 与 `summarizeAlarms()`；其 `alarmContent()`、`levelText()`、`alarmStatusText()` 因主页面/工作台文案语义不同，本阶段不强行统一。
- 从 `LegacyConsoleView.vue` 删除主 Alarm template、页面级 Alarm state、`alarmHistorySummary`、`alarmScopeText`、ack dialog computed、`loadAlarms()`、`refreshAlarmAcknowledgements()`、`openAlarmAcknowledgementDialog()`、`closeAlarmAcknowledgementDialog()`、`submitAlarmAcknowledgement()`、`locateAlarmLogs()`、`diagnoseAlarmNetwork()`、`alarmCurrentValue()`、`alarmLevelText()` 等主 Alarm 实现。
- 为保留 Diagnostic 诊断包里的告警样本能力，Legacy 未保留主 Alarm 页面状态，而是在导出诊断包时通过 `loadDiagnosticAlarmSample()` 最小化请求 `getRecentAlarms({ limit: 20 })`。
- Alarm 专属样式从 `legacy-console.css` 迁入 `AlarmView.vue` scoped style，包括 `alarm-ack-table`、`alarm-ack-detail`、`alarm-action-row`、`alarm-ack-backdrop`、`alarm-ack-dialog`、`alarm-ack-target`、`alarm-ack-idempotency`；公共 `section-heading`、`exact-page`、`exact-toolbar`、`exact-table-card`、`status-badge`、`exact-diagnostic-card` 等仍保留公共 legacy 样式。

## Phase 5 验证结果

执行时间：2026-08-28 14:53-14:58，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 30 个测试文件、151 个测试通过；新增 `src/features/alarm/utils/alarm-utils.test.ts`，`src/features/alarm/utils/alarm-history-utils.test.ts` 保留并通过，`router.test.ts` 覆盖 `/dashboard -> DashboardView`、`/realtime -> RealtimeView`、`/log -> LogView`、`/alarm -> AlarmView` 和其它过渡页面仍走 `LegacyConsoleView` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `AlarmView` 与 `alarm-utils` chunks |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 28 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 5 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 5 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5175/#/dashboard` 能打开，页面文本显示“控制台总览”，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5175/#/realtime` 能打开，页面文本显示“实时数据查询”，仍由 `RealtimeView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5175/#/log?deviceId=dev-1&keyword=temp` 能打开，仍由 `LogView.vue` 承载，query 交接状态下页面不崩溃。
- `/alarm`：dev server `http://127.0.0.1:5175/#/alarm` 能打开，页面文本显示“告警历史中心”“确认状态批量查询”“刷新告警历史”“全部设备最近告警”“全部级别”“最近 24 小时/3 天/7 天”“告警总数/未确认/已确认/严重/警告”“当前值”“确认状态”“确认信息”“操作”。
- `/alarm?level=WARNING&keyword=temp&hours=72&limit=20`：能打开，query 交接状态下页面不崩溃。
- `/device`：dev server `http://127.0.0.1:5175/#/device` 能打开，仍显示 Legacy 设备管理页面。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，Network Legacy 页面已显示 `TCP`、目标 `127.0.0.1`、端口 `502`，证明 Alarm -> Network 的 route query 交接最小兼容可用。
- `/device/workbench`：dev server 能打开，设备工作台未选择设备时不崩溃，仍显示“告警历史”分区入口；`AlarmTablePanel.vue` 编译、类型检查和构建均通过。
- 搜索确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'alarm'`、主 Alarm 页面 state、主 Alarm `loadAlarms()` / acknowledgement dialog / Log 和 Network 定位函数。
- 搜索确认 `src/views/legacy/` 下已无 `alarm-history-utils.ts` / `alarm-history-utils.test.ts`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 已无主 Alarm 页面 `alarm-ack-*` / `alarm-action-*` 专属选择器。

## Phase 5 新增文件

- `collector-desktop/src/views/alarm/AlarmView.vue`
- `collector-desktop/src/features/alarm/utils/alarm-history-utils.ts`
- `collector-desktop/src/features/alarm/utils/alarm-history-utils.test.ts`
- `collector-desktop/src/features/alarm/utils/alarm-utils.ts`
- `collector-desktop/src/features/alarm/utils/alarm-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/AlarmView--Upfw_c7.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/AlarmView-D2FKGM9K.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/alarm-utils-4gXIzPsW.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/ops.api-B0jzCTFs.js`（`build:web` 生成）

## Phase 5 修改文件

- `collector-desktop/src/components/alarm/AlarmTablePanel.vue`
- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/dashboard/DashboardView.vue`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/legacy/LegacyHistoryPanel.vue`
- `collector-desktop/src/views/ops/ops-utils.ts`
- `collector-desktop/src/views/ops/ops-utils.test.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 5 删除文件

- `collector-desktop/src/views/legacy/alarm-history-utils.ts`
- `collector-desktop/src/views/legacy/alarm-history-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 6：Network 迁移完成内容

- 新增 `src/views/network/NetworkView.vue`，承载原 `activeModule === "network"` 的“网络检测”页面。
- `/network` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `NetworkView.vue`；当前 `/dashboard`、`/realtime`、`/log`、`/alarm`、`/network` 均为独立 View。
- `/history`、`/device`、`/device/workbench`、`/collect`、`/cloud`、`/diagnostic`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- Network 新增独立 `runNetwork()`，按页面表单状态调用 `buildNetworkDiagnosticPayload()`，再调用 `diagnoseNetwork()`，然后通过 `normalizeNetworkDiagnosticResult()` 写入 `networkResult` 并用 `appendNetworkHistory(..., 10)` 保留最多 10 条历史。
- Network 页面状态保留在 View 内：`networkType`、`networkDeviceId`、`networkTarget`、`networkPort`、`networkTimeout`、`networkOperating`、`networkResult`、`networkHistory`。
- Network 设备列表来源切到 `useDeviceStore()` 的 `devices`，进入页面时调用 `deviceStore.refresh()`，不再维护另一套长期 `devices = ref([])`。
- PING / TRACE / TCP 表单语义保持不变：仍通过 `NETWORK_DIAGNOSTIC_TYPES` 渲染，`buildNetworkDiagnosticPayload()` 统一校验；PING/TRACE 不发送无意义 port；TCP 必须校验 1-65535；timeout 保持 100-10000 ms 限制。
- 设备配置带入保留：`fillNetworkFromSelectedDevice()` 与 `applyNetworkDevice()` 使用 `resolveNetworkTargetFromDevice()`，不在 View 中重新写 `device.ipAddress || device.host` 第二套解析逻辑。
- Alarm -> Network route query 由 `NetworkView.vue` 接管：支持 `target`、`port`、`type`、`deviceId`；`target+port` 会填充目标并切到 TCP，只有 `target` 且无显式 `type` 时保持 PING；query 变化时通过 watcher 重新应用，不自动发起网络检测。
- 结果行继续通过 `networkResultRows = computed(() => networkResult.value ? buildNetworkResultRows(networkResult.value) : [])` 构造；TRACE `details` 路由明细继续显示。
- 报告导出继续使用 `buildNetworkExportText(networkHistory.value)`，View 只负责 Blob 下载。
- `src/views/legacy/network-utils.ts` 与 `src/views/legacy/network-utils.test.ts` 已迁移到 `src/features/network/utils/`，保留 `NETWORK_DIAGNOSTIC_TYPES`、`buildNetworkDiagnosticPayload()`、`resolveNetworkTargetFromDevice()`、`normalizeNetworkDiagnosticResult()`、`buildNetworkResultRows()`、`appendNetworkHistory()`、`buildNetworkExportText()` 及相关类型。
- `src/views/legacy/LegacyEdgeTelemetryPanel.vue` 已迁移为 `src/features/network/components/EdgeTelemetryPanel.vue`。
- `src/views/legacy/edge-telemetry-utils.ts` 与 `src/views/legacy/edge-telemetry-utils.test.ts` 已迁移到 `src/features/network/utils/`。
- `EdgeTelemetryPanel.vue` 仅调整目录与依赖路径，继续使用 `ingestEdgeTelemetry()`，保留快捷表单、原始 JSON、gatewayId、protocol、configVersion、deviceId、pointRef、valueType、value、quality、timestamp、sequence、提交遥测、刷新时间戳/序号、请求预览和响应预览。
- `EdgeTelemetryPanel.vue` 继续通过 `select-device` 事件通知父级，`NetworkView.vue` 将页面本地 `networkDeviceId` 作为 `selected-device-id` 传入，不操作全局 selectedDeviceId。
- `src/views/ops/ops-utils.ts` 中无用 Network 重复函数 `formatNetworkResult()` 已删除，并删除对应 `ops-utils.test.ts` 测试；Report / Diagnostic 函数仍保留在 `ops-utils.ts`，未提前拆分。
- 从 `LegacyConsoleView.vue` 删除主 Network template、Network 页面级 state、`networkResultRows`、`runNetwork()`、`applyNetworkDevice()`、`fillNetworkFromSelectedDevice()`、`syncNetworkMode()`、`downloadNetworkReport()`，以及 Phase 5 临时加入 Legacy 的 `applyNetworkRouteQuery()` / route query watcher。
- 诊断包不再依赖已迁出 Network 页面会话状态；Legacy 中不再保留 `networkHistory` 作为跨页面缓存。
- Network 专属样式从 `legacy-console.css` 迁入 `NetworkView.vue` scoped style，包括 `network-toolbar`、`network-toolbar-actions`、`network-summary-cards`、`network-result-panel .json-view`、`network-result-grid`、`network-trace-lines`、`network-history-table`。
- EdgeTelemetry 专属样式从 `legacy-console.css` 迁入 `EdgeTelemetryPanel.vue` scoped style，包括 `edge-mode-row`、`edge-action-row`、`edge-form-grid textarea`、`edge-json-grid`。
- 公共 `section-heading`、`exact-page`、`exact-page-body`、`exact-toolbar`、`exact-table-card`、`exact-surface`、`exact-diagnostic-card`、`exact-config-item`、`status-badge`、`json-view`、`form-grid` 等仍保留公共 legacy 样式。

## Phase 6 验证结果

执行时间：2026-08-28 15:21-15:26，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 30 个测试文件、152 个测试通过；`src/features/network/utils/network-utils.test.ts` 与 `src/features/network/utils/edge-telemetry-utils.test.ts` 保留并通过，`router.test.ts` 覆盖 `/network -> NetworkView` 和 `/network?target=127.0.0.1&port=502` resolve |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `NetworkView` chunk |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 30 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误，Git 仅提示部分 Web 构建产物 LF/CRLF 工作区换行转换 |

Phase 6 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 6 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5176/#/dashboard` 能打开，页面文本显示“控制台总览”，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5176/#/realtime` 能打开，页面文本显示“实时数据查询”，仍由 `RealtimeView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5176/#/log` 能打开，仍由 `LogView.vue` 承载。
- `/alarm`：dev server `http://127.0.0.1:5176/#/alarm` 能打开，仍由 `AlarmView.vue` 承载。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，页面文本显示“网络检测”“PING 可达性”“TRACE 路由跟踪”“TCP 端口”“从设备配置带入”“开始检测”“导出检测结果”“检测方式 TCP”“检测结果 127.0.0.1:502”“尚未执行网络检测”“最多保留 10 条”“边缘遥测调试”。说明 Alarm -> Network 的 `target/port` query 由 `NetworkView.vue` 接收，并且没有自动执行网络检测。
- `/network?target=127.0.0.1&type=TRACE`：dev server 能打开，页面文本显示“检测方式 TRACE”“检测结果 127.0.0.1”，证明显式 type query 可接收。
- `/device`：dev server `http://127.0.0.1:5176/#/device` 能打开，仍显示 Legacy 设备管理页面。
- 搜索确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'network'`、Network 页面 state、主 Network `runNetwork()` / route query watcher / report export 函数。
- 搜索确认 `src/views/legacy/` 下已无 `network-utils*`、`edge-telemetry-utils*`、`LegacyEdgeTelemetryPanel.vue`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 已无 Network / EdgeTelemetry 专属选择器。

## Phase 6 新增文件

- `collector-desktop/src/views/network/NetworkView.vue`
- `collector-desktop/src/features/network/components/EdgeTelemetryPanel.vue`
- `collector-desktop/src/features/network/utils/network-utils.ts`
- `collector-desktop/src/features/network/utils/network-utils.test.ts`
- `collector-desktop/src/features/network/utils/edge-telemetry-utils.ts`
- `collector-desktop/src/features/network/utils/edge-telemetry-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/NetworkView-9MUMl5H9.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/NetworkView-BvkqIyXT.js`（`build:web` 生成）

## Phase 6 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/ops/ops-utils.ts`
- `collector-desktop/src/views/ops/ops-utils.test.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 6 删除文件

- `collector-desktop/src/views/legacy/LegacyEdgeTelemetryPanel.vue`
- `collector-desktop/src/views/legacy/edge-telemetry-utils.ts`
- `collector-desktop/src/views/legacy/edge-telemetry-utils.test.ts`
- `collector-desktop/src/views/legacy/network-utils.ts`
- `collector-desktop/src/views/legacy/network-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 7：Cloud 迁移完成内容

- 新增 `src/views/cloud/CloudView.vue`，承载原 `activeModule === "cloud"` 的“云平台配置 / 上报链路”页面。
- `/cloud` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `CloudView.vue`；当前 `/dashboard`、`/realtime`、`/log`、`/alarm`、`/network`、`/cloud` 均为独立 View。
- `/history`、`/device`、`/device/workbench`、`/collect`、`/diagnostic`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- `CloudView.vue` 只持有页面级 `reportMetrics`、`loading`、`error`、`lastRefresh` 状态，不引入 `useDeviceStore()`，不新建 `cloud.store.ts`。
- `CloudView.vue` 新增独立 `loadCloud()` / `refreshCloud()`，只调用 `getCloudReportMetrics()`，不复制或调用 `loadOverview()`。
- “刷新链路”按钮改为调用 `refreshCloud()`，只刷新 `/monitor/report` 指标，不刷新运行状态、系统资源、配置摘要、缓存、设备连接、性能、异常、存储等其它监控接口。
- 页面展示能力保持：云上报状态、云上报是否启用、待发送、待 ACK、隔离消息、上报模式、云服务商、可上报点位、批量聚合、ACK 提交点、ACK 超时、可靠发件箱、ACK 成功/失败、链路风险和原始上报链路 JSON。
- 没有新增编辑云配置、保存配置、MQTT 参数编辑、云服务商配置表单、测试连接或配置提交功能。
- 新增 `src/features/cloud/utils/cloud-report-utils.ts`，收敛 Cloud / Report 纯转换逻辑：`cloudStatusText()`、`buildCloudEnabledText()`、`buildCloudSummaryCards()`、`buildCloudStrategyRows()`、`buildCloudOperationalRows()`、`buildCloudRisks()`、`summarizeReportMetrics()`。
- 新增 `src/features/cloud/utils/cloud-report-utils.test.ts`，覆盖 status 中文映射、启用显示、Outbox、ACK runtime、configured、batch、risks 和原 `summarizeReportMetrics()` 摘要逻辑。
- `summarizeReportMetrics()` 已从 `src/views/ops/ops-utils.ts` 迁出到 `src/features/cloud/utils/cloud-report-utils.ts`，对应测试也从 `ops-utils.test.ts` 迁出。
- `src/views/ops/ops-utils.ts` 现在只保留 `buildDiagnosticAdvice()` 及其私有辅助函数，继续作为 Diagnostic 过渡 helper，不提前迁 Diagnostic。
- 从 `LegacyConsoleView.vue` 删除主 Cloud template、Cloud 页面 computed：`cloudStatusTextValue`、`cloudEnabledText`、`cloudSummaryCards`、`cloudStrategyRows`、`cloudOperationalRows`、`cloudRisks`。
- `cloudStatusText()` 已不再在 `LegacyConsoleView.vue` 本地定义；Diagnostic 仍需要 status 中文映射，所以改为小范围复用 `features/cloud/utils/cloud-report-utils.ts` 中的 `cloudStatusText()`，没有重构 Diagnostic 页面结构。
- `LegacyConsoleView.vue` 的 `loadActiveLegacyModule()` 已删除 `module === "cloud"` 分支，现在只在 `module === "collect"` 时调用 `loadOverview()`，`module === "diag"` 仍走 `runDiagnostic()`。
- Legacy 中的 `reportMetrics` 暂时保留，因为 Diagnostic 的 `diagnosticRows`、`buildDiagnosticRaw()`、诊断包导出仍依赖云端上报指标；这是 Legacy Diagnostic 的独立快照，不与 `CloudView.vue` 或 `DashboardView.vue` 共享 mutable ref。
- Cloud 专属样式 `exact-cloud-grid`、`exact-cloud-status`、`exact-cloud-icon`、`cloud-stat-row` 已从 `legacy-console.css` 迁入 `CloudView.vue` scoped style。
- `modao-property-grid`、`modao-property-item`、`modao-risk-list`、`modao-risk-item` 仍被 `LegacyDiagnosticDetailPanel.vue` 使用，暂时保留在 `legacy-console.css` 中。
- 公共 `section-heading`、`heading-title-line`、`heading-actions`、`heading-online`、`exact-page`、`exact-page-body`、`exact-surface`、`exact-surface-head`、`exact-json-panel`、`json-view` 仍保留公共 legacy 样式。

## Phase 7 验证结果

执行时间：2026-08-31 08:39-08:49，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 31 个测试文件、169 个测试通过；新增 `src/features/cloud/utils/cloud-report-utils.test.ts`，`router.test.ts` 覆盖 `/cloud -> CloudView` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `CloudView` 与 `cloud-report-utils` chunks |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 33 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 7 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 7 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5177/#/dashboard` 能打开，页面文本显示“控制台总览”，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5177/#/realtime` 能打开，页面文本显示“实时数据查询”，仍由 `RealtimeView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5177/#/log` 能打开，仍由 `LogView.vue` 承载。
- `/alarm`：dev server `http://127.0.0.1:5177/#/alarm` 能打开，仍由 `AlarmView.vue` 承载。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，仍由 `NetworkView.vue` 承载。
- `/cloud`：dev server `http://127.0.0.1:5178/#/cloud` 能打开，页面文本显示“云平台配置”“刷新链路”“未知”“上报策略”“Outbox / ACK 明细”“链路风险”“查看原始上报链路 JSON”；后端不可达时展示“无法连接采集服务，请检查服务地址和后端是否已启动”，页面未崩溃。
- `/device`：dev server `http://127.0.0.1:5177/#/device` 能打开，仍显示 Legacy 设备管理页面。
- `/diagnostic`：dev server `http://127.0.0.1:5177/#/diagnostic` 能打开，仍显示 Legacy 系统诊断页面；云端上报诊断行可展示“未知”。
- 代码检查确认 `CloudView.vue` 中没有 `getRuntimeStatus()`、`getSystemResources()`、`getConfigSummary()`、`getCacheMetrics()`、`getDeviceConnectionMetrics()`、`getCollectorPerformance()`、`getExceptionStats()`、`getStorageMetrics()`、`getPerformanceDetail()`、`useDeviceStore()`、`deviceStore` 或 `loadOverview()`。
- 代码检查确认 `CloudView.vue` 只直接调用 `getCloudReportMetrics()`；`AppShell` 的 `appStore.initialize()` 只做本地配置/桌面桥初始化，不发起 Overview 监控接口请求。
- 代码检查确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'cloud'`、Cloud 页面 computed、Cloud 页面 template 和 `module === "cloud"` 初始化分支。
- 搜索确认 `src/styles/legacy-console.css` 中已无 `exact-cloud-grid`、`exact-cloud-status`、`exact-cloud-icon`、`cloud-stat-row`。
- 搜索确认仓库未新增 `cloud.store.ts`。

## Phase 7 新增文件

- `collector-desktop/src/views/cloud/CloudView.vue`
- `collector-desktop/src/features/cloud/utils/cloud-report-utils.ts`
- `collector-desktop/src/features/cloud/utils/cloud-report-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/CloudView-BAlPB5eO.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/CloudView-D7KgGvmM.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/cloud-report-utils-DuSH22lD.js`（`build:web` 生成）

## Phase 7 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/ops/ops-utils.ts`
- `collector-desktop/src/views/ops/ops-utils.test.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 8：Diagnostic 迁移完成内容

- 新增 `src/views/diagnostic/DiagnosticView.vue`，承载原 `activeModule === "diag"` 的“系统实时状态诊断”页面。
- `/diagnostic` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `DiagnosticView.vue`；当前 `/dashboard`、`/realtime`、`/log`、`/alarm`、`/network`、`/cloud`、`/diagnostic` 均为独立 View。
- `/history`、`/device`、`/device/workbench`、`/collect`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- `DiagnosticView.vue` 页面级聚合快照保留在 View 内：`runtimeStatus`、`systemResource`、`reportMetrics`、`configSummary`、`cacheMetrics`、`deviceConnectionMetrics`、`collectorPerformance`、`exceptionStats`、`storageMetrics`、`performanceDetail`、`diagnosticRaw`、`loading`、`error`。
- `DiagnosticView.vue` 使用 `useDeviceStore()` 提供设备领域状态：`devices`、`selectedDeviceId`、`selectedDevice`、`onlineCount`、`totalPointCount`，进入页面时按需调用 `deviceStore.refresh()`，不再维护长期 `devices = ref([])` / `selectedDeviceId = ref("")` 副本。
- 新增独立 `loadDiagnostic()`，先应用 `route.query.deviceId`，再通过 `Promise.allSettled()` 并行加载 Diagnostic 真正需要的监控数据：`getRuntimeStatus()`、`getSystemResources()`、`getCloudReportMetrics()`、`getConfigSummary()`、`getCacheMetrics()`、`getDeviceConnectionMetrics()`、`getCollectorPerformance()`、`getExceptionStats()`、`getStorageMetrics()`、`getPerformanceDetail()`。
- `runDiagnostic()` 现在只调用 `loadDiagnostic()`，不再调用 Legacy `loadOverview()` 或 `refreshAll()`；`onMounted()` 保持进入 `/diagnostic` 自动运行完整诊断。
- `loadDiagnostic()` 独立容错：单个监控接口失败只记录部分不可用提示，已有数据继续展示；后端整体不可达时页面显示“无法连接采集服务，请检查服务地址和后端是否已启动”，不崩溃。
- 诊断包导出迁入 `DiagnosticView.vue`，继续包含 `generatedAt`、`selectedDeviceId`、`selectedDevice`、`overview` / `diagnosticRaw`、`alarms`、`logs`、`runtimeSummary`。
- 诊断包中的告警样本继续在导出时最小化调用 `getRecentAlarms({ limit: 20 })` 并通过 `normalizeAlarmHistoryRows()` 归一化，不依赖 `AlarmView` 状态。
- 诊断包中的日志样本继续在导出时最小化调用 `getOpsLogs({ limit: 50 })` 并通过 `normalizeLogRows()` 归一化，不依赖 `LogView` 状态。
- Device -> Diagnostic 跨页跳转改为 Router Query：`openDeviceRuntimeStatus()` 现在跳转 `{ path: "/diagnostic", query: { deviceId } }`，不再 `switchModule("diag")`。
- `DiagnosticView.vue` 支持 `/diagnostic?deviceId=dev-1`：读取 `route.query.deviceId` 后调用 `deviceStore.selectDevice(deviceId)`，并在 query 后续变化时同步更新；`DeviceRuntimePanel` 通过 props 接收选中设备。
- `src/views/legacy/LegacyDiagnosticDetailPanel.vue` 已迁移并改名为 `src/features/diagnostic/components/DiagnosticDetailPanel.vue`，新 DiagnosticView 不再反向依赖 `views/legacy/*`。
- `src/views/legacy/LegacyDeviceRuntimePanel.vue` 已迁移并改名为 `src/features/diagnostic/components/DeviceRuntimePanel.vue`，组件继续通过 `devices`、`selectedDeviceId` props 与 `select-device` 事件保持低耦合，不直接依赖整个 `deviceStore`。
- `DeviceRuntimePanel` 继续使用 `getRunningDevices()`、`getDeviceRuntime()`、`getDeviceStatus()`、`isDeviceRunning()`，保留刷新运行列表、查询单设备状态、检查是否运行、运行态摘要、运行设备表格和单设备状态 JSON。
- `src/views/legacy/diagnostic-detail-utils.ts` 与测试已迁移到 `src/features/diagnostic/utils/diagnostic-detail-utils.ts` / `.test.ts`，保留 `buildCacheDetail()`、`buildDeviceConnectionRows()`、`buildPerformanceDetail()`、`buildExceptionDetail()`、`buildStorageDetail()`。
- `src/views/legacy/device-runtime-utils.ts` 与测试已迁移到 `src/features/diagnostic/utils/device-runtime-utils.ts` / `.test.ts`，保留 `normalizeRunningDeviceIds()`、`normalizeDeviceRuntimeRows()`、`normalizeDeviceStatusDetail()`、`normalizeDeviceRunningFlag()`、`buildDeviceRuntimeSummary()`。
- 新增 `src/features/diagnostic/utils/diagnostic-utils.ts` / `.test.ts`，迁移 `buildDiagnosticAdvice()`，并抽出 `buildResourceSummary()`、`buildDiagnosticCards()`、`buildDiagnosticRows()`、`buildDiagnosticRaw()`、`buildDiagnosticRuntimeSummary()`、`hasDiagnosticData()` 等纯数据转换。
- `src/views/ops/ops-utils.ts` 与 `src/views/ops/ops-utils.test.ts` 已无剩余有效职责并删除；`src/views/ops/` 目录已为空并删除。
- 从 `LegacyConsoleView.vue` 删除主 Diagnostic template、`LegacyDiagnosticDetailPanel` / `LegacyDeviceRuntimePanel` imports、Diagnostic 页面 state/computed/function/API imports：`runtimeStatus`、`systemResource`、`reportMetrics`、`cacheMetrics`、`deviceConnectionMetrics`、`collectorPerformance`、`exceptionStats`、`storageMetrics`、`performanceDetail`、`diagnosticRaw`、`resourceSummary`、`diagnosticCards`、`diagnosticRows`、`runDiagnostic()`、`buildDiagnosticRaw()`、`downloadDiagnosticPackage()`、`loadDiagnosticAlarmSample()`、`loadDiagnosticLogSample()`。
- `configSummary` 与 `getConfigSummary()` 继续保留在 Legacy，因为 `/collect` 的 `collectionSummaryItems` 仍真实依赖配置摘要。
- `LegacyConsoleView.vue` 已删除 `loadOverview()`；Collection 的“刷新概览”和 `loadActiveLegacyModule("collect")` 改为只调用 `loadConfigSummary()`。
- `refreshAll()` 已收敛为 `Promise.allSettled([loadProtocols(), loadDevices(), loadConfigSummary()])`，不再调用 `runDiagnostic()` 或加载已迁出的 Dashboard / Cloud / Diagnostic 监控接口。
- Diagnostic 专属样式 `diagnostic-detail-panel`、`device-runtime-panel`、`diagnostic-detail-grid`、`diagnostic-detail-cards`、`diagnostic-sub-card`、`diagnostic-connection-table`、`diagnostic-exception-table` 已从 `legacy-console.css` 迁入 `DiagnosticDetailPanel.vue` / `DeviceRuntimePanel.vue` scoped style。
- 公共样式 `section-heading`、`exact-page`、`exact-page-body`、`exact-surface`、`exact-table-card`、`exact-diagnostic-cards`、`exact-diagnostic-card`、`status-badge`、`exact-json-panel`、`json-view`、`modao-property-grid`、`modao-property-item`、`modao-risk-list`、`modao-risk-item` 仍被多个页面使用，按 Phase 边界暂不复制或删除。

## Phase 8 验证结果

执行时间：2026-08-31 09:11-09:15，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 31 个测试文件、175 个测试通过；新增 `src/features/diagnostic/utils/diagnostic-utils.test.ts`，迁移后的 diagnostic/detail/runtime utils 测试通过，`router.test.ts` 覆盖 `/diagnostic -> DiagnosticView` 和 `/diagnostic?deviceId=dev-1` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `DiagnosticView` 与 `device-runtime-utils` chunks，Diagnostic 纯工具随页面 chunk 打包 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 36 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 8 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 8 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5179/#/dashboard` 能打开，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5179/#/realtime` 能打开，仍由 `RealtimeView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5179/#/log` 能打开，仍由 `LogView.vue` 承载。
- `/alarm`：dev server `http://127.0.0.1:5179/#/alarm` 能打开，仍由 `AlarmView.vue` 承载。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，仍由 `NetworkView.vue` 承载。
- `/cloud`：dev server `http://127.0.0.1:5179/#/cloud` 能打开，仍由 `CloudView.vue` 承载。
- `/diagnostic`：dev server `http://127.0.0.1:5179/#/diagnostic` 能打开，页面文本显示“系统实时状态诊断”“运行完整诊断”“导出诊断包”“诊断摘要”“诊断项列表”“诊断详情增强”“缓存服务明细”“性能详情”“设备连接指标”“异常统计 Top”“最慢设备 Top”“最近异常”“运行设备状态”“查看原始诊断 JSON”；后端不可达时显示“无法连接采集服务，请检查服务地址和后端是否已启动”，页面未崩溃。
- `/diagnostic?deviceId=dev-1`：dev server 能打开，单设备状态 JSON 中带入 `deviceId: "dev-1"`，后端不可达时展示“单设备状态查询失败”，页面未崩溃。
- `/device`：dev server `http://127.0.0.1:5179/#/device` 能打开，仍显示 Legacy 设备管理页面；设备“运行状态”动作代码已改为 `/diagnostic?deviceId=...` query 跳转。
- `/collect`：dev server `http://127.0.0.1:5179/#/collect` 能打开，仍显示 Legacy 采集配置页面；代码检查确认 `LegacyConsoleView.vue` 已无 `loadOverview`、`getRuntimeStatus`、`getCloudReportMetrics`、`getPerformanceDetail` 等 Diagnostic 监控接口引用，只保留 `loadConfigSummary()`。
- 代码检查确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'diag'`、`runDiagnostic`、`downloadDiagnosticPackage`、`buildDiagnosticRaw`、`diagnosticRaw`、Diagnostic metrics state 和 Diagnostic 监控 API imports。
- 搜索确认 `src/views/legacy/` 下已无 `LegacyDiagnosticDetailPanel.vue`、`LegacyDeviceRuntimePanel.vue`、`diagnostic-detail-utils*`、`device-runtime-utils*`。
- 搜索确认 `src/views/ops/` 目录已删除。
- 搜索确认 `src/styles/legacy-console.css` 中已无 Diagnostic 专属 selector：`diagnostic-detail-panel`、`diagnostic-detail-cards`、`diagnostic-detail-grid`、`diagnostic-sub-card`、`diagnostic-connection-table`、`diagnostic-exception-table`、`device-runtime-panel`、`runtime-device-toolbar`、`runtime-summary-cards`、`runtime-device-table`。
- Vite dev server 使用 5179 完成 smoke check；用于 smoke check 的 5179 dev server 已停止。

## Phase 8 新增文件

- `collector-desktop/src/views/diagnostic/DiagnosticView.vue`
- `collector-desktop/src/features/diagnostic/components/DiagnosticDetailPanel.vue`
- `collector-desktop/src/features/diagnostic/components/DeviceRuntimePanel.vue`
- `collector-desktop/src/features/diagnostic/utils/diagnostic-detail-utils.ts`
- `collector-desktop/src/features/diagnostic/utils/diagnostic-detail-utils.test.ts`
- `collector-desktop/src/features/diagnostic/utils/device-runtime-utils.ts`
- `collector-desktop/src/features/diagnostic/utils/device-runtime-utils.test.ts`
- `collector-desktop/src/features/diagnostic/utils/diagnostic-utils.ts`
- `collector-desktop/src/features/diagnostic/utils/diagnostic-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/DiagnosticView-D9ULrdcb.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DiagnosticView-BijKmlcA.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/device-runtime-utils-BYamEGO2.js`（`build:web` 生成）

## Phase 8 修改文件

- `collector-desktop/src/components/device/DeviceConfigPanel.vue`
- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 8 删除文件

- `collector-desktop/src/views/legacy/LegacyDiagnosticDetailPanel.vue`
- `collector-desktop/src/views/legacy/LegacyDeviceRuntimePanel.vue`
- `collector-desktop/src/views/legacy/diagnostic-detail-utils.ts`
- `collector-desktop/src/views/legacy/diagnostic-detail-utils.test.ts`
- `collector-desktop/src/views/legacy/device-runtime-utils.ts`
- `collector-desktop/src/views/legacy/device-runtime-utils.test.ts`
- `collector-desktop/src/views/ops/ops-utils.ts`
- `collector-desktop/src/views/ops/ops-utils.test.ts`
- `collector-desktop/src/views/ops/`（空目录删除）

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 9：History 迁移完成内容

- `src/views/legacy/LegacyHistoryPanel.vue` 已移动并改名为 `src/views/history/HistoryView.vue`，承载原“历史趋势”页面，不保留第二套 Legacy History 实现。
- `/history` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `HistoryView.vue`；当前 `/dashboard`、`/realtime`、`/history`、`/log`、`/alarm`、`/network`、`/cloud`、`/diagnostic` 均为独立 View。
- `/device`、`/device/workbench`、`/collect`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移。
- `HistoryView.vue` 删除 `props.devices`、`props.selectedDeviceId`、`props.selectedPointRef` 和 `emit("selectDevice")`，不再通过 Legacy 父子 props 获取设备状态。
- `HistoryView.vue` 使用 `useDeviceStore()` 作为设备领域状态来源：设备下拉来自 `deviceStore.devices`，设备选择调用 `deviceStore.selectDevice(deviceId)`，进入页面时调用 `deviceStore.refresh()`。
- History 页面本地状态继续留在 View 内：`deviceId`、`pointRef`、`comparePointRefs`、`points`、`historyRows`、`comparePointRows`、`relatedAlarms`、`loading`、`limit`、`startTime`、`endTime`，没有新增 `history.store.ts`。
- `onMounted()` 现在按独立页面初始化：`appStore.initialize()` -> `deviceStore.refresh()` -> 应用 route query -> 确定设备 -> 加载点位。
- 普通 `/history` 不会默认请求历史数据；有可用设备时只选择当前或第一台设备并加载点位，保留用户点击“查询历史”的交互。
- 支持 `/history?deviceId=xxx&pointId=xxx`，并兼容 `/history?deviceId=xxx&pointRef=xxx`。query 指定点位能在当前设备点位中匹配时，自动执行一次历史查询。
- route query 后续变化通过 watcher 响应，且不反向 `router.replace()`，避免 query watcher 循环。
- 设备不存在时优先使用可用设备或保持未选择；点位不存在时不自动查询，保留用户手动选择能力，页面不崩溃。
- 手动切换设备时继续执行 `handleDeviceChange()` / `loadPoints()` 语义：更新 `deviceStore.selectedDeviceId`，清空旧点位、对比点位、历史数据、对比曲线和相关告警，再加载新设备点位。
- 点位配置继续使用 `getDevicePointsConfig(deviceId)`，响应解析继续兼容 `points`、`data`、`items`、`records`、`rows`。
- 历史查询继续使用 `getPointHistory(deviceId, pointRef, { startTs, endTs, limit })`，多点位对比仍按主 `pointRef` + `comparePointRefs` 分别请求，没有新增虚构批量接口。
- 相关告警继续使用 `getDeviceAlarmHistory(deviceId, { pointCode, pointId, startTs, endTs, limit: 20 })`，并复用 `features/alarm/utils/alarm-history-utils.ts` 中的 `normalizeAlarmHistoryRows()`，不依赖 `AlarmView` 状态。
- 趋势导出继续使用 `buildHistoryTrendExportText()`，导出内容保持 `deviceId`、`pointRef`、`pointLabel`、`generatedAt`、`series`、`relatedAlarms`，文件名仍为 `collector-history-{device}-{point}-{timestamp}.json` 形式。
- `src/views/legacy/history-trend-utils.ts` 与测试已移动到 `src/features/history/utils/history-trend-utils.ts` / `.test.ts`，保留 `buildHistoryTrendSeries()`、`buildHistoryTrendExportText()`、`buildHistoryTrendSummaryCards()`，未修改 SVG polyline 趋势算法。
- `HistoryRow` 与 `normalizeHistoryRows()` 已从 `src/views/runtime/runtime-utils.ts` 移动到 `src/features/history/utils/history-data-utils.ts`，对应“归一化历史数据响应”测试移动到 `history-data-utils.test.ts`。
- `src/views/runtime/runtime-utils.ts` 未整体迁移，继续只保留非 History 职责：`normalizeDeviceOptions()`、`buildSinglePointWritePayload()`、`buildBatchWriteTemplate()`、`buildCommandTemplate()`、`parseJsonOrThrow()`。
- 代码搜索确认 `runtime-utils.ts` 当前除自身测试外无生产引用；因其剩余函数属于 Control / Shadow / 后续运行操作边界，本 Phase 不删除。
- DeviceWorkbench -> History 联动改为 Router Query：`openWorkbenchHistory()` 现在跳转 `{ path: "/history", query: { deviceId, pointId: pointRef } }`，不再写 `historySelectedPointRef` 或 `switchModule("history")`。
- `LegacyConsoleView.vue` 已删除 `<LegacyHistoryPanel ... />`、`LegacyHistoryPanel` import 和 `historySelectedPointRef`。
- `src/router/route-names.ts` 中 `LegacyModuleKey` 已删除 `"history"`，`legacyModuleByRoutePath` / `routePathByLegacyModule` 也不再把 History 定义为 Legacy Module；`RouteNames.HISTORY` 保留为正式路由名。
- History 专属样式已从 `legacy-console.css` / `workbench.css` 移入 `HistoryView.vue` scoped style，包括历史查询栏、对比点位多选、摘要卡片、SVG 曲线、图例、统计行、相关告警表等规则。
- 公共样式 `section-heading`、`heading-title-line`、`heading-online`、`exact-page`、`exact-page-body`、`exact-toolbar`、`exact-diagnostic-cards`、`exact-diagnostic-card`、`surface-grid`、`surface-card`、`surface-card-head`、`exact-table-card`、`exact-table-title`、`runtime-table`、`json-view`、`empty-state` 继续保留公共样式，没有复制一整套公共 CSS。

## Phase 9 验证结果

执行时间：2026-08-31 09:40-09:44，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 32 个测试文件、177 个测试通过；新增 `src/features/history/utils/history-data-utils.test.ts`，迁移后的 `history-trend-utils.test.ts` 通过，`router.test.ts` 覆盖 `/history -> HistoryView` 与 `/history?deviceId=dev-1&pointId=temp-1` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `HistoryView` chunk |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 38 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 9 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 9 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5180/#/dashboard` 能打开，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5180/#/realtime` 能打开，仍由 `RealtimeView.vue` 承载。
- `/history`：dev server `http://127.0.0.1:5180/#/history` 能打开，页面文本显示“历史趋势”“设备”“点位”“开始”“结束”“条数”“对比点位”“查询历史”“导出趋势”“主曲线最新值”“采样总数”“数值范围”“点位历史曲线”“查询结果 JSON”“相关告警”“历史数据表”“暂无历史数据”；无后端数据时页面不崩溃，普通进入未自动执行无效历史查询。
- `/history?deviceId=dev-1&pointId=temp-001`：dev server 能打开，查询结果 JSON 中带入 `deviceId: "dev-1"`；因本地后端采集服务不可达，点位配置无法加载，`pointRef` 保持空并显示“无法连接采集服务，请检查服务地址和后端是否已启动”，页面未崩溃。
- `/history?deviceId=dev-1&pointRef=temp-001`：dev server 能打开，兼容别名 query，后端不可达时同样不崩溃。
- `/log`：dev server `http://127.0.0.1:5180/#/log` 能打开，仍由 `LogView.vue` 承载。
- `/alarm`：dev server `http://127.0.0.1:5180/#/alarm` 能打开，仍由 `AlarmView.vue` 承载。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，仍由 `NetworkView.vue` 承载。
- `/cloud`：dev server `http://127.0.0.1:5180/#/cloud` 能打开，仍由 `CloudView.vue` 承载。
- `/diagnostic`：dev server `http://127.0.0.1:5180/#/diagnostic` 能打开，仍由 `DiagnosticView.vue` 承载。
- `/device`：dev server `http://127.0.0.1:5180/#/device` 能打开，仍显示 Legacy 设备管理页面。
- `/collect`：dev server `http://127.0.0.1:5180/#/collect` 能打开，仍显示 Legacy 采集配置页面。
- 代码检查确认 `LegacyConsoleView.vue` 中已无 `activeModule === 'history'`、`LegacyHistoryPanel`、`historySelectedPointRef`，`openWorkbenchHistory()` 已改为 `/history?deviceId=...&pointId=...`。
- 搜索确认 `src/views/legacy/` 下已无 `LegacyHistoryPanel.vue`、`history-trend-utils.ts`、`history-trend-utils.test.ts`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 中已无 History 专属 selector：`legacy-history-panel`、`history-query-bar`、`history-filter-main`、`history-filter-field`、`history-filter-bottom`、`history-compare-field`、`history-compare-select`、`history-query-actions`、`history-summary-cards`、`history-chart`、`history-chart-dark`、`history-legend`、`history-stat-row`、`history-json-view`、`history-alarm-card`、`history-alarm-table`、`history-toolbar`。
- Vite dev server 使用 5180 完成 smoke check；用于 smoke check 的 5180 dev server 已停止。

## Phase 9 新增文件

- `collector-desktop/src/views/history/HistoryView.vue`
- `collector-desktop/src/features/history/utils/history-trend-utils.ts`
- `collector-desktop/src/features/history/utils/history-trend-utils.test.ts`
- `collector-desktop/src/features/history/utils/history-data-utils.ts`
- `collector-desktop/src/features/history/utils/history-data-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/HistoryView-D18EPo6x.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/HistoryView-CCRsoyj6.js`（`build:web` 生成）

## Phase 9 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/styles/workbench.css`
- `collector-desktop/src/views/history/HistoryView.vue`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/runtime/runtime-utils.ts`
- `collector-desktop/src/views/runtime/runtime-utils.test.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 9 删除文件

- `collector-desktop/src/views/legacy/LegacyHistoryPanel.vue`
- `collector-desktop/src/views/legacy/history-trend-utils.ts`
- `collector-desktop/src/views/legacy/history-trend-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 10：Collection 迁移完成内容

- 新增 `src/views/collection/CollectionView.vue`，承载原 `activeModule === "collect"` 的“数据采集配置”页面。
- `/collect` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `CollectionView.vue`；当前 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/collect` 均为独立 View。
- `/device`、`/device/workbench`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移 Device、DeviceWorkbench、Control、Shadow、LocalDeviceEditor 或 PointEditor。
- `CollectionView.vue` 使用 `useDeviceStore()` 提供设备领域状态：`deviceStore.devices`、`deviceStore.selectedDeviceId`、`deviceStore.selectedDevice`，进入页面和导入/同步/保存后按需调用 `deviceStore.refresh()`。
- `CollectionView.vue` 使用 `useProtocolStore()` 提供协议领域状态：模板使用 `protocolStore.protocols`，进入页面调用 `protocolStore.refresh()`，不再维护长期 `protocols = ref([])` 或直接长期调用 `listProtocols()`。
- `configSummary` 继续作为 Collection 页面聚合快照留在 `CollectionView.vue`，通过 `loadConfigSummary()` 调用 `getConfigSummary()`；没有新增 `collection.store.ts`。
- 进入 `/collect` 的初始化只执行 `appStore.initialize()`，并行刷新 `deviceStore.refresh()`、`protocolStore.refresh()`、`loadConfigSummary()`；`ConfigOpsPanel` 自身读取同步状态 `getConfigSyncStatus()`。没有请求 Diagnostic / Cloud / History / Alarm / Network 的监控接口。
- `src/views/legacy/LegacyConfigOpsPanel.vue` 已迁移并改名为 `src/features/collection/components/ConfigOpsPanel.vue`，CollectionView 不再 import `views/legacy/*`。
- `ConfigOpsPanel.vue` 保留低耦合 props / emit：`devices`、`selectedDeviceId`、`imported`、`synced`；组件自身不直接依赖整个 `deviceStore`。
- `ConfigOpsPanel` 保留配置导出、下载 JSON、配置导入、`reloadAfterImport`、全量同步、局部同步、同步类型、目标设备、同步状态和同步结果。
- `ConfigOpsPanel` 的 `imported` / `synced` 事件现在只触发 `refreshCollectionContext()`，刷新 Collection 真正依赖的设备、协议和配置摘要，不再调用 Legacy `refreshAll()` 或其它页面 loader。
- 通用配置导入导出函数已从 `src/views/legacy/config-utils.ts` 迁移到 `src/features/config/utils/config-transfer-utils.ts`，包括 `normalizeConfigExportText()`、`parseConfigImportText()`、`buildConfigImportRequest()`、`countConfigImportBundles()`、`buildConfigExportFilename()`。
- Collection 配置同步函数已迁移到 `src/features/collection/utils/config-sync-utils.ts`，包括 `CONFIG_SYNC_TYPES` 和 `normalizeSyncStatusItems()`。
- `buildDeviceListEmptyText()` 继续留在 `src/views/legacy/config-utils.ts`，该文件已缩减为 Device 列表过渡 helper，没有把 Device 专属空态错误放入 Collection feature。
- `config-utils.test.ts` 已按职责拆分：通用导入导出测试迁入 `features/config/utils/config-transfer-utils.test.ts`，同步状态测试迁入 `features/collection/utils/config-sync-utils.test.ts`，Device 空态测试保留在 `views/legacy/config-utils.test.ts`。
- 配置导入导出后端 API 保持不变：继续使用 `exportConfigs()`、`importConfigs()`、`reloadAfterImport`，并继续兼容 `{ "bundles": [...] }`、bundle 数组和单个 bundle JSON。
- 配置同步 API 保持不变：继续使用 `triggerFullConfigSync()`、`triggerPartialConfigSync()`、`getConfigSyncStatus()`，同步类型仍为 `device`、`points`、`connection`、`collection`、`all`。
- 局部同步目标设备逻辑保持：需要设备的同步类型优先使用 `syncDeviceId`，其次使用 `props.selectedDeviceId`，否则为 `undefined`；不强制所有局部同步必须指定设备。
- `CollectionView.vue` 支持 `/collect?deviceId=xxx`，query 变化时会重新应用设备上下文；设备存在时选择该设备，设备列表为空时保留 query 上下文，设备不存在且存在其它设备时回退到可用设备，不自动执行同步操作。
- Legacy Device 的 `openDeviceDiff()` 已改为 Router Query 跳转 `{ path: "/collect", query: { deviceId } }`，保留“已切换到采集配置，可查看当前设备相关配置”提示，不再 `switchModule("collect")`。
- `src/router/route-names.ts` 中 `LegacyModuleKey` 已删除 `"collect"`，`legacyModuleByRoutePath` / `routePathByLegacyModule` 也不再把 `/collect` 定义为 Legacy Module；`RouteNames.COLLECTION` 保留为正式路由名。
- `LegacyConsoleView.vue` 已删除 Collection template、`LegacyConfigOpsPanel` import、`configSummary`、`collectionSummaryItems`、`selectedProtocol`、`loadConfigSummary()`、`protocolDefaultPort()`、`protocolMode()`、`protocolCapability()`、`openProtocolConfig()`。
- `loadActiveLegacyModule()` 不再处理 `collect`，当前只为剩余 Workbench 入口加载选中设备实时数据。
- Legacy 的刷新入口已从 `refreshAll()` 收敛为 `refreshDeviceContext()`，只刷新 `loadProtocols()` 与 `loadDevices()`，不再加载已迁出的 Collection `configSummary`。
- Collection 协议 Schema 展示已从错误的 `<section><summary>` 改为正确的 `<details><summary>`，保持视觉和功能不变，并消除 Collection 区域的 `<summary>` 非 `<details>` 子元素 warning。
- Collection 专属样式已从全局 legacy CSS 中退出：`exact-config-grid` 和 Collection 使用的 `capability-badge` 改为 `CollectionView.vue` / `ConfigOpsPanel.vue` scoped style；ConfigOpsPanel 的导出视图、导入文本域、同步表单和同步状态网格也收口到组件 scoped style。
- 公共样式 `section-heading`、`heading-title-line`、`heading-online`、`heading-actions`、`exact-page`、`exact-page-body`、`exact-surface`、`exact-surface-head`、`exact-table-card`、`exact-table-title`、`exact-config-item`、`exact-json-panel`、`json-view`、`surface-grid`、`surface-card`、`form-grid`、`inline-actions` 继续保留公共样式，没有复制一整套公共 CSS。

## Phase 10 验证结果

执行时间：2026-08-31 10:22-10:23，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 34 个测试文件、179 个测试通过；新增 `src/features/config/utils/config-transfer-utils.test.ts`、`src/features/collection/utils/config-sync-utils.test.ts`，`router.test.ts` 覆盖 `/collect -> CollectionView` 与 `/collect?deviceId=dev-1` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `CollectionView` 和 `config-transfer-utils` chunks |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 41 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 10 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 10 路由与页面检查结果

- `/dashboard`：dev server `http://127.0.0.1:5181/#/dashboard` 能打开，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server `http://127.0.0.1:5181/#/realtime` 能打开，仍由 `RealtimeView.vue` 承载。
- `/history`：dev server `http://127.0.0.1:5181/#/history` 能打开，仍由 `HistoryView.vue` 承载。
- `/alarm`：dev server `http://127.0.0.1:5181/#/alarm` 能打开，仍由 `AlarmView.vue` 承载。
- `/log`：dev server `http://127.0.0.1:5181/#/log` 能打开，仍由 `LogView.vue` 承载。
- `/network?target=127.0.0.1&port=502`：dev server 能打开，仍由 `NetworkView.vue` 承载。
- `/cloud`：dev server `http://127.0.0.1:5181/#/cloud` 能打开，仍由 `CloudView.vue` 承载。
- `/diagnostic`：dev server `http://127.0.0.1:5181/#/diagnostic` 能打开，仍由 `DiagnosticView.vue` 承载。
- `/collect`：dev server `http://127.0.0.1:5181/#/collect` 能打开，页面文本显示“数据采集配置”“刷新概览”“全局采集配置”“设备配置”“点位总数”“连接配置”“配置来源”“配置导入导出与同步”“配置导出”“下载 JSON”“配置导入”“导入后刷新设备”“全量同步”“局部同步”“同步类型”“目标设备”“同步状态”“协议配置列表”“协议名称”“规范编码”“默认端口”“采集方式”“能力状态”“操作”。后端不可达时显示“无法连接采集服务，请检查服务地址和后端是否已启动”，页面未崩溃。
- `/collect?deviceId=dev-1`：dev server 能打开，后端不可达/无设备时页面不崩溃，不自动执行同步操作。
- `/device`：dev server `http://127.0.0.1:5181/#/device` 能打开，仍显示 Legacy 设备管理页面。
- 代码检查确认 `CollectionView.vue` / `ConfigOpsPanel.vue` 没有引用 Diagnostic / Cloud / History / Alarm / Network 监控 API；`LegacyConsoleView.vue` 已无 `activeModule === 'collect'`、`LegacyConfigOpsPanel`、`loadConfigSummary()`、`configSummary`、`collectionSummaryItems`、`selectedProtocol`。
- 搜索确认 `src/views/legacy/` 下已无 `LegacyConfigOpsPanel.vue`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 中已无 Collection 专属 selector：`exact-global-config`、`exact-config-grid`、`config-ops-panel`、`config-export-view`、`config-import-textarea`、`config-sync-form`、`config-sync-status-grid`、`capability-badge`。
- 搜索 `<summary>` 确认剩余 summary 均为 `<details>` 子元素；dev server 输出未再出现 Collection 旧 `<summary>` HTML warning。
- Vite dev server 使用 5181 完成 smoke check；用于 smoke check 的 5181 dev server 已停止。

## Phase 10 新增文件

- `collector-desktop/src/views/collection/CollectionView.vue`
- `collector-desktop/src/features/collection/components/ConfigOpsPanel.vue`
- `collector-desktop/src/features/collection/utils/config-sync-utils.ts`
- `collector-desktop/src/features/collection/utils/config-sync-utils.test.ts`
- `collector-desktop/src/features/config/utils/config-transfer-utils.ts`
- `collector-desktop/src/features/config/utils/config-transfer-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/CollectionView-BDtsIuwO.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/CollectionView-CjcJC7SP.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/config-transfer-utils-CxKDpqe5.js`（`build:web` 生成）

## Phase 10 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/legacy/config-utils.ts`
- `collector-desktop/src/views/legacy/config-utils.test.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 10 删除文件

- `collector-desktop/src/views/legacy/LegacyConfigOpsPanel.vue`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 11：Device List 迁移完成内容

- 新增 `src/views/device/DeviceListView.vue`，承载原 `activeModule === "device"` 的“设备管理”页面。
- `/device` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `DeviceListView.vue`；当前 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/device` 均为独立 View。
- `/device/workbench`、`/control`、`/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移 DeviceWorkbench、Control、Shadow、DeviceConfigPanel、LocalDeviceEditor 或 PointEditor。
- `DeviceListView.vue` 使用 `useDeviceStore()` 作为设备领域状态唯一来源：模板和筛选基于 `deviceStore.devices`，选中状态使用 `deviceStore.selectedDeviceId`，加载/错误使用 `deviceStore.loading` 与 `deviceStore.error`。
- `DeviceListView.vue` 不再维护 Legacy 设备副本：没有 `devices = ref([])`、`deviceRuntimeMap = ref({})`、`selectedDeviceId = ref("")`、`deviceLoading = ref(false)`、`deviceLoadError = ref("")`。
- 设备展示优先使用 `DeviceViewModel` 字段：设备 ID 使用 `device.normalizedId`，设备名称使用 `device.displayName`，协议显示使用 `device.displayProtocol`，状态展示使用 `device.status`。
- Device List 页面局部 UI 状态仍保留在 View 内：`deviceKeyword`、`protocolFilter`、`statusFilter`、`localEditorVisible`、`editingBundle`、`configImportInput`、`configFileExporting`、`configFileImporting`、`deviceConfigOperatingId`。
- `filteredDevices` 作为页面 computed 基于 `deviceStore.devices` 计算，搜索条件不进入 Pinia。
- `DeviceListView.vue` 使用 `useProtocolStore()`，协议筛选下拉和 `LocalDeviceEditor` 均使用 `protocolStore.protocols`；进入页面调用 `protocolStore.refresh()`，不维护长期 `protocols = ref([])`，不直接长期调用 `listProtocols()`。
- 页面初始化按边界执行：`await appStore.initialize()` 后并行 `deviceStore.refresh()` 与 `protocolStore.refresh()`，再应用 `route.query.deviceId`；没有请求 Realtime、Diagnostic、Alarm、Collection、Cloud 等其它页面数据。
- 支持 `/device?deviceId=xxx`：当设备列表中存在该设备时调用 `deviceStore.selectDevice(deviceId)`，设备不存在或后端不可达时保持安全空状态，不反向修改 query。
- 设备启动改为 `await deviceStore.startSmart(deviceId)`，复用 Store 内部 `resolveDeviceStartMode()`，保持本地设备走 `/start-local`、远端设备走 `/start` 的 smart start 语义。
- 设备停止改为 `await deviceStore.stop(deviceId)`，不在 DeviceListView 直接调用 `stopDevice()`。
- `deviceStore` 新增 `syncRemoteDevices()`，明确保留旧“同步远端配置”的完整语义：`triggerFullConfigSync()` -> `reloadDevices()` -> `refresh()`；DeviceListView 的“同步远端配置”使用该 Store action。
- 删除本地设备继续由 View 使用 `ElMessageBox.confirm()` 承担确认弹窗，再调用 `deviceStore.deleteLocal(deviceId)`；文案继续强调“不会删除远端配置”。
- 设备配置刷新 / 清理缓存继续由 `operateDeviceConfig(deviceId, type)` 调用 `refreshDeviceConfig()` / `clearDeviceConfig()`，保留确认弹窗、操作 loading、响应归一化和中文提示；成功后调用 `deviceStore.refresh()`，不再调用 Legacy `loadDevices()`。
- `src/views/legacy/device-config-actions-utils.ts` 已迁移到 `src/features/device/utils/device-config-actions-utils.ts`，测试迁移到 `src/features/device/utils/device-config-actions-utils.test.ts`。
- `buildDeviceListEmptyText()` 已迁移到 `src/features/device/utils/device-list-utils.ts`，测试迁移到 `src/features/device/utils/device-list-utils.test.ts`。
- `src/views/legacy/config-utils.ts` 与 `src/views/legacy/config-utils.test.ts` 已删除，没有保留空壳 legacy util。
- `DeviceListView.vue` 统一复用 `stores/device.store.ts` export 的 `isLocalDevice()`，不再定义第三套本地设备判断。
- 状态展示只保留小范围 `localizeDeviceStatus()` / `statusBadgeClass()`，优先使用 `device.status`；状态文案继续支持“在线 / 离线 / 异常 / 已停止 / 未知”。
- 连接地址 helper 保持简单：优先 `ipAddress + port`，小范围兼容 `host` / `url`，未重构连接地址模型。
- 新增本地设备继续打开 `LocalDeviceEditor`，`editingBundle = null`，保存后关闭弹窗并刷新 `deviceStore` / `protocolStore`。
- 本地设备编辑继续通过 `getLocalDevice(deviceId)` -> `extractLocalDeviceBundle()` -> `editingBundle` -> `LocalDeviceEditor`，未修改 `LocalDeviceEditor` 或 `LocalDeviceBundle` DTO。
- 远端设备“编辑”和“配置”均跳转 `/device/workbench?deviceId=xxx`，保持进入 DeviceWorkbench config 分区的旧行为，不改成编辑弹窗。
- “控制”跳转 `/control?deviceId=xxx`，“影子”跳转 `/shadow?deviceId=xxx`。
- “差异”保持旧产品行为：跳转 `/collect?deviceId=xxx`，不因 `deviceStore.loadDiff()` 存在而新增 Diff Dialog 或 Diff 页面。
- “运行状态”跳转 `/diagnostic?deviceId=xxx`，“告警历史”跳转 `/alarm?deviceId=xxx`，不共享其它页面 state。
- Legacy Workbench 增加最小 route query 过渡逻辑：`applyRouteDeviceQuery()` 读取 `route.query.deviceId` 并写入 Legacy 局部 `selectedDeviceId`；watch `route.query.deviceId` 支持同一路由内设备切换，且只做 Query -> Legacy state 单向同步。
- 直接打开 `/device/workbench?deviceId=dev-1`、`/control?deviceId=dev-1`、`/shadow?deviceId=dev-1` 时，Legacy Workbench 能显示 `dev-1` 上下文；后端无设备或不可达时不崩溃。
- Legacy Workbench “返回列表”已从 `switchModule("device")` 改为 `router.push({ path: "/device", query: selectedDeviceId ? { deviceId } : {} })`，返回后由新 DeviceListView 识别 query 并选中设备。
- `src/router/route-names.ts` 中 `LegacyModuleKey` 已删除 `"device"`，`legacyModuleByRoutePath` 与 `routePathByLegacyModule` 也删除 `/device` 的 Legacy 映射；`RouteNames.DEVICE` 保留正式路由名。
- `LegacyConsoleView.vue` 已删除 Device List template，以及只属于 Device List 的 `deviceKeyword`、`protocolFilter`、`statusFilter`、`filteredDevices`、`deviceListEmptyText`、`deviceLoading`、`deviceLoadError`、`localEditorVisible`、`editingBundle`、`configImportInput`、`configFileExporting`、`configFileImporting`、`syncDevices()`、`deleteLocal()`、`openLocalEditor()`、`handleLocalSaved()`、`exportDeviceConfigData()`、`openConfigImportFile()`、`handleConfigImportFile()`、`editDevice()`、`openDeviceDiff()`、`openDeviceOperation()`、本地 `isLocalDevice()`、`localizeDeviceStatus()`、`statusBadgeClass()`。
- `LegacyConsoleView.vue` 中的 `protocols` / `loadProtocols()` / `listProtocols()` 已删除；迁完 Device List 后 Legacy Workbench 不再真实使用 protocols。
- `LegacyConsoleView.vue` 继续保留 Workbench 必需的 `devices`、`deviceRuntimeMap`、`selectedDeviceId`、`selectedDevice`、`selectedDeviceView`、`selectedRuntimeSnapshot`、`selectedRealtimeRows`、`loadDevices()`、`loadSelectedRealtime()`、`startSelectedDevice()`、`stopSelectedDevice()`、`operateDeviceConfig()`、`openSelectedDeviceRuntimeStatus()`、`openSelectedDeviceAlarmHistory()`、`openWorkbenchHistory()`、`openWorkbenchRealtime()`、`deviceAddress()`。
- Device List 专属 CSS 已从 `src/styles/legacy-console.css` 迁入 `DeviceListView.vue` scoped style：`exact-device-list`、`exact-device-card`、`exact-device-main`、`exact-device-meta`、`exact-device-actions` 及其响应式规则。
- `workbench.css` 中的 `local-editor`、`local-device-panel`、`device-operation-panel`、`local-editor-title`、`local-editor-tabs`、`local-editor-layout`、`device-operation-rail`、`device-operation-body`、`local-checklist`、`local-editor-stat` 等 Workbench / LocalDeviceEditor 样式未提前迁移。
- 公共样式 `section-heading`、`heading-title-line`、`heading-online`、`heading-actions`、`exact-page`、`exact-page-body`、`exact-toolbar`、`exact-toolbar-group`、`exact-toolbar-filters`、`status-badge`、`exact-empty` 继续保留公共样式。

## Phase 11 验证结果

执行时间：2026-08-31 11:00-11:01，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm test` | 通过 | 34 个测试文件、182 个测试通过；新增 `features/device/utils/device-list-utils.test.ts`，迁移后的 `device-config-actions-utils.test.ts` 通过，`router.test.ts` 覆盖 `/device -> DeviceListView`、`/device?deviceId=dev-1`、`/device/workbench?deviceId=dev-1`、`/control?deviceId=dev-1`、`/shadow?deviceId=dev-1` |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `DeviceListView` chunk |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 45 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 11 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 11 路由与页面检查结果

- `/device`：dev server `http://127.0.0.1:5182/#/device` 能打开，页面文本显示“设备管理”“0 台设备”“刷新列表”“导出配置数据”“导入配置数据”“新增本地设备”“全部协议”“全部状态”“在线”“离线”“异常”“同步远端配置”。后端不可达时显示“设备配置加载失败：无法连接采集服务，请检查服务地址和后端是否已启动”，页面未崩溃。
- `/device?deviceId=dev-1`：dev server 能打开，后端不可达/无设备时页面不崩溃，不反向修改 query。
- `/device/workbench?deviceId=dev-1`：dev server 能打开，Legacy Workbench 显示 `dev-1`，未先经过 DeviceList 也能接收 query；后端不可达时显示安全空状态。
- `/control?deviceId=dev-1`：dev server 能打开，手动控制分区显示设备上下文 `dev-1`，说明 Control 过渡入口能接收 query。
- `/shadow?deviceId=dev-1`：dev server 能打开，设备影子分区显示设备上下文 `dev-1`，说明 Shadow 过渡入口能接收 query。
- `/collect?deviceId=dev-1`：dev server 能打开，仍由 `CollectionView.vue` 承载，后端不可达时不崩溃。
- `/diagnostic?deviceId=dev-1`：dev server 能打开，单设备状态 JSON 带入 `deviceId: "dev-1"`，后端不可达时显示“单设备状态查询失败”。
- `/alarm?deviceId=dev-1`：dev server 能打开，页面显示“设备 dev-1 · 0 条 · 已确认 0”，后端不可达时不崩溃。
- `/dashboard`：dev server 能打开，仍由 `DashboardView.vue` 承载。
- `/realtime`：dev server 能打开，仍由 `RealtimeView.vue` 承载。
- `/history`：dev server 能打开，仍由 `HistoryView.vue` 承载。
- 额外 smoke：`/cloud`、`/log`、`/network?target=127.0.0.1&port=502` 均能打开，仍由对应独立 View 承载。
- 代码检查确认 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/device` 均直接解析到独立 View；`/device/workbench`、`/control`、`/shadow` 仍解析到 `LegacyConsoleView.vue`。
- 代码检查确认 `DeviceListView.vue` 没有 `listProtocols`、`startDevice`、`stopDevice` 直接调用；使用 `deviceStore` 27 处、`protocolStore` 3 处。
- 代码检查确认 `LegacyConsoleView.vue` 已无 `activeModule === 'device'`、Device List 专属状态/函数、`LocalDeviceEditor`、`listProtocols`、`protocols`。
- 搜索确认 `src/views/legacy/` 下已无 `config-utils*`、`device-config-actions-utils*`。
- 搜索确认 `src/styles/legacy-console.css` 与 `src/styles/workbench.css` 中已无 Device List 专属 selector：`exact-device-list`、`exact-device-card`、`exact-device-main`、`exact-device-meta`、`exact-device-actions`。
- Vite dev server 使用 5182 完成 smoke check；用于 smoke check 的 5182 dev server 已停止。

## Phase 11 新增文件

- `collector-desktop/src/views/device/DeviceListView.vue`
- `collector-desktop/src/features/device/utils/device-config-actions-utils.ts`
- `collector-desktop/src/features/device/utils/device-config-actions-utils.test.ts`
- `collector-desktop/src/features/device/utils/device-list-utils.ts`
- `collector-desktop/src/features/device/utils/device-list-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/DeviceListView-CjH3v4r8.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DeviceListView--S1lRRz4.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/device-config-actions-utils-BQm5k-Fc.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/protocol.store-BDoIQlUI.js`（`build:web` 生成）

## Phase 11 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/stores/device.store.ts`
- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 11 删除文件

- `collector-desktop/src/views/legacy/config-utils.ts`
- `collector-desktop/src/views/legacy/config-utils.test.ts`
- `collector-desktop/src/views/legacy/device-config-actions-utils.ts`
- `collector-desktop/src/views/legacy/device-config-actions-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 12：Device Workbench 迁移完成内容

- 新增 `src/views/device/DeviceWorkbenchView.vue`，承载原 `/device/workbench` 的设备配置工作台外层宿主。
- `/device/workbench` 路由已从 `LegacyConsoleView.vue` 改为 lazy import `DeviceWorkbenchView.vue`；最终结构为 `AppShell -> AppTopbar -> RouterView -> DeviceWorkbenchView.vue`。
- `/control` 与 `/shadow` 继续保持 `LegacyConsoleView.vue` 过渡状态，未提前迁移 Control、Shadow、LegacyManualShadowPanels、LocalDeviceEditor、PointEditor 或 DeviceConfigPanel 内部大结构。
- `DeviceWorkbenchView.vue` 继续组合 `<DeviceConfigPanel />` 作为主体，未复制、重写或拆分 `DeviceConfigPanel.vue` 内部的设备基础信息、运行控制、连接检查、协议连接配置、配置差异、点位列表、实时数据、告警、日志和 PointEditor。
- `DeviceWorkbenchView.vue` 使用 `useDeviceStore()` 作为设备领域状态来源：设备列表来自 `deviceStore.devices`，运行快照来自 `deviceStore.runtimeMap`，当前选择来自 `deviceStore.selectedDeviceId` 与 `deviceStore.selectedDevice`。
- `DeviceWorkbenchView.vue` 没有维护第二套 `const devices = ref([])`、`deviceRuntimeMap` 或 `const selectedDeviceId = ref("")`。
- `DeviceConfigPanel` 直接接收 `:device="deviceStore.selectedDevice"`，没有在新 View 中调用 `normalizeDeviceViewModelWithRuntimeStatus()` 构建第二套 `selectedDeviceView`。
- 页面初始化按独立路由执行：`appStore.initialize()` -> `deviceStore.refresh()` -> `applyRouteDevice()` -> `loadRealtimePreview()`；没有额外手工调用 `getConfigDevices()` / `getDeviceRuntime()`。
- 完整支持 `/device/workbench?deviceId=dev-1`：route query 单向写入 Store；设备存在时选择该设备，设备不存在但设备列表存在时回退第一台设备，设备列表为空时保留 query 上下文并让 `DeviceConfigPanel` 进入 `device = null` 空状态。
- route query 后续变化由 `watch(route.query.deviceId)` 处理，只做 `Route Query -> deviceStore`，不反向 `router.replace()` 或 `router.push()`，避免 query/store 循环。
- 顶部设备信息改用 Store：设备名称优先 `deviceStore.selectedDevice.displayName`，deviceId 优先 `normalizedId` / `deviceStore.selectedDeviceId`，协议优先 `displayProtocol`，采集周期来自 `collectionInterval`。
- 顶部地址继续使用页面级简单 `deviceAddress()`：优先 `ipAddress/host + port`，小范围兼容 `url`，未新增设备详情 API。
- 运行状态摘要改为基于 `deviceStore.selectedDevice.runtime` 与 `deviceStore.runtimeMap[selectedDeviceId]` 计算；连接状态同时参考 runtime 与页面级实时预览行数。
- 顶部和左侧“实时点位 N 个”使用页面局部 `realtimePreviewRows`，通过 `getDeviceRealtimeData(deviceId)` + `normalizeRealtimeRows()` 生成，只服务 Workbench 外层摘要，不放入 Pinia，也不访问 `DeviceConfigPanel` 内部 ref。
- `DeviceConfigPanel` 的 `start(deviceId)` 事件由 `DeviceWorkbenchView` 接收后调用 `deviceStore.startSmart(deviceId)`，保持本地设备走 `startLocalDevice`、远端设备走 `startDevice` 的语义；成功后刷新实时预览。
- `DeviceConfigPanel` 的 `stop(deviceId)` 事件由 `DeviceWorkbenchView` 接收后调用 `deviceStore.stop(deviceId)`，成功后刷新实时预览；新 View 不直接调用 `stopDevice()`。
- 外层左侧栏“刷新配置 / 清理缓存”继续使用 `refreshDeviceConfig()` / `clearDeviceConfig()`，并复用 `features/device/utils/device-config-actions-utils.ts` 中的 `DEVICE_CONFIG_ACTIONS`、`normalizeDeviceConfigActionResult()`、`buildDeviceConfigActionMessage()`。
- `deviceConfigOperatingId` 继续作为页面局部按钮 loading 状态保存在 `DeviceWorkbenchView.vue`，未进入 `deviceStore`。
- 配置缓存刷新 / 清理成功后执行 `deviceStore.refresh()`、重新应用 route query，并刷新 realtime preview。
- “返回列表”继续走 Router Query：`/device?deviceId=当前设备`，没有回退到 `switchModule("device")`。
- “运行状态”继续跳转 `/diagnostic?deviceId=xxx`；“告警历史”继续跳转 `/alarm?deviceId=xxx`，不直接调用 DiagnosticView / AlarmView，也不共享它们的页面状态。
- `DeviceConfigPanel` 的 `open-history` 事件由 `DeviceWorkbenchView` 跳转 `/history?deviceId=target.deviceId&pointId=target.pointRef`，并保留中文提示。
- `DeviceConfigPanel` 的 `open-realtime` 事件由 `DeviceWorkbenchView` 跳转 `/realtime?deviceId=target.deviceId&pointId=target.pointRef`，并保留中文提示。
- 新 Workbench 顶部三项外层 Tab 改为路由导航：“工作台”当前 active，“批量和协议命令”跳 `/control?deviceId=xxx`，“设备影子”跳 `/shadow?deviceId=xxx`；新 View 不维护 `workbenchTab = "control" / "shadow"`。
- `LegacyConsoleView.vue` 中 Control / Shadow 顶部三项 Tab 做最小兼容修改：Tab active 状态由 `resolveWorkbenchRouteTab(route.path)` 推导，点击“工作台”跳 `/device/workbench?deviceId=xxx`，点击“批量和协议命令”跳 `/control?deviceId=xxx`，点击“设备影子”跳 `/shadow?deviceId=xxx`。
- `LegacyConsoleView.vue` 不再 import / render `DeviceConfigPanel`，也不再负责 `/device/workbench`。
- `LegacyConsoleView.vue` 删除只属于 config Workbench 的 `startSelectedDevice()`、`stopSelectedDevice()`、`resetSelectedAdaptive()`、`openWorkbenchHistory()`、`openWorkbenchRealtime()` 以及 `resetAdaptiveConfig`、`startDevice`、`startLocalDevice`、`stopDevice`、`resolveDeviceStartMode` imports。
- `LegacyConsoleView.vue` 保留 Control / Shadow 仍需要的过渡外壳、当前设备上下文、`ManualShadowPanels`、`devices`、`deviceRuntimeMap`、`selectedDeviceId`、`selectedDevice`、`selectedDeviceView`、`selectedRuntimeSnapshot`、`selectedRealtimeRows`、`loadDevices()`、`loadSelectedRealtime()`、`operateDeviceConfig()`、运行状态/告警历史跳转和 `deviceAddress()`。
- `src/router/route-names.ts` 删除 `LegacyModuleKey`、`resolveLegacyModuleByRoutePath()` 和 `routePathForLegacyModule()`，避免未知 Legacy route 继续 fallback 到 `overview` / Dashboard。
- `src/router/route-names.ts` 新增并保留最小路由 helper：`WorkbenchRouteTab = "control" | "shadow"`、`WorkbenchNavigationTab = "config" | "control" | "shadow"`、`resolveWorkbenchRouteTab()`、`routePathForWorkbenchTab()`。
- `src/router/router.test.ts` 更新覆盖：`/device -> DeviceListView`、`/device/workbench -> DeviceWorkbenchView`、`/device/workbench?deviceId=dev-1 -> DeviceWorkbenchView`、`/control?deviceId=dev-1 -> LegacyConsoleView`、`/shadow?deviceId=dev-1 -> LegacyConsoleView`，以及 Workbench 路由 helper 不再把 `/device/workbench`、`/dashboard`、`/device` 解析为 Legacy fallback。
- 本 Phase 未迁移 Workbench CSS 到 scoped style：当前 `deviceOperationPanel` 外壳、外层 Tab、左侧 rail、`device-config-workbench-pane`、`manual-shadow-pane`、`device-operation-body`、DeviceConfigPanel 内部和 ManualShadowPanels 仍共享同一套 `workbench.css` 全局样式，避免复制第二套 CSS。
- `LegacyConsoleView.vue` 保留自身 `legacy-page-host` scoped style；`DeviceWorkbenchView.vue` 没有新增重复 scoped CSS。

## Phase 12 验证结果

执行时间：2026-08-31 11:25-11:31，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 34 个测试文件、184 个测试通过；`router.test.ts` 新增/更新 `/device/workbench -> DeviceWorkbenchView`、Control/Shadow Legacy 和 Workbench route helper 断言 |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成独立 `DeviceWorkbenchView` chunk，`LegacyConsoleView` chunk 明显收敛 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 46 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 12 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。一次额外尝试 `npm test -- --runInBand` 因 Vitest 不支持 `--runInBand` 失败，随后已按项目标准命令重新执行 `npm test` 并通过。

## Phase 12 路由与页面检查结果

- `/device`：dev server `http://127.0.0.1:5183/#/device` 能打开，仍由 `DeviceListView.vue` 承载，后端不可达时显示“设备配置加载失败：无法连接采集服务，请检查服务地址和后端是否已启动”。
- `/device/workbench`：dev server 能打开，页面文本显示“设备配置”“请选择设备”“返回列表”“01 工作台”“02 批量和协议命令”“03 设备影子”“当前设备”“刷新配置”“清理缓存”“运行状态”“告警历史”，并显示 `DeviceConfigPanel` 的空状态“请从左侧设备树或设备列表选择设备”。
- `/device/workbench?deviceId=dev-1`：dev server 能打开，顶部和左侧上下文显示 `dev-1`，后端不可达/无设备时 `DeviceConfigPanel` 保持空状态，不崩溃，不反向修改 query。
- `/control?deviceId=dev-1`：dev server 能打开，仍由 `LegacyConsoleView.vue + LegacyManualShadowPanels.vue` 承载，显示 `dev-1` 上下文和“手动控制”主体。
- `/shadow?deviceId=dev-1`：dev server 能打开，仍由 `LegacyConsoleView.vue + LegacyManualShadowPanels.vue` 承载，显示 `dev-1` 上下文和“设备影子”主体。
- `/history?deviceId=dev-1&pointId=p1`：dev server 能打开，仍由 `HistoryView.vue` 承载，查询结果 JSON 带入 `deviceId: "dev-1"`，后端不可达时不崩溃。
- `/realtime?deviceId=dev-1&pointId=p1`：dev server 能打开，仍由 `RealtimeView.vue` 承载，后端不可达时不崩溃。
- `/diagnostic?deviceId=dev-1`：dev server 能打开，仍由 `DiagnosticView.vue` 承载，单设备状态 JSON 带入 `deviceId: "dev-1"`，后端不可达时显示“单设备状态查询失败”。
- `/alarm?deviceId=dev-1`：dev server 能打开，仍由 `AlarmView.vue` 承载，页面显示“设备 dev-1 · 0 条 · 已确认 0”，后端不可达时不崩溃。
- 额外 smoke：`/dashboard`、`/cloud`、`/log`、`/network?target=127.0.0.1&port=502` 均能打开，仍由对应独立 View 承载。
- 代码检查确认 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/device`、`/device/workbench` 均直接解析到独立 View；`/control`、`/shadow` 仍解析到 `LegacyConsoleView.vue`。
- 代码检查确认 `LegacyConsoleView.vue` 已无 `DeviceConfigPanel`、`activeModule`、`resetSelectedAdaptive`、`openWorkbenchHistory`、`openWorkbenchRealtime`、`startSelectedDevice`、`stopSelectedDevice`、`resetAdaptiveConfig`、`startDevice`、`startLocalDevice`、`stopDevice`。
- 代码检查确认 `DeviceWorkbenchView.vue` 没有 `normalizeDeviceViewModelWithRuntimeStatus`、`const devices = ref`、`deviceRuntimeMap` 或 `const selectedDeviceId = ref`。
- 受当前 in-app preview session 限制，`drive_preview` 无法执行真实点击；Tab 点击链路通过源码 `openWorkbenchTab()` / `routePathForWorkbenchTab()`、router test 和直接打开目标 URL 验证 URL 与显示内容一致。
- Vite dev server 使用 5183 完成 smoke check；用于 smoke check 的 5183 dev server 已停止。

## Phase 12 新增文件

- `collector-desktop/src/views/device/DeviceWorkbenchView.vue`
- `collector-boot/src/main/resources/static/desktop/assets/DeviceWorkbenchView-DigYNbxw.js`（`build:web` 生成）

## Phase 12 修改文件

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/route-names.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 12 删除文件

- 无源码文件删除。
- `build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 13：Local Device Editor 整理与迁移完成内容

- `src/components/device/LocalDeviceEditor.vue` 已正式移动到 `src/features/device/components/LocalDeviceEditor.vue`，继续作为 DeviceList、Collection、Dashboard 三个独立页面共享的本地临时设备编辑器。
- `src/components/device/local-device-utils.ts` 与 `src/components/device/local-device-utils.test.ts` 已移动到 `src/features/device/utils/local-device-utils.ts` / `.test.ts`，旧路径不保留第二套长期副本。
- `local-device-utils.ts` 继续 export `AdaptiveConfig`、`CloudTargetConfig`、`LocalDeviceDraft`、`LocalDevicePayload`、`LocalDeviceBundle`、`ProtocolPointNotes`，以及 `DEFAULT_ADAPTIVE_CONFIG`、`extractLocalDeviceBundle()`、`buildLocalDevicePayload()`、`validateLocalDeviceDraft()`、`buildProtocolPointNotes()`、`normalizeLocalPoints()`、`normalizeAdaptive()`。
- `LocalDeviceEditor.vue` 删除内部重复 `interface LocalDeviceBundle`，统一从 `features/device/utils/local-device-utils` import `LocalDeviceBundle`；代码搜索确认 `LocalDeviceBundle` 只有 `local-device-utils.ts` 一个正式接口定义。
- `DeviceListView.vue`、`CollectionView.vue`、`DashboardView.vue` 均已改为 import `@/features/device/components/LocalDeviceEditor.vue`，需要 `LocalDeviceBundle` / `extractLocalDeviceBundle()` 的地方统一 import `@/features/device/utils/local-device-utils`。
- `LocalDeviceEditor.vue` 保持低耦合 props：`modelValue`、`editingBundle`、`protocols`；组件内部未引入 `useDeviceStore()` 或 `useProtocolStore()`，仍由父页面传入协议列表。
- 协议 Schema 按需加载保持不变：编辑器仍在打开或协议切换时调用 `getProtocol(protocol)` 获取完整 `connectionFields`、`pointFields`、`pointAddressHints`、`dataTypes`，不会把父页面传入的列表协议元数据误认为完整 Schema。
- `DashboardView.vue` 的 LocalDeviceEditor 协议来源已收敛到 `useProtocolStore()`：模板传入 `protocolStore.protocols`，`openLocalEditor()` 仅在协议列表为空时 `await protocolStore.refresh()`，有 `protocolStore.error` 时提示并停止打开；已删除 Dashboard 自己维护的 `protocols ref`、`listProtocols` import 和 `ProtocolSchema` type import。
- `DeviceListView.vue` 与 `CollectionView.vue` 本 Phase 只调整 LocalDeviceEditor / local-device-utils import，继续保持原有 `protocolStore`、`deviceStore`、配置同步、导入导出、Router Query 等业务边界。
- 新增 `src/features/device/utils/local-device-editor-utils.ts` / `.test.ts`，仅承接输入输出明确、不依赖 Vue ref/computed/props 的纯 helper，没有新增 `useLocalDeviceEditor` composable，也没有创建 `local-device.store.ts`。
- 迁出的 pure helper 包括：`normalizeInitialPoints()`、`defaultPointTemplate()`、`defaultAddress()`、`normalizeCloudTarget()`、`alarmRules()`、`serializeAlarmRules()`、`parsePointsJson()`、`sanitizePointForSave()`、`removeDeprecatedCloudIdentityConfig()`、`cloudTargetSummary()`、`cloudPointStatus()`、`statusLabel()`、`parseBooleanOption()`、`parseFieldValue()`、`toNumber()`、`findDuplicatePointCode()`、`createUniqueCode()`、`buildReadonlyItems()`、`isOpcUaProtocol()`、`hasValue()`、`pruneEmpty()`。
- 留在组件内的仍是 UI / Vue 状态操作：`setActiveStep()`、`moveStep()`、`reset()`、`onProtocolChanged()`、`selectPoint()`、`updatePointField()`、`save()`、`close()`、keydown/modal 生命周期、协议 Schema state 和点位选择 state。
- 本 Phase 没有拆 `LocalDeviceSetupStep.vue`、`LocalDeviceCloudStep.vue`、`LocalDeviceJsonStep.vue` 或复杂 Point Step。原因是当前模板拆分会制造大量 props/emits，尤其点位详情区与 JSON/云/协议 Schema 之间仍有紧耦合；Phase 13 只做目录与纯函数边界整理。
- 四个主步骤完整保留：`01 基础连接`、`02 点位建模`、`03 云平台上报`、`04 JSON 高级`；没有新增 Route、没有新增 Wizard，也没有调整步骤顺序。
- 基础连接能力保持：设备 ID、设备名称、协议、基础/最小/最大采集周期、点位变化阈值、cloudTarget、Topic preview、ProtocolDynamicForm 动态连接参数、必填字段统计和校验仍在原编辑器流程中。
- 新增模式保持：`deviceId` 可编辑，默认协议仍为 `MODBUS_TCP` 或父级协议列表第一项，默认 adaptive 使用 `DEFAULT_ADAPTIVE_CONFIG`，无点位时自动建立一个默认点位，`overwrite` 与 `startAfterSave` 默认关闭。
- 编辑模式保持：`editingBundle` 存在时继续读取旧 device / connection / points / cloudTarget，设备 ID 禁止修改，`overwrite` 默认启用，协议、adaptive、动态连接参数和点位全部回填。
- 协议切换保持：`onProtocolChanged()` 继续清空/重建 connection model、重新归一化 points、同步 JSON，并触发 `ensureProtocolSchema(protocol)` 获取完整 Schema；默认地址/类型逻辑由纯 helper 覆盖 MQTT、OPC UA、SIEMENS S7、Modbus。
- `ProtocolDynamicForm.vue` 和 `protocol-form-utils.ts` 未迁移、未重写；LocalDeviceEditor 继续使用 `buildConnectionPayload()`、`buildProtocolInitialModel()`、`extractProtocolModel()`、`getPathValue()`、`setPathValue()`、`validateProtocolModel()`。
- 点位编辑能力保持：新增点位、复制点位、删除点位、搜索点位、选择点位仍在组件内；点位详情 Tabs `基础信息`、`数据处理`、`上报 / 缓存参数`、`协议扩展`、`告警规则`、`只读信息` 均保留。
- Phase 13 未提前迁移 Point Feature：未移动 `src/components/point/PointEditor.vue`，未创建 point store，未统一两个点位编辑器，未重做点位表格。
- `alarmRule` 语义保持为保存前 JSON 字符串；`local-device-utils.test.ts` 保留并补充覆盖点位 adaptive、reportField/reportEnabled、alarmRule JSON string、cloudTarget、connection extJson、temporaryConfig、configSource 等保存格式。
- 云上报语义保持：设备级 `cloudTarget` 继续负责 `productKey`、`deviceName`、`deviceType`、`topologyEnabled`；点位级 `additionalConfig.reportField`、`reportEnabled`、`eventEnabled`、`streamEnabled`、`historyEnabled` 保持；保存前仍清理废弃 `reportBindings`、`reportProductKey`、`reportDeviceName`、`cloudBindings`。
- JSON 高级模式保持：点位 JSON 数组编辑、格式化 JSON、应用 JSON 到列表和解析失败明确错误仍在；列表变更与 JSON 同步仍由组件状态操作维护。
- 保存流程保持：补 connection 默认值 -> normalize points -> sanitize points -> `validateLocalDeviceDraft()` -> `validateProtocolModel()` -> `buildConnectionPayload()` -> `buildLocalDevicePayload()` -> `updateLocalDevice()` 或 `createLocalDevice()` -> `startAfterSave` 时 `startLocalDevice()` -> `emit("saved", deviceId)` -> 关闭。
- `saved(deviceId)` 契约保持，三个父页面仍以保存后的 deviceId 刷新/选择设备上下文；没有改成无参数事件或 payload 事件。
- 关闭行为保持：Teleport 到 body、点击背景关闭、关闭按钮、Escape 关闭、`document.body.classList.toggle("modal-active", visible)`、unmount 时移除 keydown listener 和 `modal-active`。
- CSS 本 Phase 未从 `workbench.css` 迁出到 scoped style。已搜索确认 `local-editor`、`local-device-panel`、`local-device-web-dialog`、`local-editor-title`、`local-editor-tabs`、`local-editor-layout`、`local-editor-rail`、`local-editor-body`、`local-checklist`、`local-section-card` 等仍被 DeviceWorkbench / Legacy Control / Legacy Shadow / DeviceConfigPanel / ManualShadowPanels / LocalDeviceEditor 共同使用，避免复制全局样式。
- 搜索中识别到若干 Editor-only selector（如 `local-setup-*`、`local-cloud-*`、`local-point-*`、`point-detail-*`、`local-options`、`local-editor-footer`、`local-connection-meta` 等）主要只由 LocalDeviceEditor 模板与 `workbench.css` 使用；考虑到这些规则夹在当前共享 modal/workbench 样式块内且共享外壳仍未迁完，本 Phase 记录边界但不复制/拆半套 CSS，等待 Control / Shadow 和最终 `workbench.css` 阶段统一处理。
- 代码搜索确认旧路径引用为 0：`components/device/LocalDeviceEditor`、`components/device/local-device-utils` 均无命中；`src/components/device/LocalDeviceEditor.vue`、`src/components/device/local-device-utils.ts`、`src/components/device/local-device-utils.test.ts` 均已不存在。
- 代码检查确认 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/device`、`/device/workbench` 仍直接解析到独立 View；`/control`、`/shadow` 仍解析到 `LegacyConsoleView.vue`。

## Phase 13 验证结果

执行时间：2026-08-31 13:51-14:31，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 35 个测试文件、193 个测试通过；新增 `src/features/device/utils/local-device-editor-utils.test.ts`，迁移后的 `local-device-utils.test.ts` 通过 |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；生成新的 `DeviceListView`、`CollectionView`、`DashboardView`、`DeviceWorkbenchView` 等 hash 产物 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 46 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 13 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。

## Phase 13 路由与页面检查结果

- `/device`：dev server `http://127.0.0.1:5184/#/device` 能打开，仍由 `DeviceListView.vue` 承载；页面显示“设备管理”“新增本地设备”等入口，后端不可达/CORS 失败时页面不崩溃。
- `/device` 新增本地设备 smoke：在带 CORS 的本地 mock 采集服务下点击“新增本地设备”，LocalDeviceEditor 正常打开；四个步骤存在，默认协议为 `MODBUS_TCP`，至少一个默认点位；关闭按钮、Escape、背景点击均能关闭，关闭后 `modal-active` 清理。
- `/device` 编辑本地设备 smoke：mock 本地设备点击“编辑”后通过 `getLocalDevice()` -> `extractLocalDeviceBundle()` -> `LocalDeviceEditor` 回填；设备 ID 禁止编辑，设备名称、协议、点位、cloudTarget/adaptive 进入编辑状态，`overwrite` 默认启用。
- `/device` 保存 smoke：mock 服务下新增本地设备并勾选“保存后立即本地启动”，确认调用 `createLocalDevice()` 1 次与 `startLocalDevice()` 1 次，保存后弹窗关闭并触发父页面刷新链路。
- `/collect`：dev server 能打开，仍由 `CollectionView.vue` 承载；mock 协议列表下点击协议行“配置设备”能打开 LocalDeviceEditor，Editor 接收 `protocolStore.protocols`，默认协议和默认点位正常。
- `/dashboard`：dev server 能打开，仍由 `DashboardView.vue` 承载；mock smoke 先清空请求日志后进入 Dashboard，页面启动阶段没有请求 `/api/protocols`；点击“新增本地设备”后才通过 `protocolStore.refresh()` 请求协议列表并打开 LocalDeviceEditor。
- `/device/workbench?deviceId=local-smoke-1`：dev server 能打开，仍由 `DeviceWorkbenchView.vue` 承载，显示 Workbench / Control / Shadow 三项外层 Tab。
- `/control?deviceId=local-smoke-1`：dev server 能打开，仍由 `LegacyConsoleView.vue + LegacyManualShadowPanels.vue` 承载，显示“手动控制”和设备上下文。
- `/shadow?deviceId=local-smoke-1`：dev server 能打开，仍由 `LegacyConsoleView.vue + LegacyManualShadowPanels.vue` 承载，显示“设备影子”和设备上下文。
- 代码检查确认 `LocalDeviceEditor.vue` 没有 `useDeviceStore()`、`useProtocolStore()` 或 `local-device.store.ts`；`getProtocol()`、`createLocalDevice()`、`updateLocalDevice()`、`startLocalDevice()` 仍是它直接依赖的后端 API 边界。
- 代码检查确认 `DashboardView.vue` 已无 `listProtocols`、`ProtocolSchema` 和 `protocols = ref<ProtocolSchema[]>([])`；只保留 `protocolStore` 按需加载。
- 因当前 in-app preview `drive_preview` 会返回 “The in-app browser only takes actions in the session the user is looking at.”，交互 smoke 通过独立 Headless Chrome + CDP 完成；dev server、headless Chrome、mock 后端进程均在验证后停止。

## Phase 13 新增文件

- `collector-desktop/src/features/device/utils/local-device-editor-utils.ts`
- `collector-desktop/src/features/device/utils/local-device-editor-utils.test.ts`
- `collector-boot/src/main/resources/static/desktop/assets/DeviceWorkbenchView-DrTXVYw_.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DeviceListView-DywcU1fT.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DeviceListView-SI_Y4VxW.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/CollectionView-CP0xkHgY.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/CollectionView-UMtYFAkz.css`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DashboardView-Cjx-EyaJ.js`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/DashboardView-CxVFgIqF.css`（`build:web` 生成）

## Phase 13 修改 / 移动文件

- `collector-desktop/src/features/device/components/LocalDeviceEditor.vue`（从 `src/components/device/LocalDeviceEditor.vue` 移动后整理）
- `collector-desktop/src/features/device/utils/local-device-utils.ts`（从 `src/components/device/local-device-utils.ts` 移动）
- `collector-desktop/src/features/device/utils/local-device-utils.test.ts`（从 `src/components/device/local-device-utils.test.ts` 移动后补充断言）
- `collector-desktop/src/views/device/DeviceListView.vue`
- `collector-desktop/src/views/collection/CollectionView.vue`
- `collector-desktop/src/views/dashboard/DashboardView.vue`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 13 删除文件

- `collector-desktop/src/components/device/LocalDeviceEditor.vue`
- `collector-desktop/src/components/device/local-device-utils.ts`
- `collector-desktop/src/components/device/local-device-utils.test.ts`

`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## 已知问题与回归风险

- `/device`、`/collect`、`/dashboard` 的 LocalDeviceEditor 交互 smoke 使用带 CORS 的本地 mock 采集服务完成，用于验证前端路由、弹窗、协议加载、保存调用顺序和关闭行为；真实设备、真实点位、真实启动/停止、配置缓存刷新/清理、协议配置读写、历史/实时跳转后的真实数据仍依赖后端服务与设备数据环境。
- Control / Shadow 仍在 `LegacyConsoleView.vue` 中过渡，仍保留 Legacy 局部 `devices`、`deviceRuntimeMap`、`selectedDeviceId`、`selectedRealtimeRows`、`loadDevices()`、`loadSelectedRealtime()`、`operateDeviceConfig()`；这些将在后续 Control / Shadow / Legacy Host 阶段继续收敛。
- `DeviceConfigPanel.vue`、`PointEditor.vue`、`RealtimeDataPanel.vue`、`AlarmTablePanel.vue`、`LogPanel.vue` 本 Phase 未拆分；`LocalDeviceEditor.vue` 只迁出纯 helper，模板和 UI 状态仍按阶段边界保留在组件内。
- `workbench.css` 中同时服务 DeviceWorkbench、Legacy Control、Legacy Shadow、DeviceConfigPanel、ManualShadowPanels、LocalDeviceEditor 的共享样式仍暂时保留为全局样式，未复制到新 View；等 Control/Shadow 迁出和最终样式阶段再统一拆解。
- Web 静态产物 hash 因 `build:web` 更新，属于验证命令产生的预期变更。

## Phase 13 后续说明

Phase 13 完成后已按确认进入 Phase 14。本段保留为历史说明；Control、Shadow 和最终 Legacy Host 清理仍未在 Phase 13 中执行。

## Phase 14：Point Feature 迁移与整理完成内容

- `src/components/point/PointEditor.vue` 已正式移动到 `src/features/point/components/PointEditor.vue`，旧路径删除；`DeviceConfigPanel.vue` 的 import 已统一改为 `@/features/point/components/PointEditor.vue`。
- `src/components/point/PointBatchEditDialog.vue` 已移动到 `src/features/point/components/PointBatchEditDialog.vue`，继续保持 `modelValue`、`selectedCount`、`apply(payload)` 契约；批量编辑字段仍为 `alarmEnabled`、`dataType`、`readWrite`、`unit`、`baseCollectionInterval`。
- `src/components/point/PointGenerateDialog.vue` 已移动到 `src/features/point/components/PointGenerateDialog.vue`，继续保持数量、起始地址、地址步长、编码前缀、名称前缀、数据类型、读写类型和 `generate(options)` 契约；没有新增 S7、OPC UA、IEC104 等协议地址递增规则。
- `src/components/point/point-editor-utils.ts` 与测试已移动到 `src/features/point/utils/point-editor-utils.ts` / `.test.ts`，保留 `BuildIncrementalPointsOptions`、`PointBatchEditPayload`、`PointImportPreview`、`PointLocationTarget`、`PointExtraModel`，以及 `buildIncrementalPoints()`、`applyPointBatchEdit()`、`buildPointImportPreview()`、`buildPointLocationTarget()`、`normalizePointRows()`、`buildPointExtraModel()`、`applyPointExtraModel()`、`mergePointRuntime()`、`formatJsonForTextarea()`、`parseJsonTextarea()`。
- `src/components/point/point-excel-utils.ts` 与测试已移动到 `src/features/point/utils/point-excel-utils.ts` / `.test.ts`。文件名暂不改为 csv-utils，避免本阶段扩大 rename 范围；实际行为仍是 CSV：只支持 `.csv`、最大 1 MB、最大 2000 行，继续支持既有中文表头。
- `src/stores/point.store.ts` 继续留在共享 `src/stores/`，但 import 已改为依赖 `@/features/point/utils/point-editor-utils`；没有新增 `features/point/point.store.ts`、`point-editor.store.ts` 或 `pointDraftStore`。
- 新增 `src/stores/point.store.test.ts`，覆盖 `addEmptyPoint()`、`appendGeneratedPoints()`、`replacePoints()`、`applyBatch()`、`removeSelected()`、`setSelectedIds()`，验证 Store 继续通过迁移后的 Point Feature utils 执行轻量 actions。
- `PointEditor.vue` 仍只接收 `deviceId`、`protocol`、`protocolCode` props，不直接使用 `useDeviceStore()`、`useProtocolStore()` 或 `useRouter()`；设备和协议上下文继续由 `DeviceConfigPanel.vue` 传入。
- `PointEditor.vue` 继续使用 `usePointStore()` 作为持久化点位编辑唯一状态源：点位数组来自 `pointStore.getPoints(deviceId)`，选中项来自 `pointStore.getSelectedIds(deviceId)`，保存仍调用 `pointStore.save(deviceId)` -> `saveDevicePointConfig()`。组件本地只保留 `keyword`、`selectedPointId`、`detailTab`、Dialog 可见性、JSON textarea、realtimeRows 等 UI 临时状态。
- `PointEditor.vue` 的实时数据加载已收敛为 `getDeviceRealtimeData(deviceId)` -> `normalizeRealtimeRows(response, deviceId)` -> `realtimeRows`，不再使用 `response.points || response.data || response.values || []` 第三套兼容逻辑；`mergePointRuntime()` 仍按 `pointId`、`pointCode`、`address` 匹配实时点位。
- `PointEditor.vue` 原有新增点位、批量生成、批量编辑、批量删除、搜索、CSV 导入、CSV 导出、实时值刷新、配置刷新、点位保存、多选、当前点位详情、基础信息、数据处理、上报参数、协议扩展、告警、additionalConfig JSON、alarmRule JSON、当前值、质量、处理耗时、查看实时、查看历史、导入预览、重复编码提示、重复地址提示均保留。
- CSV Import Preview 流程保持：选择文件 -> `validatePointImportFile()` -> `parsePointCsv()` -> `buildPointImportPreview()` -> Preview Dialog -> 确认导入 -> `pointStore.replacePoints()`；重复编码/地址仍是 warning，不升级为阻断 error。
- 动态协议字段语义保持：`ProtocolSchema.pointFields` 继续通过 `buildPointExtraModel()` / `applyPointExtraModel()` 映射到 `additionalConfig`，没有修改 Protocol Schema 或 Point API。
- `open-history` / `open-realtime` emit 契约保持，目标仍由 `buildPointLocationTarget()` 生成，路由跳转仍由 `DeviceConfigPanel` -> `DeviceWorkbenchView` 承接，Point Feature 不直接持有 Router。
- `LocalDeviceEditor.vue` 未嵌入或复用 `<PointEditor />`，也未改用 `usePointStore()`；本地设备草稿点位仍是 LocalDevicePayload 的一部分，继续随 device / connection / cloudTarget 一起保存，避免尚未保存的新设备污染全局点位状态或触发 point API。
- 从 Phase 13 的 `features/device/utils/local-device-editor-utils.ts` 中迁出通用点位草稿 helper 到 `src/features/point/utils/point-draft-utils.ts` / `.test.ts`：`alarmRules()`、`serializeAlarmRules()`、`parsePointsJson()`、`statusLabel()`、`parseBooleanOption()`、`parseFieldValue()`、`toNumber()`、`findDuplicatePointCode()`、`createUniqueCode()`、`buildReadonlyItems()` 以及相关轻量类型。迁移原因是这些能力是点位编辑通用数据规则，不依赖 Local Device Draft 的保存事务。
- Device 专属 Draft helper 继续留在 `features/device/utils/local-device-editor-utils.ts`：`normalizeInitialPoints()`、`defaultPointTemplate()`、`defaultAddress()`、`normalizeCloudTarget()`、`sanitizePointForSave()`、`removeDeprecatedCloudIdentityConfig()`、`cloudTargetSummary()`、`cloudPointStatus()`、`firstPointValue()`、`isOpcUaProtocol()`、`hasValue()`、`isPlainObject()`、`cloneData()`。保留原因是它们与 Local Device 草稿初始化、cloudTarget/adaptive、保存前清理和旧 Web 兼容默认值强相关。
- CSS 本 Phase 没有大规模搬迁：`point-toolbar`、`point-workbench-grid`、`point-table-panel`、`point-detail-panel`、`point-detail-tabs`、`point-json-textarea`、`point-import-preview-*` 等仍在 `workbench.css` 中；原因是其中部分选择器仍与 DeviceWorkbench、LocalDeviceEditor 或全局 workbench 结构共用，避免复制一套 scoped CSS 与全局 CSS 并存。后续 Control/Shadow/Legacy Host 清理阶段再统一拆解。
- 代码检查确认 `src/components/point/` 已无旧文件且目录不存在；`components/point`、`@/components/point`、`./point-editor-utils`、`./point-excel-utils` 搜索均为 0；`PointEditor.vue` 内 `useDeviceStore()`、`useProtocolStore()`、`useRouter()` 搜索均为 0；`LocalDeviceEditor.vue` 内 `usePointStore()` 搜索为 0。
- 代码检查确认 `/dashboard`、`/realtime`、`/history`、`/alarm`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/device`、`/device/workbench` 仍解析到独立 View；`/control`、`/shadow` 仍解析到 `LegacyConsoleView.vue`。

## Phase 14 验证结果

执行时间：2026-09-01 08:53-09:26，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 37 个测试文件、200 个测试通过；包含迁移后的 point utils、point draft utils、point excel/csv utils、point.store 测试 |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 46 |
| `git diff --check` | 通过 | exit code 0；最终无空白错误 |

Phase 14 后仍存在与 baseline 一致的提示：Vite/Rollup `@vueuse/core` PURE annotation warning；Element Plus vendor chunk 超过 500 kB。这些不是本阶段新增失败。

## Phase 14 路由与页面检查结果

- `/device`：独立 `DeviceListView.vue` 保持；LocalDeviceEditor “新增本地设备”入口 smoke 通过。
- `/device/workbench?deviceId=dev-1`：独立 `DeviceWorkbenchView.vue` 保持；点击“编辑点位”后 `PointEditor.vue` 正常显示。
- `/dashboard`：独立 `DashboardView.vue` 保持；LocalDeviceEditor “新增本地设备”入口回归 smoke 通过。
- `/collect`：独立 `CollectionView.vue` 保持；协议列表“配置设备”入口回归 smoke 通过。
- `/control?deviceId=dev-1`：仍由 `LegacyConsoleView.vue` 承载，页面显示“手动控制”。本 Phase 未迁移 Control。
- `/shadow?deviceId=dev-1`：仍由 `LegacyConsoleView.vue` 承载，页面显示“设备影子”。本 Phase 未迁移 Shadow。
- PointEditor smoke 使用本地 mock 采集服务验证：点位加载、新增点位、批量生成、多选、批量编辑、CSV 导入、导入预览、重复编码/地址 warning、确认导入、CSV 导出、刷新实时值、保存、协议扩展字段、additionalConfig JSON、alarmRule JSON、查看实时、查看历史均可走通。
- PointEditor 后端失败态 smoke 使用 `dev-fail` mock 设备验证：点位配置接口和实时数据接口返回失败时，编辑器显示“配置加载失败”“实时数据加载失败”，页面仍保持可显示和可操作的空/失败状态。
- LocalDeviceEditor 三入口回归 smoke：`/device`、`/collect`、`/dashboard` 均能打开四步编辑器，并验证点位新增/复制/删除、告警规则、云配置、JSON 高级入口无回归。

## Phase 14 新增文件

- `collector-desktop/src/features/point/utils/point-draft-utils.ts`
- `collector-desktop/src/features/point/utils/point-draft-utils.test.ts`
- `collector-desktop/src/stores/point.store.test.ts`

## Phase 14 修改 / 移动文件

- `collector-desktop/src/features/point/components/PointEditor.vue`（从 `src/components/point/PointEditor.vue` 移动）
- `collector-desktop/src/features/point/components/PointBatchEditDialog.vue`（从 `src/components/point/PointBatchEditDialog.vue` 移动）
- `collector-desktop/src/features/point/components/PointGenerateDialog.vue`（从 `src/components/point/PointGenerateDialog.vue` 移动）
- `collector-desktop/src/features/point/utils/point-editor-utils.ts`（从 `src/components/point/point-editor-utils.ts` 移动）
- `collector-desktop/src/features/point/utils/point-editor-utils.test.ts`（从 `src/components/point/point-editor-utils.test.ts` 移动）
- `collector-desktop/src/features/point/utils/point-excel-utils.ts`（从 `src/components/point/point-excel-utils.ts` 移动）
- `collector-desktop/src/features/point/utils/point-excel-utils.test.ts`（从 `src/components/point/point-excel-utils.test.ts` 移动）
- `collector-desktop/src/components/device/DeviceConfigPanel.vue`
- `collector-desktop/src/features/device/components/LocalDeviceEditor.vue`
- `collector-desktop/src/features/device/utils/local-device-editor-utils.ts`
- `collector-desktop/src/features/device/utils/local-device-editor-utils.test.ts`
- `collector-desktop/src/stores/point.store.ts`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

## Phase 14 删除文件

- `collector-desktop/src/components/point/PointEditor.vue`
- `collector-desktop/src/components/point/PointBatchEditDialog.vue`
- `collector-desktop/src/components/point/PointGenerateDialog.vue`
- `collector-desktop/src/components/point/point-editor-utils.ts`
- `collector-desktop/src/components/point/point-editor-utils.test.ts`
- `collector-desktop/src/components/point/point-excel-utils.ts`
- `collector-desktop/src/components/point/point-excel-utils.test.ts`

`src/components/point/` 目录为空后已不存在。`build:web` 同步时删除上一版 Web 静态构建 hash 文件，新增本次构建对应 hash 文件；这些都是验证命令产生的预期变更。

## Phase 14 已知问题与回归风险

- 本阶段 smoke 使用本地 mock 采集服务和 Headless Chrome/CDP 验证前端流程；真实设备、真实协议连接、真实点位保存后的设备行为、真实历史/实时数据仍依赖实际后端与设备环境。
- PointEditor 模板仍较大，本阶段没有拆 `PointBasicTab`、`PointDataTab`、`PointReportTab`、`PointProtocolTab`、`PointAlarmTab`，避免产生大量双向绑定 props；可选 `PointImportPreviewDialog` 也未拆，因为当前内联预览逻辑仍足够清晰且不影响 feature 目录边界。
- `workbench.css` 仍承载 PointEditor、DeviceWorkbench、LocalDeviceEditor、Control/Shadow 过渡样式，最终样式拆解留到 Legacy Host 清理阶段统一处理。
- `/control`、`/shadow` 仍由 `LegacyConsoleView.vue` 承载，`LegacyConsoleView.vue` 未删除；Phase 15 前必须先分析 Control、Shadow、`LegacyManualShadowPanels.vue` 和 Legacy Host 的完整迁出方式。

## Phase 15：Remove Legacy Host 完成内容

- 新增 `src/views/control/ControlView.vue` 与 `src/views/shadow/ShadowView.vue`，`/control`、`/shadow` 已直接由独立 View 承载，不再进入 Legacy Host。
- 新增 `src/features/device/components/DeviceOperationShell.vue`，由 `AppShell -> AppTopbar -> RouterView` 下的独立页面复用设备操作外壳；它负责 `appStore.initialize()`、`deviceStore.refresh()`、`route.query.deviceId` 单向同步、当前设备摘要、实时点位预览、运行/连接摘要、配置刷新、清理缓存、返回设备列表、运行状态、告警历史和工作台/控制/影子三路 Router 导航。
- Control 业务从 `LegacyManualShadowPanels.vue` 拆到 `src/features/control/components/ControlPanel.vue`；`ControlView.vue` 只组合 `DeviceOperationShell active-tab="control"` 与 `ControlPanel`。
- Control 新增 `src/features/control/utils/control-utils.ts` 与测试，覆盖单点值转换、单点 payload、批量模板、命令模板和 JSON 解析。单点写入、批量写点位、协议命令继续使用 `writeDevicePoint()`、`writeDevicePoints()`、`executeDeviceCommand()`，后端 URL 与 payload 语义未改。
- Shadow 业务从 `LegacyManualShadowPanels.vue` 拆到 `src/features/shadow/components/ShadowPanel.vue`；`ShadowView.vue` 只组合 `DeviceOperationShell active-tab="shadow"` 与 `ShadowPanel`。
- `shadow-utils.ts` 与测试从 `src/views/legacy/` 移到 `src/features/shadow/utils/`，保留 `ShadowHistoryRow`、`ShadowStateSummary`、`normalizeShadowHistoryRows()`、`summarizeShadowState()`，并补充 `parseShadowJson()`、`formatShadowTime()`、`compactJson()`、导出 payload 和文件名 helper 测试。
- Shadow 继续使用 `getShadow()`、`getShadowDelta()`、`getShadowHistory()`、`updateShadowDesired()`、`clearShadowDesired()`；desired 默认 payload 仍为 `{ "desired": {} }`，提交时仍发送当前 JSON 对象本身，不改成只发送内层对象。
- Router 已改为 `/control -> ControlView`、`/shadow -> ShadowView`，并删除 `LegacyConsoleView` lazy import；`router.test.ts` 覆盖 `/control`、`/control?deviceId=dev-1`、`/shadow`、`/shadow?deviceId=dev-1` 以及已迁移业务路由独立解析。
- 删除 `src/views/legacy/LegacyConsoleView.vue`、`src/views/legacy/LegacyManualShadowPanels.vue`、旧 `shadow-utils.ts` / `shadow-utils.test.ts`，`src/views/legacy/` 目录已不存在。
- 复查 `src/views/runtime/runtime-utils.ts` / `runtime-utils.test.ts` 仅剩旧模板测试用途，且其中批量模板已与当前生产 Control payload 不一致；本阶段已删除 `src/views/runtime/`，没有把过时 helper 重新引入 Control。
- 本阶段未重写 `DeviceWorkbenchView.vue`、`DeviceListView.vue`、`PointEditor.vue`、`LocalDeviceEditor.vue`，未修改后端、Electron 和 CSS 主体；`legacy-console.css` 与 `workbench.css` 按 Phase 16 之前的共享样式继续保留。

## Phase 15 验证结果

执行时间：2026-09-01 10:22-10:23，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 37 个测试文件、206 个测试通过；新增 `control-utils.test.ts`，迁移后 `shadow-utils.test.ts` 继续通过，Router 测试覆盖 Control/Shadow 独立 View |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过，生成独立 `ControlView`、`ShadowView` 与 `DeviceOperationShell` chunk |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 47 |
| `git diff --check` | 通过 | exit code 0；仅有 Git 对 Web 构建产物 LF/CRLF 的工作区换行提示 |

本阶段 Headless Chrome/CDP smoke 使用本地 mock 采集服务验证以下页面和交互：`/device`、`/device/workbench?deviceId=dev-1`、`/control?deviceId=dev-1`、`/shadow?deviceId=dev-1`、`/diagnostic?deviceId=dev-1`、`/alarm?deviceId=dev-1`、`/dashboard`、`/realtime`、`/history`、`/collect`、`/cloud`、`/network`、`/log`。其中 Control 覆盖 STRING / BOOLEAN / INT / FLOAT / DOUBLE 单点写入、批量模板、非法 JSON 防崩溃、批量写入、命令模板和协议命令；Shadow 覆盖读取全部、读取影子、读取 delta、history limit、读取历史、提交 desired、清理 desired；三路导航覆盖 Workbench -> Control -> Shadow -> Workbench -> Shadow -> Control -> Device List，`deviceId=dev-1` 未丢失。

## Phase 15 文件变化

新增：

- `collector-desktop/src/views/control/ControlView.vue`
- `collector-desktop/src/views/shadow/ShadowView.vue`
- `collector-desktop/src/features/device/components/DeviceOperationShell.vue`
- `collector-desktop/src/features/control/components/ControlPanel.vue`
- `collector-desktop/src/features/control/utils/control-utils.ts`
- `collector-desktop/src/features/control/utils/control-utils.test.ts`
- `collector-desktop/src/features/shadow/components/ShadowPanel.vue`

移动：

- `collector-desktop/src/views/legacy/shadow-utils.ts` -> `collector-desktop/src/features/shadow/utils/shadow-utils.ts`
- `collector-desktop/src/views/legacy/shadow-utils.test.ts` -> `collector-desktop/src/features/shadow/utils/shadow-utils.test.ts`

修改：

- `collector-desktop/src/router/route-definitions.ts`
- `collector-desktop/src/router/router.test.ts`
- `collector-desktop/scripts/workbench-layout-contract.test.mjs`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

删除：

- `collector-desktop/src/views/legacy/LegacyConsoleView.vue`
- `collector-desktop/src/views/legacy/LegacyManualShadowPanels.vue`
- `collector-desktop/src/views/runtime/runtime-utils.ts`
- `collector-desktop/src/views/runtime/runtime-utils.test.ts`
- `collector-desktop/src/views/legacy/` 空目录
- `collector-desktop/src/views/runtime/` 空目录

## Phase 15 已知问题与回归风险

- 本阶段 smoke 使用本地 mock 采集服务验证前端页面和请求 payload；真实设备、真实协议命令、真实 Shadow desired/delta/history 行为仍需真实后端与设备环境联调。
- `DeviceWorkbenchView.vue` 暂未接入 `DeviceOperationShell`，避免扩大 Phase 12 已验证工作台的改动范围；目前 Shell 只服务 Control/Shadow。
- `legacy-console.css` 与 `workbench.css` 仍保留共享样式，Phase 16 需要基于实际 selector 使用情况系统化清理，不能直接按文件名删除。
- 搜索 `runtime-utils` 仍会命中 `features/diagnostic/utils/device-runtime-utils.ts`，这是诊断 feature 的独立 helper，不是已删除的 `src/views/runtime/runtime-utils.ts`。

## 下一步

等待确认后进入 Phase 16：清理 `legacy-console.css` / `workbench.css`。

Phase 16 执行前需要先统计 `legacy-console.css` / `workbench.css` 中仍被已迁页面复用的 selector，区分全局共享样式、设备操作工作台样式、已死亡 Legacy Host 样式和页面专属样式；当前不自动执行 Phase 16。

## Phase 16：CSS Migration / Cleanup 完成内容

- 完成 `legacy-console.css` / `workbench.css` selector inventory，先按当前 `src/**/*.vue`、`src/**/*.ts`、`src/**/*.mjs` 引用情况区分死亡 selector、App 全局基础、共享 primitive 和 feature/component 专属规则，再开始迁移。
- 删除 `src/styles/legacy-console.css` 与 `src/styles/workbench.css`，`main.ts` 不再导入两个历史大 CSS。
- `main.ts` 样式入口固定为：Element Plus 原始 CSS -> `tokens.css` -> `global.css` -> `base.css` -> `element-plus.css` -> `utilities.css`。
- `AppShell.vue` 根节点从 `shell legacy-console theme-anchor modao-exact app-shell` 收敛为 `shell app-shell`，删除 `onMounted/onBeforeUnmount` 中对 `theme-anchor`、`modao-exact` 的 body class 操作；`modal-active` 仅由 `LocalDeviceEditor` 管理弹层滚动锁定。
- `tokens.css` 中 `--console-*`、`--exact-*` compatibility aliases 改为挂在 `:root`，不再依赖 `body.modao-exact` 才存在；本阶段没有扩大到全量变量重命名，后续新代码优先使用 `--app-*`。
- `global.css` 只保留 `html/body/#app` 高度链路、box-sizing、body reset、字体继承和 `.dot` 状态点等真正全局 primitive。
- 新增 `base.css`，只放跨业务页面共享的 `exact-page`、`section-heading`、toolbar、surface/card、table、badge、json、empty-state、form-grid 等基础语义样式。
- 新增 `element-plus.css`，承接 Element Plus 变量、表单/表格/弹层/Dialog/MessageBox/Popper/DatePicker 等必要全局覆盖，Teleport 样式不放入普通 scoped 规则。
- 新增 `utilities.css`，只保留 `inline-actions`、`text-muted`、`hidden`、`sr-only` 等小型 utility，避免形成新的 `workbench.css`。
- `AppShell` 主布局样式迁入 `AppShell.vue` scoped style；`AppSidebar` 导航、品牌、分组、底部状态和令牌抽屉样式迁入 `AppSidebar.vue`；`AppTopbar` 仅保留自身状态条样式。
- `DeviceOperationShell.vue` 作为 Workbench / Control / Shadow 公共外壳，拥有 `device-operation-*`、外壳标题、三段 tab、左侧 rail、设备信息与外壳 body 样式。
- `DeviceWorkbenchView.vue` 改为复用 `DeviceOperationShell active-tab="config"`，不再重复旧 Device Operation Shell CSS。
- `DeviceConfigPanel.vue` 拥有运行控制、快捷导航、协议配置折叠区、运行数据区、点位详情、表格滚动和响应式布局样式。
- `LocalDeviceEditor.vue` 拥有 Teleport 编辑器 overlay/panel、四步 tab、左侧校验 rail、setup/point/cloud/json 区、footer、点位详情与相关响应式规则。
- `PointEditor.vue` 拥有 point toolbar、point workbench grid、table/detail/tabs/runtime/json/import preview 样式；`PointBatchEditDialog.vue` / `PointGenerateDialog.vue` 拥有各自小型弹窗表单样式。
- `ControlPanel.vue` 拥有 manual shadow/control 专属 pane、head card、surface grid 与 textarea 样式；`ShadowPanel.vue` 拥有 shadow summary、history、desired/delta 操作区样式。
- `AlarmTablePanel.vue`、`LogPanel.vue`、`ProtocolDynamicForm.vue` 分别迁入自身 filter/stat/form-grid scoped style，不再依赖旧 `workbench.css`。
- `RealtimeDataPanel.vue` 迁入自身表格与空状态小范围样式。
- 重构 `scripts/workbench-layout-contract.test.mjs`：测试不再读取 `workbench.css`，改为拼接真实样式所有者（`base.css`、`element-plus.css`、`DeviceOperationShell.vue`、`DeviceConfigPanel.vue`、`LocalDeviceEditor.vue`、`PointEditor.vue`、`AlarmTablePanel.vue`、`LogPanel.vue`、`ProtocolDynamicForm.vue`）验证布局契约。
- 新增 `scripts/css-architecture.test.mjs`，长期防止历史 CSS 文件、旧入口 import、AppShell legacy/modao anchor、旧高权重 selector 和 tokens `body.modao-exact` 绑定回归；同步更新根 `.gitignore` 只放行该新增长期测试脚本。

## Phase 16 Selector Inventory 与规模统计

| 文件 | 原始 bytes | 原始行数 | 规则数 | selector 数 | unique class/id | `!important` | A 死亡规则/selector | B App/Global 规则 | C 共享 primitive 规则 | D feature/component 规则 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `src/styles/legacy-console.css` | 24,622 | 1,047 | 126 | 187 | 70 | 3 | 30 / 41 | 26 | 70 | 0 |
| `src/styles/workbench.css` | 179,926 | 6,164 | 870 | 1,406 | 363 | 24 | 113 / 133 | 10 | 259 | 488 |
| 合计 | 204,548 | 7,211 | 996 | 1,593 | - | 27 | 143 / 174 | 36 | 329 | 488 |

- 第一轮直接删除的死亡样式为 A 类至少 143 条规则 / 174 个 selector；剩余仍有引用的规则没有整体搬家，而是按 AppShell、Sidebar、Topbar、DeviceOperationShell、DeviceConfigPanel、LocalDeviceEditor、PointEditor、Control、Shadow、Alarm、Log、ProtocolDynamicForm、RealtimeDataPanel 与共享 primitive 分发并压缩。
- 新共享项目 CSS 规模：`base.css` 7,767 bytes / 404 行，`element-plus.css` 6,917 bytes / 231 行，`utilities.css` 598 bytes / 44 行，合计 15,282 bytes。
- 入口 CSS 总规模（含 tokens/global）：`tokens.css` 2,917 bytes / 77 行，`global.css` 1,051 bytes / 61 行，加上 base/element/utilities 合计 19,250 bytes。
- `src/styles/` 下不存在超过 50 KB 的单一项目全局 CSS；未出现把 200 KB 历史 CSS 改名搬家的情况。
- 旧 CSS `!important` 合计 27 处；迁移后项目共享 CSS 中仅 `element-plus.css` 保留 8 处，均用于 Element Plus Teleport/组件库 DOM 覆盖。当前 `src` 全量 `!important` 为 10 处，其中 2 处为既有 `DashboardView.vue` scoped 样式。

## Phase 16 验证结果

执行时间：2026-09-01 15:11-15:15，执行目录：`collector-desktop/`。

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm test` | 通过 | 38 个测试文件、211 个测试通过；包含重构后的 `workbench-layout-contract.test.mjs` 与新增 `css-architecture.test.mjs` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；baseline `@vueuse` PURE warning 与 Element Plus vendor chunk > 500 KB warning 仍存在，本阶段未处理 |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 53 |
| `git diff --check` | 通过 | exit code 0；仅有 Git 对部分工作区文件 LF/CRLF 的提示 |

最终搜索：

| 搜索 | 结果 |
|---|---:|
| `rg 'legacy-console\.css' collector-desktop/src collector-desktop/scripts` | 0 命中 |
| `rg 'workbench\.css' collector-desktop/src collector-desktop/scripts` | 0 命中 |
| `rg 'legacy-console' collector-desktop/src` | 0 命中 |
| `rg 'modao-exact' collector-desktop/src` | 0 命中 |
| `rg 'body\.modao-exact' collector-desktop/src` | 0 命中 |
| `rg 'views/legacy' collector-desktop/src` | 0 命中 |
| `rg 'theme-anchor' collector-desktop/src` | 0 命中 |

本阶段 Headless Chrome/CDP smoke 使用本地 mock 采集服务验证以下内容：

- 业务 Route：`/dashboard`、`/realtime`、`/history`、`/alarm`、`/device`、`/device/workbench?deviceId=dev-1`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/control?deviceId=dev-1`、`/shadow?deviceId=dev-1`。
- 尺寸：`1920x1080`、`1440x900`、`1280x720`。
- 断言：页面可打开、`AppShell` 高度链路正常、无 `legacy-console` / `modao-exact` DOM/class、无页面级横向溢出、无严重 console error。
- LocalDeviceEditor 三入口：`/device -> 新增本地设备`、`/collect -> 配置设备`、`/dashboard -> 新增本地设备`；均验证 Teleport panel 可见、四步 tab 存在、基础输入存在、`modal-active` 正常、Escape 可关闭、弹层不超屏。
- Element Plus Popup：在设备工作台告警子页验证 `ElSelect` dropdown 与 `DatePicker`，在设备列表验证 `ElMessageBox` 与 `ElMessage`；弹层均为深色背景、文字可见、z-index 可用。

Smoke 验证后已停止临时 Headless Chrome、Vite dev server 和 mock backend 进程。

## Phase 16 文件变化

新增：

- `collector-desktop/src/styles/base.css`
- `collector-desktop/src/styles/element-plus.css`
- `collector-desktop/src/styles/utilities.css`
- `collector-desktop/scripts/css-architecture.test.mjs`

修改：

- `.gitignore`
- `collector-desktop/src/main.ts`
- `collector-desktop/src/styles/tokens.css`
- `collector-desktop/src/styles/global.css`
- `collector-desktop/src/app/AppShell.vue`
- `collector-desktop/src/app/AppSidebar.vue`
- `collector-desktop/src/app/AppTopbar.vue`
- `collector-desktop/src/views/device/DeviceWorkbenchView.vue`
- `collector-desktop/src/features/device/components/DeviceOperationShell.vue`
- `collector-desktop/src/components/device/DeviceConfigPanel.vue`
- `collector-desktop/src/features/device/components/LocalDeviceEditor.vue`
- `collector-desktop/src/features/point/components/PointEditor.vue`
- `collector-desktop/src/features/point/components/PointBatchEditDialog.vue`
- `collector-desktop/src/features/point/components/PointGenerateDialog.vue`
- `collector-desktop/src/features/control/components/ControlPanel.vue`
- `collector-desktop/src/features/shadow/components/ShadowPanel.vue`
- `collector-desktop/src/components/alarm/AlarmTablePanel.vue`
- `collector-desktop/src/components/log/LogPanel.vue`
- `collector-desktop/src/components/protocol/ProtocolDynamicForm.vue`
- `collector-desktop/src/components/realtime/RealtimeDataPanel.vue`
- `collector-desktop/scripts/workbench-layout-contract.test.mjs`
- `collector-desktop/docs/frontend-refactor/PROGRESS.md`
- `collector-boot/src/main/resources/static/desktop/index.html`（`build:web` 生成）
- `collector-boot/src/main/resources/static/desktop/assets/*`（`build:web` 生成 hash 产物）

删除：

- `collector-desktop/src/styles/legacy-console.css`
- `collector-desktop/src/styles/workbench.css`

## Phase 16 已知问题与回归风险

- 本阶段 smoke 基于本地 mock 后端和 Headless Chrome/CDP，验证 CSS 迁移后的布局、弹层和基础页面打开；真实后端、真实设备、真实历史/告警/影子数据仍依赖后续联调环境。
- `--console-*`、`--exact-*` 作为 compatibility aliases 保留在 `:root`；本阶段未做全量 CSS variable rename，避免扩大低价值 diff。
- `exact-*`、`modao-property-*` 等已成为长期 UI primitive 的 class 名本阶段未强制重命名；已清除的是 Legacy Host anchor：`legacy-console`、`modao-exact` 与 `body.modao-exact .legacy-console`。
- 构建仍只有既有两类 baseline warning：`@vueuse/core` PURE annotation warning 与 Element Plus vendor chunk > 500 KB warning；Phase 16 未处理 vendor chunk / tree-shaking / manualChunks 优化。

## Phase 17 Final Code Quality Gate 完成内容

执行时间：2026-09-02 13:28-13:52，执行目录：`collector-desktop/`。

### 静态质量门禁

- ESLint 已落地为 Flat Config：`eslint.config.mjs`，覆盖 `src/**/*.{ts,vue}`、`electron/**/*.{ts,cts}`、`scripts/**/*.mjs`、`vite.config.ts`、`eslint.config.mjs`、`stylelint.config.mjs`。
- Renderer ESLint 环境只开放 browser globals；Electron / scripts / Vite / lint config 使用 node globals。
- Renderer Node/Electron import 限制已从少量手写模块扩展为 `node:module` 的 `builtinModules` + `node:*` pattern + `electron` / `electron/*`，临时 stdin 校验证明 `node:fs` 与 `electron` 会被 `no-restricted-imports` 拦截。
- Stylelint 已覆盖 `src/**/*.{css,vue}`；`no-duplicate-selectors` 已开启，发现并修复 `tokens.css` 中重复 `:root`，无大范围 CSS 格式化。
- `npm run lint`、`npm run stylelint`、`npm run quality`、`npm run verify` 均为 check-only；不包含 `--fix`，不会修改源码。`build:web` 仍按设计更新后端静态 `desktop/**` 构建产物。
- 未新增 `eslint-disable`、`stylelint-disable` 或整文件关闭规则。

### Prettier 评估

- 已执行 Prettier check 评估，日志显示当前 `src/**/*.{ts,vue,css}` 会产生 116 个文件的纯格式 warning。
- 本轮未采用 Prettier 作为硬 Gate，也未执行 `prettier --write`，避免 Phase 17 变成大范围非语义格式化重写。
- 后续如需采用 Prettier，应作为独立格式化窗口处理。

### Dead Code / Type Escape / 架构审查

- 已删除并在前半段提交：`DeviceTree.vue`、`AppStatusBar.vue`、`cache.api.ts`、`health.api.ts`；本次断点继续未继续删除未确认 dead code。
- 保留 `runtime.store.ts`、`runtime.api.ts`、`diagnostic-detail-utils.ts`、`websocket-utils.ts` 等仍有生产或测试引用的模块。
- 最终源码扫描 132 个 `src` / `electron` 文件：`LegacyConsoleView`、`LegacyManualShadowPanels`、`views/legacy`、`views/runtime`、`legacy-console`、`modao-exact`、`activeModule`、`switchModule`、`components/point`、`components/device/LocalDeviceEditor` 均为 0。
- `features -> views`、`stores -> views`、`api -> views` 反向依赖均为 0。
- `@ts-ignore`、`@ts-expect-error`、`eslint-disable`、`stylelint-disable`、`debugger`、`console.log`、`as any`、`: any`、`<any>` 均为 0。
- `tsconfig.json` 继续保持 `strict: true`，未降低 TypeScript 严格度。
- `electron/main/http-proxy-utils.ts` 的超时错误 message 仍为 `请求采集服务超时`，仅保留 `{ cause: error }` 以保留原始异常。

### 文档收尾

- `AGENTS.md` 已改为最终长期规则：独立 Router/View 架构、Legacy Host 禁止回归、CSS 最终结构、正式 lint/stylelint/test/typecheck/build/build:web/verify 门禁。
- `PLAN.md` 保留 Phase 0 ~ Phase 17 历史计划，并新增“本轮重构状态”；明确前端架构重构已完成，不创建 Phase 18。
- `DECISIONS.md` 保留 ADR-001 ~ ADR-006，并追加 ADR-007 ~ ADR-014，覆盖 Router 独立承载、Legacy Host 退出、Point 草稿隔离、CSS ownership、Element Plus Teleport、ESLint/Stylelint、Prettier 与 architecture contract。
- `README.md` 已与真实实现一致：删除 ECharts，Excel 描述修正为 CSV，补充 `quality` / `verify` 命令和当前 AppShell / RouterView 架构。

### 最终 Smoke

- 使用本地 mock backend + Vite + Headless Chrome/CDP 执行轻量 route smoke。
- 覆盖 13 个 route：`/dashboard`、`/realtime`、`/history`、`/alarm`、`/device`、`/device/workbench?deviceId=dev-1`、`/collect`、`/cloud`、`/diagnostic`、`/log`、`/network`、`/control?deviceId=dev-1`、`/shadow?deviceId=dev-1`。
- 重点交互：`/device -> 新增本地设备` 打开 LocalDeviceEditor；`/device/workbench -> 编辑点位` 打开 PointEditor。
- 结果：`routes = 13`，`localDevice = passed`，`pointEditor = passed`，`severeErrors = 0`。
- Smoke 后已停止 mock backend、Vite dev server、Headless Chrome/CDP；tracked background processes 均为 exited。

### 最终验证结果

| 命令 | 结果 | 说明 |
|---|---:|---|
| `npm run lint` | 通过 | 0 error / 0 warning |
| `npm run stylelint` | 通过 | 0 error / 0 warning；`no-duplicate-selectors` 已开启 |
| `npm test` | 通过 | 39 个测试文件、218 个测试通过；包含 `frontend-architecture.test.mjs`、`css-architecture.test.mjs`、`workbench-layout-contract.test.mjs` |
| `npm run typecheck` | 通过 | `vue-tsc --noEmit` 与 `tsc -p tsconfig.node.json --noEmit` 通过 |
| `npm run build` | 通过 | renderer 与 Electron main/preload 构建通过；仅保留 baseline warning |
| `npm run build:web` | 通过 | renderer 构建后同步到 `collector-boot/src/main/resources/static/desktop`，同步文件数 53 |
| `npm run verify` | 通过 | 串联 `quality`、`build`、`build:web` 通过 |
| `git diff --check` | 通过 | Phase 17 最终收尾后执行，exit code 0 |

### npm audit

- 已执行 `npm audit`，命令返回 exit code 1。
- 当前报告：18 vulnerabilities（2 moderate、14 high、2 critical）。主要来自 Electron / electron-builder 相关依赖链、Vitest/Vite/esbuild、glob、tar 等。
- 未执行 `npm audit fix`、`npm audit fix --force`、`npm update`；不在 Phase 17 中升级 Vue / Electron / Element Plus 或引入破坏性依赖变更。

### baseline warning 与剩余真实联调风险

- baseline warning 仍为两类：`@vueuse/core` PURE annotation warning；`vendor-element-plus` chunk 超过 500 kB。
- 最终 smoke 使用 mock backend 验证前端 route、Router、主要交互和 console/runtime 错误；真实后端、真实设备、真实 Redis/TDengine、真实云上报 ACK、真实历史/告警/影子数据仍需在具备环境时单独联调。
- 后续独立优化建议：真实后端 / 真实设备联调、Element Plus bundle 优化、`@vueuse/core` PURE warning 跟踪、compatibility CSS variable 渐进收敛、coverage 体系、Prettier 独立格式化窗口。

Phase 17 已完成并通过最终验收。本轮 `collector-desktop` 前端架构重构正式结束。

## 独立优化：设备配置页 Frontend Design（2026-10-09）

本次不新增 Phase 18，不改变已完成的架构重构结论。按用户“开始修改，使用 Frontend Design 优化页面，业务逻辑和接口调用要正确”的要求，只优化设备配置工作台。

### 范围与实现

- `DeviceOperationShell.vue`：仅配置分区启用 `config-workspace`，整合设备标题、基础信息和辅助操作；控制页、影子页仍保留原侧栏布局。
- `DeviceConfigPanel.vue`：运行状态集中展示，协议配置按需展开，点位表格与只读详情并列，选中行高亮，窄窗口下详情移至表格下方。
- `ProtocolDynamicForm.vue` / `protocol-form-utils.ts`：新增配置页可选完整标签展示，保留已知中文映射；原紧凑标签函数签名及其他调用方默认行为不变。
- 长数值和时间戳采用单行省略及完整内容悬浮提示，不截断原始数据、不改变精度；协议表单末组占满可用宽度，桌面四列、窄窗口两列。
- 协议连接仍来源于真实 Schema，设备状态仍由共享 `deviceStore` 提供，未修改后端 API、DTO、共享状态、全局主题、Electron Main/Preload 或依赖。
- 修正点位详情“查看实时”与表格“写入”动作的混用：读写点位也可从详情进入实时查询，表格写入仍沿用既有命令分区提示。

### 业务调用链检查

- 协议配置读取保持 `getProtocol` + `getDeviceConfigBundle`。
- 保存仍以同一个 Bundle 的 `configVersion` 作为 `baseVersion`，先校验后提交；409 不自动重读重提，保留错误提示和当前草稿。
- 读请求继续保留设备/协议上下文、latest-request ownership、设备 epoch 校验及卸载失效处理。
- 启动/停止继续通过 View 调用 `deviceStore.startSmart/stop`，不改本地设备启动路由或运行状态语义。
- 实时/历史跳转仍携带实际 `deviceId` 和 `pointRef`，未用名称替代稳定点位标识。

### 已执行验证（无测试）

| 命令 | 结果 |
|---|---|
| `npm run lint` | 退出 0 |
| `npm run stylelint` | 退出 0 |
| `npm run typecheck` | 退出 0 |
| `npm run build` | 退出 0，renderer / Electron 构建通过 |
| `npm run build:web` | 退出 0，静态页面资源同步到后端源码目录 |

未新增、修改或运行测试；未运行含测试的 `quality` / `verify`。构建仍提示既有 `@vueuse/core` PURE annotation 和 Element Plus 大 chunk warning，未扩大为依赖/性能优化。

### 真实只读联调与视觉检查

- 使用已构建的 Electron renderer 和现有客户端授权连接真实后端，未读取、输出或导出令牌。
- 实际设备 `pf_modbus_tcp_fanuc-cnc`：Bundle、协议详情、实时数据和正确的 `/api/device/{deviceId}/runtime` 返回 HTTP 200；配置及运行快照业务码为 200，实时数据包含 16 个点位，运行快照为 `ONLINE` / `ready=true`。
- 已实际操作：点位选择、分页（第二页 6 行）、编辑器打开/收起、点位/实时/告警/日志切换、详情实时与历史跳转、控制页与影子页导航。
- 控制/影子分区不带 `config-workspace`，仍显示原侧栏；本次观察期间无 `pageerror`。
- 1600×1000、1440×900、1280×800、1024×768 的文档和配置工作区无横向溢出，协议分组/字段无横向溢出；表格内部横向滚动为列宽保护措施。
- 悬浮已验证显示完整原始值，未为了设计而四舍五入。独立截图审查指出的长值断行、标签截断及末组空白已修正；最新 1600×1000 实际页面及协议展开截图经最终像素复核，未见必须修正的遮挡、断行或无意义空白块，状态为 `READY FOR USER VISUAL REVIEW`，不代表用户已接受。

### 未执行及发布边界

- 未提交真实协议配置保存、点位保存、启动/停止、清理缓存、同步或删除，避免改变现有采集环境；写路径仅完成调用链和类型检查，不宣称写入闭环已验证。
- Web 构建资源已同步到 `collector-boot/src/main/resources/static/desktop`；实查 9090 页面仍引用旧入口脚本，与最新构建不同。未重新打包/重启运行中的后端，不宣称现有 JAR 已加载新版页面。
- 本次临时设计服务、Vite 和 Electron 验证进程已清理；4311、5173、9341 端口已关闭，9090 原后端仍在运行。
- 本次不执行 Git 提交或推送；保留任务开始前的其他工作区修改。

## 独立优化：控制 / 设备影子实现与协议连接设计（2026-10-09）

### 范围与 Frontend Design 监督

- 用户明确授权直接实现“批量和协议命令”“设备影子”，沿用已经展示的设备影子设计体系；协议连接配置必须先给整体多协议设计，确认前不实施。
- 实施范围保存于 `.hermes/plans/device-operations-frontend-design.md`；共享 Shell 只统一设备标题、标签、基础上下文与运行态，未扩展到全局导航、主题、依赖、后端 API/DTO 或 Electron Main/Preload。
- `ControlPanel.vue`：单点 / 批量 / 协议命令本地标签，左编辑右结果；批量 JSON 格式化及只读预览，结果目标、时间、写入、读回与逐字段返回分开；预览和逐字段结果每页 12 项，完整 JSON 保留。
- `ShadowPanel.vue`：当前快照的 reported / 已保存 desired / delta 字段对照，右侧独立期望状态草稿，底部历史；完整值详情、原始 JSON、导出与读取动作保留。字段每页 20 项、历史每页 10 条，仅限制显示，不截断完整快照或导出。
- `DeviceOperationShell.vue`：三个分区共用配置工作台层级，移除控制 / 影子重复侧栏；传输连接与协议就绪使用明确布尔运行快照，不从缓存点位或 running 推断连接正常 / ONLINE。
- 真实截图检查发现 16 个 reported 字段使影子表格区域达到 820px，推动历史与编辑区失衡；已为状态表格设置 320px 内部滚动、历史表格设置 280px 内部滚动，未减少真实字段或用演示数据替换。
- 首次独立 Frontend Design 监工没有放行操作页，指出编辑器实际高度未生效、轻量文字动作出现按钮边框、控制结果栏比例不符和历史空态首列折行四项问题。已定位现有全局 / 宿主样式的覆盖关系，仅修复三个组件的局部 scoped 规则，没有扩展全局覆盖层。
- 修正后批量 JSON 编辑区实际为 185px、命令编辑区 244px，均不可拖拽；desired 编辑区 1600 / 1440 / 1280 宽度为 230px，1024 宽度为 190px。面包屑、分区标签、操作模式、格式化、字段名、原始内容和清理期望状态等轻量动作实际无边框 / 透明背景；真正操作按钮和键盘焦点保留。
- 控制结果栏宽度实际为 330px、列间距 16px；影子历史空态仍为 `colspan="4"`，修复全局 flex 覆盖后实际 `display: table-cell`、1600 宽度横跨 1312px。真实空历史仍显示“暂无影子历史”，没有补造示例记录。
- 最后源码修正后已重新执行完整允许门禁，明确 reload 到新入口 `index-vUGtNvUj.js`，等待实际 CNC 内容加载后重拍 1600 / 1440 / 1280 / 1024 图片。二次独立监工已返回：当前 CSS 与真实 DOM 记录支持四项修正，但未能读取 PNG 像素，结论为 `NOT READY — 独立像素复核尚未完成`；这不是新增页面缺陷，也不能写成无视觉问题。

### 保留的业务与异步边界

- 控制继续调用 `writeDevicePoint` / `writeDevicePoints` / `executeDeviceCommand`，保持原参数、确认框、captured target 与 `ONLINE && ready && connected` 禁用条件；右侧最近完成结果以 `actionResult.target` 归属，不伪装成当前选中设备结果。
- 写入成功与读回成功分别解释；批量部分成功按实际汇总 / 逐字段返回展示；协议命令没有统一执行成功字段时只显示请求完成与原始结果。
- 影子继续复用三个独立读 owner、版本注入、409 草稿保留和设备切换 reset。新增卸载失效与写目标 generation 校验；成功写返回会废弃较早的 current-shadow 读取，防止旧快照覆盖新版本。
- 字段对照仅来自当前影子的同一 `state` 快照，不混合独立 delta 查询版本；独立 delta 另显示版本和原始 JSON，不同版本明确提示不合并。
- 已保存 desired 的摘要不统计 JSON 草稿；缺失 / 0 / null / false 分别显示。保存期望状态不等于设备已执行，也不等于云端已确认。

### 最后源码修改后已执行验证（无测试）

| 命令 | 结果 |
|---|---|
| `npm run lint` | 退出 0 |
| `npm run stylelint` | 退出 0 |
| `npm run typecheck` | 退出 0 |
| `npm run build` | 退出 0，renderer / Electron 构建通过 |
| `npm run build:web` | 退出 0，58 个静态文件同步到后端源码目录 |
| `git diff --check` | 退出 0 |

未新增、修改或运行测试；未执行包含测试的 quality / verify。保留既有 PURE annotation 和 Element Plus 大 chunk warning，不扩大依赖或性能治理范围。

### 真实后端只读与新构建截图

- 临时 Electron 使用刚构建的 `dist/renderer/index.html`，通过现有授权桥接查询，不提取、输出或保存凭据。
- 实际设备 `pf_modbus_tcp_fanuc-cnc`：runtime / current shadow / delta / history 均 HTTP 200、业务码 200；运行快照为 ONLINE、ready=true、connected=true；影子 reported 16、desired 0、delta 0、历史 0。版本随真实采集推进，不将截图版本当作固定业务值。
- 已操作：三种控制模式切换、草稿保留、批量 JSON 格式化保持值一致、影子读取全部、字段完整值、原始 JSON、独立 delta 展开；从 CNC 切到 `pf_ab_pf_proto_ethernet_ip` 后单点草稿清空。
- 1600 / 1440 / 1280 / 1024 宽度的新构建页面均无文档 / Shell 横向溢出；窄窗口左右面板自然堆叠，表格内部滚动保留列宽与完整值；配置相邻路由仍能加载并显示字段校验通过。
- 最后修正后的影子状态面板高度约 571px、内部表格高度 320px；16 个真实字段全部保留。当前 16 个字段和空历史未触发字段 / 历史分页，不把静态分页检查宣称为真实多页联调。
- 两轮真实只读观察均无 pageerror；未提交控制写入、命令、desired 保存 / 清理、缓存清理、设备启停或导出。
- 新截图与只读记录位于 `C:/Users/wangbin/AppData/Local/hermes/cache/scratch/designs/device-operations-frontend-design/`。以最新 `actual-*-final-repair-*.png`、`control-repair-render.json`、`shadow-repair-render.json` 为本轮修正的证据，旧 `actual-*-final-*.png` 保留为首次监工前的历史，不用旧图证明修正完成。
- 最新截图无文档 / Shell 横向溢出、无 pageerror；另补 1600×1400 影子完整内容图与 1024×768 实际滚到底部图，核对历史及下方操作可达。修正后再次只读访问相邻配置工作台，确认字段校验通过、无文档横向溢出及 pageerror。

### 协议连接配置：当轮仅设计、等待确认（历史，已被下述授权实施更新）

- 主稿 `C:/Users/wangbin/AppData/Local/hermes/cache/scratch/designs/protocol-connection-unified-design/protocol-layout.html`，自包含可切换 HTML；真实 Provider 字段来源记录于相邻 `design-notes.md` / `schema-snapshot.json`。
- 代表协议为 Modbus TCP（12 字段）、EtherNet/IP（13）、OPC UA（35）、MQTT（35）、Modbus RTU（15）。它们是布局代表性覆盖，不是全部协议支持或连接联调声明。
- 统一外壳、纵向 Schema 分组与自适应字段网格；完整标签上置，端点 / 路由 / 对象等长字段占整行，高级参数逐步展开，条件要求保留，安全字段统一脱敏。
- 已用真实 Chrome 从现有独立 HTTP 服务加载，HTTP 200；5 协议 × 1600 / 1280 / 1024 宽度共 15 组合，文档 / 面板 / 分组网格无横向溢出，完整标签未裁切，无 pageerror、无业务接口请求。
- 已演示高级分组展开 / 收起、字段标识显示及保存确认 / 取消；确认只给演示反馈，不写后端、设备或浏览器持久化存储。所有输入为演示占位，安全信息为 `[REDACTED]`。
- `DeviceConfigPanel.vue`、`ProtocolDynamicForm.vue`、`protocol-form-utils.ts` 的生产协议布局本轮不改；未来仍必须消费协议 Schema，而不是把原型五协议集合做成业务白名单。
- 协议提案独立监工确认代表 Schema 与 HTML 内嵌字段一致，没有必须修正的视觉阻断项，状态为 `READY FOR USER DESIGN CONFIRMATION`。这是等待用户确认的设计稿，不代表生产保存、连接、全部协议或用户验收已完成。

### 当前待复核与发布边界

- 操作页二次监工只完成代码 / DOM 复核，独立像素复核受工具能力阻塞。已尝试隔离的只读截图对照窗口，桌面捕获未成功，不将失败的尝试伪称通过。最新真实截图和只读对照入口交用户查看，最终视觉确认仍未完成。
- 只读对照入口为 `http://127.0.0.1:4311/device-operations-frontend-design/visual-review.html`，只展示已有 PNG，不连接业务 API；协议设计入口为 `http://127.0.0.1:4311/protocol-connection-unified-design/protocol-layout.html#ETHERNET_IP`。
- Web 构建同步源码资源不等于运行中后端 JAR 已部署新版，未重启原后端。
- 不提交 / 推送，保留任务开始前的仿真器及其他无关修改。已关闭本轮临时 Electron，隔离截图窗口也已退出；9341 / 9342 均关闭，9090 原后端和 4311 只读设计预览仍可访问。没有重启后端或清理用户其他进程。

## 2026-10-09 独立优化：已确认协议连接目标图生产实施

### 授权、实施与影响面

- 用户确认 `ethernet-ip-final-target-1600.png` 并明确授权开始修改，要求 Frontend Design 全程监工和最终通过；此前“生产协议表单不变、等待确认”仅描述历史设计轮，不再代表当前状态。
- 实施计划：`.hermes/plans/protocol-workbench-approved-design.md`。仅替换设备配置工作台中央协议连接展开区；未修改后端、业务 API / DTO / Schema / storage、Main / Preload、依赖或测试，未新增 Phase 18。
- 新增 `src/features/protocol/components/WorkbenchProtocolForm.vue`：真实 Schema 分组默认纵向全展开，内容定宽、左对齐自然换行；数字 / 布尔 / 多行文本沿原 model 和验证契约，密码遮罩，未知枚举保留。对象和遗嘱文本保持既有 string 边界，不声称新增 JSON / requiredWhen 校验。
- 新增 `src/features/protocol/utils/connection-field-presentation.ts`：五种代表协议110字段展示尺寸和标签；未知协议、新增字段按真实类型 / 选项 / 输入语义回退，不设协议白名单，不把像素宽度转成 maxlength / 范围。
- `DeviceConfigPanel.vue` 接入专用表单，device+protocol key 隔离设备切换；完整说明、条件及能力通过协议徽章详情可达。保留 owner / configSession / baseVersion / CAS、读取 / 差异 / 保存处理。旧 `ProtocolDynamicForm.vue` 和本地设备编辑消费者不变。

### Frontend Design 实施中监工与修正

- 前期监工已实际读取已确认目标PNG像素，给出控件尺寸、纵向分组、宿主CSS赢者和同协议设备生命周期约束。
- 实施中独立像素监工指出运行卡过高、协议徽章被宿主原生按钮覆盖、字段计数位置和底部截图证据不完整；未把该轮结果伪称 PASS。
- 已修正运行卡为72px及紧凑状态项 / 标题分隔线、徽章23px / 11px / 常规字重、计数右对齐，底部非主操作透明背景和13px字体；保存是否启用仍由真实业务条件决定。
- 最终独立 Frontend Design 监工 `deleg_a6db8f58` 已实际读取目标图和所附末次生产截图像素，结论 `PASS`，未发现新的必须修正项。运行卡、徽章、计数、分组密度、窄宽、底部操作和点位可达的必修项均通过。
- 通过范围：所附 EtherNet/IP 完整图 / 1600底图 / 1024上下图及 MQTT1280 / OPC UA1280 / HTTP1024上图的独立像素复核，另核验五协议三宽度权威运行记录。Modbus及未附图组合仅为记录核验，未冒称逐张像素审查；不是整页逐像素完全一致、业务测试或用户最终验收。
- 允许差异：真实设备名、地址、路由、运行状态、宿主导航与真实五点位，敏感字段遮罩，协议卡相对目标下移11.625px及完整图更高。监工确认不应为了空态示例隐藏真实数据。

### 末次允许门禁与真实只读核查

| 项目 | 结果 |
|---|---|
| `npm run lint` / `npm run stylelint` | 均退出0 |
| `npm run typecheck` | 退出0 |
| `npm run build` / `npm run build:web` | 均退出0，Web源码同步57文件 |
| `git diff --check` | 退出0 |

- 未创建、修改或运行测试，未调用包含测试的 quality / verify。既有 PURE annotation / vendor chunk warning 保留。
- 临时 Electron 加载新构建 `index-BfV_1SPx.js`、`DeviceWorkbenchView-C9C5tX2r.js`，每条目标路由显式 reload 后截图，通过既有授权桥接只读真实后端，不提取凭据。
- 真实 EtherNet/IP13 / Modbus TCP12 / MQTT36 / OPC UA36 / HTTP34 字段；MQTT与OPC UA的新增 `subscriptionFallbackStrategy` 来自当前真实 Schema，走展示回退，不隐藏。HTTP证明五协议清单不是支持白名单。当前无真实 Modbus RTU 设备，未宣称其运行联调。
- 5协议×1600 / 1280 / 1024宽度共15组合，文档 / 表单 / 分组无横向溢出，无 pageerror；真实textarea为85px、resize=vertical，端口112px、槽位104px、输入35px、开关32×18px。全部组合滚动到底可见点位区。
- 共31张末次截图：每组合上下各一张，另补EtherNet/IP1600×1800完整工作台。目标PNG为1600×1284，生产更高图保留真实5点位，不拼接或伪造目标空态；匹配原1600×1100的上下截图补齐内部滚动证据。
- 同协议EtherNet/IP设备A→B实际只读切换重置组折叠状态；协议详情13项和整体折叠后字段不可见已核查。未实际保存、启停、清理、写点位、执行协议命令或提交desired。
- 最终权威证据：`C:/Users/wangbin/AppData/Local/hermes/cache/scratch/designs/protocol-connection-unified-design/production-final-runtime-settled.json` 和 `production-final-*.png`。在真实ready和CSS转场结束后捕获，15组合保存按钮均启用且computed为蓝色；不使用转场初瞬间的灰色作为最终颜色。
- 截图中凭据 / 连接串 / 敏感对象使用深蓝遮罩，未将 `[REDACTED]` 写入生产model。
- 运行中9090仍服务 `index-D3SQcMKX.js`，与新构建不同；本轮源码Web同步不等于旧JAR已部署，不重启用户后端。未提交 / 推送，保留原工作树无关修改。
- 交付前已关闭本轮自有临时Electron，实际复查9341无监听；9090原后端与4311设计预览保留。最终文档更新后 `git diff --check` 退出0。签核与交付摘要为相邻scratch `production-frontend-design-signoff.md`；最终视觉确认留给用户。

## 2026-10-10 独立优化：新建本地临时设备弹窗已批准方案生产实施

### 授权与生产范围

- 用户认可完整视觉方案并要求开始生产实施，Frontend Design 前期、实施中和修正后持续监督；实施计划为 `.hermes/plans/local-device-editor-approved-design.md`。
- 仅修改 `src/features/device/components/LocalDeviceEditor.vue` 模板及局部展示，引入新增 `LocalDeviceEditorIcon.vue`、`LocalDeviceCloudTargetForm.vue`、`LocalDevicePointFieldGrid.vue`、`LocalDeviceProtocolForm.vue`、`local-device-editor.css`；样式通过本弹窗 scoped 入口隔离。
- 基础连接、点位建模、告警规则、云平台上报、JSON 高级五 Tab 全部沿批准层次组织；固定标题、导航和底栏，主体滚动，Schema 表与 JSON 文本保留必要的独立滚动。短数字和长文本按内容定宽，不为显示增加业务 maxlength 或范围。
- 旧协议工作台、共享 `ProtocolDynamicForm.vue`、全局主题、导航、后端、依赖与 Electron Main/Preload 不改；不新增 Phase 18。

### 业务和接口保留核对

- 从 Git 基线保存源码至 scratch 后比较，`const props` 至 `</script>` 的业务状态、handlers、watchers、默认值和载荷构建在 CRLF/LF 归一后完全一致；该区 SHA256 为 `cf21d112287a3471952c8d1e541c97a88674a7886c687f75d8935bf615cfcda0`。
- 原模板 `v-model` 和事件处理绑定共62处，当前仍为62处，各表达式及次数无减少；点位和共享云身份仍编辑原状态对象。
- 专用动态协议表单沿既有 Schema 分组、options/default/storage/extJson 与 update/validate/watch 契约；真实协议和字段不是原型白名单。
- 保留 create/update、`overwrite`、`startAfterSave`、会话 generation 和迟到 Schema 隔离；“保存并测试”仍执行原处理，常驻说明明确保存与条件启动，不执行独立连接测试。JSON 草稿与已应用结构、保存边界不变。
- 真实新建默认一个点位、设备名称空、云关闭；不将参考示例设备、地址或身份写入生产默认值。

### Frontend Design 实施中发现与修正

- 前期实际看批准五图，并形成尺寸、字体、内容定宽、Schema完整性、共享字段及宿主样式约束。
- 实施中独立监工 `deleg_a8c395b9` 实际查看附件，发现旧 `implementation/` 图片全为 `#09121e`：遮罩 `#app` 的全视口矩形同时盖住 Teleport 弹窗。该批不具备像素验收条件；旧图和其 DOM 记录不能宣称生产视觉通过，也不据此判定生产页面自身空白。
- 当前捕获改为截图时隐藏背景子树、仅遮罩密码控件；另定位自有 Electron 最小化导致截图等待，在不抢输入焦点的情况下恢复该捕获窗口。没有改生产渲染器/业务状态以制造截图。
- 修正基础身份网格，使长字段不再受两个145px短控件槽限制；点位概览补真实完整度进度线；告警补副标题和开关/触发预览语义说明；云映射状态加 badge，云关闭仍显示真实状态。
- 修正 Schema 末行记录为实际 `tbody` 最后一行，并核对整行矩形可见；高视口图重命名 `tall-top`，不冒充完整内部展开长图。
- 第一轮修正的38张有效截图提交 `deleg_82a04dcb` 六份 Frontend Design 只读像素复核；该轮没有给整窗无条件PASS，后续问题和最终签收见下文。

### 修正后的允许门禁与真实截图

| 项目 | 结果 |
|---|---|
| `npm run lint` / `npm run stylelint` | 均退出0 |
| `npm run typecheck` | 退出0 |
| `npm run build` / `npm run build:web` | 均退出0；Web源码同步57个文件 |
| `git diff --check` | 退出0 |

- 未新增、修改或运行测试；未运行包含测试的 quality/verify。保留既有 PURE annotation 和 vendor 大 chunk warning，不扩大依赖/性能治理。
- 第一轮修正图为 `C:/Users/wangbin/AppData/Local/hermes/cache/scratch/local-device-editor-production/repair-visible/`，报告 `render-report.json`；当轮真实 Electron 加载 `index-CjCD_DAg.js`，既有 preload 桥接可用。最终权威图使用下文 `final-visible/`，不沿用此轮入口冒充末次构建。
- 38张图、41条记录，PNG抽样均有多种颜色；五 Tab 正常高度底部、点位高级末端与告警规则底部可达，footer可见，记录无文档/弹窗/主体/字段网格横向溢出，无 pageerror/console error。
- 五张 Schema 参考表均 `atEnd=true`、实际末行整行可见：协议 `addressMode`、点位 `remark`、上报 `additionalConfig.eventMinIntervalMs`、告警 `description`、元数据 `cloudTarget`。JSON文本自身滚到底；像素可读性由本轮独立监工另判，不用 DOM 代替像素。
- 五种代表协议 MODBUS_TCP/ETHERNET_IP/MQTT/OPC_UA/HTTP，以及1280/1024/640宽度下五 Tab记录均来自真实新建未保存草稿，不是业务连接测试。
- 未保存、启动、停止、清理或写设备，未提交协议命令/desired；未提交/推送。`build:web` 仅同步后端源码静态资源，不等于运行中9090旧JAR已部署；原后端与既有4311设计服务保留。

### 第二轮实际像素问题闭环与最新构建

- `deleg_82a04dcb` 六份监工均实际查看所附图片；云上报、JSON内部末端及相应窄屏/主体底部获得范围PASS，但基础/点位/告警与协议组仍指出四项必须修正，故当轮没有整窗无条件PASS。
- 已修正点位编辑卡启用标签为原状态对应的绿色；告警触发预览最后的等级标签复用现有 `alarmLevelClass` 配色；专用协议表单的布尔控件给文字独立自然宽度、禁止折行与收缩；弹窗内 Element 数字输入去除原生 spin 外观，只保留原组件的步进按钮。均不改值、handlers、校验或业务状态。
- 末次生产修改后再次执行 lint、Stylelint、typecheck、Electron build、Web build，均通过；在仓库原 Git 换行配置下 `git diff --check` 退出0。临时关闭 autocrlf 的检查曾将CRLF误判为尾部空格，已撤回该命令参数，未为此重写或格式化生产文件。
- 最新权威图片/报告在 scratch `local-device-editor-production/final-visible/`：62图、65记录，新入口 `index-DBqXg9DV.js` 与末次构建一致；PNG抽样空白图为0，无记录横溢、pageerror或console error。另有 `final-source-evidence.json` 保存业务区一致、SHA256和62/62绑定核对结果。
- 点位/告警启用标签computed为 `rgb(129,213,181)` / `rgb(24,61,54)`，警告预览为 `rgb(244,191,123)` / `rgb(66,55,40)`；协议启用/禁用文字均20px高、nowrap；数字输入appearance为textfield。以上仅为DOM证据，最终仍由实际像素签收。
- 已补1280/1024/640宽度五Tab底部图，15组均主体到底且footer可见；五协议另按主体高度重叠滚动补9段图片，保留动态Schema全部内容，不拿顶部图代替完整下方覆盖。
- `deleg_60f7ce4e` 使用末次新图进行最终复核时五个代理全部遭HTTP 429配额限制；该批没有PASS，不要求用户重新提交开发请求。后续恢复小批实际像素复核，最终结论见下文。

### 最终 Frontend Design 附图范围签收

- `deleg_02484114` 实际看最终构建图片，确认编辑卡绿色启用标签、琥珀警告预览与 EtherNet/IP 开关单行通过，640协议末端/footer可见；另一份监工对JSON顶部/文档末端、云顶部、点位高级末端、640 JSON底部与HTTP末端给予范围PASS。
- 数字控件仍被首份监工怀疑有额外箭头，未据DOM强行放行。父代理读取真实单位和控件结构，并采集125/2000/64三个控件原始局部图及nearest-neighbor三倍像素图；未重画控件或删除单位。
- `deleg_944209bd` 实际查看高清局部及对应整图，确认数字旁符号是中文计数单位“个”，最右侧只有一套上下步进按钮，明确撤回“双套箭头”must-fix；EtherNet/IP真实字段为“单次最大字段数”而非字节数。另一份监工实际逐图确认MQTT和OPC UA上下分组、HTTP顶部的控件定宽、开关单行、安全/条件说明与可见末端通过。
- 最终签收范围：`final-visual-review-manifest.json` 列出的18张末次主/滚动截图和4张实际局部图；五Tab、代表五协议、重点640底部及必须修正项已纳入实际像素复核。其余生成图片和65条DOM记录是辅助证据，不冒称62张全都逐图像素签收。
- 状态为 `READY FOR USER VISUAL REVIEW`：所有已识别的must-fix已闭环或凭高清证据撤回，没有遗留未修must-fix；不是整窗每一像素完全一致、业务保存/启动/连接测试、运行中JAR部署或用户最终验收。
- 最终截图/签收资料及ZIP位于 scratch `local-device-editor-production/`，仅交付末次有效图片，旧纯色 `implementation/` 不打入交付包。不提交或推送，原无关未追踪文件保留。
- 已关闭本轮自有Electron `proc_6e027fde0432`；2026-10-10 10:07:44 +0800实际复查9342无监听，9090原后端与4311既有设计服务仍监听。未清理用户其他进程，未重启后端；记录为scratch `cleanup-evidence.json`。

## 2026-10-10 独立优化：设备管理批准方案生产实施

### 范围与业务保留

- 用户批准设备管理设计并要求Frontend Design全程监工后实施；基线 `e94e68fb`，计划 `.hermes/plans/device-management-approved-design.md`。
- 修改 `src/views/device/DeviceListView.vue`，新增 `DeviceListIcon.vue`、`device-list.css`、`device-list-presentation.ts`。标题/筛选、五列登记册、独立滚动、固定计数栏、状态分层及分组更多菜单均按批准稿落实；1600/1280五列，1024双列，640单列。
- 共享220px导航/40px顶栏、全局主题、API/DTO/Store/路由、依赖及Electron Main/Preload不改；已交付五Tab本地设备弹窗直接复用，不重新设计，不新增Phase18。
- 原24处模板业务绑定完整保留，17个原操作按钮的disabled表达式逐项一致；只有启动、停止、刷新配置、清理缓存、删除本地保留逐设备写锁，配置/编辑/导航及更多入口不额外锁定。
- 最初原业务区与Git来源baseline一致。最终三处命令式确认窗仅增加7行呈现选项：customClass三行、customStyle三行、delete confirmButtonType danger一行；去除这7行后原业务区仍完全一致。不宣称最终逐字节相同，但handler、确认文字/取消分支、watcher、defaults、载荷和生命周期保持不变。
- 未注入设计示例；运行阶段、通信就绪和采集健康独立显示，缺值未知，旧快照计数/有效时间提示来源。数量首次加载/失败未知与成功0结果分开，旧列表保留上次加载提示。

### 全程 Frontend Design 监督与闭环

- `deleg_992911c7` 实际看批准图，形成实施前尺寸和逐按钮禁用清单。
- `deleg_a61b5d2c` 实施中实际看生产图：宽屏/窄屏方向通过，筛选无结果主标题重复必须修正；已条件隐藏重复辅助文字，不改空态业务helper。
- `deleg_17a9c4a8` 首次最终实际看14图：台账/窄屏/空态通过，但两个确认窗横贯窗口、删除按钮蓝色、背景mask盖住部分后果提示不通过；不把该轮标为整体PASS。
- 确认窗增加本页unique class/style，500px限宽与紧凑边距，删除为红色danger，其余确认蓝色。截图改为只隐藏背景敏感文字，避免mask矩形穿过overlay盖住确认正文；生产数据未改。
- 最后源码修改后再次六门禁通过，显式reload最新renderer并采图；`deleg_189e6e61` 三组实际逐张审阅4/6/4张末次生产图，对台账、四宽度、菜单、空态及两个确认窗全部给予所附范围PASS，无遗留must-fix。

### 末次门禁、真实截图与边界

| 命令 | 结果 |
|---|---|
| npm run lint / npm run stylelint | 均exit0 |
| npm run typecheck | exit0 |
| npm run build / npm run build:web | 均exit0；同步57文件 |
| git diff --check | exit0 |

- 未新增、修改或运行项目测试，不调用含测试的quality/verify；既有PURE annotation/vendor chunk warning保留。
- 权威证据在 `C:/Users/wangbin/AppData/Local/hermes/cache/scratch/device-management-production/final-confirmation-repaired/`：14张PNG、12条布局记录、2条确认窗记录；入口 `index-eRHN2GYP.js` 与末次构建一致、原preload桥接可用，真实列表31台本地设备。
- 四宽度没有文档/页面/列表横溢或菜单越界，pageerror/console error为0；1280/1024/640底部实际scroll-at-end且最后一行完整可见。两个确认窗500×215.59375居中，危险删除与普通确认区分，后果文字完整可读。
- 实际只做行选择/Enter选择、菜单外点击/Escape/resize关闭、删除/清理确认后取消、本地新建弹窗打开/取消；未提交设备写入、启停、同步、导入导出、缓存清理、协议命令或desired。
- 当前没有远端设备，远端菜单未实际观察；首次loading、成功空列表、请求错误、写锁及导入覆盖确认也未在真实环境观察，不注入状态制造截图。旧五Tab组件源码未改，新弹窗截图超时仅记录DOM打开/取消，不重签弹窗像素。
- 14图全部实际像素复核，但遮罩内容不评价，640空态没有同宽批准图，仅通过可见布局/层级。真实数据差异、设计免责声明不进入生产导致台账上移、ID换行和确认窗不显示设计预览说明属于允许差异。
- 当前 `READY FOR USER VISUAL REVIEW`：所附范围PASS不等于全状态逐像素一致、业务写入/采集验证或用户最终视觉接受。
- dist/renderer与后端源码static/desktop集合/字节一致57文件，仅资源同步不是部署；运行中9090 HTTP200仍为 `index-D3SQcMKX.js`，未替换/重启旧JAR。
- 2026-10-10 11:26:13 +0800关闭本轮自有 `proc_5c1ca414f31b`，实查9343无监听，9090原后端和4311既有设计服务保持监听。没有清理用户其他实例。
- 报告 `PRODUCTION-VISUAL-REVIEW.md`、逐图 `final-visual-review-manifest.json`、源码 `final-confirmation-source-evidence.json`、门禁/清理及ZIP位于上述scratch根目录。交付只打包末次14张有效图，不纳入中间失败/待修图或凭据。
- 未提交/推送本轮代码，无关后端修改与原未追踪文件保留；上一弹窗提交为 `e94e68fb`，不混同本轮设备管理完成。

## 2026-10-10 独立优化：概览 / 实时数据 / 历史趋势批准方案生产实施

### 范围与业务保留

- 用户批准三页方案并授权Frontend Design全程监工；基线与当前HEAD为 `c1c57cbfd7dfa03230b9a4dddbf84eca0088d484`，分支 `feature_2.0`。计划 `.hermes/plans/monitoring-three-pages-approved-design.md`，不新增Phase18。
- 修改三个View，新增分别由scoped入口加载的dashboard/realtime/history-view.css；不改共享220px导航、40px顶栏、主题、API/DTO/Store/Router、Electron Main/Preload、依赖及本地设备弹窗。
- 原三个业务script按UTF-8/LF一致；历史只追加 `historyPresentation` 与 `historyChartPresentation` 两个只读展示computed，比较去除新增块前的一行空白分隔。40处model/event/disabled表达式完全一致（6/21/13），56个保护文件散列无漂移。
- 概览保留六摘要、原真实告警/风险列表、资源环与线程条；批准CSS双栏1.45:1，不沿用早期粗估1.25:1。
- 实时保留原5秒刷新、完整健康快照、增量/分页/单点请求、13列及原精度；主表420px内滚动，不截损完整数据。
- 历史保留五查询字段、多选、六摘要、曲线/JSON双栏、5列告警与4列记录；原series.points/color、按样本序号与统一原始数值尺度、统计/格式化/失败保持语义不改。

### 三个 Frontend Design 检查点与修正

- 前期批准三张设计实际图像读取成功，记录在交付证据的PREIMPLEMENTATION-DESIGN-REVIEW.md，原型不是生产验收。
- 实施中 `deleg_141e90c4 / sa-0-237c3fa9` 加载技能并实际查看三张左右比对与640六视图拼图，返回CHANGES_REQUIRED。
- 页面浅层选择器修复共享壳覆盖按钮；实时表正文11px提高至12px，作为真实长字段可读性小偏离记录，不舍入；两处UNKNOWN只在模板中文化，原状态/helper/script不改。
- 云状态点改行内均衡换行；窄拓扑用蛇形grid避免孤箭头；历史两个空态移出宽表滚动区，保留原条件/文案及列结构。
- 最后源码修改后重新六门禁、reload末次构建及新截图。`deleg_1546e7bd / sa-0-a4d631df` 实际查看四张附图，返回“附图范围PASS，无remaining must-fix”，限宽屏与640当前离线/未选择状态；不是25张逐图签收、全状态通过或用户最终验收。
- CLI辅助调用遇到429/timeout，概览单图成功后默认provider返回401，整次调用失败；没有修改Hermes/provider/model配置或读取凭据，改用原生images委派。失败CLI未计入成功签收。

### 末次门禁、产物及实际运行记录

- `git diff --check`、`npm run lint`、`npm run stylelint`、`npm run typecheck`、`npm run build`、`npm run build:web` 均实际exit0；不新增、修改或运行测试，不运行含测试quality/verify。既有PURE/vendor chunk警告保留。
- 末次真实renderer为 `index-Co4ELAHL.js`，入口与dist一致，preload桥接存在；final-offline含25张原图、3张左右标识比对及1张640六视图拼图。
- 原图记录覆盖1600/1280/1024/640及窄屏顶/底、局部滚动；25条记录pageerror/console.error均0，外层横向溢出记录0。computed主按钮34px、单点36px、分页29px；精确尺寸来自DOM，不伪称像素模型测量。
- 修正前真实后端可用时已读取200/500真实行，420px表内滚动末行完全可见，单点JSON末端可达；这些为修正前证据，不能替代末次数据态补审。
- 57个renderer文件与源码static/desktop完整路径及字节一致，只是build:web资源同步，未打包或部署JAR。
- 仅截图样式隐藏密码控件与潜在敏感错误自由文本并保留布局，没有注入Store/响应或示例状态；隐藏区域不评价。

### 当前阻塞与交付边界

- 2026-10-10 16:05及16:19实查9090均拒绝连接，父代理未停止或擅自重启后端；待用户启动原服务或明确授权启动后，再采末次真实数据态并让Frontend Design补审。
- 修正后真实表格正文、UNKNOWN中文标签、长云风险文案及真实告警/历史成功曲线仍未实际观察。原环境历史存储不可用，不修数据库、Redis或配置来制造成功画面。
- `.editorconfig`要求CRLF；最终实查历史Vue已为766处CRLF、无单独LF，三个新CSS仍LF。完整读取后CRLF write_file被账本误判partial拒绝，拒绝调用未修改文件，未shell绕过；三个CSS格式规范尚未闭环，不能以门禁通过掩盖。
- 当前 `IMPLEMENTED / ATTACHED-STATE VISUAL PASS / POPULATED-STATE REVIEW PENDING`；整体最终验收尚未满足，用户视觉接受由用户决定。
- 交付 `C:/Users/wangbin/AppData/Local/hermes/artifacts/monitoring-three-pages-production-v1/`：PRODUCTION-VISUAL-REVIEW.md、末次截图与capture-report、final-contract-check、前期/实施中记录及ZIP；旧待修图不冒充末次产物。
- 交付前六项允许门禁再次exit0，重建57个renderer文件字节与已送审构建完全一致，入口保持index-Co4ELAHL.js。仅关闭本轮自有截图Electron `proc_5710ec7a4eb8`，原生终端实查9344无监听10061；9090与既有4311亦无监听10061，未启动或停止它们。
- 未提交、推送、部署或重启9090，不执行设备写入、启停、删除、同步、清缓存、导入导出、点位写入、协议命令或desired提交；无关后端改动和原未追踪文件保持。

### 2026-10-10 用户授权后的原服务恢复补审

- 上述离线交付记录保留为当时事实；用户随后授权启动原现有9090服务，本轮已实际启动既有JAR并确认TCP可连接、health HTTP200/body DOWN，不是全依赖健康或新JAR部署。
- 前序末次index-Co4ELAHL.js通过原认证桥接补采27张真实原图及4张派生图，未注入数据或执行写入。旧截图程序固定serviceNotRestarted字段不能描述本次生命周期，权威操作是获授权启动已停止服务。
- deleg_a1783768 / sa-0-a40458a8加载frontend-design并实际看4张附图，当前状态/尺寸PASS，无remaining must-fix；真实指标、长名称/线程值/云状态说明、12px正文及640拓扑/两表空态已补审。
- 成功历史曲线/记录、真实采集长精度数值、640横滚后所有列及历史曲线中段未覆盖；27原图非逐图签收，敏感隐藏正文不评价。前序三个CSS换行问题未修，用户接受待用户确认。
- 补审记录：scratch/operations-five-pages-production/RESTORED-THREE-PAGES-REVIEW.md；没有替换前序v1交付包或把五页门禁混成三页新构建。

## 2026-10-10 独立优化：采集配置 / 云平台配置 / 系统诊断 / 日志 / 网络检测五页收口

### 实施与契约边界

- 用户授权在既有深色工业工作台内优化五个运维页面；本轮仅调整五个View模板呈现和各自scoped页面CSS，保留220px导航、40px顶栏、共享主题、Router、API、DTO、Store、请求生命周期、控件模型/事件/disabled及共享组件业务源码。
- 五页原script均按LF基线一致：采集11、云13、诊断13、日志19、网络16，共72处原绑定；158个既有生产保护文件无漂移。五页View与新页面CSS最终均为CRLF。诊断仅将既有warning状态呈现为琥珀色，未改tone/状态计算。
- 云平台继续作为真实链路监控，不新增凭据表单或保存逻辑；网络检测、导出、设备写入、启停、删除、同步、缓存清理、云Replay/Flush/测试、协议命令均未执行。

### 允许门禁与真实效果图

- 末次 `git diff --check`、`npm run lint`、`npm run stylelint`、`npm run typecheck`、`npm run build`、`npm run build:web` 均实际exit0；不新增、修改或运行测试，不调用quality/verify。构建保留既有Vite PURE annotation与vendor chunk warning。
- 末次renderer入口为 `index-BM7lzMzh.js`；Electron/Web构建资源完整57文件，dist/renderer与后端源码static/desktop路径及字节一致。资源同步不等于运行中9090部署。
- 末次真实capture为90张原图、12张派生/对比图；入口页面无pageerror/console error，外层无横向溢出。覆盖1600/1280/1024/640及页面底部、内部表格/面板滚动末端；真实数据态仅按当前可达依赖采集。
- `deleg_4af70a33`对采集、云、诊断、日志、网络所附末次图完成实际像素审查；日志补图 `deleg_2c498bc2`确认左端时间/级别/记录器、右端日志消息及两端空态完整可读。五页当前附图范围均PASS，无remaining must-fix；不等于全部原图逐张签收、所有业务成功/失败状态验证或用户最终视觉接受。

### 运行与交付边界

- 按用户授权启动的是原有JAR；9090可达，`/collector/health` HTTP200但health为 `DOWN`，数据库/Redis等依赖未修复。没有部署新JAR、提交、推送或重启替换运行服务。
- 本轮自有9345截图Electron已关闭（实查无监听）；用户授权启动的原9090服务保持运行。交付目录为 `C:/Users/wangbin/AppData/Local/hermes/artifacts/operations-five-pages-production-v1/`，包含五页末次PNG、报告、契约检查、构建日志和ZIP；敏感正文只在截图层隐藏，不改生产数据。

## 2026-10-10 独立任务：五页综合设计分析、打包与9090重启

- 最新用户授权为“综合分析优缺点、提出优化建议、给出效果图，最后重新启动打包9090”。本轮未实施新的设计建议，不修改五页生产Vue/CSS、API/DTO/Store/Router/业务处理，不新增Phase18、不提交推送、不编译或运行测试。
- 已使用frontend-design读取五页模板/CSS及设计基线，独立源码审查后核实：采集/诊断未知与零值区分、云指标/队列新鲜度、诊断JSON默认展开、日志过滤说明层级、网络当前配置/结果边界。日志已有table/row/columnheader/cell语义，已撤回“缺少行列ARIA”推断。视觉建议与源码确认分别表述。
- 首次五图独立视觉审查上游超时；重派真实联系图后返回HTTP 401 / API_KEY_DISABLED，未获得像素分析。本轮不作新的视觉PASS、不修改provider或读取凭据，不以旧截图签收新运行状态。
- diff-check、lint、Stylelint、typecheck、Electron build、Web build均exit0。Git Bash Maven Launcher失败后直接用已安装JDK17/ Classworlds运行。当前工作区16模块生产输入隔离快照 `-pl collector-boot -am -Dmaven.test.skip=true clean package` 为BUILD SUCCESS，未复制/编译测试源。该产物不是新提交版本。
- 新可执行JAR为 `collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar`，SHA-256 `b73e82b1b4f2556535bfcf4be91aa714744a9f230d2c80f7ad3514e564497e1d`。打包前未触碰被旧进程占用的原JAR；已保留本地可执行回滚包 `data-collection-service-pre-frontend-design.jar`。只停止已核实的原9090 PID6540，端口释放后替换并以原JDK/编码/工作目录启动，实际新PID32160。
- HTTP读回完整57个页面资源均200、路径集合/字节与renderer一致，入口index-BM7lzMzh.js。health HTTP200但DOWN；application/cache为UP，collectionService为DOWN（停止，运行设备0），connections为UNKNOWN。未修依赖、未启动设备、未执行云测试/网络检测等业务写入。
- 重启后真实Electron renderer补采90张1600/1280/1024/640及内部/底部滚动图，pageerror/console error为0，外层无横向溢出；截图敏感自由文本仅样式隐藏，不改生产数据。新截图不是新建议已实现，也未全部逐像素签收。完整renderer路径/SHA与前次审核构建保持一致。
- 交付目录 `C:/Users/wangbin/AppData/Local/hermes/artifacts/operations-five-pages-design-audit-release/` 保存综合报告、真实PNG、安全运行/构建摘要和验证后的ZIP。仅关闭本轮自有9345截图Electron；用户要求的9090新服务保持运行。

## 2026-10-11 独立优化：五页Frontend Design建议实施后的真实像素收口

### 实施、构建与审查身份

- 用户要求按Frontend Design建议实际实施并持续复核，范围仍限定为采集配置、云平台配置、系统诊断、日志、网络检测五页；不改共享组件业务源码、API、Store、Router、请求顺序、参数、权限和业务写入路径。
- 末次候选真实renderer入口为 `index-DsbgyuIk.js`；审查使用的57个renderer资源在补拍前后按完整相对路径和SHA-256核对一致。9090仍服务前序入口 `index-BM7lzMzh.js`，没有把候选源码冒称为9090已部署。
- 末次允许门禁 `git diff --check`、`npm run lint`、`npm run stylelint`、`npm run typecheck`、`npm run build`、`npm run build:web` 均实际exit0；未新增、修改或运行测试，未执行quality/verify聚合命令。
- 首轮独立Frontend Design像素复核为 `deleg_2b4ea181`，实际查看五页40张附图；采集细节、云动作时间和日志行容器补审为 `deleg_d06f90d1`，实际查看8张原尺寸/原像素派生局部图。两次均不是仅读源码或DOM结论。

### 真实图片范围结论

- 48张图片在各自声明的视口、滚动位置和业务状态范围内获得 `PASS`，没有当前图片支持的遗留pixel-level must-fix：采集配置13张、云平台配置10张、系统诊断8张、日志9张、网络检测8张。
- 采集配置补审确认概览→导出/导入→同步层级、唯一普通蓝色刷新主操作、导入/同步中性outline、640宽协议表左右横滚端点与Custom UDP末行完整可见。
- 云平台补审确认两条独立读取时间的对齐可读性及Replay/Flush风险说明层级；系统诊断和网络检测保留真实降级/未知/未执行分层，不用伪造成功态取得视觉PASS。
- 日志补审仅签可见时间、级别、记录器、线程、消息列和行容器结构；原始自由正文在截图层隐藏，不能据此签正文可读性。云详情/Replay/Flush确认、网络成功/失败、导出/同步/检测等未执行状态同样未签业务结果。

### 交付、运行边界与最终状态

- 冻结交付目录：`C:/Users/wangbin/AppData/Local/hermes/artifacts/operations-five-pages-recommendation-final/`。其中包含五页主图、48张明确签收图片、Frontend Design最终报告、构建/契约/脚本差异安全证据、相对路径SHA-256清单及重新打开校验过的ZIP；ZIP哈希成员58、连同manifest归档总成员59，归档SHA-256记录在 `artifact-receipt.json`。
- 交付总览图为 `effects-contact-sheet.png`；具体真实主图位于 `screenshots/candidate/`，补审局部图位于 `screenshots/supplement/`。报告只把48张列入的当前图片称为PASS，不把候选目录其余图片或历史图片追认为逐图签收。
- 9090运行边界保持独立：既有服务页面HTTP200，入口仍为 `index-BM7lzMzh.js`，health HTTP200但业务状态为 `DOWN`；本轮不重启、不替换、不修依赖。自有截图Electron已关闭，9345实查无监听。
- 当前状态：`SCOPED VISUAL PASS / READY FOR USER ACCEPTANCE`。该状态表示Frontend Design对交付图片范围已通过，不等于全部106张逐图签收、敏感正文可读性、未执行业务成功/失败态、业务联调、9090部署或用户最终接受。

## 2026-10-13 全部授权后的五页剩余收口：静态完成，临时认证输入待补

- 本窗口授权原文为“全部授权 把解析来的任务完成”。继承剩余真实状态/交互与生命周期、新build真实图及Frontend Design终审、最终报告和ZIP；不把授权解释为读取秘密、造测试数据、提交push或修改既有业务/服务/运行包。
- 已重跑lint、Stylelint、typecheck、task-owned scratch Vite renderer build及Electron TypeScript编译，五项exit0；第三方PURE注解/大chunk警告保持原状态。`git diff --check`exit0；没有执行测试或quality/verify聚合命令。
- `full-closure-authorized/CANDIDATE-IDENTITY.json`冻结最新build的60个完整资源及158个实际源hash，完整资源集合与前序焦点候选完全相同，entry为`index-CUrdtFzV.js`；不能仅以entry相同替代完整集合核证。原Boot资源仅身份记录、未同步替换；HEAD及既有工作树保留。
- 执行计划与本轮观察工具独立保存在scratch，按真实API定义guard原GET和有限本机网络操作；不读取接口正文/凭据/raw日志，不持久化认证。尚未创建新认证浏览器、截图或accepted-source观察，也没有执行配置提交/同步/ingest/Replay/Flush或制造业务记录。
- 当前阻塞是输入而非许可：按前轮不保留认证的要求，凭据已不在本窗口内存；不能从`[REDACTED]`恢复。本地任务终端`proc_31798cd5b732`已真实显示不回显输入提示，状态文件读回`WAITING_FOR_LOCAL_EPHEMERAL_CREDENTIAL`，须由用户本地输入原凭据，不发送到聊天。该临时上下文不改9090/9346服务或其配置。
- 原条款盘点`deleg_3738a697`已完成并实际消费。JSON为266240 bytes、SHA `7b7838fbb20eb30243bafec924f9609f9c7b5e47069048fbcf26c02797385422`；Markdown为83989 bytes、SHA `061dd620e6a87448e54916a363f45c3393b6e81da030399571eafa7583b076d2`。`PARENT-INVENTORY-READBACK.json`逐套对原文件核id/priority/criterion全文、顺序与原文SHA，共120条（云/诊断42、日志/网络42、共享/采集36）一致；28个同名ID的80次出现独立保留。P0/P1/P2为50/38/32，9个输入manifest与9个安全源snapshot全部匹配；原84、旧157子门禁及共享/采集新增51证据路由分支各自保留，208不是原条款数量。10条待采动作不等于已执行；没有签新PASS。历史两套S-NAV-04 FAIL/82 BLOCKED、共享/采集旧限定像素结论及专项`SCOPED_MF_FOCUS_01_PASS`分别保留。
- 原盘点不能授予或撤销父级既有许可：Q2一次task-owned真实root卸载/cleanup可以观察，但不制造延迟、断网、拦包或竞态，也不把换route当root卸载。真实0/false/空/混排序/accepted-owner/last-good若未自然出现仍缺证；秘密原文不因全部授权可读取。执行计划已按该无测试/无制造状态边界收紧，未改不可变盘点文件或原合同。
- 新像素终审、真实成功/last-good/混排序/隐藏全文/accepted-owner条件以及最终效果图ZIP仍PENDING。旧PNG或HTTP200不替代本窗口的新证据；秘密全文和现环境不存在的真实状态不能单靠全部授权变为PASS。
