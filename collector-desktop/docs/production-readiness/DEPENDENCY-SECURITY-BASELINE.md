# Task 07.1 — Dependency Security Baseline & Vulnerability Audit

## 1. Scope

本基线覆盖当前仓库 `DABIN-HUB/data-collection-service` 的 Java/Maven 后端与 `collector-desktop` Electron/npm 前端：

- Java / Maven direct and transitive dependencies
- `collector-boot` executable Spring Boot runtime JAR contents
- Electron / npm direct and transitive dependencies
- Electron packaged runtime reality (`files: [dist/**/*, package.json]`, `asar: true`)
- Build toolchain and packaging dependencies
- Known vulnerabilities from npm audit, OSV/GitHub Advisory, Electron official lifecycle, and attempted OWASP Dependency-Check
- Lockfile / integrity / repository / registry supply-chain baseline

本轮是 **AUDIT FIRST**：没有执行 `npm audit fix`、没有生产依赖升级、没有 Spring Boot / Electron / Vue / PLC4X / Netty major upgrade。

## 2. Baseline Commit

- Baseline commit: `749510c937eb1400224a5140324356232f016b91`
- Local `git log -1 --oneline`: `749510c Task UI-04`
- Branch: `feature_2.0...github/feature_2.0`
- Working tree at audit start/end still包含用户自管的 untracked `.hermes.md`，本任务未修改它。

## 3. Audit Date

- Audit date: `2026-09-14 17:32:33` local host time

## 4. Java Dependency Inventory

Source of truth used for Java runtime classification:

1. `cmd.exe` equivalent Maven via local Maven executable: `mvn -pl collector-boot -am dependency:tree`
2. `mvn -DskipTests package`
3. `collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar` `BOOT-INF/lib/**` inspection
4. `mvn -pl collector-boot dependency:tree -Dverbose`
5. OSV query over actual runtime coordinates parsed from the verbose tree and packaged runtime JAR evidence

Counts:

| Metric | Count | Evidence |
| --- | ---: | --- |
| `collector-boot` packaged runtime JARs in `BOOT-INF/lib` | 188 | executable JAR inspection |
| Internal project runtime JARs | 15 | `collector-*` modules |
| Third-party packaged runtime JARs | 173 | `BOOT-INF/lib` minus internal modules |
| `collector-boot` direct compile/runtime dependencies | 25 | `collector-boot/pom.xml` |
| Direct internal compile/runtime dependencies | 15 | `com.wangbin:*` modules |
| Direct external compile/runtime dependencies | 10 | Spring Boot starters, MyBatis, Micrometer, Lombok |
| Runtime coordinates parsed for OSV scan | 163 | actual-ish packaged Maven coordinates |

Important packaged runtime families confirmed present:

| Family | Resolved Version(s) | Runtime Evidence |
| --- | --- | --- |
| Spring Boot | `3.2.0` | `spring-boot-3.2.0.jar`, starters in dependency tree |
| Spring Framework | `6.1.1` | `spring-web`, `spring-webmvc`, `spring-core`, etc. |
| Tomcat | `10.1.16` | `tomcat-embed-core`, `tomcat-embed-websocket`, `tomcat-embed-el` |
| Netty | `4.1.100.Final` | 35 Netty runtime JARs, including HTTP/2, MQTT, Redis codecs |
| Fastjson2 | `2.0.43` | `fastjson2-2.0.43.jar` |
| Guava | `32.1.3-jre` | `guava-32.1.3-jre.jar` |
| Caffeine | `3.1.8` | `caffeine-3.1.8.jar` |
| Commons Lang3 | `3.14.0` | `commons-lang3-3.14.0.jar` |
| TDengine JDBC | `3.7.1` | `taos-jdbcdriver-3.7.1.jar` |
| PLC4X | `0.13.0` | `plc4j-*0.13.0.jar` drivers/transports |
| DigitalPetri Modbus | `2.1.3` | `modbus-*2.1.3.jar` |
| jSerialComm | `2.11.2` | `jSerialComm-2.11.2.jar` |
| j60870 | `1.7.2` | `j60870-1.7.2.jar` |
| iec61850bean | `1.9.0` | `iec61850bean-1.9.0.jar` |
| Californium | `3.11.0` | `californium-core`, `element-connector` |
| Eclipse Milo | `1.0.8` | `milo-sdk-*`, `milo-stack-core`, `milo-transport` |
| SNMP4J | `3.9.6` | `snmp4j-3.9.6.jar` |
| Eclipse Paho MQTT | `1.2.5` | MQTT v3 and v5 clients |
| MyBatis | `3.5.14` / starter `3.0.3` | MyBatis runtime JARs |
| Springdoc / Swagger UI | `2.3.0` / `5.10.3` | OpenAPI UI runtime JARs |

## 5. collector-boot Runtime Tree

`collector-boot` is the final Java product entrypoint. Its runtime contains all protocol modules, web MVC, embedded Tomcat, Redis client stack, telemetry/monitoring, storage, protocol stacks, and OpenAPI UI.

Key exposure classification:

| Runtime Area | Dependencies | Exposure | Reachability |
| --- | --- | --- | --- |
| HTTP / REST / static desktop UI | Spring Boot Web, Spring MVC, Tomcat, Jackson | `NETWORK_EXPOSED` | `CONFIRMED_REACHABLE` because `collector-boot` starts embedded web server |
| Actuator / Micrometer | Spring Boot Actuator, Micrometer, Prometheus client | `NETWORK_EXPOSED` / `INTERNAL_API` depending endpoint config | `LIKELY_REACHABLE` |
| Redis cache/client | Spring Data Redis, Lettuce, Netty Redis codec | `DATABASE_INPUT` / internal network | `LIKELY_REACHABLE` if Redis enabled |
| TDengine | `taos-jdbcdriver`, Apache HttpClient | `DATABASE_INPUT` | `POSSIBLY_REACHABLE`, environment dependent |
| PLC / Modbus / OPC UA / IEC / MQTT / SNMP / CoAP | PLC4X, DigitalPetri Modbus, Milo, j60870, iec61850bean, Paho, SNMP4J, Californium, Netty codecs | `NETWORK_EXPOSED` outbound/client; some may listen depending protocol implementation/config | `LIKELY_REACHABLE` when corresponding collector protocol is enabled |
| JSON/XML/YAML parsing | Jackson, Fastjson2, Woodstox, SnakeYAML, swagger parsers | `CONFIG_INPUT`, `NETWORK_EXPOSED`, `FILE_INPUT` | `LIKELY_REACHABLE` for HTTP JSON; protocol/parser-specific for XML/YAML |
| Lombok | `lombok-1.18.30.jar` is packaged | runtime not needed | `NOT_REACHABLE_BY_CURRENT_USAGE`; packaging hygiene issue |

## 6. npm Dependency Inventory

`collector-desktop` package manager evidence:

| Item | Value |
| --- | --- |
| Package manager | npm |
| `package-lock.json` lockfileVersion | 3 |
| Lock packages, excluding root | 781 |
| Lock packages with `resolved` | 781 |
| Lock packages with `integrity` | 781 |
| Lock prod-ish packages | 75 |
| Lock dev packages | 706 |
| npm audit metadata total dependencies | 781 |
| npm audit metadata prod dependencies | 76 |
| npm audit metadata dev dependencies | 706 |
| Direct runtime dependencies | 6 |
| Direct dev/build dependencies | 21 |

Direct renderer/runtime dependencies:

| Dependency | Package Range | Current Resolved | Runtime Classification |
| --- | --- | ---: | --- |
| `vue` | `^3.5.13` | `3.5.41` | renderer production bundle |
| `vue-router` | `^4.5.0` | `4.6.4` | renderer production bundle |
| `pinia` | `^2.3.0` | `2.3.1` | renderer production bundle |
| `axios` | `^1.7.9` | `1.19.0` | renderer production bundle / HTTP client |
| `element-plus` | `^2.9.3` | `2.14.4` | renderer production bundle |
| `@element-plus/icons-vue` | `^2.3.1` | `2.3.2` | renderer production bundle |

Direct dev/build/packaging dependencies include Electron, electron-builder, Vite, Vitest, ESLint, Stylelint, TypeScript, vue-tsc, and supporting tools.

## 7. Packaged Electron Runtime

`collector-desktop/package.json` packaging config confirms:

```text
files:
  dist/**/*
  !**/*.map
  package.json
asar: true
```

Therefore:

- Renderer runtime bundle dependencies (`vue`, `axios`, `element-plus`, etc.) enter packaged app through `dist/renderer` bundle.
- Electron itself is a `devDependency` but is **PACKAGED_RUNTIME** because the Electron runtime ships with the desktop application.
- `electron-builder` and its dependency graph are **BUILD / PACKAGING** dependencies; they are not placed inside app runtime by `files`, but they directly affect generated installers/packages.
- `vitest`, `eslint`, `stylelint`, `typescript`, `vue-tsc`, `@vitejs/plugin-vue`, Vite dev server internals are **DEV / BUILD ONLY** unless their output is bundled.
- Local `node_modules` presence alone was **not** used as packaged-runtime evidence.

Electron runtime status from official lifecycle sources:

| Item | Current | Security Support | Shipped? | Risk | Recommended Path |
| --- | --- | --- | --- | --- | --- |
| Electron | `33.4.11` | Electron schedule lists Electron 33 EOL `2025-04-29`; latest three stable majors are supported | Yes, packaged runtime | **P1 / UNSUPPORTED + known advisories** | Separate Electron major upgrade task; current supported majors at audit date are 42/43/44 |
| Chromium runtime | Electron 33 uses Chromium `M130` | Chromium embedded in Electron 33 no longer receives Electron backports | Yes | Browser engine security lag | Upgrade Electron; cannot patch Chromium independently here |
| Node runtime | Electron 33 uses Node `v20.18.0` | tied to EOL Electron line | Yes | Runtime security lag | Upgrade Electron |
| electron-builder | `25.1.8` | npm audit High through `app-builder-lib`/`builder-util` | Build/packaging only | P2/P3 depending distribution workflow | Targeted upgrade to `26.15.3` candidate, separate from Electron runtime upgrade |
| Vite | `6.4.3` direct dev dependency; vulnerable copies under Vitest tree reported as `<=6.4.2` | direct Vite not flagged by npm audit; transitive Vitest Vite is build/test only | dev/build only | P3, dev-server-only exposure | Upgrade Vitest/Vite in build toolchain task |

## 8. Vulnerability Sources

| Source | Result |
| --- | --- |
| `npm --prefix collector-desktop audit --json` | Completed, non-zero due findings: 22 total vulnerabilities |
| `npm --prefix collector-desktop audit --omit=dev --json` | Completed: 0 production npm vulnerabilities |
| `npm --prefix collector-desktop ls --all --json` | Completed with peer/invalid metadata captured; usable dependency inventory generated |
| `npm --prefix collector-desktop outdated --json` | Completed, non-zero because outdated packages exist |
| OSV API query over Maven runtime coordinates | Completed: 35 runtime packages with OSV/GHSA findings, 153 advisory records |
| OWASP Dependency-Check Maven plugin | **SCAN INCOMPLETE**: initial run timed out during NVD database update; no “0 vulnerabilities” conclusion drawn |
| Electron official release schedule/support docs | Completed via official Electron release/timeline pages |
| Spring/Tomcat/Netty/Electron/GitHub Advisory web lookups | Used for representative severity/fixed-version evidence |

## 9. Vulnerability Findings

### 9.1 npm audit result

Full npm audit:

| Severity | Count |
| --- | ---: |
| Critical | 2 |
| High | 16 |
| Moderate | 3 |
| Low | 1 |
| Total | 22 |

Production-only npm audit (`--omit=dev`):

| Severity | Count |
| --- | ---: |
| Critical | 0 |
| High | 0 |
| Moderate | 0 |
| Low | 0 |
| Total | 0 |

Interpretation: npm Critical/High findings are not renderer production dependencies. They are Electron packaged runtime (`electron`) or development/build/packaging toolchain (`vitest`, `electron-builder`, `tar`, `node-gyp`, etc.). Electron is still production relevant even though listed under `devDependencies`.

### 9.2 npm Critical / High findings matrix

| Ecosystem | Dependency | Resolved / Range | Direct/Transitive | Runtime/Dev | Advisory | Severity | Reachability | Exposure | Fixed Version / npm Suggestion | Action |
| --- | ---: | --- | --- | --- | --- | --- | --- | --- | ---: | --- |
| npm | `electron` | `33.4.11`, audit range `<=40.10.2...` | Direct | `PACKAGED_RUNTIME` | multiple GHSA, including Electron UAF/context/protocol advisories | High | `LIKELY_REACHABLE` for Electron runtime classes; individual advisories vary | `LOCAL_ONLY`, renderer/browser engine, external URL/protocol dependent | npm suggests `electron@44.3.0` major | **Major upgrade required**; separate task |
| npm | `extract-zip` | transitive under Electron | Transitive | Electron install/runtime support dependency | GHSA symlink/path traversal | High | `BUILD/PACKAGE_DOWNLOAD_PATH`, not app business runtime | archive extraction | npm suggests Electron major | Major via Electron |
| npm | `vitest` | `2.1.9` | Direct | DEV/TEST ONLY | GHSA-5xrq-8626-4rwp | Critical | `NOT_RUNTIME`; reachable only when Vitest UI/server is run | `BUILD_TIME_ONLY`, local dev server | npm suggests `vitest@5.0.0` major | Dev/build only; separate toolchain task |
| npm | `tar` | `<=7.5.20` transitive | Transitive | BUILD/PACKAGING | multiple node-tar advisories incl DoS/path traversal | Critical | `BUILD_TIME_ONLY`; archive extraction during builder/rebuild/node-gyp | local file/archive input | via `electron-builder@26.15.3` major according npm | Packaging dependency upgrade task |
| npm | `electron-builder` | `25.1.8` | Direct | BUILD/PACKAGING | GHSA via app-builder-lib/builder-util | High | package generation/distribution flow | build host / generated artifacts | `26.15.3` | Targeted packaging upgrade task |
| npm | `app-builder-lib` | `<=26.14.0` | Transitive | BUILD/PACKAGING | GHSA-7g7r-gx96-252g | High | packaging only; Linux AppImage advisory, Windows app less directly affected | `BUILD_TIME_ONLY` / artifact path | `electron-builder@26.15.3` | Minor/major packaging task depending semver |
| npm | `builder-util-runtime` | `<9.7.0` | Transitive | BUILD/PACKAGING / updater lib | GHSA-p2f4-r6v6-j797 | High | only if updater/runtime utility used; current package config has no auto-update flow | build/update HTTP redirect | `electron-builder@26.15.3` | Upgrade builder; reachability low now |
| npm | `@electron/rebuild`, `node-gyp`, `make-fetch-happen`, `cacache` | transitive | Transitive | BUILD/PACKAGING | inherited from `tar`/cache tooling | High | `BUILD_TIME_ONLY` | archive/cache input | `electron-builder@26.15.3` | Upgrade builder toolchain |
| npm | `@xmldom/xmldom` | `0.9.11` affected | Transitive | BUILD/PACKAGING metadata/XML tooling | multiple XML injection/DoS GHSA | High | `BUILD_TIME_ONLY`; no renderer runtime evidence | XML input during packaging/tooling | transitive fix available | Toolchain update |
| npm | `glob` | `10.2.0 - 10.4.5` | Transitive | BUILD/PACKAGING | GHSA-5j98-mcp5-4vw2 | High | CLI `--cmd` path; not app runtime | local build CLI input | fix available | Toolchain update |
| npm | `js-yaml` | `4.0.0 - 4.3.1` | Transitive | BUILD/PACKAGING | GHSA-2883-xcg3-v3hh | High | config/YAML parsing in toolchain; not renderer runtime | local file/config input | fix available | Toolchain update |
| npm | `vite` transitive under `vitest` | `<=6.4.2` in audit nodes | Transitive | DEV/TEST ONLY | Vite dev-server path traversal/NTLM advisories | High | only when dev/test server is exposed | `BUILD_TIME_ONLY` / local dev server | npm suggests Vitest major | Dev-server hardening/update task |

Moderate/low npm findings are `@vitest/mocker`, `vite-node`, `esbuild`, and `joi`; all are development/test/build-only in this project based on `npm audit --omit=dev` returning 0 and packaging config excluding `node_modules`.

### 9.3 Java runtime OSV / GHSA finding summary

OSV query over actual-ish packaged Maven runtime coordinates found 35 runtime packages with 153 advisory records. The following are the production-relevant package groups and action class:

| Ecosystem | Dependency / Group | Resolved Version | Direct/Transitive | Runtime/Dev | Advisory Evidence | Severity | Reachability | Exposure | Fixed Version / Path | Action |
| --- | --- | ---: | --- | --- | --- | --- | --- | --- | ---: | --- |
| Maven | Spring Boot / Framework | Boot `3.2.0`, Spring `6.1.1` | Direct starters + transitive | Runtime | OSV/GHSA across `spring-web`, `spring-webmvc`, `spring-core`, `spring-expression`, `spring-context`, `spring-boot` | High/Moderate mix; exact advisories vary | `CONFIRMED_REACHABLE` for web stack | `NETWORK_EXPOSED` | Spring Framework examples fix in `6.1.21+` / `6.2.x`; Boot 3.2 is EOL | **P1**; minor Spring Boot line upgrade or supported line migration required |
| Maven | Tomcat embedded | `10.1.16` | Transitive via Boot Web | Runtime | 35 OSV/GHSA records on `tomcat-embed-core`; representative GHSA-gqp3 fixed `10.1.44`, newer Tomcat security page lists later fixes | High/Moderate mix | `CONFIRMED_REACHABLE` | `NETWORK_EXPOSED` HTTP server | patched Tomcat line via Spring Boot managed upgrade | **P1** |
| Maven | Netty family | `4.1.100.Final` | Transitive via Lettuce/PLC4X/Modbus/IOT | Runtime | 21 Netty modules with advisories; representative Netty advisories fixed around `4.1.125+` and later | High/Moderate mix | `LIKELY_REACHABLE` for Redis/industrial protocol clients; codec-specific varies | `NETWORK_EXPOSED` / protocol input | prefer Spring Boot managed Netty or explicit 4.1.x patch after compatibility check | **P1/P2** depending protocol enablement |
| Maven | Jackson core/databind | `2.15.3` | Direct/transitive | Runtime | OSV/GHSA on `jackson-core` and `jackson-databind` | High/Moderate mix | `CONFIRMED_REACHABLE` for HTTP JSON | `NETWORK_EXPOSED` JSON | managed upgrade via Boot/Jackson BOM | **P1/P2** |
| Maven | Logback | `1.4.11` | Transitive via Boot logging | Runtime | 8 OSV/GHSA records | High/Moderate mix | `LIKELY_REACHABLE` if attacker can influence logged data/config | log/config input | managed upgrade via Boot | **P2** |
| Maven | Bouncy Castle | `1.81` | Transitive via Milo/OPC UA stack | Runtime | OSV/GHSA on `bcprov`/`bcpkix` | High/Moderate mix | `POSSIBLY_REACHABLE` when OPC UA TLS/cert flows enabled | `NETWORK_EXPOSED` protocol TLS/cert input | patch line upgrade; verify Milo compatibility | **P2** |
| Maven | Commons Lang3 | `3.14.0` | Transitive/direct managed | Runtime | GHSA-j288-q9x7-2f5v | likely Medium/High depending source | `POSSIBLY_REACHABLE`; depends on vulnerable API use | internal utility input | patch to newer 3.x | Patch/minor candidate |
| Maven | Log4j API | `2.21.1` | Transitive bridge/API only | Runtime | GHSA-qv9r-c865-cp47 | source-specific | `NOT_REACHABLE_BY_CURRENT_USAGE` for Log4j Core RCE class; only API/bridge present | logging API | managed patch | Lower priority |
| Maven | Spring Data Commons/KeyValue | `3.2.0` | Transitive via Redis | Runtime | OSV/GHSA on Spring Data | High/Moderate mix | `LIKELY_REACHABLE` if data repository/web binding patterns match | internal/database input | Spring Data managed by Boot upgrade | P2 |
| Maven | Protocol libraries without confirmed advisories in this scan | Fastjson2 `2.0.43`, Guava `32.1.3-jre`, Caffeine `3.1.8`, TDengine JDBC `3.7.1`, PLC4X `0.13.0`, Milo `1.0.8`, Californium `3.11.0`, Paho `1.2.5`, SNMP4J `3.9.6`, j60870 `1.7.2`, iec61850bean `1.9.0`, jSerialComm `2.11.2` | mixed | Runtime | no confirmed OSV findings in current query | none confirmed | reachability depends on protocol enablement | network/file/serial/database | maintenance review still needed | INFO / deeper reachability |

Important: OWASP Dependency-Check did not finish NVD update within the allowed run window, so OSV/GHSA is the current completed Java vulnerability source. This is not a claim that Java has only these vulnerabilities.

## 10. Reachability

| Classification | Findings |
| --- | --- |
| `CONFIRMED_REACHABLE` | Spring MVC/Tomcat/Jackson HTTP request handling; renderer runtime direct deps; Electron runtime shell as packaged app |
| `LIKELY_REACHABLE` | Netty via Redis/protocol clients; Spring Data Redis; Logback logging path; MQTT/OPC/Modbus/PLC protocol stacks when configured |
| `POSSIBLY_REACHABLE` | Bouncy Castle via OPC UA cert/TLS; TDengine JDBC; protocol-specific parsers depending enabled device configs |
| `NOT_RUNTIME` | Vitest, ESLint, Stylelint, TypeScript, most Vite dev-server findings, node-gyp/tar/cacache when only used during dependency/build steps |
| `NOT_REACHABLE_BY_CURRENT_USAGE` | Log4j Core style exploit class not present; Log4j API/SLF4J bridge only; Lombok packaged but not used at runtime |
| `UNKNOWN` | Superseded by R1: Runtime Critical/High advisory reachability is now complete with `UNKNOWN = 0`; Moderate/Low package-family detail and OWASP Dependency-Check scanner completion remain deferred. |

## 11. Exposure

| Exposure | Dependencies / Findings |
| --- | --- |
| `NETWORK_EXPOSED` | Spring Web/MVC, Tomcat, Jackson request bodies, protocol clients/servers, Netty codecs, Electron renderer/browser surface |
| `LOCAL_ONLY` | Electron local desktop runtime advisories requiring local interaction/filesystem/user action |
| `FILE_INPUT` | Electron archive extraction/build tooling; diagnostics/export/import style flows; node `tar`, `extract-zip`, `js-yaml`; Java XML/YAML libraries where used |
| `CONFIG_INPUT` | Spring config, logging config, protocol config, Jackson/Fastjson2 parser inputs |
| `DATABASE_INPUT` | TDengine JDBC, MyBatis, Redis/Lettuce/Netty Redis codec |
| `INTERNAL_API` | Actuator/Micrometer/internal service APIs depending endpoint exposure |
| `BUILD_TIME_ONLY` | Vitest/Vite dev server, electron-builder, node-gyp, tar, cacache, eslint/stylelint/vue-tsc |

## 12. Supply Chain

Maven:

- No `<repositories>` / `<pluginRepositories>` blocks were found beyond Maven schema URLs.
- No external `SNAPSHOT`, `LATEST`, `RELEASE`, or version-range dependency was confirmed.
- Project modules use `0.0.1-SNAPSHOT`; this is internal project versioning and not a third-party supply-chain issue.
- Maven Central HTTPS was used by Maven tool output.

npm:

- Project `.npmrc`: not present.
- User home `.npmrc`: not present.
- `package-lock.json` uses resolved registry URLs and integrity values for all packages.
- No npm token/registry auth token was printed or retained.

## 13. Lockfile / Integrity

`collector-desktop/package-lock.json`:

| Check | Result |
| --- | --- |
| lockfileVersion | 3 |
| packages excluding root | 781 |
| `resolved` present | 781 / 781 |
| `integrity` present | 781 / 781 |
| missing integrity | 0 |

`npm ci` was not forced because `npm ls`, `npm audit`, and lockfile parsing produced a usable inventory and this audit intentionally avoided unnecessary local dependency churn. `npm sbom`/CycloneDX failures also indicate current `node_modules`/peer metadata has known `ELSPROBLEMS` that should be resolved before requiring strict CI install gates.

## 14. Unsupported / EOL

| Dependency | Current | Status | Evidence | Risk |
| --- | ---: | --- | --- | --- |
| Electron | `33.4.11` | EOL since `2025-04-29` per Electron release schedule; latest three stable majors are supported | Electron official schedule/timeline | **P1 unsupported packaged runtime** |
| Spring Boot | `3.2.0` | Spring Boot 3.2 OSS support ended in 2024; commercial support windows also stale/ending depending source | Spring lifecycle/advisory sources | **P1 unsupported Java web runtime baseline** |
| Spring Framework | `6.1.1` | Old 6.1 patch level with multiple advisories fixed in later 6.1/6.2 releases | Spring/GHSA evidence | P1/P2 |
| Tomcat | `10.1.16` | Very old 10.1 patch level with many fixed advisories through 10.1.44+ and later | Tomcat/GHSA evidence | P1 |
| Electron builder | `25.1.8` | Not EOL per se, but vulnerable build toolchain | npm audit | P2/P3 |

## 15. Upgrade Candidates

### A. Patch upgrade candidates

| Candidate | Current | Target Class | Reason | Risk |
| --- | ---: | --- | --- | --- |
| `axios` | `1.19.0` | patch/minor `1.20.0` | outdated only; no prod audit vuln | low |
| `element-plus` | `2.14.4` | patch `2.14.5` | outdated only | low/visual regression needed |
| `vue` | `3.5.41` | patch `3.5.42` | outdated only | low |
| ESLint/stylelint/typescript-eslint | current patch behind | patch | dev-only maintenance | low |

### B. Minor upgrade candidates

| Candidate | Current | Target Class | Reason | Risk |
| --- | ---: | --- | --- | --- |
| Spring Boot 3.5.16 transitional security uplift / Boot 4 supported path | `3.2.0` | 3.5.16 transitional security uplift; not current OSS-supported | brings many Spring/Tomcat/Jackson/Netty patches without Boot major, but remains transitional because Boot 3.5.x OSS support has ended | medium/high regression; needs dedicated backend test/smoke task |
| Netty 4.1.x | `4.1.100.Final` | newer 4.1.x | many Netty advisories; may be managed by Boot or protocol libraries | medium; protocol regression risk |
| Tomcat 10.1.x | `10.1.16` | newer 10.1.x via Boot | HTTP server advisories | medium; prefer Boot-managed |
| Jackson 2.x | `2.15.3` | newer 2.x via Boot | parser advisories | medium; serialization compatibility |
| Logback 1.4/1.5 line via Boot | `1.4.11` | managed patch | logging advisories | low/medium |
| Bouncy Castle | `1.81` | newer compatible line | crypto advisory | medium; check Milo/OPC UA compatibility |
| electron-builder | `25.1.8` | `26.15.3` | npm audit High packaging findings | medium; packaging/signing regression |

### C. Major upgrade required

| Candidate | Current | Target Class | Reason | Risk |
| --- | ---: | --- | --- | --- |
| Electron | `33.4.11` | Electron 44 latest stable patch by default (`42/43/44` supported at R1 audit date) | EOL + multiple Electron advisories | high; needs separate Electron runtime verification |
| Vitest | `2.1.9` | `5.0.0` per npm audit | Critical dev-server finding | medium; tests/build config may change |
| Vite | `6.4.3` | latest major available is `8.3.0`; direct Vite not prod vulnerable | dev-server/toolchain modernization | medium; not 07.2 first priority unless dev exposure required |
| Vue Router / Pinia | current major behind latest major | major available | outdated only, no confirmed vuln | defer |

### D. No fix available

No “no fix available” runtime blocker was confirmed in this audit. Some Java advisory fixed versions require selecting a supported Spring Boot/Spring Framework line rather than overriding one JAR blindly.

### E. Dev/build only

Vitest/Vite dev-server advisories, ESLint/Stylelint/TypeScript tooling, `tar`/`node-gyp`/`cacache` unless used by packaging, most `electron-builder` transitive packages.

### F. False positive / not applicable

- npm production audit: 0 vulnerabilities for renderer runtime dependencies.
- Log4j API/bridge present but not Log4j Core runtime RCE path.
- `devDependency` does **not** mean Electron is dev-only; Electron is explicitly classified as `PACKAGED_RUNTIME`.

### G. Needs deeper reachability investigation

- Specific Spring MVC advisories requiring particular annotations/headers/resource handler configurations.
- Netty codec-specific advisories by enabled protocol stack.
- Bouncy Castle advisories by OPC UA certificate/TLS flows.
- Jackson/Fastjson2 deserialization features and polymorphic typing usage.

## 16. Deferred Items

- No dependency remediation was performed in 07.1.
- OWASP Dependency-Check needs rerun with NVD cache/API key or longer CI window.
- npm SBOM generation failed due current dependency metadata issues; Java CycloneDX SBOM succeeded temporarily.
- License compliance remains separate; `j60870` should stay tracked as license/compliance topic, not a vulnerability remediation blocker in this task.

## 17. Verification

Executed commands/evidence:

```text
git status --short --branch && git log -1 --oneline && git diff --stat
mvn -version
mvn -pl collector-boot -am dependency:tree
mvn -pl collector-boot -am -DskipTests package
mvn -DskipTests install
mvn -pl collector-boot dependency:tree -Dverbose
mvn -pl collector-boot dependency:list -DincludeScope=runtime
mvn org.owasp:dependency-check-maven:12.1.0:check ...   # SCAN INCOMPLETE / timeout during NVD update
mvn -pl collector-boot org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom ...
npm --prefix collector-desktop ls --all --json
npm --prefix collector-desktop audit --json
npm --prefix collector-desktop audit --omit=dev --json
npm --prefix collector-desktop outdated --json
npm sbom / npx @cyclonedx/cyclonedx-npm ...             # failed due npm ls ELSPROBLEMS
OSV API querybatch for Maven runtime coordinates
package-lock resolved/integrity parser
Electron official release schedule extraction
```

## 18. Final Risk Matrix

| Priority | Risk | Production? | Reachability | Recommended 07.2 Scope |
| --- | --- | --- | --- | --- |
| P1 | Spring Boot 3.2.0 / Spring 6.1.1 / Tomcat 10.1.16 old vulnerable web stack | Yes | `CONFIRMED_REACHABLE` | Targeted Spring Boot 3.x supported-line upgrade, full backend regression |
| P1 | Electron 33.4.11 EOL packaged runtime with known Electron advisories | Yes | `LIKELY_REACHABLE` | Separate Electron major upgrade task, packaged runtime smoke/security verification |
| P1/P2 | Netty 4.1.100.Final across Redis/industrial protocols | Yes | `LIKELY_REACHABLE` when those protocols/configs active | Prefer managed upgrade; verify protocol drivers |
| P2 | Jackson/Logback/Bouncy Castle advisories | Yes | likely/possible by usage | Managed patch via Boot and targeted compatibility checks |
| P2/P3 | electron-builder/app-builder-lib/tar packaging vulnerabilities | Build/packaging | build host/artifact generation | Upgrade builder toolchain; rerun pack/dist and installer checks |
| P3 | Vitest/Vite dev-server Critical/High | Dev/test only | only when dev/test server exposed | Dev toolchain upgrade; restrict dev server exposure |
| INFO | Renderer runtime packages outdated (`axios`, `vue`, `element-plus`) but no prod audit vuln | Yes but no vuln | normal renderer usage | Patch/minor maintenance after security blockers |
| SCANNER_LIMITATION | OWASP Dependency-Check not completed | N/A | N/A | Rerun with NVD API/cache before final release certification; this no longer represents Critical/High reachability UNKNOWN after R1 |

## 19. Task 07.2 Recommendation

Recommended 07.2 split:

1. **07.2-R0 / Java Web Runtime Security Remediation**
   - Upgrade Spring Boot within Boot 3.5.16 transitional security uplift / Boot 4 supported path, allowing managed Spring/Tomcat/Jackson/Logback/Netty updates.
   - Re-run Maven dependency tree, package, backend smoke, and OSV/Dependency-Check.
2. **07.2-R1 / Electron Runtime Upgrade Plan**
   - Upgrade Electron from 33 to Electron 44 latest stable patch by default in a dedicated task, unless compatibility validation finds a blocker.
   - Verify preload, CSP, app.asar, pack/dist, Windows launch smoke, and existing Electron security architecture.
3. **07.2-R2 / npm Build Toolchain Remediation**
   - Upgrade electron-builder and Vitest/Vite toolchain intentionally.
   - Resolve npm `ELSPROBLEMS` / SBOM blockers.
4. **07.2-R3 / Protocol Stack Patch Review**
   - Netty, Bouncy Castle, PLC4X/Milo/Californium/Paho/SNMP4J compatibility verification by enabled protocol.

## 20. Final Status (superseded by R1)

The original 07.1 PASS status is superseded by the R1 clean-install gate. See `Task 07.1-R1 — Baseline Closure` below.

## Task 07.1-R1 — Baseline Closure

### R1 Scope

本 R1 是 focused repair：只处理 npm reproducible install、Java Runtime Critical/High reachability UNKNOWN、Spring Boot/Electron lifecycle 与 07.2 target 修正。没有开始 07.2，没有升级生产依赖，没有修改 `pom.xml` / `package.json` / `package-lock.json`。

### npm ci Result

命令：

```text
npm ci --prefix collector-desktop
```

结果：

```text
npm ci exit=1
```

`npm ci` exact error 摘要：

```text
npm error code EUSAGE
npm error `npm ci` can only install packages when your package.json and package-lock.json are in sync.
npm error Invalid: lock file's keyv@4.5.4 does not satisfy keyv@5.6.0
npm error Missing: electron-builder-squirrel-windows@25.1.8 from lock file
npm error Missing: keyv@4.5.4 from lock file
npm error Missing: archiver@5.3.2 from lock file
npm error Missing: fs-extra@10.1.0 from lock file
...
```

R1 分析：

- 这是 lockfile 与 `package.json` / transitive graph 不同步，不是可忽略的 optional dependency 下载失败。
- `package-lock.json` 中 `node_modules/electron-builder` 为 `25.1.8`，依赖 `app-builder-lib 25.1.8`。
- lockfile 缺少 `electron-builder-squirrel-windows@25.1.8`，也缺少其 Windows/Squirrel 打包链条中的 `archiver` / `fs-extra` / `tar-stream` 等节点。
- lockfile 当前存在 `node_modules/keyv@4.5.4`；但 `@cacheable/memory@2.2.0`、`@cacheable/utils@2.5.0`、`cacheable@2.5.0` 要求 `keyv ^5.6.0`，导致 npm 12 的 strict clean install 拒绝继续。
- 按用户约束，未使用 `npm install` / `npm update` / `npm audit fix` / `npm audit fix --force` 修复或规避。

### npm ls / audit / SBOM Rerun

由于 `npm ci` 未通过，未产生 clean reproducible `node_modules`。因此 R1 没有把当前 dependency graph 声称为最终可重复基线。

- `npm ls after clean install`: **NOT RUN / BLOCKED BY npm ci failure**
- `npm audit after clean install`: **NOT RUN / BLOCKED BY npm ci failure**
- `npm audit --omit=dev after clean install`: **NOT RUN / BLOCKED BY npm ci failure**
- `npm outdated after clean install`: **NOT RUN / BLOCKED BY npm ci failure**
- `npm sbom --sbom-format=cyclonedx`: **NOT RUN / BLOCKED BY npm ci failure**

保留旧 07.1 npm audit 数字仅作为 pre-R1 evidence；最终 clean-install 基线不能声称 reproducible。

### Lockfile Reproducibility

`npm ci` 前后 `git status --short`：

```text
before:
?? .hermes.md

after:
?? .hermes.md
```

Manifest diff：

```text
git diff -- collector-desktop/package.json
# no diff

git diff -- collector-desktop/package-lock.json
# no diff
```

结论：`npm ci` 没有修改 `package.json` / `package-lock.json`，但 clean install 失败，说明当前 lockfile 本身不能作为 npm 12 下的可重复安装基线。

### Runtime Critical/High advisory candidates

基于 `collector-boot` runtime OSV/GHSA 原始结果重新提取：

- Runtime Critical/High advisory candidates = 78
- `CONFIRMED_REACHABLE` = 0
- `LIKELY_REACHABLE` = 14
- `POSSIBLY_REACHABLE` = 31
- `NOT_REACHABLE_BY_CURRENT_USAGE` = 33
- `FALSE_POSITIVE` = 0
- `UNKNOWN` = 0

### Critical/High Exact Advisory Matrix

| Advisory / CVE | Severity | Maven coordinate | Resolved version | Affected / fixed | Runtime packaged? | Exposure | Attack prerequisite | Current project usage evidence | Reachability | Reason | 07.2 action |
| --- | --- | --- | ---: | --- | --- | --- | --- | --- | --- | --- | --- |
| GHSA-vmq6-5m68-f53m, CVE-2023-6378 | HIGH | `ch.qos.logback:logback-classic` | `1.4.11` | 当前 1.4.11 受影响；fixed: 1.2.13, 1.3.12, 1.4.12 | yes | Logback logging runtime | Logback receiver/serialization input or attacker-controlled serialized payload to logging component | logback-classic/core packaged via Boot logging; no logback receiver/socket-server config found。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 普通日志写入不满足 serialization receiver 前提。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-vmq6-5m68-f53m, CVE-2023-6378 | HIGH | `ch.qos.logback:logback-core` | `1.4.11` | 当前 1.4.11 受影响；fixed: 1.2.13, 1.3.12, 1.4.12 | yes | Logback logging runtime | Logback receiver/serialization input or attacker-controlled serialized payload to logging component | logback-classic/core packaged via Boot logging; no logback receiver/socket-server config found。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 普通日志写入不满足 serialization receiver 前提。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-r7wm-3cxj-wff9 | HIGH | `com.fasterxml.jackson.core:jackson-core` | `2.15.3` | 当前 2.15.3 受影响；fixed: 2.18.8, 2.21.4, 3.1.4 | yes | Jackson JSON parser | Affected async/non-blocking parser numeric limit path with untrusted JSON | Spring MVC HTTP JSON parser confirmed; no evidence of Jackson async parser direct use。 | `POSSIBLY_REACHABLE` | untrusted JSON parser reachable，但该 advisory 指向 async/parser feature，具体 parser mode 未确认。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-j3rv-43j4-c7qm, CVE-2026-54512 | HIGH | `com.fasterxml.jackson.core:jackson-databind` | `2.15.3` | 当前 2.15.3 受影响；fixed: 2.18.8, 2.21.4, 3.1.4 | yes | Jackson databind / Redis serializer polymorphic typing | Polymorphic typing with BasicPolymorphicTypeValidator bypass and attacker-controlled typed JSON/Redis payload | RedisConfig activates default typing with BasicPolymorphicTypeValidator allowing com.wangbin/java.util/java.time; HTTP ObjectMapper itself not globally default-typed。 | `POSSIBLY_REACHABLE` | 默认 typing 只在 Redis serializer copy 中使用；需攻击者控制 Redis/cache payload or trusted boundary break。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-rmj7-2vxq-3g9f, CVE-2026-54513 | HIGH | `com.fasterxml.jackson.core:jackson-databind` | `2.15.3` | 当前 2.15.3 受影响；fixed: 2.18.8, 2.21.4, 3.1.4 | yes | Jackson databind / Redis serializer polymorphic typing | Polymorphic typing with BasicPolymorphicTypeValidator bypass and attacker-controlled typed JSON/Redis payload | RedisConfig activates default typing with BasicPolymorphicTypeValidator allowing com.wangbin/java.util/java.time; HTTP ObjectMapper itself not globally default-typed。 | `POSSIBLY_REACHABLE` | 默认 typing 只在 Redis serializer copy 中使用；需攻击者控制 Redis/cache payload or trusted boundary break。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-g3pr-3p32-fp23, CVE-2026-40984 | HIGH | `io.micrometer:micrometer-core` | `1.12.0` | 当前 1.12.0 受影响；fixed: 1.15.12, 1.16.6 | yes | Micrometer HTTP server instrumentation / actuator metrics | HTTP server instrumentation active and attacker sends high-cardinality/DoS triggering requests | actuator metrics/prometheus exposed in application.yml; micrometer runtime packaged。 | `LIKELY_REACHABLE` | metrics/prometheus endpoint enabled; exact instrumentation path likely active in Boot web app。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-558v-64gr-wgg4, CVE-2026-59901 | HIGH | `io.netty:netty-codec` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Generic Netty codec/common runtime | Affected decoder/compression/common helper is selected by an enabled Netty protocol path | Netty packaged via Redis/protocol stacks; Redis path confirmed, other protocol path config dependent。 | `POSSIBLY_REACHABLE` | 通用 Netty runtime 存在，但具体 codec (bzip2/lz4/etc.) use not confirmed。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-mj4r-2hfc-f8p6, CVE-2026-42583 | HIGH | `io.netty:netty-codec` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.133.Final, 4.2.13.Final | yes | Generic Netty codec/common runtime | Affected decoder/compression/common helper is selected by an enabled Netty protocol path | Netty packaged via Redis/protocol stacks; Redis path confirmed, other protocol path config dependent。 | `POSSIBLY_REACHABLE` | 通用 Netty runtime 存在，但具体 codec (bzip2/lz4/etc.) use not confirmed。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-cm33-6792-r9fm, CVE-2026-42579 | HIGH | `io.netty:netty-codec-dns` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.133.Final, 4.2.13.Final | yes | Netty DNS resolver | Netty DnsNameResolver used and DNS response attacker-influenced | Netty DNS resolver packaged；industrial/Redis clients may resolve remote hosts。 | `POSSIBLY_REACHABLE` | 运行时存在且网络客户端会解析主机，但未确认使用 Netty DnsNameResolver 而非 JVM resolver。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-cc37-9q2j-3hfv, CVE-2026-44893 | HIGH | `io.netty:netty-codec-haproxy` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Netty netty-codec-haproxy module | netty-codec-haproxy codec/transport is actually used by enabled protocol and handles untrusted frames | netty-codec-haproxy packaged via netty-all/protocol dependency; no direct production code reference found for this specific codec except protocol libraries. | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现项目生产代码直接使用 netty-codec-haproxy codec/transport；仅因 netty-all 打包存在。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-h2qv-fj59-j46j, CVE-2026-48059 | HIGH | `io.netty:netty-codec-haproxy` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Netty netty-codec-haproxy module | netty-codec-haproxy codec/transport is actually used by enabled protocol and handles untrusted frames | netty-codec-haproxy packaged via netty-all/protocol dependency; no direct production code reference found for this specific codec except protocol libraries. | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现项目生产代码直接使用 netty-codec-haproxy codec/transport；仅因 netty-all 打包存在。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-q6cq-mhr2-jmr5, CVE-2026-55851 | HIGH | `io.netty:netty-codec-haproxy` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty netty-codec-haproxy module | netty-codec-haproxy codec/transport is actually used by enabled protocol and handles untrusted frames | netty-codec-haproxy packaged via netty-all/protocol dependency; no direct production code reference found for this specific codec except protocol libraries. | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现项目生产代码直接使用 netty-codec-haproxy codec/transport；仅因 netty-all 打包存在。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-57rv-r2g8-2cj3, CVE-2026-42584 | HIGH | `io.netty:netty-codec-http` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.133.Final, 4.2.13.Final | yes | Netty HTTP codec/client codec | Netty HTTP client/server codec handles attacker-controlled HTTP traffic | netty-codec-http packaged；collector-boot Web server is Tomcat；protocol libraries may use Netty HTTP internally。 | `POSSIBLY_REACHABLE` | 不是主 HTTP server；具体协议启用时可能进入 Netty HTTP path。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-6jqx-86gh-f27w, CVE-2026-55831 | HIGH | `io.netty:netty-codec-http` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty HTTP codec/client codec | Netty HTTP client/server codec handles attacker-controlled HTTP traffic | netty-codec-http packaged；collector-boot Web server is Tomcat；protocol libraries may use Netty HTTP internally。 | `POSSIBLY_REACHABLE` | 不是主 HTTP server；具体协议启用时可能进入 Netty HTTP path。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-f6hv-jmp6-3vwv, CVE-2026-42587 | HIGH | `io.netty:netty-codec-http` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.133.Final, 4.2.13.Final | yes | Netty HTTP codec/client codec | Netty HTTP client/server codec handles attacker-controlled HTTP traffic | netty-codec-http packaged；collector-boot Web server is Tomcat；protocol libraries may use Netty HTTP internally。 | `POSSIBLY_REACHABLE` | 不是主 HTTP server；具体协议启用时可能进入 Netty HTTP path。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-jppx-w49h-x2qq, CVE-2026-56745 | HIGH | `io.netty:netty-codec-http` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty HTTP codec/client codec | Netty HTTP client/server codec handles attacker-controlled HTTP traffic | netty-codec-http packaged；collector-boot Web server is Tomcat；protocol libraries may use Netty HTTP internally。 | `POSSIBLY_REACHABLE` | 不是主 HTTP server；具体协议启用时可能进入 Netty HTTP path。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-mvh2-crg5-v77c, CVE-2026-55833 | HIGH | `io.netty:netty-codec-http` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty HTTP codec/client codec | Netty HTTP client/server codec handles attacker-controlled HTTP traffic | netty-codec-http packaged；collector-boot Web server is Tomcat；protocol libraries may use Netty HTTP internally。 | `POSSIBLY_REACHABLE` | 不是主 HTTP server；具体协议启用时可能进入 Netty HTTP path。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-pwqr-wmgm-9rr8, CVE-2026-33870 | HIGH | `io.netty:netty-codec-http` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.132.Final, 4.2.10.Final | yes | Netty HTTP codec/client codec | Netty HTTP client/server codec handles attacker-controlled HTTP traffic | netty-codec-http packaged；collector-boot Web server is Tomcat；protocol libraries may use Netty HTTP internally。 | `POSSIBLY_REACHABLE` | 不是主 HTTP server；具体协议启用时可能进入 Netty HTTP path。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-93wv-jw9v-4972, CVE-2026-56819 | HIGH | `io.netty:netty-codec-http2` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty HTTP/2 codec | Netty-based HTTP/2 client/server path enabled | netty-codec-http2 packaged via netty-all/protocol deps；embedded web server is Tomcat, no server.http2 config。 | `POSSIBLY_REACHABLE` | 库打包但主 HTTP server 不使用 Netty；可能被 OPC UA/PLC4X/other protocol clients indirectly used。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-f6hv-jmp6-3vwv, CVE-2026-42587 | HIGH | `io.netty:netty-codec-http2` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.133.Final, 4.2.13.Final | yes | Netty HTTP/2 codec | Netty-based HTTP/2 client/server path enabled | netty-codec-http2 packaged via netty-all/protocol deps；embedded web server is Tomcat, no server.http2 config。 | `POSSIBLY_REACHABLE` | 库打包但主 HTTP server 不使用 Netty；可能被 OPC UA/PLC4X/other protocol clients indirectly used。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-prj3-ccx8-p6x4, CVE-2025-55163 | HIGH | `io.netty:netty-codec-http2` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 1.75.0, 4.1.124.Final, 4.2.4.Final | yes | Netty HTTP/2 codec | Netty-based HTTP/2 client/server path enabled | netty-codec-http2 packaged via netty-all/protocol deps；embedded web server is Tomcat, no server.http2 config。 | `POSSIBLY_REACHABLE` | 库打包但主 HTTP server 不使用 Netty；可能被 OPC UA/PLC4X/other protocol clients indirectly used。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-w9fj-cfpg-grvv, CVE-2026-33871 | HIGH | `io.netty:netty-codec-http2` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.132.Final, 4.2.11.Final | yes | Netty HTTP/2 codec | Netty-based HTTP/2 client/server path enabled | netty-codec-http2 packaged via netty-all/protocol deps；embedded web server is Tomcat, no server.http2 config。 | `POSSIBLY_REACHABLE` | 库打包但主 HTTP server 不使用 Netty；可能被 OPC UA/PLC4X/other protocol clients indirectly used。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-3244-j874-rhc2, CVE-2026-44250 | HIGH | `io.netty:netty-codec-redis` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Redis/Lettuce RESP codec | Redis server or network peer returns crafted RESP payload / compromised Redis path | application.yml spring.data.redis enabled；RedisTemplate/StringRedisTemplate/Redis stream code存在；Lettuce brings Netty Redis codec。 | `LIKELY_REACHABLE` | Redis runtime path confirmed；攻击前提通常是 Redis peer/input compromise。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-5w86-c3rq-vjj7, CVE-2026-50011 | HIGH | `io.netty:netty-codec-redis` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Redis/Lettuce RESP codec | Redis server or network peer returns crafted RESP payload / compromised Redis path | application.yml spring.data.redis enabled；RedisTemplate/StringRedisTemplate/Redis stream code存在；Lettuce brings Netty Redis codec。 | `LIKELY_REACHABLE` | Redis runtime path confirmed；攻击前提通常是 Redis peer/input compromise。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-6ghj-frrj-jjj3, CVE-2026-44890 | HIGH | `io.netty:netty-codec-redis` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Redis/Lettuce RESP codec | Redis server or network peer returns crafted RESP payload / compromised Redis path | application.yml spring.data.redis enabled；RedisTemplate/StringRedisTemplate/Redis stream code存在；Lettuce brings Netty Redis codec。 | `LIKELY_REACHABLE` | Redis runtime path confirmed；攻击前提通常是 Redis peer/input compromise。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-6jv9-x5w9-2ccm, CVE-2026-48006 | HIGH | `io.netty:netty-codec-redis` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Redis/Lettuce RESP codec | Redis server or network peer returns crafted RESP payload / compromised Redis path | application.yml spring.data.redis enabled；RedisTemplate/StringRedisTemplate/Redis stream code存在；Lettuce brings Netty Redis codec。 | `LIKELY_REACHABLE` | Redis runtime path confirmed；攻击前提通常是 Redis peer/input compromise。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-jq43-27x9-3v86, CVE-2025-59419 | HIGH | `io.netty:netty-codec-smtp` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.128.Final, 4.2.7.Final | yes | Netty netty-codec-smtp module | netty-codec-smtp codec/transport is actually used by enabled protocol and handles untrusted frames | netty-codec-smtp packaged via netty-all/protocol dependency; no direct production code reference found for this specific codec except protocol libraries. | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现项目生产代码直接使用 netty-codec-smtp codec/transport；仅因 netty-all 打包存在。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-vhch-2wf3-m8rp, CVE-2026-44891 | HIGH | `io.netty:netty-codec-stomp` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty netty-codec-stomp module | netty-codec-stomp codec/transport is actually used by enabled protocol and handles untrusted frames | netty-codec-stomp packaged via netty-all/protocol dependency; no direct production code reference found for this specific codec except protocol libraries. | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现项目生产代码直接使用 netty-codec-stomp codec/transport；仅因 netty-all 打包存在。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-4qhr-g3c6-fcfx, CVE-2026-56817 | HIGH | `io.netty:netty-codec-xml` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty netty-codec-xml module | netty-codec-xml codec/transport is actually used by enabled protocol and handles untrusted frames | netty-codec-xml packaged via netty-all/protocol dependency; no direct production code reference found for this specific codec except protocol libraries. | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现项目生产代码直接使用 netty-codec-xml codec/transport；仅因 netty-all 打包存在。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-v74w-7mr3-4qg3, CVE-2026-73507 | HIGH | `io.netty:netty-codec-xml` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty netty-codec-xml module | netty-codec-xml codec/transport is actually used by enabled protocol and handles untrusted frames | netty-codec-xml packaged via netty-all/protocol dependency; no direct production code reference found for this specific codec except protocol libraries. | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现项目生产代码直接使用 netty-codec-xml codec/transport；仅因 netty-all 打包存在。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-3qp7-7mw8-wx86, CVE-2026-44249 | HIGH | `io.netty:netty-handler` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Netty TLS/SNI/proxy/handler stack | Netty TLS/SNI/native SSL/proxy handler path processes attacker-controlled traffic | Netty handler packaged; OPC UA/Milo/protocol TLS paths and Redis/protocol clients exist。 | `POSSIBLY_REACHABLE` | Netty handler runtime exists through protocol clients；specific handler use depends enabled protocol。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-4g8c-wm8x-jfhw, CVE-2025-24970 | HIGH | `io.netty:netty-handler` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.118.Final | yes | Netty TLS/SNI/proxy/handler stack | Netty TLS/SNI/native SSL/proxy handler path processes attacker-controlled traffic | Netty handler packaged; OPC UA/Milo/protocol TLS paths and Redis/protocol clients exist。 | `POSSIBLY_REACHABLE` | 有 OPC UA/protocol TLS path evidence，但未确认当前设备配置启用 SNI/native SSL/OCSP 前提。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-c4c3-7fpv-j4q5, CVE-2026-75595 | CRITICAL | `io.netty:netty-handler` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.137.Final, 4.2.17.Final | yes | Netty TLS/SNI/proxy/handler stack | Netty TLS/SNI/native SSL/proxy handler path processes attacker-controlled traffic | Netty handler packaged; OPC UA/Milo/protocol TLS paths and Redis/protocol clients exist。 | `POSSIBLY_REACHABLE` | 有 OPC UA/protocol TLS path evidence，但未确认当前设备配置启用 SNI/native SSL/OCSP 前提。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-c653-97m9-rcg9, CVE-2026-50010 | HIGH | `io.netty:netty-handler` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Netty TLS/SNI/proxy/handler stack | Netty TLS/SNI/native SSL/proxy handler path processes attacker-controlled traffic | Netty handler packaged; OPC UA/Milo/protocol TLS paths and Redis/protocol clients exist。 | `POSSIBLY_REACHABLE` | 有 OPC UA/protocol TLS path evidence，但未确认当前设备配置启用 SNI/native SSL/OCSP 前提。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-x4gw-5cx5-pgmh, CVE-2026-45416 | HIGH | `io.netty:netty-handler` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Netty TLS/SNI/proxy/handler stack | Netty TLS/SNI/native SSL/proxy handler path processes attacker-controlled traffic | Netty handler packaged; OPC UA/Milo/protocol TLS paths and Redis/protocol clients exist。 | `POSSIBLY_REACHABLE` | 有 OPC UA/protocol TLS path evidence，但未确认当前设备配置启用 SNI/native SSL/OCSP 前提。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-272m-gcwp-mpwg, CVE-2026-56820 | HIGH | `io.netty:netty-handler-ssl-ocsp` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty TLS/OCSP validation | Netty SslHandler OCSP validation enabled with attacker-controlled cert/OCSP response | OPC UA/Milo and protocol TLS paths exist; application.yml 无全局 OCSP 配置。 | `POSSIBLY_REACHABLE` | TLS protocol path可能存在，但 OCSP validator usage 未确认。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-g7hg-vrcf-mvmr, CVE-2026-56821 | HIGH | `io.netty:netty-handler-ssl-ocsp` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty TLS/OCSP validation | Netty SslHandler OCSP validation enabled with attacker-controlled cert/OCSP response | OPC UA/Milo and protocol TLS paths exist; application.yml 无全局 OCSP 配置。 | `POSSIBLY_REACHABLE` | TLS protocol path可能存在，但 OCSP validator usage 未确认。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-wc96-39fc-566f, CVE-2026-56822 | HIGH | `io.netty:netty-handler-ssl-ocsp` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.136.Final, 4.2.16.Final | yes | Netty TLS/OCSP validation | Netty SslHandler OCSP validation enabled with attacker-controlled cert/OCSP response | OPC UA/Milo and protocol TLS paths exist; application.yml 无全局 OCSP 配置。 | `POSSIBLY_REACHABLE` | TLS protocol path可能存在，但 OCSP validator usage 未确认。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-5pvg-856g-cp85, CVE-2026-47691 | HIGH | `io.netty:netty-resolver-dns` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Netty DNS resolver | Netty DnsNameResolver used and DNS response attacker-influenced | Netty DNS resolver packaged；industrial/Redis clients may resolve remote hosts。 | `POSSIBLY_REACHABLE` | 运行时存在且网络客户端会解析主机，但未确认使用 Netty DnsNameResolver 而非 JVM resolver。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-676x-f7gg-47vc, CVE-2026-45674 | HIGH | `io.netty:netty-resolver-dns` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Netty DNS resolver | Netty DnsNameResolver used and DNS response attacker-influenced | Netty DNS resolver packaged；industrial/Redis clients may resolve remote hosts。 | `POSSIBLY_REACHABLE` | 运行时存在且网络客户端会解析主机，但未确认使用 Netty DnsNameResolver 而非 JVM resolver。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-2qj4-mmr9-4v2f, CVE-2026-59902 | HIGH | `io.netty:netty-transport-sctp` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.137.Final, 4.2.17.Final | yes | Netty netty-transport-sctp module | netty-transport-sctp codec/transport is actually used by enabled protocol and handles untrusted frames | netty-transport-sctp packaged via netty-all/protocol dependency; no direct production code reference found for this specific codec except protocol libraries. | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现项目生产代码直接使用 netty-transport-sctp codec/transport；仅因 netty-all 打包存在。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-5xrh-qmmq-w6ch, CVE-2026-46340 | HIGH | `io.netty:netty-transport-sctp` | `4.1.100.Final` | 当前 4.1.100.Final 受影响；fixed: 4.1.135.Final, 4.2.15.Final | yes | Netty netty-transport-sctp module | netty-transport-sctp codec/transport is actually used by enabled protocol and handles untrusted frames | netty-transport-sctp packaged via netty-all/protocol dependency; no direct production code reference found for this specific codec except protocol libraries. | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现项目生产代码直接使用 netty-transport-sctp codec/transport；仅因 netty-all 打包存在。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-25xr-qj8w-c4vf, CVE-2025-53506 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.43, 11.0.9, 9.0.107 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/2 connector enabled and attacker can send crafted HTTP/2 traffic | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现 server.http2.enabled/HTTP2 upgrade protocol 配置；默认 Tomcat HTTP/1.1。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-27hp-xhwr-wr2m, CVE-2024-56337 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.34, 11.0.2, 9.0.98 | yes | Embedded Tomcat HTTP/1.1 server | DefaultServlet writable/partial PUT/WebDAV LOCK/PROPFIND or vulnerable static file write settings | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现 DefaultServlet writable、WebDAV servlet 或相关 Tomcat customization；当前只是 Boot static resources。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-563x-q5rq-57qp, CVE-2026-24880 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.52, 11.0.20, 9.0.116 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/1.1 network traffic to embedded Tomcat default connector | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `LIKELY_REACHABLE` | embedded Tomcat 对外监听 9090，HTTP/1.1 request parsing/general DoS 类前提成立。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-5j33-cvvr-w245, CVE-2024-50379 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.34, 11.0.2, 9.0.98 | yes | Embedded Tomcat HTTP/1.1 server | DefaultServlet writable/partial PUT/WebDAV LOCK/PROPFIND or vulnerable static file write settings | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现 DefaultServlet writable、WebDAV servlet 或相关 Tomcat customization；当前只是 Boot static resources。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-5m62-pw8w-7w9f, CVE-2026-43515 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.55, 11.0.22, 9.0.118 | yes | Embedded Tomcat HTTP/1.1 server | Tomcat container-managed FORM/DIGEST auth/security-constraint path | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 项目未发现 Tomcat FORM/DIGEST authenticator 或 web.xml security-constraint；鉴权由应用 Filter/YAML path-scope 管理。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-5mp6-jrq3-r938, CVE-2026-43513 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.55, 11.0.22, 9.0.118 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/1.1 network traffic to embedded Tomcat default connector | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `LIKELY_REACHABLE` | embedded Tomcat 对外监听 9090，HTTP/1.1 request parsing/general DoS 类前提成立。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-7jqf-v358-p8g7, CVE-2024-38286 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.25, 11.0.0-M21, 9.0.90 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/1.1 network traffic to embedded Tomcat default connector | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `LIKELY_REACHABLE` | embedded Tomcat 对外监听 9090，HTTP/1.1 request parsing/general DoS 类前提成立。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-83qj-6fr2-vhqg, CVE-2025-24813 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.35, 11.0.3, 9.0.99 | yes | Embedded Tomcat HTTP/1.1 server | DefaultServlet writable/partial PUT/WebDAV LOCK/PROPFIND or vulnerable static file write settings | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现 DefaultServlet writable、WebDAV servlet 或相关 Tomcat customization；当前只是 Boot static resources。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-9xv2-5v5q-p794, CVE-2026-65905 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.58, 11.0.25, 9.0.121 | yes | Embedded Tomcat HTTP/1.1 server | Tomcat container-managed FORM/DIGEST auth/security-constraint path | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 项目未发现 Tomcat FORM/DIGEST authenticator 或 web.xml security-constraint；鉴权由应用 Filter/YAML path-scope 管理。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-fv25-8xcx-gqjc, CVE-2026-42498 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.55, 11.0.22, 9.0.118 | yes | Embedded Tomcat HTTP/1.1 server | Tomcat WebSocket endpoint with authentication/header exposure path | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `POSSIBLY_REACHABLE` | tomcat-embed-websocket packaged；需确认生产是否启用后端 WebSocket endpoint。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-gcx9-497g-6cp6, CVE-2026-65182 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.58, 11.0.25, 9.0.121 | yes | Embedded Tomcat HTTP/1.1 server | Tomcat container-managed FORM/DIGEST auth/security-constraint path | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 项目未发现 Tomcat FORM/DIGEST authenticator 或 web.xml security-constraint；鉴权由应用 Filter/YAML path-scope 管理。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-gqp3-2cvr-x8m3, CVE-2025-48989 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.44, 11.0.10, 9.0.108 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/1.1 network traffic to embedded Tomcat default connector | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `LIKELY_REACHABLE` | embedded Tomcat 对外监听 9090，HTTP/1.1 request parsing/general DoS 类前提成立。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-gx5v-xp9w-j4cg, CVE-2026-41284 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.55, 11.0.22, 9.0.118 | yes | Embedded Tomcat HTTP/1.1 server | DefaultServlet writable/partial PUT/WebDAV LOCK/PROPFIND or vulnerable static file write settings | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现 DefaultServlet writable、WebDAV servlet 或相关 Tomcat customization；当前只是 Boot static resources。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-h3gc-qfqq-6h8f, CVE-2025-48988 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.42, 11.0.8, 9.0.106 | yes | Embedded Tomcat HTTP/1.1 server | multipart upload endpoint receives attacker-controlled upload | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | collector-application/collector-boot main 中 MultipartFile/@RequestPart 搜索为 0。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-h3x4-894j-xpx5, CVE-2026-68525 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.58, 11.0.25, 9.0.121 | yes | Embedded Tomcat HTTP/1.1 server | Tomcat container-managed FORM/DIGEST auth/security-constraint path | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 项目未发现 Tomcat FORM/DIGEST authenticator 或 web.xml security-constraint；鉴权由应用 Filter/YAML path-scope 管理。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-h6fc-48rj-7qqh, CVE-2026-43512 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.55, 11.0.22, 9.0.118 | yes | Embedded Tomcat HTTP/1.1 server | Tomcat container-managed FORM/DIGEST auth/security-constraint path | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 项目未发现 Tomcat FORM/DIGEST authenticator 或 web.xml security-constraint；鉴权由应用 Filter/YAML path-scope 管理。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-mgp5-rv84-w37q, CVE-2026-24734 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.52, 11.0.18, 9.0.115 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/1.1 network traffic to embedded Tomcat default connector | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `LIKELY_REACHABLE` | embedded Tomcat 对外监听 9090，HTTP/1.1 request parsing/general DoS 类前提成立。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-r29c-68gh-xp6x, CVE-2026-41293 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.55, 11.0.22, 9.0.118 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/2 connector enabled and attacker can send crafted HTTP/2 traffic | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现 server.http2.enabled/HTTP2 upgrade protocol 配置；默认 Tomcat HTTP/1.1。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-rv64-5gf8-9qq8, CVE-2026-34483 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.54, 11.0.21, 9.0.116 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/1.1 network traffic to embedded Tomcat default connector | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `LIKELY_REACHABLE` | embedded Tomcat 对外监听 9090，HTTP/1.1 request parsing/general DoS 类前提成立。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-wm9w-rjj3-j356, CVE-2024-34750 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.25, 11.0.0-M21, 9.0.90 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/1.1 network traffic to embedded Tomcat default connector | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `LIKELY_REACHABLE` | embedded Tomcat 对外监听 9090，HTTP/1.1 request parsing/general DoS 类前提成立。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-wmwf-9ccg-fff5, CVE-2025-55752 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.45, 11.0.11, 9.0.109 | yes | Embedded Tomcat HTTP/1.1 server | DefaultServlet writable/partial PUT/WebDAV LOCK/PROPFIND or vulnerable static file write settings | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现 DefaultServlet writable、WebDAV servlet 或相关 Tomcat customization；当前只是 Boot static resources。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-wr62-c79q-cv37, CVE-2025-52520 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.43, 11.0.9, 9.0.107 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/1.1 network traffic to embedded Tomcat default connector | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `LIKELY_REACHABLE` | embedded Tomcat 对外监听 9090，HTTP/1.1 request parsing/general DoS 类前提成立。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-x4m4-345f-5h5g, CVE-2026-34487 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | `10.1.16` | 当前 10.1.16 受影响；fixed: 10.1.54, 11.0.21, 9.0.117 | yes | Embedded Tomcat HTTP/1.1 server | HTTP/1.1 network traffic to embedded Tomcat default connector | collector-boot application.yml 使用 embedded Tomcat，server.port=9090/context-path=/collector；GracefulShutdown 自定义 Connector pause，无 HTTP/2/WebDAV/rewrite/Digest/Form 自定义。 | `LIKELY_REACHABLE` | embedded Tomcat 对外监听 9090，HTTP/1.1 request parsing/general DoS 类前提成立。 | 07.2-R0 通过 Boot 3.5.16 过渡升级带入 Tomcat 10.1.x patched line；长期 Boot 4 migration |
| GHSA-574f-3g2m-x479, CVE-2025-14813 | CRITICAL | `org.bouncycastle:bcprov-jdk18on` | `1.80` | 当前 1.80 受影响；fixed: 1.80.2, 1.81.1, 1.84 | yes | Bouncy Castle crypto provider via Milo/OPC UA TLS/cert stack | Affected GOST algorithm/mode used in TLS/certificate/crypto path | Milo/OPC UA and securityPolicy schema present; no production code evidence of GOST 28147 usage。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | OPC UA TLS path可能存在，但 GOST algorithm 未在源码/配置中出现。 | 07.2-R3 protocol stack compatible Bouncy Castle patch; verify Milo compatibility |
| GHSA-574f-3g2m-x479, CVE-2025-14813 | CRITICAL | `org.bouncycastle:bcprov-jdk18on` | `1.81` | 当前 1.81 受影响；fixed: 1.80.2, 1.81.1, 1.84 | yes | Bouncy Castle crypto provider via Milo/OPC UA TLS/cert stack | Affected GOST algorithm/mode used in TLS/certificate/crypto path | Milo/OPC UA and securityPolicy schema present; no production code evidence of GOST 28147 usage。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | OPC UA TLS path可能存在，但 GOST algorithm 未在源码/配置中出现。 | 07.2-R3 protocol stack compatible Bouncy Castle patch; verify Milo compatibility |
| GHSA-jmp9-x22r-554x, CVE-2025-41249 | HIGH | `org.springframework:spring-core` | `6.1.1` | 当前 6.1.1 受影响；fixed: 6.2.11 | yes | Spring annotation/method security support | 依赖 Spring annotation detection + method/security annotation combination | 运行时打包 spring-core；当前搜索未确认生产 main 中 @PreAuthorize/@PostAuthorize/@EnableMethodSecurity 命中。 | `POSSIBLY_REACHABLE` | Framework 基础库可达，但 advisory 需要特定 annotation detection 组合，当前未确认业务路径。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-r5w3-xv2f-j59q, CVE-2026-41850 | HIGH | `org.springframework:spring-expression` | `6.1.1` | 当前 6.1.1 受影响；fixed: 6.2.19, 7.0.8 | yes | SpEL expression evaluation | 攻击者可控 SpEL expression 或可触发复杂 SpEL evaluation | 运行时打包 spring-expression；源码搜索未发现直接 SpelExpressionParser/ExpressionParser 业务调用。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现项目直接解析用户可控 SpEL 表达式；保留框架间接风险由 Boot 统一升级处理。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-2wrp-6fg6-hmc5, CVE-2024-22262 | HIGH | `org.springframework:spring-web` | `6.1.1` | 当前 6.1.1 受影响；fixed: 5.3.34, 6.0.19, 6.1.6 | yes | Spring Web URL parsing / HTTP client-server helper surface | 应用使用受影响 URL parser/redirect/SSRF host validation path处理攻击者 URL | collector-boot 打包 spring-web；HTTP API confirmed；需按 URL validation 调用点约束。 | `POSSIBLY_REACHABLE` | Web 栈可达，但未确认存在把用户 URL 交给 UriComponentsBuilder/redirect/SSRF 校验的具体业务路径。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-ccgv-vj62-xf9h, CVE-2024-22243 | HIGH | `org.springframework:spring-web` | `6.1.1` | 当前 6.1.1 受影响；fixed: 5.3.32, 6.0.17, 6.1.4 | yes | Spring Web URL parsing / HTTP client-server helper surface | 应用使用受影响 URL parser/redirect/SSRF host validation path处理攻击者 URL | collector-boot 打包 spring-web；HTTP API confirmed；需按 URL validation 调用点约束。 | `POSSIBLY_REACHABLE` | Web 栈可达，但未确认存在把用户 URL 交给 UriComponentsBuilder/redirect/SSRF 校验的具体业务路径。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-hgjh-9rj2-g67j, CVE-2024-22259 | HIGH | `org.springframework:spring-web` | `6.1.1` | 当前 6.1.1 受影响；fixed: 5.3.33, 6.0.18, 6.1.5 | yes | Spring Web URL parsing / HTTP client-server helper surface | 应用使用受影响 URL parser/redirect/SSRF host validation path处理攻击者 URL | collector-boot 打包 spring-web；HTTP API confirmed；需按 URL validation 调用点约束。 | `POSSIBLY_REACHABLE` | Web 栈可达，但未确认存在把用户 URL 交给 UriComponentsBuilder/redirect/SSRF 校验的具体业务路径。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-3chg-m5w7-qfv5, CVE-2026-41845 | HIGH | `org.springframework:spring-webmvc` | `6.1.1` | 当前 6.1.1 受影响；fixed: 6.2.19, 7.0.8 | yes | Spring MVC HTTP runtime | 应用显式调用 Spring JavaScriptUtils 转义攻击者输入 | collector-boot 打包 spring-webmvc；未发现 VersionResourceResolver/resourceChain/addResourceHandlers 自定义配置；普通 static resources 存在但不等同 versioned resources。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 源码搜索未发现 JavaScriptUtils/HtmlUtils 调用。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-cx7f-g6mp-7hqm, CVE-2024-38816 | HIGH | `org.springframework:spring-webmvc` | `6.1.1` | 当前 6.1.1 受影响；fixed: 6.1.13 | yes | Spring MVC HTTP runtime | functional routing/static resource path traversal specific usage | collector-boot 打包 spring-webmvc；未发现 VersionResourceResolver/resourceChain/addResourceHandlers 自定义配置；普通 static resources 存在但不等同 versioned resources。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现 RouterFunction/functional WebMvc 路由；应用主要为注解 Controller + Boot static。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-g5vr-rgqm-vf78, CVE-2024-38819 | HIGH | `org.springframework:spring-webmvc` | `6.1.1` | 当前 6.1.1 受影响；fixed: 6.1.14 | yes | Spring MVC HTTP runtime | functional routing/static resource path traversal specific usage | collector-boot 打包 spring-webmvc；未发现 VersionResourceResolver/resourceChain/addResourceHandlers 自定义配置；普通 static resources 存在但不等同 versioned resources。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现 RouterFunction/functional WebMvc 路由；应用主要为注解 Controller + Boot static。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-x23c-287f-qqv5, CVE-2026-41842 | HIGH | `org.springframework:spring-webmvc` | `6.1.1` | 当前 6.1.1 受影响；fixed: 6.2.19, 7.0.8 | yes | Spring MVC HTTP runtime | Spring MVC/WebFlux versioned static resources enabled (VersionResourceResolver/resourceChain or spring.web.resources.chain.strategy) | collector-boot 打包 spring-webmvc；未发现 VersionResourceResolver/resourceChain/addResourceHandlers 自定义配置；普通 static resources 存在但不等同 versioned resources。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 源码/配置搜索未发现 versioned-resource chain 配置；普通静态资源不满足 advisory 前提。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-rc42-6c7j-7h5r, CVE-2025-22235 | HIGH | `org.springframework.boot:spring-boot` | `3.2.0` | 当前 3.2.0 受影响；fixed: 3.3.11, 3.4.5 | yes | Actuator EndpointRequest matcher | Spring Security EndpointRequest.to() matcher用于未暴露 actuator endpoint | 运行时打包 spring-boot；源码搜索未发现 SecurityFilterChain/EndpointRequest 生产配置。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 未发现当前项目使用 EndpointRequest.to() 自定义 matcher。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |
| GHSA-mgvc-8q2h-5pgc, CVE-2026-22733 | HIGH | `org.springframework.boot:spring-boot-starter-actuator` | `3.2.0` | 当前 3.2.0 受影响；fixed: 3.5.12, 4.0.4 | yes | Actuator CloudFoundry endpoint | CloudFoundry actuator endpoint/filter exposed in deployment | application.yml 暴露 health/info/metrics/prometheus；未暴露 cloudfoundry。 | `NOT_REACHABLE_BY_CURRENT_USAGE` | 当前 actuator exposure 未包含 CloudFoundry endpoint。 | 07.2-R0 tactical Boot/JVM stack uplift or focused compatible patch |

### Spring Framework CVE-2026-41842 Specific Decision

`org.springframework:spring-webmvc 6.1.1` 受 `GHSA-x23c-287f-qqv5 / CVE-2026-41842` 影响范围覆盖。

R1 搜索项：

```text
VersionResourceResolver
ResourceUrlProvider
ResourceUrlEncodingFilter
resourceChain
addResourceHandlers
spring.web.resources.chain
spring.web.resources.chain.strategy
```

结果：未发现当前仓库生产源码/配置启用 versioned static resources 或 resource chain strategy。

分类：

```text
NOT_REACHABLE_BY_CURRENT_USAGE
```

理由：该 advisory 前提是 Spring MVC/WebFlux versioned static resources；当前存在普通 static resources / `/desktop/**` 静态文件并不自动满足 versioned resources 前提。

### Spring Boot Lifecycle Correction

原文中的 “Spring Boot 3.x supported line” 已被 R1 修正为两条路径：

1. Tactical compatibility path：
   - `3.2.0 -> 3.5.16`
   - 目的：保持 Boot 3 / Spring 6 / Tomcat 10 / Jackson 2 的兼容边界，带入大量 security patch。
   - 状态：`TRANSITIONAL SECURITY UPLIFT`。
   - 限制：`3.5.16` 已是 Spring Boot 3.5.x 最后一个 OSS release，3.5.x OSS support 已结束；不能宣称为长期 OSS-supported endpoint。
2. Strategic supported path：
   - 当前 OSS-supported path 应为 Spring Boot `4.0.x` 或 `4.1.x`。
   - R1 evidence：endoflife.date / Spring references 显示 `4.1.1`、`4.0.8` 为 2026-08 patch；Boot 4 带来 Spring Framework 7 / Tomcat 11 / Servlet 6.1 / Jackson 3 等 managed dependency 变化。
   - 结论：需要单独 `Spring Boot 4 Migration`，不能放入小型 security patch task。

### Electron Lifecycle Correction

Electron 33 lifecycle 修正：

```text
Electron 33 EOL = 2025-04-29
Chromium = M130
Node.js = v20.18.0
```

当前 2026-09 官方支持策略仍是 latest 3 stable major releases。R1 通过 npm registry 核实当前 supported majors/latest patch：

```text
Electron 42 latest patch = 42.11.3
Electron 43 latest patch = 43.7.0
Electron 44 latest patch = 44.3.0
```

07.2 默认建议目标：

```text
Electron 44 latest stable patch
```

除非 07.2-R1 兼容性验证发现 Electron 44 blocker。

### electron-builder Advisory Reclassification

当前 Windows distribution target：

```text
win.target = nsis
```

`GHSA-7g7r-gx96-252g` 主要影响 Linux AppImage。对于当前 Windows NSIS：

```text
NOT_REACHABLE_BY_CURRENT_DISTRIBUTION_TARGET
```

但 `app-builder-lib < 26.15.0` 仍属于 vulnerable build dependency，应在 07.2-R2 toolchain remediation 中升级并重新验证 pack/dist。

### builder-util-runtime updater advisory

当前项目未发现：

```text
electron-updater
autoUpdater
authenticated GitLab updater
private token redirect flow
```

因此 `GHSA-p2f4-r6v6-j797`：

```text
NOT_REACHABLE_BY_CURRENT_USAGE
```

仍可通过 electron-builder toolchain upgrade 消除。

### Vitest Advisory Reclassification

当前：

```text
vitest 2.1.9
standard script: npm test -> vitest run
```

未发现标准脚本使用：

```text
--ui
browser mode
network-exposed Vitest API
```

`GHSA-5xrq-8626-4rwp` 分类：

```text
DEV/TEST ONLY
NOT_REACHABLE_IN_STANDARD_TEST_COMMAND
```

仍建议后续 toolchain task 升级。

### Dependency-Check Remaining Limitation

Dependency-Check 状态保持：

```text
Dependency-Check secondary scanner: SCAN INCOMPLETE
```

但 R1 Java Runtime Critical/High reachability matrix 已用 OSV/GHSA + code/config evidence 补齐：

```text
Critical/High reachability matrix: COMPLETE
Critical/High UNKNOWN: 0
```

### Revised Task 07.2 Scope

1. **Task 07.2-R0 — Java Web Runtime Tactical Security Uplift**
   - Candidate target: Spring Boot `3.5.16`
   - Classification: `TRANSITIONAL`, `NOT CURRENT OSS-SUPPORTED`
   - After R0 still must decide:
     - A. Spring commercial support, or
     - B. Boot 4 migration.
2. **Task 07.2-R1 — Electron Runtime Upgrade**
   - Default target: Electron `44.3.0` or latest Electron 44 stable patch at execution time.
   - Verify preload / CSP / navigation / external URL / app.asar / Windows launch smoke.
3. **Task 07.2-R2 — npm Build Toolchain / Lockfile Reproducibility Repair**
   - Fix npm ci blocker without blind mass upgrade.
   - Resolve electron-builder / app-builder-lib / Vitest / Vite / SBOM issues.
4. **Task 07.2-R3 — Protocol Stack Patch Review**
   - Netty / Bouncy Castle / PLC4X / Milo compatibility by actually enabled protocol.

### R1 Status

```text
Task 07.1-R1: INCOMPLETE
Task 07.1: INCOMPLETE

Dependency Security Baseline:
INCOMPLETE — npm reproducible install failed

Dependency Remediation:
NOT STARTED

Next:
Fix npm lockfile reproducibility / clean-install baseline before Task 07.2 remediation
```

## Task 07.1-R2 — npm Lockfile Reproducibility Repair

### R2 Scope

本 R2 只尝试修复 `collector-desktop/package-lock.json` 与当前 `collector-desktop/package.json` 在 npm 12 下不同步的问题。未启动 07.2，未升级生产依赖，未修改 `package.json` / `pom.xml` / 源码。

### Node / npm Environment

```text
node --version = v22.23.2
npm --version = 12.0.2
npm config get registry = https://registry.npmjs.org/
npm config get legacy-peer-deps = false
npm config get strict-peer-deps = false
npm config get allow-remote = none before session override
```

说明：npm 12 当前环境默认 `allow-remote=none`，会拒绝从 lockfile 的 remote tarball URL 拉包。由于 lockfile 记录了大量 `resolved` tarball URL，本轮为验证 dependency graph 曾在 shell session 中设置 `NPM_CONFIG_ALLOW_REMOTE=all` 后执行 `npm ci --prefix collector-desktop`；未使用 `--legacy-peer-deps`、`--force`。

### Root Cause

R1 的 `npm ci` 首个 blocker 是 lockfile 与 manifest/transitive graph 不同步：

```text
Invalid: lock file's keyv@4.5.4 does not satisfy keyv@5.6.0
Missing: electron-builder-squirrel-windows@25.1.8 from lock file
Missing: archiver@5.3.2 / fs-extra@10.1.0 / ... from lock file
```

R2 发现另一个仍未关闭的 npm graph 问题：

```text
invalid: vite@6.4.3, ^5.0.0 required by @vitest/mocker@2.1.9
```

该问题来自当前 direct dependency 意图：项目直接锁定 `vite 6.4.3`，同时 `vitest 2.1.9` / `@vitest/mocker 2.1.9` 要求 `vite ^5.0.0`。在不修改 `package.json`、不升级/降级 direct dependency、不开 `legacy-peer-deps` 的约束下，单靠 npm 生成 `package-lock.json` 不能让 `npm ls --all` 无 ELSPROBLEMS。

### Repair Method

授权命令已执行：

```text
npm --prefix collector-desktop install --package-lock-only --ignore-scripts --no-audit --no-fund
```

为确认 npm 是否能通过不同安装策略自动 nest peer，又执行过 npm 生成的 lockfile-only 尝试：

```text
npm --prefix collector-desktop install --package-lock-only --ignore-scripts --no-audit --no-fund --install-strategy=nested
```

最终 lockfile 仍由 npm 生成，没有手工补节点、复制 integrity 或移动依赖。

### Lockfile Diff Summary

| Metric | Count |
| --- | ---: |
| packages before | 781 |
| packages after | 811 |
| added package nodes | 33 |
| removed package nodes | 3 |
| version-changed existing package nodes | 1 |
| resolved URL changed existing nodes | 2 |
| integrity changed existing nodes | 1 |
| resolved host changed existing nodes | 2 |

Added package nodes are focused around the missing `electron-builder-squirrel-windows@25.1.8` / Squirrel-Windows archive graph and keyv placement repair, including `archiver`, `fs-extra`, `tar-stream`, `zip-stream`, `archiver-utils`, and nested `keyv@4.5.4` for consumers that still require keyv 4.x.

Version-changed existing node:

```text
node_modules/keyv: 4.5.4 -> 5.6.0
```

This is required for `@cacheable/*` / `cacheable` consumers that require `keyv ^5.6.0`; keyv 4.x remains nested for `cacheable-request` / `flat-cache` consumers.

### Direct Version Before/After

| Dependency | Before | After | Changed? |
| --- | ---: | ---: | --- |
| `electron` | `33.4.11` | `33.4.11` | NO |
| `electron-builder` | `25.1.8` | `25.1.8` | NO |
| `vite` | `6.4.3` | `6.4.3` | NO |
| `vitest` | `2.1.9` | `2.1.9` | NO |
| `vue` | `3.5.41` | `3.5.41` | NO |
| `vue-router` | `4.6.4` | `4.6.4` | NO |
| `pinia` | `2.3.1` | `2.3.1` | NO |
| `axios` | `1.19.0` | `1.19.0` | NO |
| `element-plus` | `2.14.4` | `2.14.4` | NO |
| `@element-plus/icons-vue` | `2.3.2` | `2.3.2` | NO |
| `typescript` | `5.9.3` | `5.9.3` | NO |
| `vue-tsc` | `2.2.12` | `2.2.12` | NO |
| `eslint` | `10.9.1` | `10.9.1` | NO |
| `stylelint` | `17.14.1` | `17.14.1` | NO |

Direct dependency versions remained unchanged.

### Registry Host Evidence

Before:

```text
{'registry.npmmirror.com': 780, 'registry.npmjs.org': 1}
```

After:

```text
{'registry.npmmirror.com': 775, 'registry.npmjs.org': 36}
```

Existing resolved host changes were limited to 2 node(s): node_modules/json-buffer, node_modules/keyv.

Lockfile still contains third-party npm mirror resolved URLs (`registry.npmmirror.com`) plus some `registry.npmjs.org` URLs. Every package-lock entry with tarball URL continues to carry SHA integrity. R2 did not perform full registry URL normalization. Whether to unify to official npm registry remains a separate supply-chain policy decision.

### npm ci Result

Default current npm config:

```text
npm config get allow-remote = none
npm ci --prefix collector-desktop
=> EALLOWREMOTE: Fetching packages of type "remote" have been disabled
```

With session fetch policy allowing lockfile remote tarballs:

```text
NPM_CONFIG_ALLOW_REMOTE=all npm ci --prefix collector-desktop
=> exit 0
```

No `--legacy-peer-deps` and no `--force` were used.

### npm ls / ELSPROBLEMS Result

```text
npm --prefix collector-desktop ls --all --json
=> exit 1 / ELSPROBLEMS
```

Remaining problem:

```text
invalid: vite@6.4.3 F:\ideaWorkSpace\data-collection-service\collector-desktop\node_modules\vite
```

SBOM reports the exact peer mismatch:

```text
invalid: vite@6.4.3, ^5.0.0 required by @vitest/mocker@2.1.9
```

This cannot be fixed by package-lock-only repair while also preserving direct `vite 6.4.3` and `vitest 2.1.9` and avoiding `legacy-peer-deps`.

### npm Audit Rerun

Clean install graph audit rerun result:

| Audit | Critical | High | Moderate | Low | Total | Dependencies total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| full | 2 | 17 | 3 | 1 | 23 | 811 |
| `--omit=dev` | 0 | 0 | 0 | 0 | 0 | 811 |

Production-only audit remains 0 vulnerabilities.

### npm outdated Rerun

```text
npm --prefix collector-desktop outdated --json
=> non-zero because outdated packages exist
outdated package count = 19
```

This is informational only and was not remediated in R2.

### npm SBOM Rerun

```text
npm --prefix collector-desktop sbom --sbom-format cyclonedx
=> ESBOMPROBLEMS
```

Exact reason:

```text
invalid: vite@6.4.3, ^5.0.0 required by @vitest/mocker@2.1.9
```

### Frontend Verification

Despite `npm ls` / SBOM peer graph problem, the rebuilt `node_modules` can run the current desktop verification chain:

```text
npm --prefix collector-desktop run lint              PASS
npm --prefix collector-desktop run stylelint         PASS
npm --prefix collector-desktop run typecheck         PASS
npm --prefix collector-desktop test                  PASS
npm --prefix collector-desktop run build             PASS
npm --prefix collector-desktop run build:web         PASS
npm --prefix collector-desktop run verify            PASS
npm --prefix collector-desktop run pack              PASS
```

`build:web` temporarily changed generated static `collector-boot/src/main/resources/static/desktop/index.html`; because R2 scope allows only lockfile/doc changes and this was generated output drift, it was restored from `HEAD` content without using `git checkout` / `git restore`.

### Java Critical/High Matrix Status

R1 Java Runtime Critical/High matrix remains unchanged:

```text
Runtime Critical/High advisory candidates = 78
UNKNOWN = 0
```

R2 did not redo Java CVE analysis.

### R2 Status

```text
Task 07.1-R2: INCOMPLETE
Task 07.1-R1: INCOMPLETE
Task 07.1: INCOMPLETE

Dependency Security Baseline:
INCOMPLETE — npm ls/SBOM still blocked by Vite/Vitest peer graph mismatch

Dependency Remediation:
NOT STARTED
```

R2 closes the original missing-node / keyv lockfile sync error, but it does not satisfy the full PASS gate because `npm ls --all` still reports ELSPROBLEMS and npm SBOM still fails.

### Next

Before `Task 07.2`, decide how to resolve the dev-tool peer graph while respecting security-remediation scope. Likely options are:

1. Accept a narrowly scoped dev-toolchain compatibility repair that changes direct versions (`vite`/`vitest`) in a dedicated task; or
2. Keep R2 incomplete and document that package-lock-only repair cannot make the current manifest graph fully reproducible under strict npm 12 checks.

## Task 07.1-R3 — Vitest/Vite Peer Compatibility Closure

### R3 Scope

R3 only resolves the remaining npm dev-tool peer compatibility blocker between direct `vite 6.4.3` and `vitest` / `@vitest/mocker 2.1.9`. It does not start Task 07.2 and does not perform production dependency remediation.

Allowed files changed in R3:

```text
collector-desktop/package.json
collector-desktop/package-lock.json
collector-desktop/docs/production-readiness/DEPENDENCY-SECURITY-BASELINE.md
```

No Java matrix update was performed.

### Baseline Environment

```text
node --version = v22.23.2
npm --version = 12.0.2
npm config get registry = https://registry.npmjs.org/
npm config get legacy-peer-deps = false
npm config get strict-peer-deps = false
npm config get allow-remote = none
```

The current npm execution policy has `allow-remote=none`; R3 therefore uses session-only `NPM_CONFIG_ALLOW_REMOTE=all` for clean install reproducibility from the lockfile tarball URLs. No project, user, or global npm config was modified.

### Root Cause

R2 left one dependency graph blocker:

```text
vite@6.4.3
vitest@2.1.9
@vitest/mocker@2.1.9 requires vite ^5.0.0
```

Baseline command result:

```text
npm --prefix collector-desktop ls vite vitest @vitest/mocker --all
=> exit 1 / ELSPROBLEMS
invalid: vite@6.4.3 ... required by @vitest/mocker@2.1.9
```

This was a manifest-level dev-tool peer mismatch, not a missing package-lock node.

### Version Decision

R3 keeps the existing Vite build baseline:

```text
vite package range = ^6.0.7
vite resolved = 6.4.3
```

R3 upgrades only Vitest:

```text
vitest: ^2.1.8 -> ^4.1.11
resolved vitest: 2.1.9 -> 4.1.11
resolved @vitest/mocker: 2.1.9 -> 4.1.11
```

Reason: Vitest `4.1.11` and `@vitest/mocker 4.1.11` support Vite 6 and Node 22, and also remove the `@vitest/mocker >=2.1.0, <4.1.11` advisory range. Vitest 3.x was intentionally not selected because `GHSA-82fw-gwwq-j7x9` remains in the affected range before 4.1.11.

### Update Command

Executed:

```text
NPM_CONFIG_ALLOW_REMOTE=all npm --prefix collector-desktop install --save-dev vitest@4.1.11 --no-audit --no-fund
```

Not used:

```text
--legacy-peer-deps
--force
npm update
npm audit fix
npm audit fix --force
```

### package.json Diff

The only direct dependency intent change is:

```diff
- "vitest": "^2.1.8"
+ "vitest": "^4.1.11"
```

No Vite, Electron, electron-builder, Vue, Router, Pinia, Axios, Element Plus, TypeScript, ESLint, Stylelint, or backend Maven dependency was changed.

### Direct Version Drift Check

| Dependency | Before | After | Expected |
| --- | ---: | ---: | --- |
| `vite` | `6.4.3` | `6.4.3` | unchanged |
| `vitest` | `2.1.9` | `4.1.11` | upgraded |
| `@vitest/mocker` | `2.1.9` | `4.1.11` | upgraded with Vitest |
| `electron` | `33.4.11` | `33.4.11` | unchanged |
| `electron-builder` | `25.1.8` | `25.1.8` | unchanged |
| `vue` | `3.5.41` | `3.5.41` | unchanged |
| `vue-router` | `4.6.4` | `4.6.4` | unchanged |
| `pinia` | `2.3.1` | `2.3.1` | unchanged |
| `axios` | `1.19.0` | `1.19.0` | unchanged |
| `element-plus` | `2.14.4` | `2.14.4` | unchanged |
| `@element-plus/icons-vue` | `2.3.2` | `2.3.2` | unchanged |
| `typescript` | `5.9.3` | `5.9.3` | unchanged |
| `vue-tsc` | `2.2.12` | `2.2.12` | unchanged |
| `eslint` | `10.9.1` | `10.9.1` | unchanged |
| `stylelint` | `17.14.1` | `17.14.1` | unchanged |

### Lockfile Diff Summary

| Metric | Count |
| --- | ---: |
| packages before | 811 |
| packages after | 757 |
| added package nodes | 4 |
| removed package nodes | 58 |
| version-changed existing package nodes | 14 |
| resolved URL changed existing nodes | 16 |
| integrity changed existing nodes | 14 |
| resolved host changed existing nodes | 16 |

Added nodes:

```text
@types/chai
@types/deep-eql
convert-source-map
obug
```

Removed nodes are focused on the old Vitest 2 tree, including old nested `vite-node`, duplicate nested `vite@5.4.21`, nested `esbuild` optional packages, and old assertion helper packages. This is expected for the Vitest 2 -> 4 dev-tool replacement.

Version-changed nodes are Vitest-related/tooling nodes:

```text
@vitest/expect
@vitest/mocker
@vitest/pretty-format
@vitest/runner
@vitest/snapshot
@vitest/spy
@vitest/utils
chai
es-module-lexer
pathe
std-env
tinyexec
tinyrainbow
vitest
```

### Registry Host Evidence

Before R3:

```text
registry.npmmirror.com = 775
registry.npmjs.org = 36
```

After R3:

```text
registry.npmmirror.com = 701
registry.npmjs.org = 56
```

R3 did not normalize registry URLs globally. Mixed `npmmirror` + `npmjs` resolved URLs remain. The host changes are localized to the Vitest subtree removed/updated by npm.

### Clean Install Gate

Executed with the current environment's explicit remote-fetch policy:

```text
NPM_CONFIG_ALLOW_REMOTE=all npm ci --prefix collector-desktop
=> exit 0
```

This distinguishes dependency graph reproducibility from the local npm execution policy `allow-remote=none`.

### npm ls / ELSPROBLEMS Gate

Executed:

```text
npm --prefix collector-desktop ls --all --json
=> exit 0
```

Result:

```text
ELSPROBLEMS = 0
missing package = 0
invalid vite = 0
invalid vitest = 0
invalid @vitest/mocker = 0
```

Actual focused graph:

```text
collector-desktop@0.1.0
+-- @vitejs/plugin-vue@5.2.4
| `-- vite@6.4.3 deduped
+-- vite@6.4.3
`-- vitest@4.1.11
  +-- @vitest/mocker@4.1.11
  | `-- vite@6.4.3 deduped
  `-- vite@6.4.3 deduped
```

### npm SBOM Gate

Executed:

```text
npm --prefix collector-desktop sbom --sbom-format cyclonedx
=> exit 0
```

CycloneDX output was generated only as a temporary audit artifact and was not committed.

### npm Audit Rerun

| Audit | Critical | High | Moderate | Low | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| full | 1 | 16 | 0 | 1 | 18 |
| `--omit=dev` | 0 | 0 | 0 | 0 | 0 |

Production npm audit remains clean.

### Vitest Security Advisories

After upgrading to Vitest `4.1.11`, these advisories are no longer present in npm audit output:

```text
GHSA-5xrq-8626-4rwp
GHSA-82fw-gwwq-j7x9
```

Remaining npm audit findings are Electron / electron-builder / packaging-toolchain related and remain out of R3 scope.

### Vitest 4 Breaking Change Review

Searched project test/config usage for:

```text
vi.mock
vi.spyOn
vi.fn
restoreAllMocks / resetAllMocks / clearAllMocks
mockReset / mockRestore / mockResolvedValue / mockImplementation
fake timers / useFakeTimers / useRealTimers / advanceTimersByTime
snapshots
pool / threads / workers / minWorkers / maxWorkers
coverage
browser mode
UI mode
```

Findings:

- Tests use common `vi.fn`, `vi.mocked`, mock reset/resolved helpers, and `vi.restoreAllMocks` / `vi.clearAllMocks` patterns.
- `src/stores/websocket.store.test.ts` uses fake timers via `vi.useFakeTimers`, `vi.useRealTimers`, and `vi.advanceTimersByTime`.
- No snapshot assertions were found.
- No custom Vitest pool/thread/worker/minWorkers/maxWorkers config was found.
- No Vitest browser mode or UI mode script/config was introduced.
- Existing standard script remains `npm test = vitest run`.

No test code changes were required for Vitest 4.

### Test Regression

Executed:

```text
npm --prefix collector-desktop test
```

Result:

```text
RUN  v4.1.11
Test Files  76 passed (76)
Tests       561 passed (561)
```

### Frontend Verification

Full validation chain passed:

```text
npm --prefix collector-desktop run lint       PASS
npm --prefix collector-desktop run stylelint  PASS
npm --prefix collector-desktop run typecheck  PASS
npm --prefix collector-desktop test           PASS
npm --prefix collector-desktop run build      PASS
npm --prefix collector-desktop run build:web  PASS
npm --prefix collector-desktop run verify     PASS
npm --prefix collector-desktop run pack       PASS
```

`build:web` generated static output drift in `collector-boot/src/main/resources/static/desktop/index.html`; the file was restored from `HEAD` content and no generated static diff is retained.

### Java Critical/High Matrix Status

R1 Java Runtime Critical/High matrix remains unchanged:

```text
Runtime Critical/High advisory candidates = 78
UNKNOWN = 0
```

R3 did not rescan or modify Java dependency reachability.

### R3 Final Status

```text
Task 07.1-R3: PASS / COMPLETE
Task 07.1-R2: SUPERSEDED / BLOCKER CLOSED
Task 07.1-R1: SUPERSEDED / BLOCKER CLOSED
Task 07.1: PASS / COMPLETE

Dependency Security Baseline:
COMPLETE

Dependency Remediation:
NOT STARTED

Next:
Task 07.2 — Targeted Dependency Security Remediation
```

Do not start 07.2 automatically.

## Task 07.2-R0 — Java Web Runtime Tactical Security Uplift

### Scope and Positioning

R0 starts dependency remediation after 07.1 baseline closure. It is limited to the Java Web Runtime first-stage uplift:

```text
Spring Boot 3.2.0 -> 3.5.16
TRANSITIONAL SECURITY UPLIFT
NOT CURRENT OSS-SUPPORTED LONG-TERM ENDPOINT
```

R0 does not perform Electron, electron-builder, Vite, Vitest, Vue, npm toolchain, protocol library major, or Spring Boot 4 remediation.

### Baseline Commit

```text
current remote baseline = a25be34701d955e1a505d671f3f4ca560b33f121
git log -1 --oneline = a25be34 修改
branch = feature_2.0...github/feature_2.0
```

### Maven Changes

Root `pom.xml` changes:

```text
spring-boot-starter-parent: 3.2.0 -> 3.5.16
spring-boot.version:       3.2.0 -> 3.5.16
springdoc.version:         2.3.0 -> 2.8.17
java.version:              stays 17
mybatis.version:           stays 3.0.3
```

Project property overrides released to Spring Boot 3.5.16 managed properties:

```text
netty.version
caffeine.version
jedis.version
lombok.version
commons-lang3.version
commons-pool2.version
```

The explicit root `maven-compiler-plugin` version was removed so Spring Boot parent plugin management controls it. Existing compiler configuration and Java 17 source/target remain.

### Boot Property Collision Matrix

| Property | Project Current | Boot 3.5.16 Managed | Security Relevant? | Action |
| --- | ---: | ---: | --- | --- |
| `netty.version` | `4.1.100.Final` | `4.1.135.Final` | Yes, R1 found many Netty runtime advisories | Released project override; Boot now manages Netty. |
| `caffeine.version` | `3.1.8` | `3.2.4` | Low/indirect cache library hygiene | Released project override; no code-level pin reason found. |
| `jedis.version` | `5.1.0` | `6.0.0` | Redis client stack; project uses Spring Data Redis/Lettuce at runtime | Released project override; no direct Jedis code import found. |
| `lombok.version` | `1.18.30` | `1.18.46` | Build/annotation processor hygiene | Released project override; annotation processor now uses Boot property. |
| `commons-lang3.version` | `3.14.0` | `3.17.0` | Yes, tracked in 07.1 advisory review | Released project override; Boot-managed version used. |
| `commons-pool2.version` | `2.12.0` | `2.12.1` | Redis/client pool hygiene | Released project override. |
| `maven.compiler.plugin.version` | `3.11.0` | Boot plugin management `3.14.1` | Build plugin, not runtime | Removed explicit plugin version; configuration kept. |

### Effective Dependency Baseline

Focused dependency tree and effective-POM evidence were regenerated under:

```text
C:/Users/wangbin/AppData/Local/Temp/collector-dep-remediation-072-r0/
```

| Family | Before | After | Evidence |
| --- | ---: | ---: | --- |
| Spring Boot | `3.2.0` | `3.5.16` | dependency tree + BOOT-INF/lib |
| Spring Framework | `6.1.1` | `6.2.19` | dependency tree + BOOT-INF/lib |
| Tomcat | `10.1.16` | `10.1.55` | dependency tree + BOOT-INF/lib |
| Jackson Core/Databind | `2.15.3` | `2.21.4` | dependency tree + BOOT-INF/lib |
| Logback | `1.4.11` | `1.5.34` | dependency tree + BOOT-INF/lib |
| Netty | `4.1.100.Final` | `4.1.135.Final` | `mvn dependency:tree -Dincludes=io.netty` + BOOT-INF/lib |
| Spring Data | `3.2.0` | `3.5.13` | dependency tree + BOOT-INF/lib |
| Micrometer | `1.12.0` | `1.15.12` | dependency tree + BOOT-INF/lib |
| Springdoc | `2.3.0` | `2.8.17` | dependency tree + runtime OpenAPI smoke |
| MyBatis starter | `3.0.3` | `3.0.3` | unchanged by design |

### Netty Convergence

`mvn -pl collector-boot -am dependency:tree -Dincludes=io.netty` shows the main Netty family converged to:

```text
4.1.135.Final
```

JAR inspection found Netty runtime modules such as:

```text
netty-all-4.1.135.Final.jar
netty-buffer-4.1.135.Final.jar
netty-codec-4.1.135.Final.jar
netty-codec-http-4.1.135.Final.jar
netty-codec-http2-4.1.135.Final.jar
netty-codec-mqtt-4.1.135.Final.jar
netty-codec-redis-4.1.135.Final.jar
netty-common-4.1.135.Final.jar
netty-handler-4.1.135.Final.jar
netty-transport-4.1.135.Final.jar
```

No `io.netty` `4.1.100.Final` module remains in the packaged runtime. `netty-channel-fsm-1.0.2.jar` remains as a DigitalPetri library and is not an `io.netty` module version conflict.

### BOOT-INF/lib Verification

Executable JAR inspected:

```text
collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar
```

Confirmed packaged runtime JARs include:

```text
spring-boot-3.5.16.jar
spring-web-6.2.19.jar
spring-webmvc-6.2.19.jar
tomcat-embed-core-10.1.55.jar
jackson-core-2.21.4.jar
jackson-databind-2.21.4.jar
logback-core-1.5.34.jar
logback-classic-1.5.34.jar
spring-data-redis-3.5.13.jar
micrometer-core-1.15.12.jar
springdoc-openapi-starter-webmvc-ui-2.8.17.jar
mybatis-spring-boot-starter-3.0.3.jar
```

Note: `jackson-annotations-2.21.jar` is present together with Jackson core/databind `2.21.4`; this is the Jackson BOM's actual artifact versioning and not a stale `2.15.x` mix.

### Mixed Version Check

No old/new mixed packaged runtime stack was found for:

```text
Spring 6.1.x + 6.2.x
Tomcat 10.1.16 + 10.1.55
Jackson 2.15.x + 2.21.x
Logback 1.4.x + 1.5.x
Netty 4.1.100.Final + 4.1.135.Final
```

### Compatibility Adjustment

During full `mvn test`, one scheduler test exposed an existing observable-state ordering race in `ReconnectCoordinator`: success count was published before `nextRetryAt` was reset. R0 made the smallest production-code compatibility/correctness adjustment:

```text
collector-runtime/src/main/java/com/wangbin/collector/core/collector/scheduler/ReconnectCoordinator.java
```

The change resets reconnect backoff state before publishing `reconnectSuccessCount`. It does not change scheduling architecture or API contracts.

### Maven Build and Tests

Executed:

```text
mvn -DskipTests clean package
=> exit 0
```

Executed after the minimal scheduler ordering fix:

```text
mvn test
=> exit 0 / BUILD SUCCESS
```

The full Maven reactor completed successfully across all modules, including protocol modules, monitor, web, application, and boot.

Final executable JAR was regenerated after the production-code fix:

```text
mvn -DskipTests clean package
=> exit 0
```

### Application Startup

Started the packaged executable JAR on a smoke port:

```text
java -jar collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar --server.port=19090
```

Startup evidence:

```text
Tomcat started on port 19090 (http) with context path '/collector'
```

No `APPLICATION FAILED TO START`, `BeanDefinition` error, `ClassNotFound`, `NoSuchMethodError`, or `LinkageError` was observed. Redis was not available locally, so health reported `DOWN` and scheduled Redis paths logged connection-refused warnings; this is an external dependency availability condition, not a Boot runtime startup failure.

### Backend HTTP / OpenAPI / Observability Smoke

Runtime smoke results:

| Endpoint | Result | Notes |
| --- | --- | --- |
| `GET /collector/actuator/health` | HTTP 200 | Body status `DOWN` due local Redis unavailable; app remains running. |
| `GET /collector/actuator/metrics` with ops token | HTTP 200 | Metrics registry available; existing collector pipeline meters listed. |
| `GET /collector/actuator/prometheus` with ops token | HTTP 200 | Prometheus export generated. |
| `GET /collector/v3/api-docs` | HTTP 200 | JSON parseable; paths non-empty. |
| `GET /collector/swagger-ui/index.html` | HTTP 200 | Swagger UI static resource served. |
| `GET /collector/api/protocols` with ops token | HTTP 200 | Representative API JSON returned. |
| `GET /collector/api/config/summary` with ops token | HTTP 200 | Representative config JSON returned. |
| `GET /collector/desktop/index.html` | HTTP 200 | Static desktop resource served. |

Request IDs were present in HTTP responses, and Prometheus output included `collector_auth_requests`, pipeline gauges, JVM/process, Tomcat, and executor meters.

### Spring Boot 3.2 -> 3.5 Migration Scan

Source/config scan covered Spring MVC/resource config, filters/interceptors, CORS/auth rules, Jackson customization, Redis properties, actuator/prometheus, logging/MDC/requestId, `@ConfigurationProperties`, and `@Value` usage. No code migration was needed beyond the scheduler ordering fix exposed by the upgraded test/runtime stack.

Preserved invariants:

```text
Java remains 17
context-path remains /collector
server port config remains unchanged
MyBatis remains 3.0.3
protocol library versions remain unchanged
Task 01/02/03 frontend/realtime/API invariants not modified
```

### Focused OSV/GHSA Rescan Delta

Focused OSV queries were rerun for the web-runtime families using before/after resolved versions. Counts are package-advisory rows for the queried representative family artifacts.

| Family | Before | After | Critical/High Before | Critical/High After | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Spring Boot | `3.2.0` | `3.5.16` | 1 | 0 | Closed in focused scan. |
| Spring Framework | `6.1.1` | `6.2.19` | 9 | 0 | Closed in focused scan. |
| Tomcat | `10.1.16` | `10.1.55` | 23 | 3 | Significantly reduced; new/future Tomcat Critical advisories still remain in OSV data. |
| Jackson | `2.15.3` | `2.21.4` | 3 | 0 | Critical/High closed; after scan retains Moderate Jackson findings only. |
| Logback | `1.4.11` | `1.5.34` | 2 | 0 | Closed in focused scan. |
| Netty | `4.1.100.Final` | `4.1.135.Final` | 23 | 6 | Significantly reduced; newer Netty Critical/High advisories still remain in OSV data. |
| Spring Data | `3.2.0` | `3.5.13` | 0 | 0 | No Critical/High in focused scan. |
| Micrometer | `1.12.0` | `1.15.12` | 1 | 0 | Closed in focused scan. |

R0 does not require Critical/High = 0 because protocol stack, Bouncy Castle, Electron, and future advisories remain outside this scope. Java Web Runtime P1/P2 risk was materially reduced.

### Remaining Risks

Remaining R0-relevant focused OSV Critical/High rows after the uplift are currently concentrated in:

```text
Tomcat 10.1.55: 3 Critical rows in OSV future/current data
Netty 4.1.135.Final: 1 Critical + 5 High rows in OSV future/current data
```

These should be evaluated in subsequent targeted remediation rather than by ad-hoc Spring Framework/Tomcat/Jackson/Netty BOM mixing inside R0.

Other 07.2 scopes remain:

```text
Electron runtime upgrade
Electron-builder / packaging toolchain upgrade
Protocol stack patch review
Boot 4 strategic migration decision
```

### R0 Final Status

```text
Task 07.2-R0: PASS / COMPLETE

Java Web Runtime Tactical Security Uplift:
COMPLETE

Spring Boot 3.5.16:
TRANSITIONAL
NOT LONG-TERM OSS SUPPORT ENDPOINT

Next:
Task 07.2-R1 — Electron Runtime Upgrade
```

## Task 07.2-R0-R1 — Residual Java Runtime Security Patch

### Scope

R0-R1 only patches residual Java runtime security fixes that appeared after Spring Boot `3.5.16`'s managed baseline:

```text
Tomcat 10.1.55 -> 10.1.59
Netty  4.1.135.Final -> 4.1.138.Final
```

It does not start Electron remediation, Boot 4 migration, frontend/npm work, or protocol-library major upgrades.

### Baseline

```text
current remote baseline = 6fcf98274c2f7fa0e93afba13587821577513011
git log -1 --oneline = 6fcf982 Task 07.2
branch = feature_2.0...github/feature_2.0
```

R0 starting point:

```text
Spring Boot = 3.5.16
Spring Framework = 6.2.19
Tomcat = 10.1.55
Netty = 4.1.135.Final
Jackson = 2.21.4
Logback = 1.5.34
Java = 17
```

### Deliberate Security Patch Override

R0 intentionally released historical `netty.version` to Boot management. R0-R1 deliberately reintroduces Boot property overrides only for current security patch levels:

```xml
<!-- Boot 3.5.16 manages Tomcat 10.1.55; override to newer compatible 10.1.x security patch. -->
<tomcat.version>10.1.59</tomcat.version>
<!-- Boot 3.5.16 manages Netty 4.1.135.Final; override to newer compatible 4.1.x security patch. -->
<netty.version>4.1.138.Final</netty.version>
```

No second BOM was introduced. Boot parent remains the primary BOM.

### Effective POM / Version Check

Effective property verification:

```text
spring-boot.version = 3.5.16
spring-framework.version = 6.2.19
tomcat.version = 10.1.59
netty.version = 4.1.138.Final
jackson-bom.version = 2.21.4
logback.version = 1.5.34
java.version = 17
```

Evidence path:

```text
C:/Users/wangbin/AppData/Local/Temp/collector-dep-remediation-072-r0-r1/effective-pom-r0-r1.xml
```

### Dependency Tree Verification

Commands executed:

```text
mvn -pl collector-boot -am dependency:tree -Dincludes=org.apache.tomcat.embed
mvn -pl collector-boot -am dependency:tree -Dincludes=io.netty
```

Results:

```text
Tomcat tree: 10.1.59 present; 10.1.55 absent
Netty tree: 4.1.138.Final present; 4.1.135.Final absent; 4.1.100.Final absent
```

### BOOT-INF/lib Verification

Executable JAR inspected:

```text
collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar
```

Tomcat runtime JARs:

```text
tomcat-embed-core-10.1.59.jar
tomcat-embed-el-10.1.59.jar
tomcat-embed-websocket-10.1.59.jar
```

Main Netty runtime JARs converge to `4.1.138.Final`, including:

```text
netty-all-4.1.138.Final.jar
netty-buffer-4.1.138.Final.jar
netty-codec-4.1.138.Final.jar
netty-codec-http-4.1.138.Final.jar
netty-codec-http2-4.1.138.Final.jar
netty-codec-mqtt-4.1.138.Final.jar
netty-codec-redis-4.1.138.Final.jar
netty-codec-stomp-4.1.138.Final.jar
netty-handler-4.1.138.Final.jar
netty-handler-ssl-ocsp-4.1.138.Final.jar
netty-resolver-dns-4.1.138.Final.jar
netty-transport-4.1.138.Final.jar
```

`netty-channel-fsm-1.0.2.jar` remains a DigitalPetri library and is not an `io.netty` family version conflict.

### Mixed Version Check

No mixed old/new family was found:

```text
Tomcat 10.1.55 + 10.1.59: NO
Netty 4.1.135 + 4.1.138: NO
Netty 4.1.100: NO
```

### Tomcat Advisory Matrix

Focused verification covered the user-named Tomcat check list (`CVE-2026-55956`, `CVE-2026-68763`, `CVE-2026-68569`, `CVE-2026-65927`, `CVE-2026-65182`) plus the Critical/High OSV/GHSA rows returned for the packaged Tomcat 10.1.x coordinate. None of the named items remain Critical/High against `tomcat-embed-core:10.1.59` in the focused after scan; the actionable pre-patch Critical rows are listed below.

Focused OSV/GHSA scan before R0-R1 (`tomcat-embed-core 10.1.55`) showed 3 Critical rows. After `10.1.59`, focused scan shows 0 Critical/High.

| Advisory / CVE | Artifact | Before | Fixed / After | Packaged? | Current usage / prerequisite | Reachability before patch | Action |
| --- | --- | ---: | ---: | --- | --- | --- | --- |
| `GHSA-9xv2-5v5q-p794` / `CVE-2026-65905` | `tomcat-embed-core` | `10.1.55` | `10.1.59` | Yes | DIGEST authenticator replay class; embedded Tomcat HTTP runtime is active, DIGEST auth is not configured in project auth. | `POSSIBLY_REACHABLE` as Tomcat HTTP runtime class, not confirmed by current auth config | Patched by 10.1.59 |
| `GHSA-gcx9-497g-6cp6` / `CVE-2026-65182` | `tomcat-embed-core` | `10.1.55` | `10.1.59` | Yes | Tomcat authorization/security constraint class; app uses embedded Tomcat and HTTP routes, auth is application filter/token based. | `POSSIBLY_REACHABLE` for Tomcat HTTP/security runtime | Patched by 10.1.59 |
| `GHSA-h3x4-894j-xpx5` / `CVE-2026-68525` | `tomcat-embed-core` | `10.1.55` | `10.1.59` | Yes | FORM authentication process; project does not use container FORM auth, but Tomcat runtime is packaged. | `NOT_REACHABLE_BY_CURRENT_USAGE` for FORM auth path | Patched by 10.1.59 |

Tomcat final classification after patch:

```text
Critical/High total = 0
CONFIRMED_REACHABLE = 0
LIKELY_REACHABLE = 0
POSSIBLY_REACHABLE = 0
NOT_REACHABLE_BY_CURRENT_USAGE = 0
UNKNOWN = 0
```

### Netty Advisory Matrix

Focused verification covered the user-named Netty patch window (`4.1.136.Final`, `4.1.137.Final`, `4.1.138.Final`) and the target modules `netty-handler`, `netty-handler-ssl-ocsp`, `netty-codec-http`, `netty-codec-http2`, `netty-codec-stomp`, `netty-codec-redis`, `netty-resolver-dns`, and `netty-codec-mqtt`. The focused after scan for `4.1.138.Final` returned no Critical/High rows for those target modules.

Focused OSV/GHSA scan before R0-R1 (`4.1.135.Final`) showed 10 Critical/High rows across the target module set. After `4.1.138.Final`, focused scan shows 0 Critical/High.

| Advisory / CVE | Module | Before | Fixed / After | Packaged? | Current usage / prerequisite | Reachability before patch | Action |
| --- | --- | ---: | ---: | --- | --- | --- | --- |
| `GHSA-558v-64gr-wgg4` / `CVE-2026-59901` | `netty-codec` | `4.1.135.Final` | `4.1.138.Final` | Yes | Bzip2Decoder event-loop hang; no direct source usage found. | `NOT_REACHABLE_BY_CURRENT_USAGE` | Patched |
| `GHSA-6jqx-86gh-f27w` / `CVE-2026-55831` | `netty-codec-http` | `4.1.135.Final` | `4.1.138.Final` | Yes | SPDY SETTINGS map; backend HTTP server is Tomcat, no direct Netty/SPDY source use found. | `NOT_REACHABLE_BY_CURRENT_USAGE` | Patched |
| `GHSA-jppx-w49h-x2qq` / `CVE-2026-56745` | `netty-codec-http` | `4.1.135.Final` | `4.1.138.Final` | Yes | SpdyHttpDecoder ByteBuf leak; backend HTTP server is Tomcat, no direct SPDY use found. | `NOT_REACHABLE_BY_CURRENT_USAGE` | Patched |
| `GHSA-mvh2-crg5-v77c` / `CVE-2026-55833` | `netty-codec-http` | `4.1.135.Final` | `4.1.138.Final` | Yes | SPDY zlib/header expansion; no current SPDY pipeline found. | `NOT_REACHABLE_BY_CURRENT_USAGE` | Patched |
| `GHSA-93wv-jw9v-4972` / `CVE-2026-56819` | `netty-codec-http2` | `4.1.135.Final` | `4.1.138.Final` | Yes | HTTP/2 decompressor ByteBuf leak; backend HTTP server is Tomcat, Netty HTTP/2 server not used. | `NOT_REACHABLE_BY_CURRENT_USAGE` | Patched |
| `GHSA-c4c3-7fpv-j4q5` / `CVE-2026-75595` | `netty-handler` | `4.1.135.Final` | `4.1.138.Final` | Yes | SNI routing bypass via fragmented TLS ClientHello. Protocol clients may use Netty TLS through Milo/PLC stacks, but no direct SNI server routing code found. | `POSSIBLY_REACHABLE` for protocol TLS clients/stacks | Patched |
| `GHSA-272m-gcwp-mpwg` / `CVE-2026-56820` | `netty-handler-ssl-ocsp` | `4.1.135.Final` | `4.1.138.Final` | Yes | OCSP CertificateID validation; no direct `io.netty.handler.ssl.ocsp` / OCSP source usage found. | `NOT_REACHABLE_BY_CURRENT_USAGE` | Patched |
| `GHSA-g7hg-vrcf-mvmr` / `CVE-2026-56821` | `netty-handler-ssl-ocsp` | `4.1.135.Final` | `4.1.138.Final` | Yes | OCSP freshness validation; no direct OCSP source usage found. | `NOT_REACHABLE_BY_CURRENT_USAGE` | Patched |
| `GHSA-wc96-39fc-566f` / `CVE-2026-56822` | `netty-handler-ssl-ocsp` | `4.1.135.Final` | `4.1.138.Final` | Yes | OCSP TOCTOU; no direct OCSP source usage found. | `NOT_REACHABLE_BY_CURRENT_USAGE` | Patched |
| `GHSA-vhch-2wf3-m8rp` / `CVE-2026-44891` | `netty-codec-stomp` | `4.1.135.Final` | `4.1.138.Final` | Yes | STOMP decoder unbounded headers; no STOMP source usage found. | `NOT_REACHABLE_BY_CURRENT_USAGE` | Patched |

Netty final classification after patch:

```text
Critical/High total = 0
CONFIRMED_REACHABLE = 0
LIKELY_REACHABLE = 0
POSSIBLY_REACHABLE = 0
NOT_REACHABLE_BY_CURRENT_USAGE = 0
UNKNOWN = 0
```

### Usage / Reachability Notes

- Backend HTTP server remains embedded Tomcat, not Netty HTTP.
- Netty modules are packaged through protocol/client stacks including Redis/Lettuce, DigitalPetri Modbus, Milo/OPC UA, PLC4X, and related transitive graph.
- Source search found no direct STOMP decoder usage, no direct Netty OCSP API usage, no direct Netty DNS resolver API usage, and no direct Netty HTTP/SPDY server pipeline usage.
- Protocol regression is covered by existing Maven tests and runtime protocol endpoint smoke rather than field-device soak.

### Maven Package / Tests

Executed:

```text
mvn -DskipTests clean package
=> exit 0
```

Executed:

```text
mvn test
=> exit 0 / BUILD SUCCESS
```

The full reactor succeeded, including:

```text
collector-runtime
collector-protocol-modbus
collector-protocol-opc
collector-protocol-plc
collector-protocol-iot
collector-cloud
collector-web
collector-boot
```

Final executable JAR was regenerated after tests:

```text
mvn -DskipTests clean package
=> exit 0
```

### Application Startup

Started the regenerated JAR:

```text
java -jar collector-boot/target/data-collection-service-0.0.1-SNAPSHOT.jar --server.port=19090
```

Startup evidence:

```text
Tomcat started on port 19090 (http) with context path '/collector'
```

No `APPLICATION FAILED TO START`, `ClassNotFound`, `NoSuchMethodError`, or `LinkageError` was observed. Local Redis is unavailable, so runtime logs contain connection-refused warnings and health body status is `DOWN`; this is external dependency availability, not patch incompatibility.

### HTTP / OpenAPI / Observability Smoke

Smoke endpoints:

| Endpoint | Result |
| --- | --- |
| `GET /collector/actuator/health` | HTTP 200, body `{"status":"DOWN"...}` |
| `GET /collector/api/protocols` with ops token | HTTP 200 |
| `GET /collector/api/config/summary` with ops token | HTTP 200 |
| `GET /collector/v3/api-docs` | HTTP 200, JSON parseable, paths non-empty |
| `GET /collector/swagger-ui/index.html` | HTTP 200 |
| `GET /collector/actuator/metrics` with ops token | HTTP 200 |
| `GET /collector/actuator/prometheus` with ops token | HTTP 200 |

### Health HTTP Status

Observed health behavior:

```text
HTTP status = 200
body status = DOWN
```

This is intentional project configuration in `collector-boot/src/main/resources/application.yml`:

```yaml
management:
  endpoint:
    health:
      status:
        http-mapping:
          down: 200
          out-of-service: 200
```

No production health mapping was changed in R0-R1.

### OSV/GHSA After Scan

Focused after-scan:

| Family | Version | Critical | High | Critical/High total |
| --- | ---: | ---: | ---: | ---: |
| Tomcat | `10.1.59` | 0 | 0 | 0 |
| Netty | `4.1.138.Final` | 0 | 0 | 0 |

### Closed Risks

Closed residual current Critical/High rows from R0:

```text
Tomcat 10.1.55 residual Critical rows: 3 -> 0
Netty 4.1.135.Final residual Critical/High rows: 10 -> 0 in focused target module scan
```

### Remaining Risks

R0-R1 does not address non-target families:

```text
Electron runtime
Electron-builder packaging toolchain
PLC4X / Milo / Bouncy Castle / other protocol stack advisories
Spring Boot 4 strategic migration
```

### R0-R1 Final Status

```text
Task 07.2-R0: PASS / COMPLETE
Task 07.2-R0-R1: PASS / COMPLETE

Java Web Runtime Tactical Remediation:
COMPLETE

Spring Boot:
3.5.16 TRANSITIONAL

Tomcat:
10.1.59

Netty:
4.1.138.Final

Next:
Task 07.2-R1 — Electron Runtime Upgrade
```
