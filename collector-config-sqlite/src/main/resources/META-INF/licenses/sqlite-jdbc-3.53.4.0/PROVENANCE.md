# sqlite-jdbc 3.53.4.0 随附声明与工程边界

本目录随 wangbin 配置模块进入 JAR，补齐精确上游构件没有打包的 NOTICE/ISC 声明。不改变第三方许可证，也不宣称整个产品属于 MIT。

- 精确构件：org.xerial:sqlite-jdbc:3.53.4.0，12,002,370 bytes。
- SHA-256：bcb1f51e36f940867e83342f9efbf5968ac44a6bef4d397bb4af7b17b45cd2fb。
- 官方 release 2026-08-26 发布，非 draft、非 prerelease；内含 SQLite 3.53.4。
- LICENSE：Xerial Apache-2.0 原文；LICENSE.zentus：2006 David Crawshaw BSD-2-Clause 原文。
- NOTICE：精确 tag 原文（包括其历史 NestedVM 表述）；保留声明不等于认定当前二进制包含该历史组件。
- NativeDB-ISC-NOTICE.txt：精确 NativeDB.c 中 2007 David Crawshaw 的 ISC 声明。
- SQLite 核心采用公有领域奉献；不能把该声明扩张到整个 Java/JNI/扩展项目，也不应误标为 SPDX Unlicense。
- 包内 org.sqlite.date 有 Apache Commons 派生代码，遵循 Apache-2.0/ASF 署名，不是新增 Maven 必需依赖。

## 仍需发布审核

extension-functions.c 的 Liam Healy/relicoder 历史未发现独立许可授予正文，仅有仓库 Apache-2.0 总体许可信号。若商业发行要求逐作者权属闭环，应向上游或法务确认，不以本目录代替法律审核。源码历史署名保留在上游文件。

本阶段未做 GPG 身份验签或可复现构建证明。SHA 与官方摘要相同只证明取得构件身份，不是法律或供应链签名背书。SLF4J 是上游 optional；Graal nativeimage 是 provided，普通 JVM 消费不应因此额外打包它们；正式安装包仍须审核实际解析依赖和所有随附声明。

## 精确公开来源

- https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0
- https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar
- https://github.com/xerial/sqlite-jdbc/releases/download/3.53.4.0/checksums_sha256.txt
- https://raw.githubusercontent.com/xerial/sqlite-jdbc/3.53.4.0/LICENSE
- https://raw.githubusercontent.com/xerial/sqlite-jdbc/3.53.4.0/LICENSE.zentus
- https://raw.githubusercontent.com/xerial/sqlite-jdbc/3.53.4.0/NOTICE
- https://raw.githubusercontent.com/xerial/sqlite-jdbc/3.53.4.0/src/main/java/org/sqlite/core/NativeDB.c
- https://raw.githubusercontent.com/xerial/sqlite-jdbc/3.53.4.0/src/main/ext/extension-functions.c
- https://www.sqlite.org/copyright.html

本说明是工程核验记录，不构成法律意见。
