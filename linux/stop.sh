#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
docker compose -p wangbincollector-010 stop
printf '仅停止本产品容器；SQLite 目录及 Redis 数据卷未删除。\n'
