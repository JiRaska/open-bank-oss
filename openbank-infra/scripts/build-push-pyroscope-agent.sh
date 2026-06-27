#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Build + push the Pyroscope Java agent carrier image (ADR-0082, profiling pillar).
#
# This is the ONE reusable artifact behind the opt-in profiling rollout: it carries
# only the pinned `pyroscope.jar`. A service opts in via an init container that copies
# the jar into a shared emptyDir + a `-javaagent` in JAVA_TOOL_OPTIONS (see the
# sepa-payment Deployment in gitops/components/payments/payments-services.yaml).
# No service image is rebuilt to enable/disable profiling.
#
# The image tag is the agent VERSION (not a git sha) — it tracks the upstream
# release pinned in docker/pyroscope-agent/Dockerfile, so bumping the agent is a
# deliberate, reviewable change to that ARG + this push.
#
# Usage:
#   openbank-infra/scripts/build-push-pyroscope-agent.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$REPO_ROOT"
ECR_REGISTRY="${ECR_REGISTRY:-265175468565.dkr.ecr.eu-north-1.amazonaws.com}"
AWS_REGION="${AWS_REGION:-eu-north-1}"
export AWS_PROFILE="${AWS_PROFILE:-openbank}"
PLATFORM="${PLATFORM:-linux/arm64}"

ECR_REPO="openbank-pyroscope-agent"
DOCKERFILE="openbank-infra/docker/pyroscope-agent/Dockerfile"
# Single source of truth for the version: the Dockerfile's pinned ARG.
VERSION="$(grep -oE 'PYROSCOPE_VERSION=[0-9.]+' "$DOCKERFILE" | head -1 | cut -d= -f2)"
[ -n "$VERSION" ] || { echo "ERROR: could not read PYROSCOPE_VERSION from ${DOCKERFILE}" >&2; exit 1; }
IMAGE="${ECR_REGISTRY}/${ECR_REPO}:${VERSION}"

echo "==> image   : ${IMAGE}"
echo "    platform: ${PLATFORM}"

echo "==> ECR login + buildx push"
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
# Build context is docker/ so the Dockerfile's relative paths resolve like the kong image.
docker buildx build --platform "$PLATFORM" -f "$DOCKERFILE" -t "$IMAGE" --push openbank-infra/docker/pyroscope-agent
echo "==> pushed ${IMAGE}"

# Sign with Cosign (ADR-0029/0030 supply-chain) — same trust root as the service images.
#
# IMPORTANT — cosign v2 is pinned on purpose. kyverno 3.2.6 discovers signatures only via the
# legacy `sha256-<digest>.sig` tag scheme. cosign v3 writes OCI 1.1 *referrer* signatures on ECR
# (no .sig tag) which kyverno cannot find ("no signatures found") — under the verify-images
# Enforce policy that rejects the image at admission. cosign v2 writes tag-based signatures
# kyverno reads. (This carrier image was once signed with v3 and blocked sepa-payment at
# admission — that is the bug this pin prevents.) Revisit once kyverno can verify referrers.
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
  if COSIGN_YES=true "$COSIGN_BIN" sign --key "${COSIGN_KEY}" "${IMAGE}"; then
    echo "    signed (key=${COSIGN_KEY})"
  else
    echo "WARN: cosign sign failed — image pushed but UNSIGNED (kyverno will reject it under Enforce)." >&2
  fi
else
  echo "WARN: cosign v2 unavailable — image pushed UNSIGNED. Install cosign v2.x or set COSIGN_VERSION (ADR-0029)." >&2
fi

echo "==> done."
