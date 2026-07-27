# CUSTOM_TCP

## 实现类

- `core/collector/protocol/custom/CustomProtocolCollector`

## 实现方式

- `CUSTOM_TCP` 和 `CUSTOM_UDP` 使用独立连接适配器，不再互为别名。
- 支持 `LENGTH_FIELD`、`FIXED_LENGTH`、`DELIMITER` 三种 TCP 帧边界；UDP 按单个数据报处理。
- 支持 HEX、TEXT、BASE64 请求模板，模板只允许固定占位符，不执行脚本。
- 支持字节偏移、位偏移和 JSON 路径值解析，以及请求响应式读写。
- 不支持原生订阅，能力状态保持 `EXPERIMENTAL`。

## 连接字段整理（createFieldConfig 写法）

主要连接字段：

- 目标：`host`、`port`、`readTimeout`、`bufferSize`
- 模板：`readRequestTemplate`、`writeRequestTemplate`、`requestEncoding`、`writeRequestEncoding`
- 写响应：`writeExpectResponse`、`writeSuccessHex`
- TCP 帧：`frameMode`、`fixedFrameLength`、`delimiterHex`
- 长度字段：`lengthFieldOffset`、`lengthFieldLength`、`lengthAdjustment`、`initialBytesToStrip`、`lengthByteOrder`
- 文本：`charset`

点位字段：

- `additionalConfig.requestTemplate`：覆盖连接级读取模板。
- `additionalConfig.writeRequestTemplate`：覆盖连接级写入模板。
- `additionalConfig.responseEncoding`：`RAW`、`TEXT`、`JSON`。
- `address`：`BYTE:offset:length`、`BIT:byteOffset:bitIndex`、`JSON:path.to.value` 或数字字节偏移。
- `additionalConfig.byteOrder`：多字节值的字节序。

## 使用方式

1. TCP 设备使用 `CUSTOM_TCP`，UDP 设备使用 `CUSTOM_UDP`。
2. 配置明确的请求模板、帧边界和点位地址，不允许把可执行脚本放入配置。
3. 使用 `RAW_EXCHANGE` 命令可做原始请求响应联调。
4. 上线前必须针对目标设备验证粘包、半包、超时、异常帧和断线重连；当前能力不能替代厂商协议认证。
