import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";

const root = join(import.meta.dirname, "..");
const exePath = process.env.COLLECTOR_DESKTOP_EXE || join(root, "release", "win-unpacked", "数据采集工作台.exe");
const devServerUrl = process.env.COLLECTOR_DESKTOP_DEV_URL || "http://127.0.0.1:5173";
const outputDir = process.env.COLLECTOR_DESKTOP_UI_AUDIT_DIR || join(root, ".ui-audit");
const screenshotDir = join(outputDir, "screenshots");
const reportPath = join(outputDir, "report.json");
const userData = mkdtempSync(join(tmpdir(), "collector-desktop-ui-audit-"));
const port = 20000 + Math.floor(Math.random() * 1000);
const viewports = [
  { width: 1280, height: 720 },
  { width: 1366, height: 768 },
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 }
];
const routes = [
  { path: "/login", name: "LOGIN", view: "LoginView", shell: false, needsAuth: false, needsDevice: false },
  { path: "/dashboard", name: "DASHBOARD", view: "DashboardView", shell: true, needsAuth: false, needsDevice: false },
  { path: "/realtime", name: "REALTIME", view: "RealtimeView", shell: true, needsAuth: false, needsDevice: true },
  { path: "/history", name: "HISTORY", view: "HistoryView", shell: true, needsAuth: false, needsDevice: true },
  { path: "/alarm", name: "ALARM", view: "AlarmView", shell: true, needsAuth: false, needsDevice: false },
  { path: "/device", name: "DEVICE", view: "DeviceListView", shell: true, needsAuth: false, needsDevice: false },
  { path: "/device/workbench", name: "DEVICE_WORKBENCH", view: "DeviceWorkbenchView", shell: true, needsAuth: false, needsDevice: true },
  { path: "/collect", name: "COLLECTION", view: "CollectionView", shell: true, needsAuth: false, needsDevice: true },
  { path: "/cloud", name: "CLOUD", view: "CloudView", shell: true, needsAuth: false, needsDevice: false },
  { path: "/diagnostic", name: "DIAGNOSTIC", view: "DiagnosticView", shell: true, needsAuth: false, needsDevice: false },
  { path: "/log", name: "LOG", view: "LogView", shell: true, needsAuth: false, needsDevice: false },
  { path: "/network", name: "NETWORK", view: "NetworkView", shell: true, needsAuth: false, needsDevice: false },
  { path: "/control", name: "CONTROL", view: "ControlView", shell: true, needsAuth: false, needsDevice: true },
  { path: "/shadow", name: "SHADOW", view: "ShadowView", shell: true, needsAuth: false, needsDevice: true }
];

const result = {
  generatedAt: new Date().toISOString(),
  target: { exePath, devServerUrl, port },
  viewports,
  routes,
  routeCount: routes.length,
  viewportCount: viewports.length,
  checks: [],
  summary: {}
};
let child;
let cdp;

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchJson(url, timeoutMs = 1000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
    return await response.json();
  } finally {
    clearTimeout(timer);
  }
}

async function waitForPage() {
  const deadline = Date.now() + 30000;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const pages = await fetchJson(`http://127.0.0.1:${port}/json/list`);
      const page = pages.find((item) => item.type === "page" && item.webSocketDebuggerUrl);
      if (page) return page;
    } catch (error) {
      lastError = error;
    }
    await delay(250);
  }
  throw lastError || new Error("CDP page not available");
}

function connectCdp(webSocketDebuggerUrl) {
  if (typeof WebSocket !== "function") throw new Error("Node WebSocket API is unavailable");
  const socket = new WebSocket(webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  const events = new Map();
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(String(event.data));
    if (message.id && pending.has(message.id)) {
      const request = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) request.reject(new Error(message.error.message || "CDP command failed"));
      else request.resolve(message.result);
      return;
    }
    if (message.method) {
      const listeners = events.get(message.method) || [];
      for (const listener of listeners) listener(message.params || {});
    }
  });
  return new Promise((resolve, reject) => {
    socket.addEventListener("open", () => resolve({
      send(method, params = {}) {
        const commandId = ++id;
        socket.send(JSON.stringify({ id: commandId, method, params }));
        return new Promise((commandResolve, commandReject) => pending.set(commandId, { resolve: commandResolve, reject: commandReject }));
      },
      on(method, listener) {
        events.set(method, [...(events.get(method) || []), listener]);
        return () => events.set(method, (events.get(method) || []).filter((item) => item !== listener));
      },
      close() {
        socket.close();
      }
    }));
    socket.addEventListener("error", () => reject(new Error("CDP websocket connection failed")), { once: true });
  });
}

async function evaluate(expression, awaitPromise = false) {
  const response = await cdp.send("Runtime.evaluate", { expression, awaitPromise, returnByValue: true });
  if (response.exceptionDetails) throw new Error(response.exceptionDetails.text || "Runtime.evaluate exception");
  return response.result.value;
}

async function waitForRoute(path) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    const state = await evaluate("({ readyState: document.readyState, hash: location.hash, textLength: document.body?.innerText?.length || 0 })");
    if (["interactive", "complete"].includes(state.readyState) && state.hash === `#${path}` && state.textLength > 10) return state;
    await delay(150);
  }
  return await evaluate("({ readyState: document.readyState, hash: location.hash, textLength: document.body?.innerText?.length || 0 })");
}

async function collectDomMetrics() {
  return await evaluate(`(() => {
    const visible = (element) => {
      const rect = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      return rect.width > 0 && rect.height > 0 && style.display !== "none" && style.visibility !== "hidden";
    };
    const selectorFor = (element) => {
      if (!element || element === document.body || element === document.documentElement) return element?.tagName?.toLowerCase() || "document";
      const parts = [];
      let current = element;
      for (let depth = 0; current && current.nodeType === 1 && depth < 5; depth += 1) {
        let part = current.tagName.toLowerCase();
        if (current.id) { part += '#' + current.id; parts.unshift(part); break; }
        const classes = [...current.classList].filter((name) => !name.includes('__v-'));
        if (classes.length) part += '.' + classes.slice(0, 2).join('.');
        const parent = current.parentElement;
        if (parent) {
          const siblings = [...parent.children].filter((item) => item.tagName === current.tagName);
          if (siblings.length > 1) part += ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')';
        }
        parts.unshift(part);
        current = parent;
      }
      return parts.join(' > ');
    };
    const elements = [...document.querySelectorAll('*')];
    const overflowElements = elements.filter((element) => {
      if (!visible(element)) return false;
      const tag = element.tagName.toLowerCase();
      const textBox = ['span', 'strong', 'em', 'small', 'label'].includes(tag);
      const widthOverflow = element.scrollWidth > element.clientWidth + 4;
      const heightOverflow = element.scrollHeight > element.clientHeight + 4;
      return (widthOverflow || heightOverflow) && (!textBox || widthOverflow);
    }).map((element) => ({
      selector: selectorFor(element),
      tag: element.tagName.toLowerCase(),
      className: String(element.className || '').slice(0, 180),
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
      overflowX: getComputedStyle(element).overflowX,
      overflowY: getComputedStyle(element).overflowY,
      intentional: ['auto', 'scroll'].includes(getComputedStyle(element).overflowX) || ['auto', 'scroll'].includes(getComputedStyle(element).overflowY)
    })).sort((a, b) => Math.max(b.scrollWidth - b.clientWidth, b.scrollHeight - b.clientHeight) - Math.max(a.scrollWidth - a.clientWidth, a.scrollHeight - a.clientHeight)).slice(0, 40);
    const controls = elements.filter((element) => visible(element) && element.matches('input, select, textarea, .el-input__wrapper, .el-select__wrapper, .el-textarea__inner, .el-date-editor, .el-input-number'));
    const whiteBackground = (value) => { const match = value.match(/rgba?\\(([^)]+)\\)/); return value === 'white' || value === '#fff' || value === '#ffffff' || (match && match[1].split(',').slice(0, 3).every((part) => Number(part.trim()) >= 245)); };
    const controlStyles = controls.map((element) => {
      const style = getComputedStyle(element);
      return { selector: selectorFor(element), tag: element.tagName.toLowerCase(), className: String(element.className || '').slice(0, 160), background: style.backgroundColor, color: style.color, borderColor: style.borderColor, height: Math.round(element.getBoundingClientRect().height), whiteBackground: whiteBackground(style.backgroundColor) };
    });
    const popupSelectors = '.el-popper, .el-select-dropdown, .el-picker__popper, .el-dropdown__popper, .el-popover, .el-dialog, .el-drawer, .el-message-box';
    const popups = [...document.querySelectorAll(popupSelectors)].filter(visible).map((element) => { const style = getComputedStyle(element); return { selector: selectorFor(element), className: String(element.className || '').slice(0, 180), background: style.backgroundColor, color: style.color, width: Math.round(element.getBoundingClientRect().width), height: Math.round(element.getBoundingClientRect().height), top: Math.round(element.getBoundingClientRect().top), bottom: Math.round(element.getBoundingClientRect().bottom) }; });
    return {
      url: location.href,
      title: document.title,
      readyState: document.readyState,
      bodyTextLength: document.body?.innerText?.length || 0,
      document: { scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth, scrollHeight: document.documentElement.scrollHeight, clientHeight: document.documentElement.clientHeight },
      body: { scrollWidth: document.body.scrollWidth, clientWidth: document.body.clientWidth, scrollHeight: document.body.scrollHeight, clientHeight: document.body.clientHeight },
      visibleElementCount: elements.filter(visible).length,
      overflowElements,
      controls: { count: controls.length, whiteBackgroundCount: controlStyles.filter((item) => item.whiteBackground).length, samples: controlStyles.slice(0, 80) },
      popups,
      shell: { hasAppShell: Boolean(document.querySelector('.app-shell')), hasPage: Boolean(document.querySelector('.exact-page, .page, main')), hasSidebar: Boolean(document.querySelector('.app-sidebar')), hasTopbar: Boolean(document.querySelector('.app-topbar')) }
    };
  })()`);
}

async function collectConsoleForRoute(route) {
  const messages = [];
  const exceptions = [];
  const removeConsoleListener = cdp.on("Runtime.consoleAPICalled", (params) => {
    const text = (params.args || []).map((arg) => arg.value ?? arg.description ?? "").join(" ");
    messages.push({ type: params.type, text: text.slice(0, 500) });
  });
  const removeExceptionListener = cdp.on("Runtime.exceptionThrown", (params) => exceptions.push({ text: String(params.exceptionDetails?.text || params.exceptionDetails?.exception?.description || "exception").slice(0, 500) }));
  const href = `${devServerUrl}/#${route.path}`;
  await cdp.send("Page.navigate", { url: href });
  const state = await waitForRoute(route.path);
  await delay(300);
  const beforePopup = await collectDomMetrics();
  await evaluate(`(() => { const target = document.querySelector('.el-select, .el-date-editor, select'); if (target) target.click(); return Boolean(target); })()`);
  await delay(150);
  const afterPopup = await collectDomMetrics();
  const metrics = afterPopup.popups.length >= beforePopup.popups.length ? afterPopup : beforePopup;
  removeConsoleListener();
  removeExceptionListener();
  return { state, metrics, console: messages, exceptions };
}

async function captureScreenshot(route, viewport) {
  const shot = await cdp.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  const filePath = join(screenshotDir, `${viewport.width}x${viewport.height}`, `${route.name.toLowerCase()}.png`);
  mkdirSync(join(screenshotDir, `${viewport.width}x${viewport.height}`), { recursive: true });
  writeFileSync(filePath, Buffer.from(shot.data, "base64"));
  return filePath.replaceAll("\\", "/");
}

function summarize() {
  const checks = result.checks;
  const routeMap = new Map();
  for (const check of checks) {
    if (!routeMap.has(check.route.path)) routeMap.set(check.route.path, []);
    routeMap.get(check.route.path).push(check);
  }
  const routeSummaries = [...routeMap.entries()].map(([path, items]) => ({
    path,
    viewports: items.length,
    rendered: items.filter((item) => item.rendered).length,
    overflow: items.some((item) => item.documentOverflowX || item.documentOverflowY || item.unintentionalOverflowCount > 0),
    themeMismatch: items.some((item) => item.themeMismatch),
    layoutIssue: items.some((item) => !item.layoutShellOk || item.unintentionalOverflowCount > 0),
    consoleErrors: items.reduce((sum, item) => sum + item.consoleErrorCount + item.exceptionCount, 0)
  }));
  result.routeSummaries = routeSummaries;
  result.summary = {
    routeCount: routes.length,
    viewportCount: viewports.length,
    executedChecks: checks.length,
    renderedRouteCount: routeSummaries.filter((item) => item.rendered === viewports.length).length,
    routesWithOverflow: routeSummaries.filter((item) => item.overflow).length,
    routesWithThemeMismatch: routeSummaries.filter((item) => item.themeMismatch).length,
    routesWithLayoutIssue: routeSummaries.filter((item) => item.layoutIssue).length,
    routesWithConsoleErrors: routeSummaries.filter((item) => item.consoleErrors > 0).length,
    notVisited: routes.filter((route) => !routeSummaries.some((item) => item.path === route.path)).map((route) => route.path)
  };
}

try {
  if (!existsSync(exePath)) throw new Error(`packaged exe missing: ${exePath}`);
  mkdirSync(outputDir, { recursive: true });
  child = spawn(exePath, [`--remote-debugging-port=${port}`, `--user-data-dir=${userData}`], {
    stdio: "ignore",
    windowsHide: true,
    env: { ...process.env, VITE_DEV_SERVER_URL: devServerUrl }
  });
  const page = await waitForPage();
  cdp = await connectCdp(page.webSocketDebuggerUrl);
  await cdp.send("Runtime.enable");
  await cdp.send("Page.enable");
  await cdp.send("Emulation.setFocusEmulationEnabled", { enabled: true });
  for (const viewport of viewports) {
    await cdp.send("Emulation.setDeviceMetricsOverride", { width: viewport.width, height: viewport.height, deviceScaleFactor: 1, mobile: false });
    for (const route of routes) {
      const startedAt = Date.now();
      const { state, metrics, console: consoleMessages, exceptions } = await collectConsoleForRoute(route);
      const screenshot = await captureScreenshot(route, viewport);
      const documentOverflowX = metrics.document.scrollWidth > metrics.document.clientWidth + 1 || metrics.body.scrollWidth > metrics.body.clientWidth + 1;
      const documentOverflowY = metrics.document.scrollHeight > metrics.document.clientHeight + 1 || metrics.body.scrollHeight > metrics.body.clientHeight + 1;
      const intentionalOverflow = metrics.overflowElements.filter((item) => item.intentional);
      const unintentionalOverflow = metrics.overflowElements.filter((item) => !item.intentional);
      const themeMismatch = metrics.controls.whiteBackgroundCount > 0 || metrics.popups.some((item) => /^rgb\\(255, 255, 255\\)$/.test(item.background));
      result.checks.push({
        route,
        viewport: `${viewport.width}x${viewport.height}`,
        rendered: state.hash === `#${route.path}` && metrics.bodyTextLength > 10,
        layoutShellOk: route.shell ? metrics.shell.hasAppShell && metrics.shell.hasSidebar && metrics.shell.hasTopbar : !metrics.shell.hasAppShell,
        documentOverflowX,
        documentOverflowY,
        document: metrics.document,
        body: metrics.body,
        overflowElements: metrics.overflowElements,
        intentionalOverflowCount: intentionalOverflow.length,
        unintentionalOverflowCount: unintentionalOverflow.length,
        controls: metrics.controls,
        popups: metrics.popups,
        themeMismatch,
        consoleErrorCount: consoleMessages.filter((item) => ["error", "assert"].includes(item.type)).length,
        consoleMessages,
        exceptionCount: exceptions.length,
        exceptions,
        screenshot,
        elapsedMs: Date.now() - startedAt
      });
      console.log(JSON.stringify({ route: route.path, viewport: `${viewport.width}x${viewport.height}`, rendered: state.hash === `#${route.path}`, overflowX: documentOverflowX, overflowY: documentOverflowY, consoleErrors: consoleMessages.length + exceptions.length }));
    }
  }
  summarize();
  result.ok = true;
} catch (error) {
  result.ok = false;
  result.errors = [error instanceof Error ? error.message : String(error)];
  process.exitCode = 1;
} finally {
  try {
    cdp?.close();
  } catch {
    // CDP 关闭失败不影响审计结果落盘。
  }
  if (child && child.exitCode === null && !child.killed) child.kill();
  try {
    rmSync(userData, { recursive: true, force: true });
  } catch {
    // 临时 userData 清理失败不影响审计结果落盘。
  }
  mkdirSync(outputDir, { recursive: true });
  writeFileSync(reportPath, `${JSON.stringify(result, null, 2)}\n`, "utf8");
  console.log(JSON.stringify({ ok: result.ok, reportPath, summary: result.summary, errors: result.errors || [] }, null, 2));
}
