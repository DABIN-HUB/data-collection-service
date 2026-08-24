import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

import { describe, expect, it } from "vitest";

import { copyWebConsoleBuild, resolveWebConsolePaths } from "./web-console-utils.mjs";

describe("web-console-utils", () => {
  it("解析后端内置网页控制台路径", () => {
    const paths = resolveWebConsolePaths("F:/repo");

    expect(paths.rendererDir.replace(/\\/g, "/")).toBe("F:/repo/collector-desktop/dist/renderer");
    expect(paths.staticDesktopDir.replace(/\\/g, "/")).toBe(
      "F:/repo/collector-boot/src/main/resources/static/desktop"
    );
    expect(paths.publicPath).toBe("/desktop/index.html");
  });

  it("复制 Vue 构建产物到后端 static/desktop 并清理旧文件", async () => {
    const repoRoot = await mkdtemp(join(tmpdir(), "collector-web-console-"));
    const paths = resolveWebConsolePaths(repoRoot);
    await writeFile(join(paths.rendererDir, "index.html"), "<main>desktop</main>", { encoding: "utf8", flag: "w" }).catch(async () => {
      await import("node:fs/promises").then(({ mkdir }) => mkdir(paths.rendererDir, { recursive: true }));
      await writeFile(join(paths.rendererDir, "index.html"), "<main>desktop</main>", "utf8");
    });
    await import("node:fs/promises").then(async ({ mkdir }) => {
      await mkdir(join(paths.rendererDir, "assets"), { recursive: true });
      await mkdir(paths.staticDesktopDir, { recursive: true });
    });
    await writeFile(join(paths.rendererDir, "assets", "app.js"), "console.log('ok')", "utf8");
    await writeFile(join(paths.staticDesktopDir, "old.js"), "old", "utf8");

    const result = await copyWebConsoleBuild(repoRoot);

    await expect(readFile(join(paths.staticDesktopDir, "index.html"), "utf8")).resolves.toContain("desktop");
    await expect(readFile(join(paths.staticDesktopDir, "assets", "app.js"), "utf8")).resolves.toContain("ok");
    await expect(readFile(join(paths.staticDesktopDir, "old.js"), "utf8")).rejects.toThrow();
    expect(result.copiedFiles).toBe(2);
  });
});
