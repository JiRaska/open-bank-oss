#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Exercise the immutable artifact before GitOps promotion, with disposable local auth.
set -euo pipefail
image="${1:?image reference with sha256 digest}"
expected_sha="${2:?source SHA stamped in image}"
# Registry manifest digest in CI; a local immutable image ID also supports offline proof.
[[ "$image" =~ (^sha256:|@sha256:)[0-9a-f]{64}$ ]] || { echo 'Expected an immutable image digest' >&2; exit 2; }
[[ "$expected_sha" =~ ^[0-9a-f]{7,40}$ ]] || { echo 'Invalid source SHA' >&2; exit 2; }
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
container_id=''
cleanup() { if [ -n "$container_id" ]; then docker rm -f "$container_id" >/dev/null; fi; }
trap cleanup EXIT
export NEXTAUTH_SECRET
NEXTAUTH_SECRET="$(openssl rand -hex 32)"
export KEYCLOAK_CLIENT_SECRET
KEYCLOAK_CLIENT_SECRET="$(openssl rand -hex 32)"
port="${OPENBANK_IMAGE_TEST_PORT:-3187}"
if ! [[ "$port" =~ ^[0-9]+$ ]] || ((port <= 1024 || port >= 65536)); then
  echo 'Invalid smoke-test port' >&2
  exit 2
fi
export OPENBANK_IMAGE_TEST_URL="http://127.0.0.1:${port}"
# Docker fails if the port is occupied; never reuse an unrelated running server.
container_id="$(docker create --publish "127.0.0.1:${port}:3000" \
  --env NEXTAUTH_SECRET --env KEYCLOAK_CLIENT_SECRET \
  --env KEYCLOAK_PUBLIC_URL=https://keycloak.image-test.invalid \
  --env ALLOW_INSECURE_STUDIO_URLS=true \
  --env "NEXTAUTH_URL=${OPENBANK_IMAGE_TEST_URL}" "$image")"
docker start "$container_id" >/dev/null
export OPENBANK_IMAGE_TEST_SHA="$expected_sha"
ready=false
for ((attempt=0; attempt<60; attempt++)); do
  if curl --fail --silent --output /dev/null "${OPENBANK_IMAGE_TEST_URL}/auth/login"; then ready=true; break; fi
  sleep 1
done
if [ "$ready" != true ]; then
  docker logs --tail 50 "$container_id" >&2
  echo 'Image did not become ready' >&2
  exit 1
fi
cd "${repo_root}/openbank-admin-ui"
npx playwright test --config playwright.image.config.ts
