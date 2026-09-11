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
