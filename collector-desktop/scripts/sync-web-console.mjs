#!/usr/bin/env node

import { copyWebConsoleBuild } from "./web-console-utils.mjs";

try {
  const result = await copyWebConsoleBuild();
  console.log(`新版网页控制台已同步到 ${result.staticDesktopDir}`);
  console.log(`访问路径：${result.publicPath}`);
  console.log(`同步文件数：${result.copiedFiles}`);
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}
