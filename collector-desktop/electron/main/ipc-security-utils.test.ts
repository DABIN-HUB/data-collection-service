import { describe, expect, it } from "vitest";

import { assertTrustedIpcSender, type IpcMainInvokeEventLike, type WebFrameLike } from "./ipc-security-utils.js";

function frame(url = "file:///app/dist/renderer/index.html"): WebFrameLike {
  const current = {
    parent: null,
    top: null,
    url
  } as WebFrameLike;
  current.top = current;
  return current;
}

function event(senderId: number, senderFrame: WebFrameLike | null): IpcMainInvokeEventLike {
  return {
    sender: { id: senderId },
    senderFrame
  };
}

describe("ipc-security-utils", () => {
  it("允许当前窗口可信主 frame", () => {
    expect(() => assertTrustedIpcSender(event(7, frame()), 7, (url) => url.startsWith("file:///app/"))).not.toThrow();
  });

  it("拒绝非当前窗口 sender", () => {
    expect(() => assertTrustedIpcSender(event(8, frame()), 7, () => true)).toThrow("非当前窗口");
  });

  it("拒绝缺少 frame 信息的 IPC", () => {
    expect(() => assertTrustedIpcSender(event(7, null), 7, () => true)).toThrow("缺少 frame");
  });

  it("拒绝子 frame IPC", () => {
    const top = frame();
    const child: WebFrameLike = {
      parent: top,
      top,
      url: "file:///app/dist/renderer/index.html"
    };
    expect(() => assertTrustedIpcSender(event(7, child), 7, () => true)).toThrow("非主 frame");
  });

  it("拒绝非可信 renderer URL", () => {
    expect(() => assertTrustedIpcSender(event(7, frame("file:///C:/Windows/win.ini")), 7, () => false)).toThrow("非可信 Renderer URL");
  });
});
