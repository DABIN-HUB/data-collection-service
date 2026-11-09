#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
umask 077

fail() { printf '部署中止：%s\n' "$*" >&2; exit 1; }
command -v docker >/dev/null || fail '缺少 Docker Engine'
docker compose version >/dev/null 2>&1 || fail '缺少 Docker Compose 插件'
command -v sha256sum >/dev/null || fail '缺少 sha256sum'
command -v openssl >/dev/null || fail '缺少 openssl'
command -v curl >/dev/null || fail '缺少 curl'
[[ "$(uname -m)" == x86_64 ]] || fail '只支持 x86_64'
[[ "$(id -u)" != 0 ]] || fail '不得以 root 执行；数据库由当前账号与容器同 UID 持有'
[[ -f VERSION && -f SHA256SUMS && -f compose.yaml ]] || fail '发行文件不完整'
[[ "$(< VERSION)" == '0.1.0' ]] || fail '发行版本不匹配'
sha256sum -c --status SHA256SUMS || fail '发行文件校验失败；不要使用损坏的压缩包'
[[ ! -L data && ! -L logs && ! -L config ]] || fail '发行目录不允许符号链接'
[[ ! -L config/application-release.yml && ! -L config/ops-token && ! -L config/passwd && ! -L .env ]] || fail '发行配置不允许符号链接'
[[ -d config && -f config/application-release.yml.example ]] || fail '发行配置模板缺失'
grep -Eq 'image: ghcr.io/.+@sha256:[a-f0-9]{64}' compose.yaml || fail '镜像没有固定 GHCR 摘要，候选包不能部署'
image_ref="$(sed -n 's/^app_image=//p' BUILD-MANIFEST)"
[[ "$image_ref" =~ ^ghcr\.io/dabin-hub/data-collection-service@sha256:[a-f0-9]{64}$ ]] || fail '构建清单中的镜像摘要无效'
grep -Fxq "    image: $image_ref" compose.yaml || fail 'Compose 镜像与构建清单不一致'

# 只准备外层绑定目录；内层数据目录和保护目录必须由 Java 安装契约排他创建。
if [[ ! -e data ]]; then mkdir -m 700 data; fi
[[ -d data && ! -L data ]] || fail 'SQLite 外层目录异常'
[[ "$(stat -c %u data)" == "$(id -u)" ]] || fail 'SQLite 外层目录所有者与运行账号不一致'
[[ "$(stat -c %a data)" == 700 ]] || fail 'SQLite 外层目录必须为 0700；不自动修改旧数据权限'
if [[ ! -f data/data/collector.db ]] && { [[ -e data/data || -e data/data.wangbin-installation || -e data/collector.db ]]; }; then
  fail '数据库缺失但存在安装证据，拒绝自动创建空库'
fi
if [[ ! -e logs ]]; then mkdir -m 700 logs; fi
[[ -d logs && ! -L logs && "$(stat -c %u logs)" == "$(id -u)" ]] || fail '日志目录所有者不匹配'

if [[ ! -e config/application-release.yml && ! -e config/ops-token ]]; then
  token="$(openssl rand -hex 32)" || fail '随机令牌生成失败'
  sed "s/__OPS_TOKEN__/$token/g" config/application-release.yml.example > config/application-release.yml
  printf '%s\n' "$token" > config/ops-token
  chmod 600 config/application-release.yml config/ops-token
  unset token
fi
[[ -f config/application-release.yml && -f config/ops-token && ! -L config/application-release.yml && ! -L config/ops-token ]] || fail '凭据不完整或路径异常，不自动重置'
[[ "$(stat -c %u config/application-release.yml)" == "$(id -u)" && "$(stat -c %a config/application-release.yml)" == 600 ]] || fail '发行配置权限必须为当前账号 0600'
[[ "$(stat -c %u config/ops-token)" == "$(id -u)" && "$(stat -c %a config/ops-token)" == 600 ]] || fail '令牌文件权限必须为当前账号 0600'
! grep -q '__OPS_TOKEN__' config/application-release.yml || fail '发行配置仍有占位符'

if [[ ! -e .env ]]; then
  printf 'COLLECTOR_UID=%s\nCOLLECTOR_GID=%s\nCOLLECTOR_PORT=9090\n' "$(id -u)" "$(id -g)" > .env
  chmod 600 .env
fi
[[ -f .env && ! -L .env && "$(stat -c %u .env)" == "$(id -u)" && "$(stat -c %a .env)" == 600 ]] || fail '.env 所有权或权限异常'
grep -Fxq "COLLECTOR_UID=$(id -u)" .env && grep -Fxq "COLLECTOR_GID=$(id -g)" .env || fail '.env 与当前运行账号不一致'
port="${COLLECTOR_PORT:-$(sed -n 's/^COLLECTOR_PORT=//p' .env)}"
[[ "$port" =~ ^[0-9]{1,5}$ ]] && (( port > 0 && port <= 65535 )) || fail '监听端口无效'
docker compose -p wangbincollector-010 config --quiet || fail 'Compose 配置解析失败'
docker compose -p wangbincollector-010 pull || fail '镜像拉取失败；请确认 GHCR 可匿名拉取'
# Java 的 SQLite 所有者核验需要容器 NSS 能按实际宿主 UID 查到用户。
# 仅首次从已固定摘要的镜像复制系统账号表，再补充当前 UID；不触碰已有数据库。
if [[ ! -e config/passwd ]]; then
  docker run --rm --entrypoint cat "$image_ref" /etc/passwd > config/passwd || fail '无法读取发行镜像账号表'
  if ! awk -F: -v uid="$(id -u)" '$3 == uid {found=1} END {exit !found}' config/passwd; then
    printf 'wbcollector-host:x:%s:%s:WangbinCollector:/opt/wangbin-collector:/usr/sbin/nologin\n' "$(id -u)" "$(id -g)" >> config/passwd
  fi
  chmod 644 config/passwd
fi
[[ -f config/passwd && ! -L config/passwd && "$(stat -c %u config/passwd)" == "$(id -u)" && "$(stat -c %a config/passwd)" == 644 ]] || fail '容器账号映射文件权限异常'
awk -F: -v uid="$(id -u)" '$3 == uid {found=1} END {exit !found}' config/passwd || fail '容器账号映射缺少当前 UID'
docker compose -p wangbincollector-010 up -d --no-build || fail '容器启动失败，请查看日志（不会清理数据）'

# /health 可能因为尚无设备报告 DOWN；就绪用 Boot readiness 与网页入口独立判断。
ready=0
for _ in {1..60}; do
  if curl --silent --fail "http://127.0.0.1:$port/collector/actuator/health/readiness" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' && \
     curl --silent --fail --output /dev/null "http://127.0.0.1:$port/collector/desktop/index.html"; then
    ready=1
    break
  fi
  sleep 2
done
[[ "$ready" == 1 ]] || fail '应用就绪或 Web 入口未通过，请执行 bash logs.sh；数据已保留'
printf '应用已就绪：http://127.0.0.1:%s/collector/desktop/index.html\n' "$port"
printf '运维令牌保存在 config/ops-token（仅当前账号可读）；查看日志：bash logs.sh\n'
