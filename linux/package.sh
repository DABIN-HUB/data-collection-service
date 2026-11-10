#!/usr/bin/env bash
set -Eeuo pipefail
# 用法：package.sh <输出目录> <源码 SHA> <Jar SHA-256> <镜像索引引用或候选占位符> <架构>。
[[ $# == 5 ]] || { printf '用法：package.sh OUTPUT SOURCE_SHA JAR_SHA256 IMAGE_REF amd64|arm64\n' >&2; exit 2; }
output="$1"; source_sha="$2"; jar_sha="$3"; image="$4"; arch="$5"
[[ "$source_sha" =~ ^[a-f0-9]{40}$ && "$jar_sha" =~ ^[a-f0-9]{64}$ ]] || { printf '源码或 JAR 校验值无效\n' >&2; exit 2; }
[[ "$arch" == amd64 || "$arch" == arm64 ]] || { printf '发行架构无效\n' >&2; exit 2; }
[[ "$image" == '__APP_IMAGE__' || "$image" =~ ^ghcr\.io/dabin-hub/data-collection-service@sha256:[a-f0-9]{64}$ ]] || { printf '镜像摘要无效\n' >&2; exit 2; }
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
version="$(< "$root/VERSION")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf '发行版本无效\n' >&2; exit 2; }
name="WangbinCollector-$version-linux-$arch.tar.gz"
mkdir -p "$output"
output="$(cd "$output" && pwd)"
[[ ! -e "$output/$name" && ! -L "$output/$name" && ! -e "$output/$name.sha256" && ! -L "$output/$name.sha256" ]] || { printf '发行目标已存在，拒绝覆盖\n' >&2; exit 2; }
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
printf 'version=%s\nsource_sha=%s\njava=17\nplatform=linux/%s\nfrontend_build=npm ci --prefix collector-desktop && npm --prefix collector-desktop run build:web\njar_build=mvn -B -ntp -pl collector-boot -am package -Dmaven.test.skip=true\nmaven_artifact_version=0.0.1-SNAPSHOT\njar_sha256=%s\napp_image=%s\nredis_image=%s\n' \
  "$version" "$source_sha" "$arch" "$jar_sha" "$image" \
  'redis:7-alpine@sha256:c5d34a98396a4775e223c74e929162c35c5a2182901ba26b50ade939941ce14b' \
  > "$staging/WangbinCollector/BUILD-MANIFEST"
(
  cd "$staging/WangbinCollector"
  sha256sum VERSION BUILD-MANIFEST README.md compose.yaml deploy.sh stop.sh logs.sh config/application-release.yml.example > SHA256SUMS
)
archive="$output/$name"
tar -C "$staging" -czf "$archive" WangbinCollector
[[ -f "$archive" ]] || exit 1
(cd "$output" && sha256sum "$name" > "$name.sha256")
printf '发行包：%s\n' "$archive"
