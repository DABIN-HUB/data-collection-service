import fs from "node:fs";

const indexPath = "src/main/resources/static/admin/index.html";
const appPath = "src/main/resources/static/admin/app.js";
const index = fs.readFileSync(indexPath, "utf8");
const app = fs.readFileSync(appPath, "utf8");

const requiredIndexFragments = [
  'id="localDevicePanel"',
  'id="realtimeRows"',
  'lang="zh-CN"'
];
const requiredAppFragments = [
  '/api/device/runtime',
  'function buildUnifiedRuntimeStatusMap',
  'case "DEGRADED":',
  'case "FAILED":'
];

const missing = [];
for (const fragment of requiredIndexFragments) {
  if (!index.includes(fragment)) {
    missing.push(`${indexPath}: ${fragment}`);
  }
}
for (const fragment of requiredAppFragments) {
  if (!app.includes(fragment)) {
    missing.push(`${appPath}: ${fragment}`);
  }
}
if (index.includes("Required field") || app.includes("Required field")) {
  missing.push("控制台仍包含需要删除的Required field提示");
}
if (missing.length > 0) {
  throw new Error(`控制台契约检查失败:\n${missing.join("\n")}`);
}

process.stdout.write("控制台契约检查通过\n");
