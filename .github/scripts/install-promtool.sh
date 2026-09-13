#!/usr/bin/env bash
# Install the pinned, checksum-verified promtool used by the alert-rule unit-test gate.
#
# Both the gate's `run` and its `selftest` need the binary and run in the same job, so this is
# idempotent and returns early when the pinned version is already on PATH.
set -euo pipefail

: "${PROMETHEUS_VERSION:?PROMETHEUS_VERSION must be set by the gate manifest}"
: "${PROMETHEUS_SHA256:?PROMETHEUS_SHA256 must be set by the gate manifest}"

if command -v promtool >/dev/null 2>&1 && promtool --version 2>&1 | grep -qF "${PROMETHEUS_VERSION}"; then
  echo "promtool ${PROMETHEUS_VERSION} already installed"
  exit 0
fi

tarball="prometheus-${PROMETHEUS_VERSION}.linux-amd64.tar.gz"
curl -fsSL --retry 3 --retry-delay 3 -o "/tmp/${tarball}" \
  "https://github.com/prometheus/prometheus/releases/download/v${PROMETHEUS_VERSION}/${tarball}"
echo "${PROMETHEUS_SHA256}  /tmp/${tarball}" | sha256sum -c -
tar -xzf "/tmp/${tarball}" -C /tmp "prometheus-${PROMETHEUS_VERSION}.linux-amd64/promtool"
sudo install -m 0755 "/tmp/prometheus-${PROMETHEUS_VERSION}.linux-amd64/promtool" /usr/local/bin/promtool
promtool --version
