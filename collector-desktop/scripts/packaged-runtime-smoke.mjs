import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";

const root = join(import.meta.dirname, "..");
const exePath = process.env.COLLECTOR_DESKTOP_EXE || join(root, "release", "win-unpacked", "数据采集工作台.exe");
const reportPath = process.env.COLLECTOR_DESKTOP_SMOKE_REPORT || join(root, "release", "packaged-runtime-smoke.json");
const userData = mkdtempSync(join(tmpdir(), "collector-desktop-smoke-"));
const port = 19000 + Math.floor(Math.random() * 1000);
const appArgs = [`--remote-debugging-port=${port}`, `--user-data-dir=${userData}`];
const result = {
  exePath,
  userData,
  port,
  checks: {},
  errors: []
};
let first;
let second;
let cdp;

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function startApp(extraArgs = []) {
  const child = spawn(exePath, [...appArgs, ...extraArgs], {
    stdio: "ignore",
    windowsHide: true
  });
  return child;
}

function isRunning(child) {
  return child && child.exitCode === null && !child.killed;
}

async function waitForExit(child, timeoutMs) {
  if (!child || child.exitCode !== null) {
    return child?.exitCode ?? 0;
  }
  return await new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), timeoutMs);
    child.once("exit", (code) => {
      clearTimeout(timer);
      resolve(code);
    });
  });
}

async function fetchJson(url, timeoutMs = 1000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} for ${url}`);
    }
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
      if (page) {
        return page;
      }
    } catch (error) {
      lastError = error;
    }
    await delay(250);
  }
  throw lastError || new Error("CDP page not available");
}

function connectCdp(webSocketDebuggerUrl) {
  if (typeof WebSocket !== "function") {
    throw new Error("Node WebSocket API is unavailable");
  }
  const socket = new WebSocket(webSocketDebuggerUrl);
  socket.binaryType = "arraybuffer";
  let id = 0;
  const pending = new Map();
  const decodeMessage = async (data) => {
    if (typeof data === "string") return data;
    if (data instanceof ArrayBuffer) return Buffer.from(data).toString("utf8");
    if (ArrayBuffer.isView(data)) return Buffer.from(data.buffer, data.byteOffset, data.byteLength).toString("utf8");
    if (data && typeof data.text === "function") return await data.text();
    return String(data);
  };
  const rejectPending = (error) => {
    for (const request of pending.values()) request.reject(error);
    pending.clear();
  };
  socket.addEventListener("message", async (event) => {
    const message = JSON.parse(await decodeMessage(event.data));
    if (message.id && pending.has(message.id)) {
      const { resolve, reject, timer } = pending.get(message.id);
      pending.delete(message.id);
      clearTimeout(timer);
      if (message.error) {
        reject(new Error(message.error.message || "CDP command failed"));
      } else {
        resolve(message.result);
      }
    }
  });
  return new Promise((resolve, reject) => {
    socket.addEventListener("open", () => {
      resolve({
        send(method, params = {}, timeoutMs = 10000) {
          const commandId = ++id;
          socket.send(JSON.stringify({ id: commandId, method, params }));
          return new Promise((commandResolve, commandReject) => {
            const timer = setTimeout(() => {
              pending.delete(commandId);
              commandReject(new Error(`CDP command timed out: ${method}`));
            }, timeoutMs);
            pending.set(commandId, { resolve: commandResolve, reject: commandReject, timer });
          });
        },
        close() {
          socket.close();
        }
      });
    });
    socket.addEventListener("error", () => reject(new Error("CDP websocket connection failed")), { once: true });
    socket.addEventListener("close", () => rejectPending(new Error("CDP websocket closed")));
  });
}

async function evaluate(expression, awaitPromise = false) {
  const response = await cdp.send("Runtime.evaluate", {
    expression,
    awaitPromise,
    returnByValue: true
  });
  if (response.exceptionDetails) {
    throw new Error(response.exceptionDetails.text || "Runtime.evaluate exception");
  }
  return response.result.value;
}

async function dispatchReloadShortcut() {
  await cdp.send("Input.dispatchKeyEvent", { type: "keyDown", key: "F5", code: "F5", windowsVirtualKeyCode: 116, nativeVirtualKeyCode: 116 });
  await cdp.send("Input.dispatchKeyEvent", { type: "keyUp", key: "F5", code: "F5", windowsVirtualKeyCode: 116, nativeVirtualKeyCode: 116 });
  await delay(750);
}

async function waitForRendererReady() {
  const deadline = Date.now() + 15000;
  let state = { readyState: "loading", hasBridge: false };
  while (Date.now() < deadline) {
    state = await evaluate("({ readyState: document.readyState, hasBridge: Boolean(window.collectorDesktop) })");
    if (["interactive", "complete"].includes(state.readyState) && state.hasBridge) {
      return state;
    }
    await delay(150);
  }
  return state;
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

try {
  if (!existsSync(exePath)) {
    throw new Error(`packaged exe missing: ${exePath}`);
  }
  first = startApp();
  result.checks.firstStarted = Boolean(first.pid);
  const page = await waitForPage();
  cdp = await connectCdp(page.webSocketDebuggerUrl);
  await cdp.send("Runtime.enable");
  const rendererState = await waitForRendererReady();
  result.checks.rendererIndexLoaded = ["interactive", "complete"].includes(rendererState.readyState);
  assert(result.checks.rendererIndexLoaded, `unexpected readyState ${rendererState.readyState}`);

  const bridgeShape = await evaluate(`(() => ({
    hasBridge: Boolean(window.collectorDesktop),
    getAppInfo: typeof window.collectorDesktop?.getAppInfo,
    getCredentialStatus: typeof window.collectorDesktop?.getCredentialStatus,
    getServerConfig: typeof window.collectorDesktop?.getServerConfig
  }))()`);
  result.checks.preloadBridge = bridgeShape;
  assert(bridgeShape.hasBridge && bridgeShape.getAppInfo === "function", "preload bridge missing");

  const appInfo = await evaluate("window.collectorDesktop.getAppInfo()", true);
  const credentialStatus = await evaluate("window.collectorDesktop.getCredentialStatus()", true);
  const serverConfig = await evaluate("window.collectorDesktop.getServerConfig()", true);
  result.checks.appInfo = {
    name: appInfo.name,
    version: appInfo.version,
    backendManaged: appInfo.backendManaged,
    startupDiagnosticPath: appInfo.startupDiagnosticPath
  };
  result.checks.credentialStatusApi = typeof credentialStatus.hasCredential === "boolean" && typeof credentialStatus.remembered === "boolean";
  result.checks.serverConfigApi = typeof serverConfig.serverUrl === "string" && serverConfig.serverUrl.startsWith("http");
  assert(appInfo.backendManaged === false, "backendManaged must remain false");
  assert(String(appInfo.startupDiagnosticPath || "").includes("logs"), "startup diagnostic path not exposed under logs");
  assert(result.checks.credentialStatusApi, "credential status API failed");
  assert(result.checks.serverConfigApi, "server config API failed");

  const diagnosticProbePath = join(userData, "logs", "startup-diagnostic-write-probe.txt");
  mkdirSync(join(userData, "logs"), { recursive: true });
  writeFileSync(diagnosticProbePath, "ok\n", "utf8");
  result.checks.startupDiagnosticPathWritable = existsSync(diagnosticProbePath);
  assert(result.checks.startupDiagnosticPathWritable, "startup diagnostic logs path is not writable");

  await evaluate("window.__collectorReloadSmoke = 'still-here'");
  await dispatchReloadShortcut();
  result.checks.productionReloadShortcutBlocked = await evaluate("window.__collectorReloadSmoke === 'still-here'");
  assert(result.checks.productionReloadShortcutBlocked, "production reload shortcut was not blocked");

  second = startApp(["--second-instance-smoke"]);
  const secondExitCode = await waitForExit(second, 5000);
  result.checks.secondInstanceExited = secondExitCode !== null;
  result.checks.firstInstanceStillRunning = isRunning(first);
  assert(result.checks.secondInstanceExited, "second top-level process did not exit promptly");
  assert(result.checks.firstInstanceStillRunning, "first instance exited unexpectedly");

  result.ok = true;
} catch (error) {
  result.ok = false;
  result.errors.push(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
} finally {
  try {
    cdp?.close();
  } catch {
    // 关闭 CDP 失败不影响 smoke 进程清理。
  }
  if (second && isRunning(second)) {
    second.kill();
  }
  if (first && isRunning(first)) {
    first.kill();
  }
  await waitForExit(second, 3000);
  await waitForExit(first, 5000);
  try {
    rmSync(userData, { recursive: true, force: true });
  } catch {
    // 临时 userData 清理失败不影响 smoke 结果输出。
  }
  writeFileSync(reportPath, `${JSON.stringify(result, null, 2)}\n`, "utf8");
  console.log(JSON.stringify({ ok: result.ok, reportPath, checks: result.checks, errors: result.errors }, null, 2));
}
