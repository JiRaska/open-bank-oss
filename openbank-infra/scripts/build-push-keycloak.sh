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

# Sign + attest with Cosign (ADR-0029/0030 D4). Kyverno runs two independent Enforce
# policies at admission: verify-openbank-image-signatures rejects an unsigned image, and
# verify-openbank-image-sbom-attestation rejects a signed-but-unattested one. This script
# previously only signed, so every keycloak build produced an image that passed the first
# gate and failed the second — latent until a pod rescheduled, which is what took admin-ui
# login down on 2026-07-16.
#
# The shared helper owns the cosign-v2 pin, the mandatory trivy --platform, and the
# post-attest verify. Provenance failures are FATAL there: a silently-unattested image is
# an image that cannot be admitted.
. "${REPO_ROOT}/openbank-infra/scripts/lib/cosign-attest.sh"

cosign_sign_and_attest "$IMAGE" "$PLATFORM" || {
  echo "ERROR: ${IMAGE} pushed but NOT fully attested — it is NOT deployable." >&2
  echo "       keycloak is the cluster IdP: an unattested tag here breaks every login" >&2
  echo "       the moment its pod reschedules. Fix the provenance failure above and re-run." >&2
  exit 1
}

echo "==> done."
