import fs from "node:fs";
import path from "node:path";

const root = "src/main/resources";
const sensitiveKey = /^\s*(api-token|password|secret|device-secret):\s*(.*?)\s*$/i;
const allowedValue = /^(|""|''|\$\{[^}]+})$/;
const findings = [];

function scan(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      scan(target);
      continue;
    }
    if (!/\.ya?ml$/i.test(entry.name)) {
      continue;
    }
    fs.readFileSync(target, "utf8").split(/\r?\n/).forEach((line, index) => {
      const match = line.match(sensitiveKey);
      if (match && !allowedValue.test(match[2])) {
        findings.push(`${target}:${index + 1}`);
      }
    });
  }
}

scan(root);
if (findings.length > 0) {
  throw new Error(`发现疑似明文秘密:\n${findings.join("\n")}`);
}
process.stdout.write("生产配置秘密扫描通过\n");
