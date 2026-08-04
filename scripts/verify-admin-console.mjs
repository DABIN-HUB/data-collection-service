import fs from "node:fs";

const indexPath = "src/main/resources/static/admin/index.html";
const appPath = "src/main/resources/static/admin/app.js";
const apiClientPath = "src/main/resources/static/admin/api-client.js";
const designLabPath = "src/main/resources/static/admin/design-lab.js";
const modaoPath = "src/main/resources/static/admin/modao-console.js";
const modaoStylePath = "src/main/resources/static/admin/modao-console.css";
const exactStylePath = "src/main/resources/static/admin/modao-exact.css";
const index = fs.readFileSync(indexPath, "utf8");
const app = fs.readFileSync(appPath, "utf8");
const apiClient = fs.readFileSync(apiClientPath, "utf8");
const designLab = fs.readFileSync(designLabPath, "utf8");
const modao = fs.readFileSync(modaoPath, "utf8");
const modaoStyle = fs.readFileSync(modaoStylePath, "utf8");
const exactStyle = fs.readFileSync(exactStylePath, "utf8");

const requiredIndexFragments = [
  'id="localDevicePanel"',
  'id="realtimeRows"',
  'id="alarm"',
  'id="cloud"',
  'id="log"',
  'id="network"',
  'id="alarmAckDialog"',
  'id="nodeIdentity"',
  'id="systemStatus"',
  'src="./icons/factory.svg"',
  'value="TRACE"',
  'src="./api-client.js',
  'src="./modao-console.js',
  'href="./modao-console.css',
  'href="./modao-exact.css',
  'lang="zh-CN"'
];
const requiredAppFragments = [
  "/api/device/runtime",
  "function buildUnifiedRuntimeStatusMap",
  'case "DEGRADED":',
  'case "FAILED":',
  "openDeviceRoute"
];
const requiredApiClientFragments = [
  "async function callApi",
  "fetch(`${resolveContextPath()}${path}`",
  "window.CollectorApi = Object.freeze"
];
const requiredModaoFragments = [
  'alarm: { title: "告警总览"',
  'cloud: { title: "云平台配置"',
  'log: { title: "日志"',
  'network: { title: "网络检测"',
  "/api/ops/logs",
  "/api/ops/network/diagnose",
  "/api/ops/alarms/",
  "window.openDeviceRoute"
];
const requiredStyleFragments = [
  "--modao-bg: #0d1b2a",
  'body[data-console-route="realtime"]',
  ".modao-log-view",
  ".local-device-panel:not(.hidden)"
];
const requiredExactStyleFragments = [
  "--exact-bg: #0d1b2a",
  ".node-status-bar",
  ".topology-flow",
  ".resource-dashboard",
  ".exact-page"
];

const missing = [];
verifyFragments(indexPath, index, requiredIndexFragments, missing);
verifyFragments(appPath, app, requiredAppFragments, missing);
verifyFragments(apiClientPath, apiClient, requiredApiClientFragments, missing);
verifyFragments(modaoPath, modao, requiredModaoFragments, missing);
verifyFragments(modaoStylePath, modaoStyle, requiredStyleFragments, missing);
verifyFragments(exactStylePath, exactStyle, requiredExactStyleFragments, missing);

const prohibitedBusinessData = [
  "previewMode",
  "previewApi",
  "previewDataset",
  "edge-node-01",
  "Gateway-V3",
  "water-pump-02"
];
const productionEntryFiles = [
  [appPath, app],
  [modaoPath, modao]
];
for (const [path, content] of productionEntryFiles) {
  if (content.includes("fetch(")) {
    missing.push(`${path}: 生产入口必须通过 api-client.js 调用接口，禁止直接 fetch(`);
  }
}
for (const fragment of prohibitedBusinessData) {
  if ([index, app, designLab].some((content) => content.includes(fragment))) {
    missing.push(`控制台仍包含模拟入口或静态业务数据: ${fragment}`);
  }
}

if ([index, app, modao].some((content) => content.includes("Required field"))) {
  missing.push("控制台仍包含需要删除的 Required field 提示");
}
if (missing.length > 0) {
  throw new Error(`控制台契约检查失败:\n${missing.join("\n")}`);
}

process.stdout.write("控制台契约检查通过\n");

function verifyFragments(path, content, fragments, missing) {
  for (const fragment of fragments) {
    if (!content.includes(fragment)) {
      missing.push(`${path}: ${fragment}`);
    }
  }
}
