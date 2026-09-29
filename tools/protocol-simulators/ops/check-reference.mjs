import { readFile } from "node:fs/promises";
import { dirname, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const listOnly = process.argv[2] === "--list";
const input = listOnly ? process.argv[3] : process.argv[2];
const intervalMs = 6000;

async function main() {
  if (!input || input.startsWith("--")) {
    throw new Error("用法：node ops/check-reference.mjs [--list] mqtt/collector-import.json");
  }
  const file = resolve(root, input);
  const pathFromRoot = relative(root, file);
  if (pathFromRoot.startsWith(`..${sep}`) || pathFromRoot === ".." || file === root || !file.endsWith("-import.json")) {
    throw new Error("只接受本目录内的 *-import.json 配置文件");
  }
  const bundles = JSON.parse(await readFile(file, "utf8")).bundles;
  if (!Array.isArray(bundles) || !bundles.length) {
    throw new Error("导入文件没有设备 bundles");
  }
  const devices = bundles.map((bundle) => ({
    id: bundle.device?.deviceId,
    points: bundle.points?.map((point) => ({ id: point.pointId, code: point.pointCode }))
  }));
  if (devices.some((device) => !device.id || !device.points?.length || device.points.some((point) => !point.id || !point.code))) {
    throw new Error("设备 ID 或点位 ID/编码缺失");
  }
  if (listOnly) {
    for (const device of devices) {
      console.log(`${device.id}: ${device.points.length} 个点位 (${device.points.map((point) => point.code).join(", ")})`);
    }
    return;
  }
  const token = process.env.COLLECTOR_TOKEN;
  if (!token) {
    throw new Error("需要从环境变量 COLLECTOR_TOKEN 提供凭据；凭据不会打印或写入文件");
  }
  const base = new URL(process.env.COLLECTOR_BASE_URL || "http://127.0.0.1:9090/collector");
  if (base.protocol !== "http:" || !["127.0.0.1", "localhost", "[::1]"].includes(base.hostname) || base.username || base.password || base.search || base.hash) {
    throw new Error("仅允许无凭据的本机 HTTP 服务地址");
  }
  let failed = false;
  for (const device of devices) {
    const endpoint = new URL(`${base.pathname.replace(/\/$/, "")}/api/data/device/${encodeURIComponent(device.id)}`, base.origin);
    const first = await fetchSnapshot(endpoint, token);
    await new Promise((done) => setTimeout(done, intervalMs));
    const second = await fetchSnapshot(endpoint, token);
    const reasons = [];
    const firstData = first.data || {};
    const secondData = second.data || {};
    for (const point of device.points) {
      const current = secondData[point.id];
      const prior = firstData[point.id];
      if (!current || current.pointId !== point.id || current.pointCode !== point.code) {
        reasons.push(`${point.code}: 点位身份不符或未返回`);
        continue;
      }
      if (current.value === null || current.value === undefined || current.qualityAvailable !== true || current.qualityAcceptable !== true || current.stale !== false || current.realtimeStatus !== "GOOD") {
        reasons.push(`${point.code}: 值/质量/状态未达到有效采集条件`);
        continue;
      }
      const currentAt = Number(current.lastValueAt || current.lastUpdateTime);
      const priorAt = Number(prior?.lastValueAt || prior?.lastUpdateTime);
      if (!Number.isFinite(currentAt) || !Number.isFinite(priorAt) || currentAt <= priorAt || Math.abs(Date.now() - currentAt) > 60000) {
        reasons.push(`${point.code}: 采集时间未推进或已过期`);
      }
    }
    if (first.status !== "success" || second.status !== "success" || first.deviceId !== device.id || second.deviceId !== device.id) {
      reasons.push("设备级响应未成功或设备身份不符");
    }
    if (reasons.length) {
      failed = true;
      console.error(`${device.id}: 未通过 API 采集检查：${reasons.join("；")}`);
    } else {
      console.log(`${device.id}: API 采集检查通过 (${device.points.length} 点)；仍需对照模拟器请求/订阅日志和期望值，不能单凭此结果声明端到端 PASS`);
    }
  }
  if (failed) process.exitCode = 1;
}

async function fetchSnapshot(url, token) {
  const response = await fetch(url, {
    headers: { "X-Collector-Token": token },
    signal: AbortSignal.timeout(10000)
  });
  if (!response.ok) {
    throw new Error(`采集接口 HTTP ${response.status}，未获取有效快照`);
  }
  const body = await response.json();
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new Error("采集接口不是原始设备 DTO");
  }
  return body;
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : "采集检查失败");
  process.exitCode = 1;
});
