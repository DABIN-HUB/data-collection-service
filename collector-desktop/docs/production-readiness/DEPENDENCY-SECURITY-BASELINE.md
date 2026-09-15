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
| Electron | `33.4.11` | Electron schedule lists Electron 33 EOL `2025-04-28`; latest three stable majors are supported | Yes, packaged runtime | **P1 / UNSUPPORTED + known advisories** | Separate Electron major upgrade task; current supported majors at audit date are 42/43/44 |
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
| `UNKNOWN` | Individual OSV Java advisory method-level reachability until code-path review in 07.2; OWASP Dependency-Check incomplete |

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
| Electron | `33.4.11` | EOL since `2025-04-28` per Electron release schedule; latest three stable majors are supported | Electron official schedule/timeline | **P1 unsupported packaged runtime** |
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
| Spring Boot 3.x supported line | `3.2.0` | supported 3.x minor line | resolves Spring/Tomcat/Jackson/Netty managed advisories without Boot major | medium/high regression; needs dedicated backend test/smoke task |
| Netty 4.1.x | `4.1.100.Final` | newer 4.1.x | many Netty advisories; may be managed by Boot or protocol libraries | medium; protocol regression risk |
| Tomcat 10.1.x | `10.1.16` | newer 10.1.x via Boot | HTTP server advisories | medium; prefer Boot-managed |
| Jackson 2.x | `2.15.3` | newer 2.x via Boot | parser advisories | medium; serialization compatibility |
| Logback 1.4/1.5 line via Boot | `1.4.11` | managed patch | logging advisories | low/medium |
| Bouncy Castle | `1.81` | newer compatible line | crypto advisory | medium; check Milo/OPC UA compatibility |
| electron-builder | `25.1.8` | `26.15.3` | npm audit High packaging findings | medium; packaging/signing regression |

### C. Major upgrade required

| Candidate | Current | Target Class | Reason | Risk |
| --- | ---: | --- | --- | --- |
| Electron | `33.4.11` | supported major (`42/43/44` at audit date) | EOL + multiple Electron advisories | high; needs separate Electron runtime verification |
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
| UNKNOWN | OWASP Dependency-Check not completed | N/A | N/A | Rerun with NVD API/cache before final release certification |

## 19. Task 07.2 Recommendation

Recommended 07.2 split:

1. **07.2-R0 / Java Web Runtime Security Remediation**
   - Upgrade Spring Boot within supported 3.x line, allowing managed Spring/Tomcat/Jackson/Logback/Netty updates.
   - Re-run Maven dependency tree, package, backend smoke, and OSV/Dependency-Check.
2. **07.2-R1 / Electron Runtime Upgrade Plan**
   - Upgrade Electron from 33 to supported major in a dedicated branch/task.
   - Verify preload, CSP, app.asar, pack/dist, Windows launch smoke, and existing Electron security architecture.
3. **07.2-R2 / npm Build Toolchain Remediation**
   - Upgrade electron-builder and Vitest/Vite toolchain intentionally.
   - Resolve npm `ELSPROBLEMS` / SBOM blockers.
4. **07.2-R3 / Protocol Stack Patch Review**
   - Netty, Bouncy Castle, PLC4X/Milo/Californium/Paho/SNMP4J compatibility verification by enabled protocol.

## 20. Final Status

```text
Task 07.1: PASS / COMPLETE

Dependency Security Baseline:
COMPLETE

Dependency Remediation:
NOT STARTED

Next:
Task 07.2 — Targeted Dependency Security Remediation
```

No blind upgrade was performed. No `npm audit fix --force`, no major Spring Boot/Electron upgrade, no production dependency remediation in this task.
