// 从 WangbinPhase3ConfigHttpSqliteTest 隔离 SQLite 的 HTTP 响应原文记录。
// 五个结果来自 127.0.0.1 随机端口的真实请求；UNCONFIRMED 来自同一后端异常处理器的 MockMvc 请求。
// 用例仅替换请求设备身份（a -> 各用例的设备 ID），保留真实 ApiResult 字段及可缺失的版本字段。
const receipts = {
  normal: {"code":200,"status":"success","message":"本地临时设备已保存","data":{"deviceId":"a","configSource":"local","temporaryConfig":true,"started":false,"saved":true,"changed":true,"configVersion":1793253989808,"persistedRevision":1,"startRequested":false,"startStatus":"NOT_REQUESTED","pointCount":1},"timestamp":1793253989810,"success":true},
  applyFailed: {"code":200,"status":"success","message":"配置已提交，但变更应用失败，请刷新核对，不要重复提交","machineCode":"CONFIG_COMMITTED_APPLY_FAILED","data":{"reconciled":false,"deviceIds":["a"],"reloadRequired":true,"committed":true,"dimension":"RUNTIME_APPLICATION"},"timestamp":1793253990550,"success":true},
  conflict: {"code":409,"status":"error","message":"设备持久修订已变化，请重新加载后核对修改","machineCode":"CONFIG_PERSISTENCE_CONFLICT","data":{"reconciled":false,"committed":false,"currentRevision":2,"deviceId":"a","dimension":"PERSISTENCE_REVISION","expectedRevision":1,"reloadRequired":true},"timestamp":1793253991175,"success":false},
  reconciled: {"code":200,"status":"success","message":"配置已提交且已完成权威对账，请重新加载核对，不要重复提交","machineCode":"CONFIG_COMMITTED_RECONCILED","data":{"reconciled":true,"deviceIds":["a"],"reloadRequired":true,"committed":true,"dimension":"RUNTIME_APPLICATION"},"timestamp":1793253992025,"success":true},
  recoveryRequired: {"code":200,"status":"success","message":"配置已提交，但运行配置需要恢复，请刷新核对，不要重复提交","machineCode":"CONFIG_COMMITTED_RECOVERY_REQUIRED","data":{"reconciled":false,"deviceIds":["a"],"reloadRequired":true,"committed":true,"dimension":"RUNTIME_APPLICATION"},"timestamp":1793253992666,"success":true},
  unconfirmed: {"code":503,"status":"error","message":"数据库提交结果待核对，请勿重复提交","machineCode":"CONFIG_COMMIT_UNCONFIRMED","data":{"reloadRequired":true,"reconciled":false,"committed":"UNKNOWN","deviceIds":["a"]},"timestamp":1793254001344,"success":false}
};

export function capturedPhase3Receipt(kind: keyof typeof receipts, deviceId: string) {
  const receipt = structuredClone(receipts[kind]);
  const data = receipt.data as { deviceId?: string; deviceIds?: string[] };
  if (data.deviceId !== undefined) data.deviceId = deviceId;
  if (data.deviceIds !== undefined) data.deviceIds = [deviceId];
  return receipt;
}
