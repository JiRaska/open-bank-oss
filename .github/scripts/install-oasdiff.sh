#!/usr/bin/env bash
# Install the pinned, checksum-verified oasdiff used by the api-contract gate.
#
# Both the gate's `run` and its `selftest` need the binary — the classifier self-test cases added
# for #6380 call oasdiff directly — and the two steps run in the same job, so this is idempotent
# and returns early when the pinned version is already on PATH.
set -euo pipefail

: "${OASDIFF_VERSION:?OASDIFF_VERSION must be set by the gate manifest}"
: "${OASDIFF_SHA256:?OASDIFF_SHA256 must be set by the gate manifest}"

if command -v oasdiff >/dev/null 2>&1 && oasdiff --version 2>/dev/null | grep -qF "${OASDIFF_VERSION}"; then
  echo "oasdiff ${OASDIFF_VERSION} already installed"
  exit 0
fi

curl -fsSL --retry 3 --retry-delay 3 -o /tmp/oasdiff.tar.gz \
  "https://github.com/oasdiff/oasdiff/releases/download/v${OASDIFF_VERSION}/oasdiff_${OASDIFF_VERSION}_linux_amd64.tar.gz"
echo "${OASDIFF_SHA256}  /tmp/oasdiff.tar.gz" | sha256sum -c -
tar -xzf /tmp/oasdiff.tar.gz -C /tmp oasdiff
sudo install -m 0755 /tmp/oasdiff /usr/local/bin/oasdiff
oasdiff --version
