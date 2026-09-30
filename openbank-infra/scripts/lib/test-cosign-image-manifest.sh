#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
set -euo pipefail

# shellcheck source=./openbank-infra/scripts/lib/cosign-attest.sh
. "$(dirname "${BASH_SOURCE[0]}")/cosign-attest.sh"

account="$(printf '%012d' 1)"
image="${account}.dkr.ecr.example-region-1.amazonaws.com/openbank-test:build"
calls="$(mktemp)"
trap 'rm -f "$calls"' EXIT
mock_type='application/vnd.oci.image.manifest.v1+json'

aws() {
  printf '%s\n' "$*" > "$calls"
  if [ "$mock_type" = 'API_FAILURE' ]; then return 1; fi
  printf '%s\n' "$mock_type"
}

assert_ecr_single_image_manifest "$image"
grep -Fq -- 'ecr batch-get-image' "$calls"
grep -Fq -- "--registry-id ${account}" "$calls"
grep -Fq -- '--region example-region-1' "$calls"
grep -Fq -- '--repository-name openbank-test' "$calls"
grep -Fq -- '--image-ids imageTag=build' "$calls"

digest="sha256:$(printf 'a%.0s' {1..64})"
assert_ecr_single_image_manifest "${image%:build}@${digest}"
grep -Fq -- "--image-ids imageDigest=${digest}" "$calls"

mock_type='application/vnd.docker.distribution.manifest.v2+json'
assert_ecr_single_image_manifest "$image"

for mock_type in 'application/vnd.oci.image.index.v1+json' \
                 'application/vnd.docker.distribution.manifest.list.v2+json' \
                 'None' 'API_FAILURE'; do
  if assert_ecr_single_image_manifest "$image" 2>/dev/null; then
    echo "ERROR: accepted ${mock_type}" >&2
    exit 1
  fi
done
if assert_ecr_single_image_manifest 'registry.invalid/openbank-test:build' 2>/dev/null; then
  echo 'ERROR: accepted a non-ECR image reference' >&2
  exit 1
fi

echo 'cosign image-manifest guard: 8 cases passed'
