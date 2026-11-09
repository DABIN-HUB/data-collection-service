#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
docker compose -p wangbincollector-010 logs --tail=200 "$@"
