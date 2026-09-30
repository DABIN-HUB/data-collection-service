import { existsSync, readdirSync, readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const sourceFileExtensions = new Set([".css", ".mjs", ".ts", ".vue"]);
const legacyHostClass = "legacy" + "-console";
const legacyCssFile = "../src/styles/" + legacyHostClass + ".css";
const workbenchCssFile = "../src/styles/" + "workbench" + ".css";
const oldBodyAnchor = "body." + "modao" + "-exact ." + legacyHostClass;
const oldModaoClass = "modao" + "-exact";

function read(relativePath) {
  return readFileSync(new URL(relativePath, import.meta.url), "utf8");
}

function collectSourceFiles(directoryUrl) {
  return readdirSync(directoryUrl, { withFileTypes: true }).flatMap((entry) => {
    const entryUrl = new URL(`${entry.name}${entry.isDirectory() ? "/" : ""}`, directoryUrl);
    if (entry.isDirectory()) {
      return collectSourceFiles(entryUrl);
    }
    const extension = entry.name.slice(entry.name.lastIndexOf("."));
    return sourceFileExtensions.has(extension) ? [entryUrl] : [];
  });
}

describe("CSS 架构边界", () => {
  it("历史大样式文件应彻底移除", () => {
    expect(existsSync(new URL(legacyCssFile, import.meta.url))).toBe(false);
    expect(existsSync(new URL(workbenchCssFile, import.meta.url))).toBe(false);
  });

  it("入口不再导入历史 CSS，且按稳定顺序导入项目样式", () => {
    const mainTs = read("../src/main.ts");
    expect(mainTs).toContain('import "element-plus/dist/index.css";');
    expect(mainTs).toContain('import "@/styles/tokens.css";');
    expect(mainTs).toContain('import "@/styles/global.css";');
    expect(mainTs).toContain('import "@/styles/base.css";');
    expect(mainTs).toContain('import "@/styles/element-plus.css";');
    expect(mainTs).toContain('import "@/styles/utilities.css";');
    expect(mainTs).not.toContain(legacyHostClass + ".css");
    expect(mainTs).not.toContain("workbench" + ".css");
  });

  it("AppShell 不再使用 legacy-console / modao-exact / theme-anchor 作为样式锚点", () => {
    const shell = read("../src/app/AppShell.vue");
    expect(shell).toContain('class="shell app-shell"');
    expect(shell).not.toContain(legacyHostClass);
    expect(shell).not.toContain(oldModaoClass);
    expect(shell).not.toContain("theme-anchor");
    expect(shell).not.toContain("document.body.classList.add");
  });

  it("源样式中不再出现旧 Legacy 高权重选择器", () => {
    const sources = collectSourceFiles(new URL("../src/", import.meta.url))
      .map((fileUrl) => readFileSync(fileUrl, "utf8"))
      .join("\n");
    expect(sources).not.toContain(oldBodyAnchor);
    expect(sources).not.toContain("." + legacyHostClass);
    expect(sources).not.toContain(oldModaoClass);
  });

  it("tokens compatibility 变量挂在 :root，而不是 body.modao-exact", () => {
    const tokens = read("../src/styles/tokens.css");
    expect(tokens).toContain("--console-bg: var(--app-color-bg)");
    expect(tokens).toContain("--exact-bg: var(--app-color-bg)");
    expect(tokens).not.toContain("body." + oldModaoClass);
  });
});
