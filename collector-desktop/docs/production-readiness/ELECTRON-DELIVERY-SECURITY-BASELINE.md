# Electron Delivery & Security Baseline

Date: 2026-09-11
Project: data-collection-service
Module: collector-desktop
Branch: feature_2.0
Baseline revision before Task 06: 2aaeb2fdafd16542a7663a7c2e868b71018ff5b7
Task: 06.1 — Electron Delivery & Security Baseline Audit

## 1. Scope

Task 06.1 is an audit, inventory, threat-model, delivery-baseline, and prioritization task. It intentionally does not implement Electron hardening.

Diff policy for this task:

| Area | Result |
| --- | --- |
| Production Java diff | 0 |
| Production Vue/TS diff | 0 |
| Production Electron diff | 0 |
| Dependency diff | 0 |
| Documentation diff | Created this baseline document |

Task 06.1 did not start Task 06.2.

## 2. Electron Architecture

Current architecture from `electron/main/main.ts`, `electron/preload/index.cts`, `electron/main/http-proxy-utils.ts`, `electron/main/main-utils.ts`, `src/stores/app.store.ts`, and `package.json`:

```text
Remote / Local Backend
        ↑
        │ HTTP
Electron Main
        ↑
        │ IPC
Preload / contextBridge
        ↑
        │ exposed API
Renderer / Vue
        ↑
        │ DOM / user input / downloaded content
User
```

Current delivery model:

| Component | Current behavior | Evidence |
| --- | --- | --- |
| Renderer source, development | `BrowserWindow.loadURL(process.env.VITE_DEV_SERVER_URL)` | `electron/main/main.ts` |
| Renderer source, production | `BrowserWindow.loadFile(.../renderer/index.html)` | `electron/main/main.ts` |
| Backend role | Independent Spring Boot collector service | `buildAboutInfo()` and login page copy |
| Electron backend management | `backendManaged=false`; no JAR/JRE launch | `collector:get-app-info` handler |
| HTTP boundary | Renderer calls Main IPC proxy; Main calls backend with `fetch()` | `collector:http-request` |
| Config persistence | Electron `userData/collector-desktop-config.json` + renderer `localStorage` server URL | `main.ts`, `app.store.ts` |
| Token persistence | Renderer memory + optional renderer `localStorage` | `app.store.ts` |
| Packaging | electron-builder, ASAR, Windows NSIS target | `package.json` |

Current model is **Model A**:

```text
Electron = operator console / UI only
Backend = independent long-running collector service / gateway
```

This is appropriate for a collection gateway because long-running protocol collection should not stop merely because an operator closes Electron.

## 3. Trust Boundaries

| Boundary | Assets / capabilities | Current trust level |
| --- | --- | --- |
| Renderer / Vue | DOM, route state, Pinia stores, localStorage token/server URL, calls exposed bridge | Most exposed to XSS/user input; treat compromised renderer as attacker-controlled |
| Preload | `contextBridge` facade, `ipcRenderer.invoke/on`; Node-capable because sandbox is false | Privileged bridge; exposed API is small but powerful |
| Electron Main | BrowserWindow, menu, shell.openExternal, config file, HTTP proxy fetch | Trusted process; compromise or abuse escalates beyond renderer |
| Backend | Collector APIs, auth token validation, collection/control operations | Trusted service once endpoint/token are valid |
| Filesystem | `app.getPath("userData")`, config JSON, package resources, Java logs under backend working directory/config | Main can write config; renderer directly controls localStorage only |
| OS shell | `shell.openExternal()` for allowed protocols | Main capability exposed via `openExternal` and navigation/window-open paths |
| localStorage | `collector-desktop-token`, `collector-desktop-server-url` | Renderer-owned; not secure against XSS/devtools/profile theft |
| userData | `collector-desktop-config.json` | Main-owned config file; currently no token/secret |
| Logs | Java backend logs currently owned by backend deployment | Electron packaged writable log path remains deferred |
| Installer | NSIS unsigned Windows installer target | Delivery trust boundary; signing/update not implemented |

## 4. BrowserWindow Security Matrix

| Setting | Current value / effective state | Evidence | Severity note |
| --- | --- | --- | --- |
| `contextIsolation` | `true` | `main.ts` webPreferences | GOOD |
| `nodeIntegration` | `false` | `main.ts` webPreferences | GOOD |
| `sandbox` | `false` | `main.ts` webPreferences | P2 hardening candidate; not P0 by itself because renderer is local bundle and nodeIntegration is false |
| `webSecurity` | not explicit; Electron default applies | no repo match | Record as default-dependent |
| `allowRunningInsecureContent` | not explicit; Electron default applies | no repo match | Record as default-dependent |
| `webviewTag` | not explicit; no `<webview>` source matches | code/search | GOOD |
| `nodeIntegrationInWorker` | not explicit; default-dependent | no repo match | Record as default-dependent |
| `nodeIntegrationInSubFrames` | not explicit; default-dependent | no repo match | Record as default-dependent |
| `enableRemoteModule` | not present | no repo match | GOOD / not enabled by code |
| `preload` | `../preload/index.cjs` | `main.ts` | GOOD: packaged preload path is explicit CJS |
| `devTools` | not disabled; production menu exposes `toggleDevTools` | `main.ts` menu | P2 policy finding |
| `autoHideMenuBar` | true; menu hidden but available by Alt | `buildWindowChromeOptions()` | GOOD UX, but menu capabilities still exist |

## 5. Preload API Inventory

`contextBridge.exposeInMainWorld("collectorDesktop", ...)` exposes exactly the following APIs.

| API | Renderer input | Main capability | Side effect | Sensitive | Validation |
| --- | --- | --- | --- | --- | --- |
| `getAppInfo` | none | reads app version/platform/config path/backendManaged | none | exposes config path | no sender validation |
| `getServerConfig` | none | reads `userData/collector-desktop-config.json` | none | server location | normalizes only via `normalizeServerConfig()` |
| `setServerConfig` | `{ serverUrl }` | writes config JSON | writes local config | may persist credential-bearing URL | weak URL/protocol/credential validation |
| `request` | `{ serverUrl, url, method, params, data, headers, token, timeoutMs }` | Main-process HTTP client | network request to selected backend base/context | token and backend operations | URL/path/header/timeout controls exist, but renderer can override `serverUrl` |
| `openExternal` | `url` string | `shell.openExternal()` | opens OS external handler | can reach filesystem/browser | protocol filter allows `http`, `https`, `file` |
| `onNavigate` | handler callback | receives `collector:navigate` events from menu | renderer route changes | low | no payload validation beyond renderer routing |

No additional exposed preload API was found.

## 6. IPC Inventory

Code search covered:

```text
ipcMain.handle
ipcMain.on
webContents.send
ipcRenderer.invoke
ipcRenderer.on
contextBridge.exposeInMainWorld
```

Inventory:

| Channel | Direction | Handler/source | Purpose | Sensitive capability |
| --- | --- | --- | --- | --- |
| `collector:get-app-info` | renderer -> main | `ipcMain.handle` | app metadata | low |
| `collector:get-server-config` | renderer -> main | `ipcMain.handle` | config read | medium |
| `collector:set-server-config` | renderer -> main | `ipcMain.handle` | config write | medium |
| `collector:open-external` | renderer -> main | `ipcMain.handle` | OS shell openExternal | high |
| `collector:http-request` | renderer -> main | `ipcMain.handle` | Main HTTP proxy | high |
| `collector:navigate` | main -> renderer | `webContents.send` + `ipcRenderer.on` | native menu route navigation | low |

Sender validation state:

| Check | Current state |
| --- | --- |
| `event.sender` / `webContents.id` | not checked |
| `event.senderFrame` | not checked |
| frame URL / origin | not checked |
| top frame only | not checked |

This is not classified as P0 by itself because production loads a local bundle and new windows are denied. It becomes material if navigation/file/CSP/XSS weaknesses allow untrusted renderer code or frames to invoke the bridge.

## 7. HTTP Proxy Threat Model

Call chain:

```text
Renderer
→ window.collectorDesktop.request()
→ ipcRenderer.invoke("collector:http-request")
→ ipcMain.handle("collector:http-request")
→ executeCollectorProxyRequest()
→ fetch()
```

Good current controls verified by code and local mock proxy probe:

| Control | Result |
| --- | --- |
| Absolute cross-origin target URL blocked against chosen base origin | PASS |
| Target path must stay under configured collector context path | PASS |
| Non-HTTP/HTTPS target protocol blocked by proxy URL builder | PASS |
| Header allowlist only forwards `Accept` and `Content-Type` from renderer | PASS |
| `Host`, `Authorization`, `Cookie`, `X-Forwarded-*`, `Proxy-Authorization` not forwarded | PASS |
| Token is injected only as `X-Collector-Token` by proxy header normalization | PASS |
| Method normalized to `GET/POST/PUT/DELETE/PATCH/HEAD`, unsupported method becomes `GET` | PASS |
| Timeout default 8000ms, max 30000ms | PASS |

Critical boundary issue:

`collector:http-request` currently uses:

```ts
serverUrl: request.serverUrl || readServerConfig().serverUrl
```

The renderer can therefore choose `request.serverUrl`; Main does not force the persisted Main config as the only source of truth. Because the origin/context guard is relative to the renderer-supplied base, compromised renderer code can choose a local or LAN HTTP service as its base and ask Main to fetch paths under that base. This makes Main a possible localhost/LAN HTTP client for a compromised renderer.

Threat levels:

| Server trust | Risk |
| --- | --- |
| trusted configured collector backend | intended behavior |
| misconfigured collector URL | operational failure / token disclosure to wrong collector-like endpoint |
| attacker-controlled HTTP backend | unbounded response/body memory risk and token exfiltration if token supplied |
| renderer-controlled `serverUrl` | localhost/LAN pivot risk through Main process HTTP client |

Bounds still missing:

| Bound | Current state |
| --- | --- |
| request body size | no explicit bound; arbitrary `data` JSON/string can be sent |
| response body size | no explicit bound; `response.text()` reads full body into memory |
| URL length / params size | no explicit bound |

## 8. Token / Credential Lifecycle

Current lifecycle from `app.store.ts` and `http.ts`:

```text
login(token, remember)
→ Pinia memory: appStore.token
→ configureHttp({ token })
→ if remember=true: localStorage["collector-desktop-token"] = token
→ requestThroughDesktopProxy(): sends token in IPC payload
→ Main normalizeProxyHeaders(): injects X-Collector-Token
→ logout(): setToken("", false), removes localStorage token
```

Answers:

| Question | Answer |
| --- | --- |
| Is token in renderer memory? | YES |
| Is token stored in localStorage when remember=true? | YES, plaintext localStorage |
| Does Main store the token? | NO |
| Does renderer pass token to Main per request? | YES |
| Is Main proxy currently a credential isolation boundary? | NO |

Threat model:

| Threat | Impact |
| --- | --- |
| Renderer XSS / malicious renderer code | can read localStorage token, call bridge, send authenticated requests |
| DevTools access | operator/local attacker can inspect or mutate renderer token state |
| Local profile theft | plaintext remembered token in Electron profile is recoverable by same OS user / malware |
| Main proxy misuse | token is renderer-provided, not Main-held; proxy does not hide token from compromised renderer |

## 9. Configuration Persistence

| Item | Current state | Evidence |
| --- | --- | --- |
| Electron config path | `app.getPath("userData")/collector-desktop-config.json` | `getConfigPath()` |
| Config content | `serverUrl`, optional `windowState` | `DesktopConfig` interface |
| Token in config file | No code path writes token to config JSON | code review |
| Server URL localStorage | `collector-desktop-server-url` | `app.store.ts` |
| Source-of-truth precedence | renderer `localStorage` server URL overrides Main config on initialize | `savedServerUrl || serverConfig.serverUrl` |
| Write method | `writeFileSync()` direct overwrite | `writeDesktopConfig()` |
| Corruption fallback | invalid JSON returns default config silently | `readDesktopConfig()` catch |
| Atomicity | no temp file + rename, no fsync | code review |
| Protocol validation | weak; `file:`, `ftp:`, and `javascript:` strings can be normalized/stored | runtime URL probe |
| Embedded credentials | `http://user:password@host/collector` can be normalized/stored | runtime URL probe |

GOOD: current Main config file stores no token/secret by design.

Risk: server URL validation is laxer than the HTTP proxy URL builder. Invalid protocols eventually fail in the proxy, but they can still persist in config/localStorage and create confusing startup/connectivity behavior. Credential-bearing URLs can be persisted and displayed.

## 10. Navigation / External URL

Current behavior:

| Surface | Behavior | Evidence |
| --- | --- | --- |
| `window.open` / `target=_blank` | `setWindowOpenHandler` denies new Electron window and calls `openExternalUrl(url)` | `main.ts` |
| External navigation | `will-navigate` prevents non-`file://` URLs and calls `openExternalUrl(url)` | `main.ts` |
| Development internal URL | dev server URL prefix treated as internal | `isExternalNavigation()` |
| Production internal URL | any `file://` URL is treated as internal | `isExternalNavigation()` |
| `shell.openExternal` protocols | `http`, `https`, `file` allowed | `isSafeExternalUrl()` |
| Unsafe protocols | `javascript:`, `data:`, `vbscript:`, `shell:`, `cmd:`, `powershell:` rejected by probe | runtime URL probe |

High-risk gaps:

1. `file:` is allowed in `isSafeExternalUrl()`, so renderer/preload can ask Main to open a local file URL via OS shell.
2. `isExternalNavigation()` treats **all** `file://` URLs as internal in production. It does not restrict navigation to the exact packaged renderer root.

A compromised renderer/XSS path could therefore attempt `file://` navigation or `openExternal("file://...")`. This was not exercised against dangerous files; the audit only verified the predicate behavior.

## 11. CSP / Renderer XSS Surface

| Check | Current result |
| --- | --- |
| Source `collector-desktop/index.html` CSP meta | Not present |
| Built `dist/renderer/index.html` CSP meta | Not present |
| HTTP response CSP in file mode | Not applicable; production uses `file://` loadFile |
| `v-html` in Vue source | no usage; ESLint has `vue/no-v-html=error` |
| `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `eval`, `new Function`, `document.write`, `iframe`, `webview` | no source matches in production source scan |
| Remote HTML / markdown-as-HTML | no production source sink found in quick scan |

CSP is still important even without obvious sinks because a future dependency/template bug or unsafe dynamic rendering would combine with:

```text
localStorage token
+ renderer-controlled HTTP proxy
+ openExternal capability
+ sandbox=false preload environment
```

## 12. Backend Lifecycle Model

Current model:

```text
Electron operator console
+ independent long-running Spring Boot collector backend
```

Evidence:

- `collector:get-app-info` returns `backendManaged: false`.
- `buildAboutInfo()` states the desktop does not auto-start the Spring Boot JAR and does not bundle a JRE.
- Login page states the backend is manually started.
- No code path starts `java`, a bundled JAR, or a JRE.

Recommendation for Task 06: keep this model unless product requirements explicitly change to “double click desktop starts backend”. For industrial collection gateways, independent backend service ownership is preferred because collection/reporting must continue when the operator console is closed.

Future managed-backend design, if ever required, must include:

```text
start
health wait
stdout/stderr capture
shutdown ownership
crash restart policy
PID ownership
port conflict handling
no kill-all java
```

## 13. Writable Paths / Logging

Electron:

| Item | Current state |
| --- | --- |
| userData | used for `collector-desktop-config.json` |
| window state | stored in same config JSON on close |
| token | renderer localStorage, not Main config |

Java backend logs:

| Deployment mode | Current ownership / risk |
| --- | --- |
| Standalone backend run from repo | `logs/collector.log` relative to backend working directory is usually writable |
| Windows service | service wrapper should explicitly configure log directory, ideally ProgramData/service log path |
| Electron child backend | not current model; would need `userData/logs` or service-managed path |
| Program Files install | relative `logs/collector.log` may be unwritable if backend runs from install directory |
| Portable install | writable only if install directory is writable |

Task 05 deferred owner accepted by Task 06:

| Deferred | Task 06 status |
| --- | --- |
| Electron packaged writable log path | belongs to 06.4 packaging/writable-path work |
| trusted proxy client IP | primarily backend deployment/security; document in 06.2/06.4 as deployment boundary, do not rewrite AuthFilter in 06.1 |
| generic async MDC | not directly Electron; remains deferred outside Task 06 unless a later backend observability phase scopes it |

## 14. Packaging / ASAR

Package configuration:

| Setting | Current value |
| --- | --- |
| electron-builder | 25.1.8 |
| Electron | 33.4.11 installed |
| ASAR | true |
| files | `dist/**/*`, `package.json` |
| Windows target | NSIS |
| artifactName | `collector-desktop-${version}-${arch}.${ext}` |

Package evidence:

| Check | Result |
| --- | --- |
| `npm run build` | PASS |
| default `npm run pack` | FAILED locally: existing `release/win-unpacked/d3dcompiler_47.dll` access denied |
| isolated temp output `electron-builder --dir` | PASS |
| packaged exe present | PASS |
| `app.asar` present | PASS |
| app.asar contains `dist/electron/main/main.js` | PASS |
| app.asar contains `dist/electron/preload/index.cjs` | PASS |
| app.asar contains `dist/renderer/index.html` | PASS |
| app.asar contains package.json | PASS |
| renderer source maps | 0 |
| suspicious packaged `.env`, `.git`, `target`, logs, local config | none found |
| node_modules source maps | present from packaged dependencies; packaging polish finding |

## 15. Installer / Signing

Installer configuration:

| Setting | Current value |
| --- | --- |
| target | `nsis` |
| `oneClick` | `false` |
| `allowToChangeInstallationDirectory` | `true` |
| `perMachine` | `false` |
| application icon | not set; electron-builder used default Electron icon |
| `signAndEditExecutable` | `false` |

`dist` was attempted using a temp output directory. Packaging reached NSIS installer build but failed with app-builder/electron-builder `exit status 2` after downloading the NSIS binary. This is classified as local packaging toolchain/environment failure until reproduced in clean CI.

Signing state: with `signAndEditExecutable=false`, the current Windows exe/installer path is unsigned. This is not P0 for internal field trial or enterprise-controlled deployment, but it affects SmartScreen, publisher identity, tamper assurance, and upgrade trust for broader field distribution.

## 16. Update Strategy

Search results:

| Search | Result |
| --- | --- |
| `electron-updater` | no source/package match |
| `autoUpdater` | no source/package match |
| update server config | not implemented |

Current state:

```text
Auto update: NOT IMPLEMENTED
```

Recommended field distribution model for industrial/offline gateway deployments:

1. manual signed installer for controlled releases;
2. enterprise software distribution where available;
3. controlled offline upgrade for isolated sites;
4. auto-update only if an authenticated/signed update channel is explicitly required.

## 17. Runtime Package Verification

Runtime checks performed on the isolated `win-unpacked` output:

| Check | Result |
| --- | --- |
| executable starts | PASS |
| process still alive after 8 seconds | PASS |
| userData directory exists | PASS |
| backend not running | app does not immediately crash; full UX remains a visual/manual audit item |
| task-started process cleanup | the exact started PID was stopped; no kill-all used |

The app did not write config during the forced process stop because the config write happens on normal close/window state persistence. This is expected for the audit smoke and does not prove close-path persistence.

## 18. Findings

### EDS-P0-01

Finding: No P0 Electron delivery/security blocker found in Task 06.1.
Severity: P0
Area: Overall
File: N/A
Current behavior: No straightforward renderer-to-OS command execution, arbitrary privileged filesystem write, or direct credential theft without prerequisites was found.
Attack/failure scenario: N/A
Prerequisites: N/A
Impact: N/A
Existing mitigation: `contextIsolation=true`, `nodeIntegration=false`, local production renderer bundle, restricted proxy headers/path/origin.
Evidence: main/preload/proxy/package audit and runtime package/proxy probes.
Recommended change: Continue with scoped P1/P2 hardening.
Recommended task: 06.2 / 06.3 / 06.4

### EDS-P1-01

Finding: Renderer-controlled `serverUrl` lets compromised renderer choose the Main HTTP proxy base.
Severity: P1
Area: HTTP Proxy / IPC
File: `collector-desktop/electron/main/main.ts`, `collector-desktop/electron/main/http-proxy-utils.ts`
Current behavior: `collector:http-request` uses `request.serverUrl || readServerConfig().serverUrl`.
Attack/failure scenario: untrusted renderer code / XSS → exposed bridge → IPC `collector:http-request` → `serverUrl=http://127.0.0.1:<port>/collector` or LAN host → Main performs HTTP request to that chosen base/context.
Prerequisites: renderer compromise or malicious renderer code execution.
Impact: Main can be abused as localhost/LAN HTTP client within the attacker-chosen base/context boundary; possible local network pivot and token exfiltration to attacker-selected collector-shaped service.
Existing mitigation: target must be HTTP/HTTPS, target origin must equal chosen base origin, target path must stay under chosen base context, unsafe request headers are stripped, timeout is bounded.
Evidence: code audit and local mock probe confirmed both good guards and renderer-selected base behavior.
Recommended change: Make Main-held/persisted server config authoritative for proxy requests; reject renderer-supplied `serverUrl` or require it to equal Main config after strict normalization.
Recommended task: 06.2 — Electron IPC & Navigation Security Hardening

### EDS-P1-02

Finding: Main proxy is not a credential isolation boundary; token is renderer-owned and renderer-supplied.
Severity: P1
Area: Credential / IPC / HTTP Proxy
File: `collector-desktop/src/stores/app.store.ts`, `collector-desktop/src/api/http.ts`, `collector-desktop/electron/main/http-proxy-utils.ts`
Current behavior: token lives in Pinia/current HTTP config, optional localStorage, and each proxied request sends `token` in the IPC payload; Main injects it as `X-Collector-Token`.
Attack/failure scenario: renderer XSS/malicious renderer code → read token from memory/localStorage → call backend APIs or ask Main proxy to inject the token.
Prerequisites: renderer compromise, DevTools/local profile access, or local malware under the same OS user.
Impact: privileged operations token can be stolen or used from renderer context.
Existing mitigation: logout removes localStorage token; unsafe request headers are stripped; token header name is centralized in Main proxy normalization.
Evidence: `app.store.ts` token lifecycle and `requestThroughDesktopProxy()` IPC payload.
Recommended change: Move persistent credential ownership to Main/OS-protected storage and expose only request capability; evaluate Electron `safeStorage`/Windows DPAPI/keytar/memory-only options without adding dependency in 06.1.
Recommended task: 06.3 — Credential / Config Storage Hardening

### EDS-P1-03

Finding: `file://` is allowed for external open and all production `file://` navigation is considered internal.
Severity: P1
Area: Navigation / External URL
File: `collector-desktop/electron/main/main.ts`, `collector-desktop/electron/main/main-utils.ts`
Current behavior: `isSafeExternalUrl()` allows `file:` and `isExternalNavigation()` returns false for any `file://` URL in production.
Attack/failure scenario: renderer compromise / XSS → `openExternal("file://...")` or `location.href=file://...` → Electron/OS may open or navigate to unintended local files.
Prerequisites: renderer compromise or future unsafe navigation input.
Impact: unnecessary local-file exposure/OS handler capability from renderer; packaged app should not need arbitrary file URL opening.
Existing mitigation: `javascript:`, `data:`, `vbscript:`, `shell:`, `cmd:`, `powershell:` are rejected; new windows are denied.
Evidence: runtime URL probe: `file:///...` returns allowed; unsafe protocols return false. Code audit: any `file://` is internal navigation.
Recommended change: Remove `file:` from external allowlist unless a specific safe local path use case exists; restrict production internal `file://` navigation to the exact packaged renderer root.
Recommended task: 06.2 — Electron IPC & Navigation Security Hardening

### EDS-P1-04

Finding: Production renderer has no CSP while holding token and bridge capabilities.
Severity: P1
Area: CSP / Renderer XSS
File: `collector-desktop/index.html`, built `dist/renderer/index.html`
Current behavior: no `Content-Security-Policy` meta was found in source or built renderer HTML.
Attack/failure scenario: future renderer XSS/dependency/template injection → localStorage token read → Main proxy request/openExternal abuse.
Prerequisites: renderer XSS or malicious rendered content path.
Impact: XSS blast radius includes remembered token and high-capability bridge APIs.
Existing mitigation: no obvious `v-html`/innerHTML/eval/iframe/webview sinks found; `vue/no-v-html` is enforced; production renderer is local bundle.
Evidence: source/build HTML read and dangerous DOM scan.
Recommended change: Add production-compatible CSP for file-loaded Vite bundle; keep `script-src`/`style-src` as tight as practical for current build.
Recommended task: 06.2 — Electron IPC & Navigation Security Hardening

### EDS-P2-01

Finding: `sandbox=false` keeps preload Node-capable.
Severity: P2
Area: BrowserWindow Security
File: `collector-desktop/electron/main/main.ts`
Current behavior: BrowserWindow explicitly sets `sandbox: false`.
Attack/failure scenario: if preload is compromised or an exposed bridge is expanded unsafely, the preload has broader Node/Electron access than a sandboxed preload.
Prerequisites: preload compromise, build/package compromise, or future unsafe bridge expansion.
Impact: defense-in-depth gap, not direct exploit by itself under current local renderer + contextIsolation + nodeIntegration=false.
Existing mitigation: renderer nodeIntegration disabled; exposed bridge is small.
Evidence: `main.ts` webPreferences.
Recommended change: Evaluate whether preload can run with `sandbox=true`; if not, document exact Node dependencies and keep bridge minimal.
Recommended task: 06.2

### EDS-P2-02

Finding: IPC handlers do not validate sender/frame origin.
Severity: P2
Area: IPC Boundary
File: `collector-desktop/electron/main/main.ts`
Current behavior: handlers ignore `event.sender`, `event.senderFrame`, frame URL, and webContents ID.
Attack/failure scenario: if navigation or an injected/child frame reaches the preload bridge, it can invoke Main capabilities without sender/frame checks.
Prerequisites: renderer compromise/navigation breakout/iframe-like future feature.
Impact: weakens defense in depth around powerful proxy/openExternal/config handlers.
Existing mitigation: current production loads local file bundle; no iframe/webview found; new windows denied.
Evidence: code search for IPC handlers and sender validation.
Recommended change: bind allowed webContents/frame URL to the single main window and reject unexpected frames.
Recommended task: 06.2

### EDS-P2-03

Finding: HTTP proxy request/response payload sizes are not bounded.
Severity: P2
Area: HTTP Proxy / Memory DoS
File: `collector-desktop/electron/main/http-proxy-utils.ts`
Current behavior: arbitrary `data` is stringified/sent; response is fully buffered via `response.text()`.
Attack/failure scenario: compromised renderer or attacker-controlled configured backend returns very large body → Main memory pressure.
Prerequisites: compromised renderer, malicious/misconfigured backend, or renderer-controlled server URL.
Impact: local process memory DoS.
Existing mitigation: timeout max 30000ms.
Evidence: `buildRequestBody()` and `response.text()` audit.
Recommended change: enforce max request body, max URL/params, and max response bytes.
Recommended task: 06.2

### EDS-P2-04

Finding: Server URL validation allows non-HTTP protocols and embedded credentials to persist.
Severity: P2
Area: Config Storage
File: `collector-desktop/electron/main/main-utils.ts`, `collector-desktop/src/api/http.ts`, `collector-desktop/src/stores/app.store.ts`
Current behavior: `file:`, `ftp:`, `javascript:` URL strings and `http://user:password@host/collector` can be normalized/stored.
Attack/failure scenario: operator typo or malicious renderer sets credential-bearing/invalid URL → config/localStorage persistence, confusing failures, accidental credential exposure in UI/config.
Prerequisites: user input or renderer control.
Impact: credential hygiene and reliability issue.
Existing mitigation: invalid unparsable URL falls back to default in Main; proxy later blocks non-HTTP/HTTPS target protocol.
Evidence: runtime URL normalization probe.
Recommended change: strict `http/https` only, reject username/password, normalize context path consistently in Main and renderer.
Recommended task: 06.3

### EDS-P2-05

Finding: Config file writes are direct overwrite and corruption fallback is silent.
Severity: P2
Area: Config Reliability
File: `collector-desktop/electron/main/main.ts`
Current behavior: `writeFileSync(configPath, JSON.stringify(...))`; invalid JSON read returns default without user-facing warning.
Attack/failure scenario: process crash/disk full during write → partial config; next launch silently resets service URL/window state.
Prerequisites: local IO failure/crash.
Impact: field reliability/diagnostic friction.
Existing mitigation: default config recovers startup.
Evidence: `readDesktopConfig()` and `writeDesktopConfig()` audit.
Recommended change: temp-file + fsync + rename, preserve corrupt file for diagnostics, surface recovery warning.
Recommended task: 06.3

### EDS-P2-06

Finding: Production menu exposes DevTools, reload, and forceReload.
Severity: P2
Area: Menu / Operator Actions
File: `collector-desktop/electron/main/main.ts`
Current behavior: menu contains `role=toggleDevTools`, `role=reload`, `role=forceReload`; menu bar is hidden but accessible.
Attack/failure scenario: field operator/local attacker opens DevTools and inspects/mutates token state; reload during pending control/write operation can lose UI response after backend side effect.
Prerequisites: local interactive access.
Impact: credential exposure risk and operational UX/safety risk.
Existing mitigation: hidden menu bar reduces accidental exposure.
Evidence: menu template audit.
Recommended change: production menu policy: dev-only DevTools, controlled reload behavior or confirmation around pending actions.
Recommended task: 06.2 or 06.4

### EDS-P2-07

Finding: No single-instance lock.
Severity: P2
Area: Runtime / Operator UX
File: `collector-desktop/electron/main/main.ts`
Current behavior: no `app.requestSingleInstanceLock()` found.
Attack/failure scenario: multiple windows/processes with different localStorage/config state can issue duplicate operator actions.
Prerequisites: user launches multiple instances.
Impact: operational confusion and duplicate control risk.
Existing mitigation: backend remains source of truth and auth boundary.
Evidence: code search.
Recommended change: add single-instance lock and focus existing window.
Recommended task: 06.4

### EDS-P2-08

Finding: Installer/signing/update maturity is not production-complete.
Severity: P2
Area: Delivery
File: `collector-desktop/package.json`
Current behavior: unsigned NSIS target, default Electron icon, no autoUpdater, local dist failed in current toolchain after NSIS download with app-builder exit status 2.
Attack/failure scenario: field distribution faces SmartScreen warnings, weaker tamper assurance, manual upgrade ambiguity, and unproven installer generation in this local environment.
Prerequisites: field distribution outside controlled internal trial.
Impact: delivery trust and supportability risk.
Existing mitigation: ASAR enabled; unpacked pack succeeds; per-user NSIS config is explicit.
Evidence: package config, pack/dist attempts.
Recommended change: clean CI installer verification, signing plan, icon metadata, documented upgrade process.
Recommended task: 06.4

### EDS-P2-09

Finding: Packaged app includes dependency source maps under `node_modules` inside ASAR.
Severity: P2
Area: Packaging / Information Exposure / Size
File: packaged `app.asar`
Current behavior: renderer source maps are absent, but dependency `.map` files are present in packaged `node_modules`.
Attack/failure scenario: package size/information exposure is larger than necessary; dependency source maps may expose library internals but no app secrets were found.
Prerequisites: package access.
Impact: packaging polish, not a credential finding based on current scan.
Existing mitigation: no renderer app source maps; no `.env`/local config/logs/target/Git metadata in package.
Evidence: ASAR inventory: 0 renderer maps, node_modules maps present.
Recommended change: evaluate electron-builder file filters / pruning after ensuring runtime is unaffected.
Recommended task: 06.4

### EDS-P2-10

Finding: Startup-critical Electron failures are swallowed.
Severity: P2
Area: Startup Reliability
File: `collector-desktop/electron/main/main.ts`
Current behavior: `loadURL(...).catch(() => undefined)`, `loadFile(...).catch(() => undefined)`, `app.whenReady().catch(() => undefined)`, dialog/openExternal best-effort catches.
Attack/failure scenario: packaged renderer/preload path issue or app ready failure can show a blank window or exit without actionable diagnostic.
Prerequisites: packaging/path/runtime error.
Impact: field supportability issue.
Existing mitigation: packaged smoke started successfully in 06.1.
Evidence: main lifecycle audit and packaged startup probe.
Recommended change: distinguish best-effort catches from startup-critical failures; show fatal error dialog/log for load/preload failures.
Recommended task: 06.4

## 19. Severity Summary

P0:

- open: none
- count: 0

P1:

- open: EDS-P1-01, EDS-P1-02, EDS-P1-03, EDS-P1-04
- count: 4

P2:

- open: EDS-P2-01, EDS-P2-02, EDS-P2-03, EDS-P2-04, EDS-P2-05, EDS-P2-06, EDS-P2-07, EDS-P2-08, EDS-P2-09, EDS-P2-10
- count: 10

Task 06.1 can pass because its purpose is to establish the baseline and prioritized follow-up sequence, not to close the findings.

## 20. Recommended Task 06 Sequence

1. `06.2 — Electron IPC & Navigation Security Hardening`
   - close EDS-P1-01, EDS-P1-03, EDS-P1-04;
   - reduce EDS-P2-01/02/03/06 where safe;
   - do not broaden backend scope.
2. `06.3 — Credential / Config Storage Hardening`
   - close EDS-P1-02;
   - address EDS-P2-04/05;
   - evaluate safeStorage / Windows DPAPI / keytar / memory-only without premature dependency changes.
3. `06.4 — Packaging, Writable Paths & Delivery Reliability`
   - address EDS-P2-07/08/09/10;
   - own Task 05 deferred Electron packaged writable log path;
   - verify clean CI NSIS dist/signing/update policy.
4. `06.5 — Electron Security & Delivery Regression / Final Audit`
   - rerun BrowserWindow/preload/IPC/proxy/navigation/CSP/token/package/startup regression and final Task 06 severity gate.

Do not add a “bundle Java backend” task unless product requirements change to explicitly require Electron-managed backend lifecycle.


---

# Task 06.2 — Electron IPC & Navigation Security Hardening Acceptance

Date: 2026-09-11
Branch: feature_2.0
Verified baseline before Task 06.2: 69f017c2c845c5d4c250f5341a34f5c5f91996f8
Scope: Electron IPC, Main HTTP Proxy, Navigation, External URL, Renderer CSP, BrowserWindow sandbox, and directly related boundary hardening.

Task 06.2 preserves the product lifecycle model:

```text
Electron = operator console / UI
Spring Boot = independent long-running collector backend
backendManaged = false
```

No Java backend lifecycle ownership, bundled JRE, backend auto-start, code signing, autoUpdater, credential vault, safeStorage, DPAPI, keytar, or installer overhaul is introduced in this task.

## 21. Task 06.2 Scope Result

| Area | Result |
| --- | --- |
| Electron IPC / Main process security | Updated |
| Electron preload contract | Updated to remove per-request `serverUrl` |
| Renderer HTTP desktop proxy payload | Updated to stop sending per-request `serverUrl` |
| Renderer CSP | Updated in source HTML and verified in built artifact |
| Main HTTP proxy destination boundary | Main persisted config is authoritative |
| Main HTTP proxy payload bounds | Added URL/request/response byte bounds |
| BrowserWindow sandbox | Enabled (`sandbox: true`) |
| Production DevTools / forceReload menu | Removed from production menu |
| Credential ownership / safeStorage | Deferred to Task 06.3 |
| Backend lifecycle / Java code | Not changed by design |

## 22. Task 06.2 Boundary Changes

### 22.1 Main HTTP proxy authoritative destination

`collector:http-request` no longer uses renderer-provided `serverUrl` as a fallback or override. The handler now derives the destination base from `readServerConfig().serverUrl` and applies it through `withAuthoritativeProxyServerUrl(...)` before calling `executeCollectorProxyRequest(...)`.

Final boundary:

```text
Renderer
→ IPC request: url, method, params, data, safe headers, token, timeout
Main
→ readServerConfig().serverUrl
→ withAuthoritativeProxyServerUrl(...)
→ executeCollectorProxyRequest(...)
```

The preload and renderer desktop proxy request type no longer expose `serverUrl` in the normal contract. A compatibility guard in Main ignores any malicious/legacy `serverUrl` field if present.

### 22.2 Trusted renderer navigation

External URL policy is reduced to `http:` and `https:` only. `file:`, `javascript:`, `data:`, `vbscript:`, `shell:`, `cmd:`, `powershell:`, and `ftp:` are not accepted by `isSafeExternalUrl(...)`.

Internal renderer URL policy is now structural rather than prefix-based:

- production allows only `file:` URLs resolving to the exact packaged `dist/renderer/index.html` path;
- production hash routes remain allowed because the file path is still the same `index.html`;
- arbitrary local files such as `file:///C:/Windows/...` are treated as external and then rejected by external URL policy;
- development allows only the configured `VITE_DEV_SERVER_URL` protocol/host/port boundary and rejects prefix spoofing such as `localhost:5173.evil.com`.

### 22.3 IPC sender/frame validation

Privileged IPC handlers now call `assertTrustedSender(event)` before serving requests:

- `collector:get-app-info`
- `collector:get-server-config`
- `collector:set-server-config`
- `collector:open-external`
- `collector:http-request`

The validation requires:

1. `event.sender.id` equals the current `mainWindow.webContents.id`;
2. `event.senderFrame` exists;
3. the request comes from the top/main frame;
4. `senderFrame.url` is a trusted renderer URL according to the same development/production renderer boundary.

Unexpected sender or unexpected frame is rejected with an explicit error. `collector:navigate` remains Main-to-Renderer and is not broken by the new inbound validation.

### 22.4 Renderer CSP

`collector-desktop/index.html` now includes a CSP meta tag. The built `dist/renderer/index.html` was checked after production build to confirm the policy is present in the real artifact.

Final CSP policy:

```text
default-src 'self';
script-src 'self';
style-src 'self' 'unsafe-inline';
img-src 'self' data: blob:;
font-src 'self' data:;
connect-src 'self' http: https: ws: wss:;
object-src 'none';
frame-src 'none';
base-uri 'none'
```

`unsafe-eval` is intentionally not used.

### 22.5 BrowserWindow sandbox

`BrowserWindow.webPreferences.sandbox` is now `true`. The existing preload only needs Electron preload-safe `contextBridge` and `ipcRenderer` and does not expose Node filesystem/process APIs to the renderer. Typecheck, tests, build, package smoke, and packaged bridge smoke are used as compatibility evidence.

### 22.6 Main proxy size bounds

Hard bounds added in `electron/main/http-proxy-utils.ts`:

| Bound | Value | Rationale |
| --- | ---: | --- |
| URL/query bytes | 64 KiB | Prevents abusive query growth while allowing normal filters |
| request body bytes | 4 MiB | Sufficient for desktop control/config requests; large realtime is read path, not request upload |
| response body bytes | 64 MiB | Above the previously verified ~23.66 MiB 100k realtime compact full snapshot |

Response enforcement is streaming-aware:

- `Content-Length` is checked before body read when present;
- `ReadableStream` is read chunk-by-chunk with cumulative byte count;
- oversized chunked/missing/incorrect length responses are rejected before full buffering;
- request string/JSON body byte size is checked before fetch.

## 23. Task 06.2 Finding Status

| Finding | Status | Evidence |
| --- | --- | --- |
| EDS-P1-01 | CLOSED | Main ignores renderer `serverUrl`; preload/renderer request contract no longer carries destination base; targeted test verifies authoritative server URL |
| EDS-P1-03 | CLOSED | external URL allowlist is only HTTP/HTTPS; trusted renderer navigation only accepts exact packaged `index.html` or exact dev origin; tests cover hash route, unrelated `file://`, unsafe protocols, prefix spoofing |
| EDS-P1-04 | CLOSED | CSP added to source HTML and verified in built `dist/renderer/index.html`; no `unsafe-eval` |
| EDS-P2-01 | CLOSED | `sandbox: true` enabled and verified by typecheck/build/package/preload bridge smoke |
| EDS-P2-02 | CLOSED | IPC handler sender, top-frame, and renderer URL validation added and tested |
| EDS-P2-03 | CLOSED | URL, request body, Content-Length, and streaming response byte bounds added and tested |
| EDS-P2-06 | PARTIAL | production DevTools and forceReload removed; normal reload remains; pending-write/reload operator-safety is deferred to 06.4 to avoid a broad write manager |
| EDS-P1-02 | DEFERRED | credential/token ownership remains renderer memory + optional localStorage; safeStorage/DPAPI/keytar belongs to Task 06.3 |

## 24. Task 06.2 Verification Evidence

Commands executed after code changes:

```text
npm --prefix collector-desktop run typecheck
npm --prefix collector-desktop test -- electron/main/main-utils electron/main/http-proxy-utils electron/main/ipc-security-utils src/api/http
npm --prefix collector-desktop test
npm --prefix collector-desktop run build
npm --prefix collector-desktop run build:web
npm --prefix collector-desktop run verify
```

Additional required package/runtime/security verification is recorded in the Task 06.2 final report.

## 25. Task 06.2 Remaining Scope

Remaining findings are outside this task or intentionally deferred:

| Finding | Status | Owner |
| --- | --- | --- |
| EDS-P1-02 | DEFERRED | Task 06.3 Credential / Config Storage Hardening |
| EDS-P2-04 | OPEN | Task 06.3 strict config URL / credential URL handling |
| EDS-P2-05 | OPEN | Task 06.3 config atomic write / corrupt file diagnostics |
| EDS-P2-06 reload pending-write part | PARTIAL / DEFERRED | Task 06.4 operator action safety |
| EDS-P2-07 | OPEN | Task 06.4 single-instance lock |
| EDS-P2-08 | OPEN | Task 06.4 signing/installer/update delivery maturity |
| EDS-P2-09 | OPEN | Task 06.4 dependency source-map packaging polish |
| EDS-P2-10 | OPEN | Task 06.4 startup-critical failure surfacing |

Task 06.2 does not start Task 06.3.


---

# Task 06.2-R1 — Close Main Proxy Destination Reconfiguration Bypass

Date: 2026-09-11
Branch: feature_2.0
Remote baseline before R1: fc38f10012dfdc158813eab7c4161089cf6f9449
Scope: focused repair for `EDS-P1-01`; no Task 06.3 credential/config-storage migration is started.

## 26. R1 Root Cause

Task 06.2 closed the direct per-request `serverUrl` override on `collector:http-request`, but `window.collectorDesktop.setServerConfig(...)` still allowed a trusted top-frame renderer to request a backend URL change and have Main persist it immediately.

The missing boundary was not IPC sender identity. The bypass attacker is already inside the trusted renderer URL/top frame through XSS or renderer compromise, so `assertTrustedSender(...)` correctly allows the sender but does not prove user intent for changing Main's authoritative network destination.

Original bypass chain:

```text
compromised trusted Renderer / XSS
→ collector:set-server-config({ serverUrl: attacker-controlled localhost/LAN URL })
→ Main writeServerConfig(...)
→ collector:http-request
→ Main readServerConfig()
→ Main fetch attacker-selected destination
```

## 27. R1 Boundary Change

A renderer may now request a backend address change, but Main owns final authorization.

Final behavior:

```text
Renderer
→ collector:set-server-config(candidate)
→ assertTrustedSender(event)
→ Main strict candidate URL validation
→ compare current Main config vs candidate
→ if unchanged: return current config, no dialog, no write
→ if changed: show Main-owned native Electron confirmation
→ cancel: return current config, no write
→ approve: persist candidate, return persisted config
```

The native confirmation is implemented in Main with `dialog.showMessageBox(...)`, not renderer UI. The dialog explicitly shows:

```text
当前采集服务：
<current URL>

准备切换到：
<candidate URL>

修改后，桌面端的后台请求将发送到新的采集服务地址。请确认该地址是可信的采集服务。
```

Buttons:

```text
取消
确认切换
```

Safe default/cancel behavior:

```text
defaultId = 0
cancelId = 0
```

## 28. R1 Server URL Minimal Validation

For candidate backend URL changes, Main now enforces the minimal destination boundary required to close `EDS-P1-01`:

| Check | Result |
| --- | --- |
| protocol | must be `http:` or `https:` |
| username/password | rejected |
| fragment | rejected |
| query | rejected because backend base URL has no current business need for query parameters |
| `127.0.0.1:9090` root | still normalized to `/collector` |
| LAN collector URL | allowed after native approval |
| remote HTTPS collector URL | allowed after native approval |

This R1 intentionally does not implement config atomic write, corrupt config diagnostics, token migration, `safeStorage`, DPAPI, or keytar. Those remain Task 06.3 scope.

## 29. R1 Renderer/Main Source-of-Truth Alignment

Electron initialization now treats Main config as authoritative:

```text
window.collectorDesktop.getServerConfig()
→ renderer display state
→ configureHttp(...)
```

Stale renderer `localStorage["collector-desktop-server-url"]` no longer overrides Main config in Electron mode.

Browser/Web mode still keeps the existing browser/localStorage behavior.

`updateServerUrl(...)` is now Main-first in Electron mode:

```text
candidate
→ await window.collectorDesktop.setServerConfig(candidate)
→ Main native confirmation / validation
→ Main returns actual final config
→ renderer commits serverUrl/localStorage/configureHttp only after Main success
```

If Main rejects, validation fails, or the user cancels in the native confirmation, renderer state, localStorage, and HTTP config remain at the previous value.

## 30. R1 Finding Status

| Finding | Status | Evidence |
| --- | --- | --- |
| EDS-P1-01 | CLOSED | direct per-request override remains closed; indirect `setServerConfig` reconfiguration now requires Main native approval; cancel keeps Main and renderer config unchanged |
| EDS-P1-03 | CLOSED / REGRESSION PASS | URL/navigation boundary from 06.2 unchanged |
| EDS-P1-04 | CLOSED / REGRESSION PASS | CSP remains in source/built renderer, no `unsafe-eval` |
| EDS-P2-01 | CLOSED / REGRESSION PASS | `sandbox: true` retained |
| EDS-P2-02 | CLOSED / REGRESSION PASS | IPC sender/top-frame/trusted URL validation retained |
| EDS-P2-03 | CLOSED / REGRESSION PASS | 64 MiB response streaming bound retained |
| EDS-P2-06 | PARTIAL / REGRESSION PASS | production DevTools/forceReload removal retained; ordinary reload pending-write safety remains deferred |
| EDS-P1-02 | DEFERRED | token ownership remains renderer memory + optional localStorage by explicit scope guard; Task 06.3 owner |

## 31. R1 Verification Notes

Targeted tests added for:

1. same URL returns current config with no confirmation;
2. different URL + native cancel leaves Main config unchanged;
3. different URL + native approval writes Main config;
4. renderer cannot silently switch localhost/LAN destination;
5. `file:`, `javascript:`, `ftp:` and URL credentials are rejected;
6. legacy `http-request.serverUrl` remains ignored by Main proxy helper;
7. Electron initialization uses Main config over stale localStorage;
8. Electron `updateServerUrl` does not pre-commit on Main cancel/reject;
9. Browser/Web localStorage server URL behavior remains intact.

Full command evidence is recorded in the final Task 06.2-R1 report.

---

# Task 06.3 — Credential / Config Storage Hardening

Date: 2026-09-11
Branch: feature_2.0
Remote baseline before Task 06.3: 879be6f38cd93d2484f4da59d722567fe4e53688
Scope: close `EDS-P1-02`, `EDS-P2-04`, and `EDS-P2-05`; no Task 06.4 work is started.

## 32. Credential Boundary Change

Before Task 06.3, the Electron credential lifecycle was renderer-owned:

```text
login(token, remember)
→ Pinia `appStore.token`
→ optional localStorage[`collector-desktop-token`]
→ each `collector:http-request` IPC payload included `token`
→ Main copied that renderer-provided token into `X-Collector-Token`
```

Task 06.3 changes the desktop boundary to Main-owned credentials:

```text
Renderer
→ only sends business HTTP request payload
→ Electron Main
→ Main-held credential state
→ Main injects `X-Collector-Token`
→ configured trusted collector backend
```

Renderer compromise can still ride the already-exposed application request capability while the user is authenticated, but it no longer owns or can read a remembered plaintext credential and the normal HTTP IPC payload no longer carries a token.

## 33. Main-owned Credential Storage

Main now owns a separate credential file:

```text
userData/collector-desktop-credentials.json
```

The file contains only:

```text
schemaVersion
encryptedToken
updatedAt
```

`encryptedToken` is `safeStorage.encryptString(...).toString("base64")`. The plaintext credential is not written to `collector-desktop-config.json`, localStorage, or any other ordinary JSON/plaintext file.

Current supported Electron API is synchronous:

```text
safeStorage.isEncryptionAvailable()
safeStorage.encryptString()
safeStorage.decryptString()
```

The store is initialized after `app.whenReady()`, before window creation and before renderer IPC can use it.

If `safeStorage.isEncryptionAvailable()` is false, or Linux reports `basic_text`, Task 06.3 forbids plaintext fallback. The credential becomes memory-only for the current application lifecycle and UI-visible status sets `rememberUnavailable=true` without exposing the token content.

## 34. Credential IPC Contract

The preload bridge exposes status/change/clear operations only:

```text
collector:get-credential-status
collector:set-credential
collector:clear-credential
```

Renderer-visible status is limited to:

```text
hasCredential
remembered
storageAvailable
rememberUnavailable
storageBackend
recovery
```

There is intentionally no renderer API such as `getToken()`, `getCredentialPlaintext()`, or `decryptCredential()`.

`collector:http-request` payload now excludes `token`. Main ignores any legacy/hostile `request.token`, reads the Main credential store, and injects `X-Collector-Token` itself. Existing request header allowlist still only permits `Accept` and `Content-Type`; `Authorization`, `Cookie`, and renderer-provided `X-Collector-Token` are not forwarded.

## 35. Legacy localStorage Token Migration

Electron renderer startup performs one safe migration from legacy `localStorage["collector-desktop-token"]`:

```text
Renderer detects legacy key
→ calls Main `setCredential(legacyToken, remember=true)`
→ only if Main returns `hasCredential=true`
→ removes the legacy localStorage key
```

If Main rejects or persistence fails, the legacy key is not removed, preventing accidental credential loss. After successful migration, future Electron startup uses Main credential status and never restores a plaintext token into Pinia or HTTP transport state.

Browser/Web mode remains separate and keeps the existing browser-only localStorage token behavior.

## 36. remember / clear Semantics

```text
remember=false
→ Main memory credential only
→ no credential JSON file
→ exits with the app process
```

```text
remember=true + protected safeStorage available
→ Main memory credential
→ encrypted credential JSON
→ next startup decrypts into Main memory
```

```text
remember=true + protected safeStorage unavailable
→ Main memory credential only
→ `rememberUnavailable=true`
→ no plaintext fallback file/localStorage
```

Logout currently means forgetting the credential for this desktop client:

```text
logout
→ collector:clear-credential
→ clear Main memory credential
→ delete persisted credential file
→ remove legacy localStorage token if present
```

After clear, subsequent Main proxy requests no longer inject the previous `X-Collector-Token`.

## 37. Server Config Strictness and Recovery

Task 06.2-R1 strict candidate URL validation is reused for persisted config reads. Main-authoritative config loaded from disk now rejects legacy dirty values including:

```text
file:
ftp:
javascript:
URL username/password
fragment
query
```

Normal localhost, LAN, and HTTPS collector URLs remain valid. Invalid/corrupt config no longer silently becomes authoritative; it is quarantined to a `.corrupt-...` file when possible, Main recovers to the safe default collector URL, and `getAppInfo()` exposes recovery metadata for UI/Main diagnostics.

## 38. Atomic Persistence

Desktop config and credential persistence now use the same atomic-style write helper:

```text
serialize JSON
→ write temp file in the same directory
→ fsync temp file
→ rename temp file over target
→ best-effort parent directory fsync
```

If the write path fails before rename, the previous valid target file remains intact and the temporary file is removed best-effort. Directory fsync is best-effort because Windows support is inconsistent; same-directory temp file + fsync + rename is the practical Windows boundary.

Corrupt config or credential JSON is quarantined and recovered to a safe state rather than causing permanent startup failure.

## 39. Task 06.3 Finding Status

| Finding | Status | Evidence |
| --- | --- | --- |
| EDS-P1-02 | CLOSED | Renderer no longer stores remembered plaintext token; HTTP IPC payload no longer carries token; Main owns memory/encrypted credential and injects `X-Collector-Token` |
| EDS-P2-04 | CLOSED | persisted Main server config uses strict HTTP/HTTPS/no credentials/no query/no fragment validation plus recovery/quarantine |
| EDS-P2-05 | CLOSED | config and credential writes use same-directory temp file, fsync, rename, cleanup on failure, and corrupt-file recovery |
| EDS-P1-01 | CLOSED / REGRESSION PASS | Main authoritative destination and Main-native server config confirmation retained |
| EDS-P1-03 | CLOSED / REGRESSION PASS | trusted renderer navigation / external URL boundary retained |
| EDS-P1-04 | CLOSED / REGRESSION PASS | CSP and `unsafe-eval` guard retained |
| EDS-P2-01 | CLOSED / REGRESSION PASS | `sandbox: true`, `contextIsolation: true`, `nodeIntegration: false` retained |
| EDS-P2-02 | CLOSED / REGRESSION PASS | trusted sender/top-frame validation retained |
| EDS-P2-03 | CLOSED / REGRESSION PASS | proxy URL/request/response bounds retained |

## 40. Task 06.3 Targeted Verification Notes

Focused tests cover:

1. Electron remembered token is not retained in localStorage/Pinia/renderer HTTP config after Main accepts it;
2. Electron HTTP IPC payload does not include `token` or `serverUrl`;
3. Main proxy injects `X-Collector-Token` from Main-owned state and ignores renderer credential/header bypasses;
4. no renderer API returns plaintext credentials;
5. `remember=false` is Main memory-only;
6. `remember=true` persists only safeStorage ciphertext;
7. safeStorage unavailable and Linux `basic_text` become memory-only with no plaintext fallback;
8. legacy localStorage token is removed only after Main returns `hasCredential=true`;
9. legacy migration failure preserves the legacy key;
10. restart/load decrypts remembered credential into Main memory;
11. corrupt credential/config recovery does not crash and does not return garbage credential;
12. logout/clear removes Main memory and persisted credential;
13. persisted dirty server URL cannot become authoritative config;
14. atomic write failure before rename does not damage the original config;
15. Browser/Web localStorage token behavior remains intact;
16. Task 06.2-R1 destination confirmation tests remain in the full suite.

Full command evidence is recorded in the final Task 06.3 report.
