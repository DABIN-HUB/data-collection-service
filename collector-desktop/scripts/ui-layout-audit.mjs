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

const themeFixtureHtml = `
  <section data-fixture-section="controls">
    <input data-audit-sample="native-input" value="native input" placeholder="请输入" />
    <input data-audit-sample="native-readonly" value="readonly input" readonly />
    <input data-audit-sample="native-disabled" value="disabled input" disabled />
    <select data-audit-sample="native-select"><option>深色选项</option></select>
    <textarea data-audit-sample="native-textarea" placeholder="textarea">多行文本</textarea>
    <label><input data-audit-sample="native-checkbox" type="checkbox" checked /> 原生复选</label>
    <label><input data-audit-sample="native-radio" type="radio" checked /> 原生单选</label>
    <div class="el-input"><div data-audit-sample="el-input-normal" class="el-input__wrapper"><input class="el-input__inner" placeholder="Element Input" /></div></div>
    <div class="el-input"><div data-audit-sample="el-input-focus" class="el-input__wrapper is-focus"><input class="el-input__inner" value="focus" /></div></div>
    <div class="el-input"><div data-audit-sample="el-input-disabled" class="el-input__wrapper is-disabled"><input class="el-input__inner" value="disabled" disabled /></div></div>
    <div class="el-form-item is-error"><div data-audit-sample="el-input-error" class="el-input__wrapper"><input class="el-input__inner" value="error" /></div><div class="el-form-item__error">错误提示</div></div>
    <div data-audit-sample="el-select-wrapper" class="el-select__wrapper is-focused"><span class="el-select__placeholder">请选择</span><span class="el-select__caret">⌄</span></div>
    <textarea data-audit-sample="el-textarea" class="el-textarea__inner" placeholder="Element Textarea">Element Textarea</textarea>
    <div data-audit-sample="el-input-number" class="el-input-number"><div class="el-input"><div class="el-input__wrapper"><input class="el-input__inner" value="12" /></div></div><span class="el-input-number__increase">+</span><span class="el-input-number__decrease">-</span></div>
    <div data-audit-sample="el-switch" class="el-switch is-checked"><span class="el-switch__core"></span><span class="el-switch__label">启用</span></div>
    <label data-audit-sample="el-checkbox" class="el-checkbox"><span class="el-checkbox__input is-checked"><span class="el-checkbox__inner"></span></span><span class="el-checkbox__label">复选项</span></label>
    <label data-audit-sample="el-radio" class="el-radio"><span class="el-radio__input is-checked"><span class="el-radio__inner"></span></span><span class="el-radio__label">单选项</span></label>
    <button data-audit-sample="primary-plain-button" class="el-button el-button--primary is-plain">主要幽灵按钮</button>
    <button data-audit-sample="primary-plain-disabled" class="el-button el-button--primary is-plain is-disabled" disabled>禁用主要幽灵按钮</button>
  </section>
  <section data-fixture-section="popups">
    <div data-audit-sample="select-popper" class="el-popper el-select__popper"><div class="el-select-dropdown"><div class="el-select-dropdown__wrap"><ul class="el-scrollbar__view"><li data-audit-sample="select-item" class="el-select-dropdown__item">普通选项</li><li data-audit-sample="select-item-hover" class="el-select-dropdown__item hover">Hover 选项</li><li data-audit-sample="select-item-selected" class="el-select-dropdown__item selected">Selected 选项</li><li data-audit-sample="select-item-disabled" class="el-select-dropdown__item is-disabled">Disabled 选项</li></ul></div></div></div>
    <div data-audit-sample="date-popper" class="el-popper el-picker__popper"><div class="el-picker-panel"><div class="el-picker-panel__body"><div class="el-date-picker__header"><button class="el-picker-panel__icon-btn">‹</button><span class="el-date-picker__header-label">2026 年 9 月</span></div><table class="el-date-table"><tbody><tr><td class="available today"><div class="el-date-table-cell"><span class="el-date-table-cell__text">14</span></div></td><td class="available current"><div class="el-date-table-cell"><span class="el-date-table-cell__text">15</span></div></td><td class="disabled"><div class="el-date-table-cell"><span class="el-date-table-cell__text">16</span></div></td></tr></tbody></table></div><div class="el-picker-panel__footer"><button class="el-button">取消</button><button class="el-button el-button--primary">确定</button></div></div></div>
    <div data-audit-sample="message-box" class="el-message-box"><div class="el-message-box__header"><span class="el-message-box__title">确认操作</span></div><div class="el-message-box__content"><div class="el-message-box__message">深色 MessageBox 内容</div></div><div class="el-message-box__btns"><button class="el-button">取消</button><button class="el-button el-button--primary">确定</button></div></div>
  </section>
  <section data-fixture-section="alerts">
    <div data-audit-sample="alert-success" class="el-alert el-alert--success"><span class="el-alert__icon">✓</span><div class="el-alert__content"><span class="el-alert__title">成功提示内容不会被裁切</span></div></div>
    <div data-audit-sample="alert-warning" class="el-alert el-alert--warning"><span class="el-alert__icon">!</span><div class="el-alert__content"><span class="el-alert__title">警告提示内容支持换行，不应再出现 line-height 裁切</span></div></div>
    <div data-audit-sample="alert-error" class="el-alert el-alert--error"><span class="el-alert__icon">×</span><div class="el-alert__content"><span class="el-alert__title">错误提示内容不会被裁切</span></div></div>
    <div data-audit-sample="alert-info" class="el-alert el-alert--info"><span class="el-alert__icon">i</span><div class="el-alert__content"><span class="el-alert__title">信息提示内容不会被裁切</span></div></div>
  </section>
  <section data-fixture-section="tags-empty">
    <span data-audit-sample="tag-default" class="el-tag is-light">默认标签</span>
    <span data-audit-sample="tag-success" class="el-tag el-tag--success is-light">成功标签</span>
    <span data-audit-sample="tag-warning" class="el-tag el-tag--warning is-light">警告标签</span>
    <span data-audit-sample="tag-danger" class="el-tag el-tag--danger is-light">危险标签</span>
    <span data-audit-sample="tag-info" class="el-tag el-tag--info is-light">信息标签</span>
    <div data-audit-sample="el-empty" class="el-empty"><div class="el-empty__image"></div><p class="el-empty__description">暂无数据</p></div>
  </section>
  <section data-fixture-section="tables">
    <div data-audit-sample="el-table" class="el-table"><div class="el-table__inner-wrapper"><table><thead><tr><th class="el-table__cell"><div class="cell">表头</div></th></tr></thead><tbody><tr><td class="el-table__cell"><div class="cell">表格内容</div></td></tr></tbody></table><div class="el-table__empty-block"><span class="el-table__empty-text">暂无数据</span></div></div></div>
    <div data-audit-sample="el-pagination" class="el-pagination"><button>‹</button><ul class="el-pager"><li>1</li><li class="is-active">2</li><li class="is-disabled">3</li></ul><button disabled>›</button><div class="el-input"><div class="el-input__wrapper"><input class="el-input__inner" value="10" /></div></div></div>
  </section>
  <section data-fixture-section="dialogs">
    <div data-audit-sample="dialog-520" data-fixture-dialog="520" class="el-dialog" style="width: 520px;"><div class="el-dialog__header"><span class="el-dialog__title">520px Dialog</span><button class="el-dialog__headerbtn"><span class="el-dialog__close">×</span></button></div><div class="el-dialog__body">Dialog body</div><div class="el-dialog__footer"><button class="el-button">取消</button><button class="el-button el-button--primary">确定</button></div></div>
    <div data-audit-sample="dialog-720" data-fixture-dialog="720" class="el-dialog" style="width: 720px;"><div class="el-dialog__header"><span class="el-dialog__title">720px Dialog</span></div><div class="el-dialog__body">Dialog body</div><div class="el-dialog__footer"><button class="el-button">取消</button><button class="el-button el-button--primary">确定</button></div></div>
    <div data-audit-sample="dialog-920" data-fixture-dialog="920" class="el-dialog" style="width: 920px;"><div class="el-dialog__header"><span class="el-dialog__title">920px Dialog</span></div><div class="el-dialog__body">Dialog body</div><div class="el-dialog__footer"><button class="el-button">取消</button><button class="el-button el-button--primary">确定</button></div></div>
  </section>
`;

const result = {
  generatedAt: new Date().toISOString(),
  target: { exePath, devServerUrl, port },
  viewports,
  routes,
  routeCount: routes.length,
  viewportCount: viewports.length,
  checks: [],
  themeFixtureChecks: [],
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
      const style = getComputedStyle(element);
      const intentionalEllipsis = style.textOverflow === 'ellipsis' && ['hidden', 'clip'].includes(style.overflowX) && style.whiteSpace === 'nowrap' && Boolean(element.getAttribute('title') || element.getAttribute('aria-label'));
      return (widthOverflow || heightOverflow) && !intentionalEllipsis && (!textBox || widthOverflow);
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
    const toolbarSelectors = '.exact-toolbar, .exact-toolbar-group, .exact-toolbar-filters, .table-actions, .panel-toolbar, [class*="toolbar"], [class*="filter-bar"]';
    const toolbarHorizontalOverflows = [...document.querySelectorAll(toolbarSelectors)].filter((element) => {
      if (!visible(element)) return false;
      return element.scrollWidth > element.clientWidth + 4;
    }).map((element) => ({
      selector: selectorFor(element),
      className: String(element.className || '').slice(0, 180),
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
      overflowX: getComputedStyle(element).overflowX,
      overflowY: getComputedStyle(element).overflowY
    })).slice(0, 20);
    const hiddenClips = overflowElements.filter((item) => {
      const horizontalClip = item.scrollWidth > item.clientWidth + 4 && ['hidden', 'clip'].includes(item.overflowX);
      const verticalClip = item.scrollHeight > item.clientHeight + 4 && ['hidden', 'clip'].includes(item.overflowY);
      return horizontalClip || verticalClip;
    });
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
      toolbarHorizontalOverflows,
      hiddenClips,
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

async function collectThemeFixture(viewport) {
  return await evaluate(`(() => {
    document.getElementById('uiThemeAuditFixture')?.remove();
    const host = document.createElement('div');
    host.id = 'uiThemeAuditFixture';
    host.style.cssText = 'position: fixed; left: 16px; top: 16px; z-index: 2147483000; display: grid; width: min(960px, calc(100vw - 32px)); max-height: calc(100vh - 32px); padding: 16px; gap: 12px; overflow: auto; color: var(--app-color-text-secondary); border: 1px solid var(--app-color-border-soft); border-radius: 12px; background: var(--app-color-bg); box-shadow: var(--app-overlay-shadow);';
    host.innerHTML = ${JSON.stringify(themeFixtureHtml)};
    document.body.appendChild(host);
    const whiteBackground = (value) => {
      const match = value.match(/rgba?\\(([^)]+)\\)/);
      return value === 'white' || value === '#fff' || value === '#ffffff' || (match && match[1].split(',').slice(0, 3).every((part) => Number(part.trim()) >= 245));
    };
    const styleOf = (element) => {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return {
        name: element.getAttribute('data-audit-sample') || element.getAttribute('data-fixture-dialog') || element.className,
        tag: element.tagName.toLowerCase(),
        className: String(element.className || '').slice(0, 160),
        background: style.backgroundColor,
        color: style.color,
        borderColor: style.borderColor,
        boxShadow: style.boxShadow,
        width: Math.round(rect.width),
        height: Math.round(rect.height),
        whiteBackground: whiteBackground(style.backgroundColor)
      };
    };
    const samples = [...host.querySelectorAll('[data-audit-sample]')].map(styleOf);
    const alerts = [...host.querySelectorAll('.el-alert')].map((element) => ({
      name: element.getAttribute('data-audit-sample'),
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
      clipped: element.scrollHeight > element.clientHeight + 1
    }));
    const emptyFills = [...host.querySelectorAll('.el-empty')].map((element) => {
      const style = getComputedStyle(element);
      const fills = Array.from({ length: 10 }, (_, index) => style.getPropertyValue('--el-empty-fill-color-' + index).trim()).filter(Boolean);
      return {
        name: element.getAttribute('data-audit-sample'),
        fills,
        lightFillCount: fills.filter((fill) => whiteBackground(fill)).length
      };
    });
    const dialogs = [...host.querySelectorAll('[data-fixture-dialog]')].map((element) => {
      const rect = element.getBoundingClientRect();
      return {
        widthToken: element.getAttribute('data-fixture-dialog'),
        width: Math.round(rect.width),
        height: Math.round(rect.height),
        safeWidth: rect.width <= window.innerWidth - 32 + 1,
        safeHeight: rect.height <= window.innerHeight - 32 + 1
      };
    });
    const fixture = {
      viewport: '${viewport.width}x${viewport.height}',
      sampleCount: samples.length,
      whiteBackgroundCount: samples.filter((sample) => sample.whiteBackground).length,
      samples,
      alerts,
      clippedAlertCount: alerts.filter((alert) => alert.clipped).length,
      emptyFills,
      emptyLightFillCount: emptyFills.reduce((sum, item) => sum + item.lightFillCount, 0),
      dialogs,
      unsafeDialogCount: dialogs.filter((dialog) => !dialog.safeWidth || !dialog.safeHeight).length
    };
    host.remove();
    return fixture;
  })()`);
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
    overflow: items.some((item) => item.documentOverflowX || item.documentOverflowY || item.unintentionalOverflowCount > 0 || item.toolbarHorizontalOverflowCount > 0 || item.hiddenClipCount > 0),
    themeMismatch: items.some((item) => item.themeMismatch),
    layoutIssue: items.some((item) => !item.layoutShellOk || item.unintentionalOverflowCount > 0 || item.toolbarHorizontalOverflowCount > 0 || item.hiddenClipCount > 0),
    toolbarHorizontalOverflows: items.reduce((sum, item) => sum + item.toolbarHorizontalOverflowCount, 0),
    hiddenClips: items.reduce((sum, item) => sum + item.hiddenClipCount, 0),
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
    toolbarHorizontalOverflows: checks.reduce((sum, item) => sum + item.toolbarHorizontalOverflowCount, 0),
    hiddenClips: checks.reduce((sum, item) => sum + item.hiddenClipCount, 0),
    notVisited: routes.filter((route) => !routeSummaries.some((item) => item.path === route.path)).map((route) => route.path),
    themeFixtureChecks: result.themeFixtureChecks.length,
    themeFixtureWhiteBackgrounds: result.themeFixtureChecks.reduce((sum, item) => sum + item.whiteBackgroundCount, 0),
    themeFixtureClippedAlerts: result.themeFixtureChecks.reduce((sum, item) => sum + item.clippedAlertCount, 0),
    themeFixtureUnsafeDialogs: result.themeFixtureChecks.reduce((sum, item) => sum + item.unsafeDialogCount, 0),
    themeFixtureLightEmptyFills: result.themeFixtureChecks.reduce((sum, item) => sum + item.emptyLightFillCount, 0)
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
      const check = {
        route,
        viewport: `${viewport.width}x${viewport.height}`,
        rendered: state.hash === `#${route.path}` && metrics.bodyTextLength > 10,
        layoutShellOk: route.shell ? metrics.shell.hasAppShell && metrics.shell.hasSidebar && metrics.shell.hasTopbar : !metrics.shell.hasAppShell,
        documentOverflowX,
        documentOverflowY,
        document: metrics.document,
        body: metrics.body,
        overflowElements: metrics.overflowElements,
        toolbarHorizontalOverflows: metrics.toolbarHorizontalOverflows,
        toolbarHorizontalOverflowCount: metrics.toolbarHorizontalOverflows.length,
        hiddenClips: metrics.hiddenClips,
        hiddenClipCount: metrics.hiddenClips.length,
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
      };
      result.checks.push(check);
      console.log(JSON.stringify({ route: route.path, viewport: `${viewport.width}x${viewport.height}`, rendered: state.hash === `#${route.path}`, overflowX: documentOverflowX, overflowY: documentOverflowY, toolbarOverflow: check.toolbarHorizontalOverflowCount, hiddenClips: check.hiddenClipCount, consoleErrors: check.consoleErrorCount, exceptions: check.exceptionCount }));
    }
    const themeFixture = await collectThemeFixture(viewport);
    result.themeFixtureChecks.push(themeFixture);
    console.log(JSON.stringify({ viewport: `${viewport.width}x${viewport.height}`, themeFixture: true, whiteBackgrounds: themeFixture.whiteBackgroundCount, clippedAlerts: themeFixture.clippedAlertCount, unsafeDialogs: themeFixture.unsafeDialogCount, lightEmptyFills: themeFixture.emptyLightFillCount }));
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
