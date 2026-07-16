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

# Sign + attest with Cosign (ADR-0029/0030 supply-chain) — same trust root and same shared
# helper as every other producer.
#
# This carrier image is an INIT CONTAINER on money-path pods, which makes its provenance
# load-bearing far beyond its own footprint: a pod whose init container is denied at
# admission never starts, no matter how well-attested the app container is. This image
# having no SBOM attestation is what took sepa-payment down on 2026-07-12 — an
# unattested init container fails the pod, silently, on the next reschedule. It was
# previously also the v3-signature bug's victim (see the cosign v2 pin in
# lib/cosign-attest.sh). Provenance failures here are FATAL for that reason.
. "${REPO_ROOT}/openbank-infra/scripts/lib/cosign-attest.sh"

cosign_sign_and_attest "$IMAGE" "$PLATFORM" || {
  echo "ERROR: ${IMAGE} pushed but NOT fully attested — it is NOT deployable." >&2
  echo "       This image is an init container on money-path pods: an unattested tag here" >&2
  echo "       fails the WHOLE pod at admission. Fix the provenance failure above and re-run." >&2
  exit 1
}

echo "==> done."
