# HTTP

## 实现类

- `core/collector/protocol/http/HttpCollector`

## 实现方式

- 已接入 HTTP 连接适配器。
- 目前采集读写逻辑以占位实现为主（示例值/日志行为）。

## 地址与点位配置

- 设备连接可配置 `url`，或由 `host+port` 自动组装。
- 点位地址含义需按业务协议自行扩展。

## 使用方式

1. 设备 `protocolType` 设为 `HTTP`。
2. 用于联调可以；用于生产需先补齐真实请求解析逻辑。
