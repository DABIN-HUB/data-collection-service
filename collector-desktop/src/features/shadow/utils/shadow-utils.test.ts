import { describe, expect, it } from "vitest";

import {
  buildShadowExportFilename,
  buildShadowExportPayload,
  compactJson,
  formatShadowTime,
  normalizeShadowHistoryRows,
  parseShadowJson,
  parseShadowJsonOrThrow,
  summarizeShadowState
} from "./shadow-utils";

describe("shadow-utils", () => {
  it("归一化影子历史响应", () => {
    expect(normalizeShadowHistoryRows({ data: { records: [{ version: 2, operation: "UPDATE" }] } })).toEqual([{ version: 2, operation: "UPDATE" }]);
    expect(normalizeShadowHistoryRows({ rows: [{ version: 3 }] })).toEqual([{ version: 3 }]);
    expect(normalizeShadowHistoryRows({ items: [{ version: 4 }] })).toEqual([{ version: 4 }]);
    expect(normalizeShadowHistoryRows({ history: [{ version: 5 }] })).toEqual([{ version: 5 }]);
    expect(normalizeShadowHistoryRows({ versions: [{ version: 6 }] })).toEqual([{ version: 6 }]);
    expect(normalizeShadowHistoryRows([{ version: 1 }])).toEqual([{ version: 1 }]);
  });

  it("汇总影子当前态、期望态和 delta 摘要", () => {
    expect(summarizeShadowState({ data: { desired: { a: 1 }, reported: { b: 2 } } }, { c: 3 }, { d: 4, e: 5 }, [{ version: 1 }, { version: 2 }])).toEqual({
      currentCount: 2,
      desiredCount: 1,
      deltaCount: 2,
      historyCount: 2,
      currentText: "2 项",
      desiredText: "1 项",
      deltaText: "2 项"
    });
  });

  it("保留影子 JSON 的宽松摘要解析和严格提交解析", () => {
    expect(parseShadowJson('{"desired":{}}')).toEqual({ desired: {} });
    expect(parseShadowJson("{")).toEqual({ raw: "{" });
    expect(parseShadowJsonOrThrow('{"desired":{"mode":"auto"}}', "desired JSON")).toEqual({ desired: { mode: "auto" } });
    expect(() => parseShadowJsonOrThrow("{", "desired JSON")).toThrow("desired JSON 格式错误");
  });

  it("格式化影子历史时间和摘要 JSON", () => {
    expect(formatShadowTime({ timestamp: "not-a-date" })).toBe("not-a-date");
    expect(formatShadowTime({})).toBe("-");
    expect(compactJson({ version: 1 })).toBe('{"version":1}');
  });

  it("构造影子导出 payload 和文件名", () => {
    expect(buildShadowExportPayload("dev-1", { reported: {} }, { desired: {} }, { delta: {} }, [], "2026-09-01T00:00:00.000Z")).toEqual({
      deviceId: "dev-1",
      generatedAt: "2026-09-01T00:00:00.000Z",
      current: { reported: {} },
      desired: { desired: {} },
      delta: { delta: {} },
      history: []
    });
    expect(buildShadowExportFilename("dev-1", "2026-09-01T00:00:00.000Z")).toBe("collector-shadow-dev-1-2026-09-01T00-00-00-000Z.json");
  });
});
