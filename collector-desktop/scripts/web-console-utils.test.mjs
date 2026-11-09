import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";

import { describe, expect, it } from "vitest";

import { copyWebConsoleBuild, resolveWebConsolePaths } from "./web-console-utils.mjs";

describe("web-console-utils", () => {
  it("解析后端内置网页控制台路径", () => {
    const repoRoot = join(tmpdir(), "collector-web-console-path-contract");
    const expectedRoot = resolve(repoRoot);
    const paths = resolveWebConsolePaths(repoRoot);

    expect(paths.repositoryRoot).toBe(expectedRoot);
    expect(paths.rendererDir).toBe(join(expectedRoot, "collector-desktop", "dist", "renderer"));
    expect(paths.staticDesktopDir).toBe(
      join(expectedRoot, "collector-boot", "src", "main", "resources", "static", "desktop")
    );
    expect(paths.publicPath).toBe("/desktop/index.html");
  });

  it("复制 Vue 构建产物到后端 static/desktop 并清理旧文件", async () => {
    const repoRoot = await mkdtemp(join(tmpdir(), "collector-web-console-"));
    try {
      const paths = resolveWebConsolePaths(repoRoot);
      await mkdir(paths.rendererDir, { recursive: true });
      await writeFile(join(paths.rendererDir, "index.html"), "<main>desktop</main>", "utf8");
      await mkdir(join(paths.rendererDir, "assets"), { recursive: true });
      await mkdir(paths.staticDesktopDir, { recursive: true });
      await writeFile(join(paths.rendererDir, "assets", "app.js"), "console.log('ok')", "utf8");
      await writeFile(join(paths.staticDesktopDir, "old.js"), "old", "utf8");

      const result = await copyWebConsoleBuild(repoRoot);

      await expect(readFile(join(paths.staticDesktopDir, "index.html"), "utf8")).resolves.toContain("desktop");
      await expect(readFile(join(paths.staticDesktopDir, "assets", "app.js"), "utf8")).resolves.toContain("ok");
      await expect(readFile(join(paths.staticDesktopDir, "old.js"), "utf8")).rejects.toThrow();
      expect(result.copiedFiles).toBe(2);
      expect(result.publicPath).toBe("/desktop/index.html");
    } finally {
      await rm(repoRoot, { recursive: true, force: true });
    }
  });
});
