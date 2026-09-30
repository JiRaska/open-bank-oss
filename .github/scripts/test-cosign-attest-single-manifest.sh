#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Self-test for assert_single_image_manifest in openbank-infra/scripts/lib/cosign-attest.sh
# (#11573). The registry read is stubbed by redefining read_image_manifest, so this runs offline.
# Known-positives (must REFUSE): OCI index, docker manifest list, index without mediaType,
# unreadable manifest, non-JSON. Known-negatives (must ACCEPT): OCI image manifest, docker v2
# manifest, OCI manifest without mediaType. Then end-to-end: cosign_sign_and_attest handed an
# index must return 1 WITHOUT ever invoking cosign (the signature is the permanent part).
set -uo pipefail

# shellcheck source=openbank-infra/scripts/lib/cosign-attest.sh
. openbank-infra/scripts/lib/cosign-attest.sh

STUB=""
read_image_manifest() {
  [ "$STUB" = "__unreadable__" ] && return 1
  printf '%s' "$STUB"
}

fail=0
check() { # <name> <want: accept|refuse> <manifest>
  local name="$1" want="$2" got
  STUB="$3"
  if assert_single_image_manifest "registry.example/img:tag" 2>/dev/null; then got=accept; else got=refuse; fi
  if [ "$got" = "$want" ]; then
    echo "ok   ${name}: ${got}"
  else
    echo "FAIL ${name}: want ${want}, got ${got}"
    fail=1
  fi
}

check "oci index" refuse \
  '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[]}'
check "docker manifest list" refuse \
  '{"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.list.v2+json","manifests":[]}'
check "index without mediaType" refuse '{"schemaVersion":2,"manifests":[{"digest":"sha256:0"}]}'
check "unreadable manifest" refuse "__unreadable__"
check "not json" refuse "<html>denied</html>"
check "oci image manifest" accept \
  '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{},"layers":[]}'
check "docker v2 manifest" accept \
  '{"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json","config":{},"layers":[]}'
check "oci manifest without mediaType" accept '{"schemaVersion":2,"config":{},"layers":[]}'

# End-to-end: the refusal happens BEFORE cosign runs.
calls="$(mktemp)"
fake="$(mktemp)"
printf '#!/bin/sh\necho "$@" >> %s\n' "$calls" > "$fake"
chmod +x "$fake"
resolve_cosign_v2() { printf '%s\n' "$fake"; }
STUB='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[]}'
if cosign_sign_and_attest "registry.example/img:tag" linux/arm64 >/dev/null 2>&1; then
  echo "FAIL cosign_sign_and_attest accepted an index"; fail=1
elif [ -s "$calls" ]; then
  echo "FAIL cosign was invoked on an index: $(cat "$calls")"; fail=1
else
  echo "ok   cosign_sign_and_attest refuses an index before signing"
fi
if cosign_attest_sbom "registry.example/img:tag" linux/arm64 "$fake" >/dev/null 2>&1 \
   || [ -s "$calls" ]; then
  echo "FAIL cosign_attest_sbom did not refuse an index before attesting"; fail=1
else
  echo "ok   cosign_attest_sbom refuses an index before attesting"
fi
rm -f "$calls" "$fake"

if [ "$fail" -ne 0 ]; then echo "self-test: FAIL"; exit 1; fi
echo "self-test: PASS"
