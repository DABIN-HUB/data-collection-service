# 第三方依赖许可证说明

本项目自有代码基于 [MIT License](./LICENSE) 授权。

本文件只用于说明第三方依赖的许可边界：项目引用的 jar 包、前端依赖、构建工具、运行时组件和其他第三方材料不属于本项目 MIT 授权范围，分别遵循其原作者或权利人的许可证。

## 当前已识别的重点依赖许可证

下面清单基于当前 `pom.xml`、`collector-desktop/package.json`、`collector-desktop/package-lock.json` 以及本地 Maven 仓库中可读取的 POM 信息整理。完整发布前应使用许可证扫描工具重新生成正式清单。

| 依赖 | 当前识别许可证 | 备注 |
| --- | --- | --- |
| `org.openmuc:j60870:1.7.2` | GNU General Public License | IEC 60870 相关依赖。若随二进制包、Docker 镜像或安装包一起分发，需要单独评估 GPL 合规义务。 |
| `com.fazecast:jSerialComm:2.11.2` | GNU Lesser GPL v3 / Apache License 2.0 | POM 声明为双许可证，使用和分发时应按所选许可证满足对应条件。 |
| `com.digitalpetri.modbus:modbus-tcp:2.1.3` | Eclipse Public License 2.0 | 第三方 Modbus TCP 依赖。 |
| `com.digitalpetri.modbus:modbus-serial:2.1.3` | Eclipse Public License 2.0 | 第三方 Modbus Serial 依赖。 |
| `org.eclipse.californium:californium-core:3.11.0` | Eclipse Public License 2.0 / Eclipse Distribution License 1.0 | CoAP 相关依赖。 |
| `org.eclipse.milo:milo-sdk-core:1.0.8` | Eclipse Public License 2.0 | OPC UA 相关依赖。 |
| `org.eclipse.milo:milo-sdk-client:1.0.8` | Eclipse Public License 2.0 | OPC UA 相关依赖。 |
| `org.eclipse.milo:milo-sdk-server:1.0.8` | Eclipse Public License 2.0 | OPC UA 相关依赖。 |
| `org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5` | Eclipse Public License 2.0 | MQTT v5 客户端依赖。 |
| `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` | Eclipse Public License 2.0 | MQTT v3 客户端依赖。 |
| `org.springframework.boot:*`、`org.apache.*`、`com.fasterxml.*` 等 | Apache License 2.0 或各自 POM 声明许可证 | 以实际依赖 POM 和发布包内许可证文件为准。 |
| `collector-desktop` 前端依赖 | MIT、Apache License 2.0、BSD 等 | 以 `collector-desktop/package-lock.json` 和各 npm 包许可证为准。 |

## 发布和分发要求

对外分发源码、可执行 jar、Docker 镜像、桌面安装包或其他二进制产物时，请至少保留以下文件：

- `LICENSE`
- `THIRD-PARTY-NOTICES.md`
- 第三方依赖自带的许可证、版权声明和 NOTICE 文件

如果发布包内包含 GPL、LGPL、EPL 或其他带有传递、源码提供、修改公开、通知保留等义务的组件，应在发布前单独完成合规评估。MIT 授权只覆盖本项目自有代码，不改变第三方组件的许可证义务。
