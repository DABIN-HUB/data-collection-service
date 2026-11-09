# WangbinCollector Linux 0.1.0

本包包含 Compose 文件和脚本，不包含源码、JRE、Electron、真实凭据或用户数据。需要 Ubuntu 22.04/24.04 x86_64、Docker Engine、Compose 插件、`curl`、`sha256sum` 和 `openssl`；首次启动需联网拉取 GHCR 和 Docker Hub 镜像。

```bash
tar -xzf WangbinCollector-0.1.0-linux-amd64.tar.gz
cd WangbinCollector
bash deploy.sh
```

默认只在 `127.0.0.1:9090` 监听；网页地址为 `http://127.0.0.1:9090/collector/desktop/index.html`。远程访问请自行配置鉴权及 HTTPS 反向代理，不要直接暴露 HTTP。首次运行在 `config/ops-token` 生成随机令牌（`X-Collector-Token`），该文件与 `config/application-release.yml` 权限均为 0600；仅在可信终端读取令牌，切勿上传、贴入工单或提交 Git。内置开发令牌在发行配置中被置为空描述，不能登录。

发行目录 `data/` 由当前非 root 用户拥有，权限 0700；容器使用相同 UID/GID。部署时从固定摘要的镜像生成 `config/passwd` 账号映射，使 Java 可按实际 UID 核验 SQLite 所有者；该文件不含令牌，权限 0644，已有映射不自动覆盖。首次安装后应有 `data/data/collector.db` 和 `data/data.wangbin-installation/`；内层目录必须由应用首次创建，禁止提前创建。已有数据库缺失时保留证据并停止，不会自动清库或重建。`stop.sh` 仅停止本项目的 Compose 服务；Redis 使用独立持久化卷、不发布宿主机 Redis 端口。更新前请备份停止状态下的数据目录与 Redis 卷；不要在数据库正在写入时直接复制 SQLite 文件作为一致备份。

```bash
bash logs.sh
bash stop.sh
```

Docker Compose 镜像由发布工作流固定到 GHCR 摘要；仅正式 Release 包可部署。构建验证上传的候选包携带无法部署的镜像占位符，用于检查文件结构，不是可安装发行版。普通 `/health` 可以在没有运行设备时显示 `DOWN`，启动就绪应查看 `/collector/actuator/health/readiness` 与网页入口；设备仍需手动 START。TDengine 默认关闭。

部署出错时不要清理 `data/`、Redis 卷或安装保护目录。需要修改运维配置时停止容器、备份文件并确保权限维持 0600；更换账号前先规划数据库目录所有权，不要递归 chown 旧数据。此包不代表商业发布批准、硬件全面认证或 Redis 构件再分发许可。
