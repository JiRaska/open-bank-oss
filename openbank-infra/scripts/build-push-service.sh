#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Build + push an OpenBank Quarkus service image — the generic counterpart to
# build-push-admin-ui.sh.
#
# WHY NOT `docker build -f openbank-<svc>/Dockerfile`: that Dockerfile runs a full
# Gradle build *inside* the image. It (a) re-downloads the Gradle distribution and
# every dependency on each build (a single slow/timed-out fetch fails the whole image
# — observed: `gradle.wrapper.Install.forceFetch: Read timed out`), and (b) is ~5x
# slower than reusing the host's warm Gradle cache. This script builds the fast-jar on
# the HOST and bakes the resulting `quarkus-app/` layout into a tiny JRE runtime image
# — byte-for-byte the same artifact the Dockerfile's runtime stage produces, just
# reliable and fast. CI (warm network + remote cache) can still use the in-image
# Dockerfile; this is the path for a developer/operator deploy from a laptop.
#
# Usage:
#   openbank-infra/scripts/build-push-service.sh <service> [--bump]
#     <service>  short name (`account`, `ledger`) OR full module dir
#                (`openbank-account-service`); resolved against the filesystem.
#     --bump     also rewrite the service's image tag in openbank-infra/gitops/
#                (commit + push the gitops repo/branch ArgoCD watches to deploy).
set -euo pipefail

SVC_ARG="${1:?usage: build-push-service.sh <service> [--bump]}"; shift || true
BUMP=0; [ "${1:-}" = "--bump" ] && BUMP=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$REPO_ROOT"
ECR_REGISTRY="${ECR_REGISTRY:-265175468565.dkr.ecr.eu-north-1.amazonaws.com}"
AWS_REGION="${AWS_REGION:-eu-north-1}"
export AWS_PROFILE="${AWS_PROFILE:-openbank}"
PLATFORM="${PLATFORM:-linux/arm64}"

# Resolve the Gradle module directory from the (possibly short) service name.
DIR=""
for cand in "openbank-${SVC_ARG}-service" "openbank-${SVC_ARG}" "${SVC_ARG}"; do
  if [ -f "${cand}/build.gradle.kts" ]; then DIR="$cand"; break; fi
done
[ -n "$DIR" ] || { echo "ERROR: no Gradle module found for '${SVC_ARG}' (tried openbank-${SVC_ARG}-service, openbank-${SVC_ARG})" >&2; exit 2; }

MODULE=":${DIR}"
ECR_REPO="$DIR"
GIT_SHA="$(git rev-parse --short HEAD)"
# Mark the tag -dirty if the service or the shared lib has uncommitted changes, so a
# laptop build never masquerades as a clean commit.
if ! git diff --quiet HEAD -- "$DIR" openbank-libs 2>/dev/null; then GIT_SHA="${GIT_SHA}-dirty"; fi
TAG="sandbox-${GIT_SHA}"
IMAGE="${ECR_REGISTRY}/${ECR_REPO}:${TAG}"

echo "==> service : ${DIR}"
echo "    image   : ${IMAGE}"
echo "    platform: ${PLATFORM}"

# 1. Build the fast-jar on the host (warm Gradle cache, real network).
echo "==> gradle ${MODULE}:quarkusBuild (fast-jar)"
./gradlew "${MODULE}:quarkusBuild" -Dquarkus.package.jar.type=fast-jar --console=plain -q
QA="${DIR}/build/quarkus-app"
[ -f "${QA}/quarkus-run.jar" ] || { echo "ERROR: fast-jar not produced at ${QA}/quarkus-run.jar" >&2; exit 1; }

# 2. Bake quarkus-app/ into a runtime image. EXPOSE is cosmetic (k8s uses the
#    Deployment containerPort); we lift it from the service Dockerfile when present.
PORT="$(grep -oE 'EXPOSE[[:space:]]+[0-9]+' "${DIR}/Dockerfile" 2>/dev/null | grep -oE '[0-9]+' | head -1 || true)"
PORT="${PORT:-8080}"
CTX="$(mktemp -d)"; trap 'rm -rf "$CTX"' EXIT
cp -r "${QA}" "${CTX}/quarkus-app"
# Gradle produces quarkus-app files with 600 permissions (owner-only). The container
# runs as a non-root 'openbank' user who can't read them → ClassNotFoundException.
chmod -R a+r "${CTX}/quarkus-app"
cat > "${CTX}/Dockerfile" <<EOF
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank
COPY quarkus-app/lib/ /app/lib/
COPY quarkus-app/*.jar /app/
COPY quarkus-app/app/ /app/app/
COPY quarkus-app/quarkus/ /app/quarkus/
EXPOSE ${PORT}
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
EOF

echo "==> ECR login + buildx push"
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
docker buildx build --platform "$PLATFORM" -t "$IMAGE" --push "$CTX"
echo "==> pushed ${IMAGE}"

# 2b. Sign the image with Cosign (ADR-0029/0030 supply-chain). Trust root = AWS KMS key
# alias/openbank-cosign-signing; kyverno verifies against the matching public key + Rekor tlog.
#
# IMPORTANT — cosign v2 is pinned on purpose. kyverno 3.2.6 discovers signatures only via the
# legacy `sha256-<digest>.sig` tag scheme. cosign v3 writes OCI 1.1 *referrer* signatures on
# ECR (no .sig tag), which kyverno cannot find ("no signatures found") — under Enforce that
# rejects every image. cosign v2 writes tag-based signatures kyverno reads. Revisit once kyverno
# can verify referrers (then cosign v3 is the target). See ADR-0029 / issue #770.
# Best-effort: warn (don't fail the build) if cosign/KMS are unavailable — CI/operators have both.
COSIGN_KEY="${COSIGN_KEY:-awskms:///alias/openbank-cosign-signing}"
COSIGN_VERSION="${COSIGN_VERSION:-v2.4.3}"

# Echo a cosign v2.x binary path: reuse one on PATH if it is v2, else fetch the pinned release
# to a cache path. Returns non-zero (empty) if no v2 binary can be obtained.
resolve_cosign_v2() {
  if command -v cosign >/dev/null 2>&1 && cosign version 2>/dev/null | grep -Eq 'GitVersion:[[:space:]]*v2\.'; then
    command -v cosign; return 0
  fi
  local os arch bin
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  case "$(uname -m)" in aarch64|arm64) arch=arm64 ;; x86_64|amd64) arch=amd64 ;; *) return 1 ;; esac
  bin="${TMPDIR:-/tmp}/cosign-${COSIGN_VERSION}-${os}-${arch}"
  if [ ! -x "$bin" ]; then
    curl -fsSL -o "$bin" "https://github.com/sigstore/cosign/releases/download/${COSIGN_VERSION}/cosign-${os}-${arch}" 2>/dev/null && chmod +x "$bin" || return 1
  fi
  printf '%s\n' "$bin"
}

COSIGN_BIN="$(resolve_cosign_v2 || true)"
if [ -n "${COSIGN_BIN:-}" ]; then
  echo "==> cosign sign ${IMAGE} (tag-based, $("$COSIGN_BIN" version 2>/dev/null | awk '/GitVersion/{print $2}'))"
  if COSIGN_YES=true "$COSIGN_BIN" sign --key "$COSIGN_KEY" "$IMAGE"; then
    echo "    signed (key=${COSIGN_KEY})"
  else
    echo "WARN: cosign sign failed — image pushed but UNSIGNED (kyverno will Audit-flag it)." >&2
  fi
else
  echo "WARN: cosign v2 unavailable — image pushed UNSIGNED. Install cosign v2.x or set COSIGN_VERSION (ADR-0029)." >&2
fi

# 3. Optionally bump the gitops image tag.
if [ "$BUMP" -eq 1 ]; then
  MANIFEST="$(grep -rl "${ECR_REPO}:sandbox-" openbank-infra/gitops/components 2>/dev/null | head -1 || true)"
  if [ -n "$MANIFEST" ]; then
    sed -i.bak "s#${ECR_REPO}:sandbox-[A-Za-z0-9._-]*#${ECR_REPO}:${TAG}#g" "$MANIFEST" && rm -f "${MANIFEST}.bak"
    echo "==> bumped ${MANIFEST#${REPO_ROOT}/} -> ${TAG}"
    echo "    commit + push the gitops branch ArgoCD watches to roll the deployment."
  else
    echo "WARN: no gitops manifest references ${ECR_REPO}:sandbox-* — bump skipped" >&2
  fi
fi

echo "==> done."
