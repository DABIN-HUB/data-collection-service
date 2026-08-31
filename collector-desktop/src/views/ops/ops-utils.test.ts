import { describe, expect, it } from "vitest";

import { buildDiagnosticAdvice } from "./ops-utils";

describe("ops-utils", () => {
  it("根据诊断结果给出建议", () => {
    expect(buildDiagnosticAdvice({ cache: { status: "ERROR" }, devices: { status: "OK" } })).toContain("缓存模块异常");
    expect(buildDiagnosticAdvice({ health: { status: "UP" } })).toContain("暂无明显异常");
  });
});
