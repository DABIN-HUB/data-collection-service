#!/usr/bin/env bash
set -Eeuo pipefail
# 用法：package.sh <输出目录> <源码 SHA> <Jar SHA-256> <镜像引用>。
[[ $# == 4 ]] || { printf '用法：package.sh OUTPUT SOURCE_SHA JAR_SHA256 IMAGE_REF\n' >&2; exit 2; }
output="$1"; source_sha="$2"; jar_sha="$3"; image="$4"
[[ "$source_sha" =~ ^[a-f0-9]{40}$ && "$jar_sha" =~ ^[a-f0-9]{64}$ ]] || exit 2
[[ "$image" == '__APP_IMAGE__' || "$image" =~ ^ghcr\.io/dabin-hub/data-collection-service@sha256:[a-f0-9]{64}$ ]] || exit 2
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
staging="$(mktemp -d "$output/.wangbin-release.XXXXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
mkdir -p "$staging/WangbinCollector/config"
for file in compose.yaml deploy.sh stop.sh logs.sh README.md VERSION; do
  cp "$root/$file" "$staging/WangbinCollector/$file"
done
cp "$root/config/application-release.yml.example" "$staging/WangbinCollector/config/"
if [[ "$image" != '__APP_IMAGE__' ]]; then
  sed -i "s|__APP_IMAGE__|$image|g" "$staging/WangbinCollector/compose.yaml"
fi
printf 'version=0.1.0\nsource_sha=%s\njava=17\nplatform=linux/amd64\nfrontend_build=npm ci --prefix collector-desktop && npm --prefix collector-desktop run build:web\njar_build=mvn -B -ntp -pl collector-boot -am package -Dmaven.test.skip=true\nmaven_artifact_version=0.0.1-SNAPSHOT\njar_sha256=%s\napp_image=%s\nredis_image=%s\n' \
  "$source_sha" "$jar_sha" "$image" \
  'redis:7-alpine@sha256:c5d34a98396a4775e223c74e929162c35c5a2182901ba26b50ade939941ce14b' \
  > "$staging/WangbinCollector/BUILD-MANIFEST"
(
  cd "$staging/WangbinCollector"
  sha256sum VERSION BUILD-MANIFEST README.md compose.yaml deploy.sh stop.sh logs.sh config/application-release.yml.example > SHA256SUMS
)
archive="$output/WangbinCollector-0.1.0-linux-amd64.tar.gz"
tar -C "$staging" -czf "$archive" WangbinCollector
sha256sum "$archive" > "$output/WangbinCollector-0.1.0-linux-amd64.tar.gz.sha256"
printf '候选发行包：%s\n' "$archive"
