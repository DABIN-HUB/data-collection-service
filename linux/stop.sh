#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
version="$(< VERSION)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf '发行版本无效\n' >&2; exit 1; }
docker compose -p "wangbincollector-${version//./}" stop
printf '仅停止本产品容器；SQLite 目录及 Redis 数据卷未删除。\n'
