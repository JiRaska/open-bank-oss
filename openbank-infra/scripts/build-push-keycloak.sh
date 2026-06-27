#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Build + push the pre-optimized Keycloak image for the sandbox IdP.
#
# The stock Keycloak image re-augments (`kc.sh build`) on every start (~40-50s);
# this image bakes that build in so the Deployment runs `start --optimized` and
# cold-starts in ~10s. See openbank-infra/docker/keycloak/Dockerfile for the
# build-time option contract. FinOps-neutral: same single replica, prebuilt image.
#
# The tag is the upstream Keycloak VERSION (read from the Dockerfile's pinned
# ARG), so bumping Keycloak is a deliberate change to that ARG + this push.
#
# Usage:
#   openbank-infra/scripts/build-push-keycloak.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$REPO_ROOT"
ECR_REGISTRY="${ECR_REGISTRY:-265175468565.dkr.ecr.eu-north-1.amazonaws.com}"
AWS_REGION="${AWS_REGION:-eu-north-1}"
export AWS_PROFILE="${AWS_PROFILE:-openbank}"
PLATFORM="${PLATFORM:-linux/arm64}"   # cluster nodes are arm64 (Graviton)

ECR_REPO="openbank-keycloak"
DOCKERFILE="openbank-infra/docker/keycloak/Dockerfile"
# Single source of truth for the version: the Dockerfile's pinned ARG.
VERSION="$(grep -oE 'KEYCLOAK_VERSION=[0-9.]+' "$DOCKERFILE" | head -1 | cut -d= -f2)"
[ -n "$VERSION" ] || { echo "ERROR: could not read KEYCLOAK_VERSION from ${DOCKERFILE}" >&2; exit 1; }
IMAGE="${ECR_REGISTRY}/${ECR_REPO}:${VERSION}-optimized"

echo "==> image   : ${IMAGE}"
echo "    platform: ${PLATFORM}"

echo "==> ECR login + buildx push"
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
docker buildx build --platform "$PLATFORM" -f "$DOCKERFILE" -t "$IMAGE" --push openbank-infra/docker/keycloak
echo "==> pushed ${IMAGE}"

# Sign with Cosign (ADR-0029/0030) — the kyverno verify-openbank-image-signatures
# Enforce policy rejects unsigned 265175468565.../openbank-* images at admission.
#
# cosign v2 is pinned on purpose: kyverno 3.2.6 discovers signatures only via the
# legacy `sha256-<digest>.sig` tag scheme. cosign v3 writes OCI 1.1 referrer
# signatures kyverno cannot find. (Same pin/rationale as build-push-pyroscope-agent.sh.)
COSIGN_KEY="${COSIGN_KEY:-awskms:///alias/openbank-cosign-signing}"
COSIGN_VERSION="${COSIGN_VERSION:-v2.4.3}"

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
    exit 1
  fi
else
  echo "WARN: cosign v2 unavailable — image pushed UNSIGNED. Install cosign v2.x or set COSIGN_VERSION (ADR-0029)." >&2
  exit 1
fi

echo "==> done."
