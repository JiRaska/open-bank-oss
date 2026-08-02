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
./gradlew "${MODULE}:quarkusBuild" "${MODULE}:cyclonedxBom" -Dquarkus.package.jar.type=fast-jar --console=plain -q
QA="${DIR}/build/quarkus-app"
[ -f "${QA}/quarkus-run.jar" ] || { echo "ERROR: fast-jar not produced at ${QA}/quarkus-run.jar" >&2; exit 1; }
# CycloneDX SBOM (schema 1.5, runtimeClasspath) — baked into the image and served
# live by libs SbomResource at /q/openbank/sbom. Best-effort: warn if absent so a
# missing plugin never fails an otherwise-good deploy build.
BOM="${DIR}/build/reports/bom.json"
[ -f "$BOM" ] || echo "WARN: no SBOM at ${BOM} — image will serve /q/openbank/sbom as not_generated" >&2

# 2. Bake quarkus-app/ into a runtime image. EXPOSE is cosmetic (k8s uses the
#    Deployment containerPort); we lift it from the service Dockerfile when present.
PORT="$(grep -oE 'EXPOSE[[:space:]]+[0-9]+' "${DIR}/Dockerfile" 2>/dev/null | grep -oE '[0-9]+' | head -1 || true)"
PORT="${PORT:-8080}"
CTX="$(mktemp -d)"; trap 'rm -rf "$CTX"' EXIT
cp -r "${QA}" "${CTX}/quarkus-app"
# Gradle produces quarkus-app files with 600 permissions (owner-only). The container
# runs as a non-root 'openbank' user who can't read them → ClassNotFoundException.
chmod -R a+r "${CTX}/quarkus-app"
# Stage the SBOM (if produced) so the runtime stage can COPY it in.
SBOM_COPY=""; SBOM_ENV=""
if [ -f "$BOM" ]; then
  mkdir -p "${CTX}/sbom"; cp "$BOM" "${CTX}/sbom/bom.json"; chmod a+r "${CTX}/sbom/bom.json"
  SBOM_COPY=$'COPY sbom/bom.json /app/sbom/bom.json'
  SBOM_ENV=$'ENV OPENBANK_SBOM_PATH=/app/sbom/bom.json'
fi
cat > "${CTX}/Dockerfile" <<EOF
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S openbank && adduser -S openbank -G openbank
USER openbank
COPY quarkus-app/lib/ /app/lib/
COPY quarkus-app/*.jar /app/
COPY quarkus-app/app/ /app/app/
COPY quarkus-app/quarkus/ /app/quarkus/
${SBOM_COPY}
${SBOM_ENV}
EXPOSE ${PORT}
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/app/quarkus-run.jar"]
EOF

echo "==> ECR login + buildx push"
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
# A new service's ECR repository is declared nowhere, so its first push fails with
# `name unknown` after the whole build has already run (#3423). Shared with auto-deploy.yml's
# inline push path — the one where it was measured — so both producers behave identically.
AWS_REGION="$AWS_REGION" bash "${REPO_ROOT}/.github/scripts/ensure-ecr-repository.sh" "$ECR_REPO"
docker buildx build --platform "$PLATFORM" -t "$IMAGE" --push "$CTX"
echo "==> pushed ${IMAGE}"

# 2b. Sign the image AND attest its SBOM (ADR-0029/0030 supply-chain), via the shared helper
# so every producer does provenance identically. Trust root = AWS KMS key
# alias/openbank-cosign-signing; kyverno verifies against the matching public key.
#
# HARD FAILS on any provenance failure. Both kyverno policies (signature + SBOM attestation)
# are Enforce, so an image missing either is undeployable — its pods run until the next
# reschedule, then are denied admission forever. Failing the push that produced it is the
# only way an operator finds out at a time when it is still cheap to fix.
. "${REPO_ROOT}/openbank-infra/scripts/lib/cosign-attest.sh"

cosign_sign_and_attest "$IMAGE" "$PLATFORM" || {
  echo "ERROR: ${IMAGE} pushed but NOT fully attested — it is NOT deployable." >&2
  echo "       Fix the provenance failure above and re-run; do not bump gitops to this tag." >&2
  exit 1
}

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
