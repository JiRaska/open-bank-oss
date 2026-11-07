#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# ADR-0358 rollback guard: before an operator-typed tag + digest becomes a pin, prove that
#   1. ECR holds that digest under that tag (the pair is not a typo or a fabrication), and
#   2. the digest carries a valid KMS cosign signature AND a CycloneDX SBOM attestation —
#      the same envelopes Kyverno's verifyImages admission policy requires, so a pin that
#      passes here will not be denied at the next pod reschedule.
# The deploy-pin contract separately requires the target to have been pinned on
# deploy/sandbox before; this adds the registry-side half.
#
# Usage: verify-pinned-image.sh <registry/repo> <tag> <sha256:digest>
# Needs AWS credentials (ECR read + KMS verify) in the environment.
set -euo pipefail

repo_ref="${1:?registry/repository required}"
tag="${2:?tag required}"
digest="${3:?digest required}"
[[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "::error::digest must be sha256:<64 hex>, got '${digest}'"; exit 1; }
[[ "$tag" =~ ^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$ ]] || { echo "::error::invalid tag '${tag}'"; exit 1; }

repository="${repo_ref#*/}"
ecr_digest="$(aws ecr describe-images --repository-name "$repository" \
  --image-ids imageTag="$tag" --query 'imageDetails[0].imageDigest' --output text)"
if [ "$ecr_digest" != "$digest" ]; then
  echo "::error::ECR ${repository}:${tag} is ${ecr_digest}, not ${digest}; refusing to pin it"
  exit 1
fi

COSIGN_KEY="awskms:///alias/openbank-cosign-signing"
COSIGN_VERSION="v2.4.3"
arch="$(uname -m)"; case "$arch" in aarch64) arch=arm64 ;; x86_64) arch=amd64 ;; esac
os="$(uname -s | tr '[:upper:]' '[:lower:]')"; case "$os" in darwin) os=darwin ;; *) os=linux ;; esac
bin="${RUNNER_TEMP:-/tmp}/cosign2"
# sha256 pins from the release's cosign_checksums.txt — bump together with COSIGN_VERSION
# (same block as release-please.yml / verify-release-evidence.yml).
case "${os}-${arch}" in
  linux-amd64)  COSIGN_SHA256="caaad125acef1cb81d58dcdc454a1e429d09a750d1e9e2b3ed1aed8964454708" ;;
  linux-arm64)  COSIGN_SHA256="bd0f9763bca54de88699c3656ade2f39c9a1c7a2916ff35601caf23a79be0629" ;;
  darwin-amd64) COSIGN_SHA256="98a3bfd691f42c6a5b721880116f89210d8fdff61cc0224cd3ef2f8e55a466fb" ;;
  darwin-arm64) COSIGN_SHA256="edfc761b27ced77f0f9ca288ff4fac7caa898e1e9db38f4dfdf72160cdf8e638" ;;
  *) echo "::error::no pinned cosign checksum for ${os}-${arch}"; exit 1 ;;
esac
curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 -o "$bin" \
  "https://github.com/sigstore/cosign/releases/download/${COSIGN_VERSION}/cosign-${os}-${arch}"
SHATOOL="sha256sum"; command -v sha256sum >/dev/null 2>&1 || SHATOOL="shasum -a 256"
echo "${COSIGN_SHA256}  ${bin}" | ${SHATOOL} -c - >/dev/null \
  || { echo "::error::cosign ${COSIGN_VERSION} failed checksum verification"; exit 1; }
chmod +x "$bin"

image="${repo_ref}@${digest}"
if ! "$bin" verify --key "$COSIGN_KEY" "$image" >/dev/null 2>&1; then
  echo "::error::${image} has no valid cosign signature; Kyverno would deny it"
  exit 1
fi
if ! "$bin" verify-attestation --key "$COSIGN_KEY" --type cyclonedx "$image" >/dev/null 2>&1; then
  echo "::error::${image} has no valid CycloneDX SBOM attestation; Kyverno would deny it"
  exit 1
fi
echo "verified ${repository}:${tag} = ${digest}: in ECR, signed and SBOM-attested"
