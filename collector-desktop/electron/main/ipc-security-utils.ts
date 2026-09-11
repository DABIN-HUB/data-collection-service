export interface WebFrameLike {
  parent: WebFrameLike | null;
  top: WebFrameLike | null;
  url: string;
}

export interface IpcMainInvokeEventLike {
  sender: {
    id: number;
  };
  senderFrame: WebFrameLike | null;
}

export function assertTrustedIpcSender(
  event: IpcMainInvokeEventLike,
  trustedWebContentsId: number | undefined,
  isTrustedRendererUrl: (url: string) => boolean
): void {
  if (trustedWebContentsId === undefined || event.sender.id !== trustedWebContentsId) {
    throw new Error("拒绝来自非当前窗口的 IPC 请求");
  }

  const frame = event.senderFrame;
  if (!frame) {
    throw new Error("拒绝缺少 frame 信息的 IPC 请求");
  }
  if (frame.parent !== null || frame.top !== frame) {
    throw new Error("拒绝来自非主 frame 的 IPC 请求");
  }
  if (!isTrustedRendererUrl(frame.url)) {
    throw new Error("拒绝来自非可信 Renderer URL 的 IPC 请求");
  }
}
