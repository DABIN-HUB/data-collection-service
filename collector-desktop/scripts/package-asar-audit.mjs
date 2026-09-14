import { existsSync, statSync } from "node:fs";
import { join } from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const asar = require("@electron/asar");

const root = join(import.meta.dirname, "..");
const appAsarPath = process.env.COLLECTOR_DESKTOP_ASAR || join(root, "release", "win-unpacked", "resources", "app.asar");

const forbiddenPatterns = [
  { name: "source maps", test: (entry) => entry.endsWith(".map") },
  { name: "compiled tests", test: (entry) => /(^|[/\\])(electron|src)[/\\].*\.(test|spec)\.js$/i.test(entry) },
  { name: "env files", test: (entry) => /(^|[/\\])\.env($|[.])/i.test(entry) },
  { name: "git metadata", test: (entry) => /(^|[/\\])\.git($|[/\\])/i.test(entry) },
  { name: "backend target", test: (entry) => /(^|[/\\])target($|[/\\])/i.test(entry) },
  { name: "runtime logs", test: (entry) => /(^|[/\\])logs($|[/\\])/i.test(entry) },
  { name: "desktop config", test: (entry) => /collector-desktop-config\.json$/i.test(entry) },
  { name: "desktop credentials", test: (entry) => /collector-desktop-credentials\.json$/i.test(entry) },
  { name: "private signing material", test: (entry) => /\.(pfx|p12|pem|key)$/i.test(entry) }
];

function assertFile(path, label) {
  if (!existsSync(path) || statSync(path).size <= 0) {
    throw new Error(`${label} missing or empty: ${path}`);
  }
}

assertFile(appAsarPath, "app.asar");
const entries = asar.listPackage(appAsarPath).map((entry) => entry.replace(/^[\\/]/, "").replaceAll("\\", "/"));
const required = [
  "dist/electron/main/main.js",
  "dist/electron/preload/index.cjs",
  "dist/renderer/index.html",
  "package.json"
];
for (const entry of required) {
  if (!entries.includes(entry)) {
    throw new Error(`required app.asar entry missing: ${entry}`);
  }
}

const violations = [];
for (const entry of entries) {
  for (const pattern of forbiddenPatterns) {
    if (pattern.test(entry)) {
      violations.push({ kind: pattern.name, entry });
    }
  }
}

if (violations.length) {
  console.error(JSON.stringify({ ok: false, appAsarPath, violations: violations.slice(0, 50), violationCount: violations.length }, null, 2));
  process.exit(1);
}

console.log(JSON.stringify({ ok: true, appAsarPath, entryCount: entries.length, mapCount: 0, required }, null, 2));
