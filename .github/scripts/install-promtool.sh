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
# github.com's web frontend can answer 504 for /releases/download/ while the REST API and the
# asset CDN keep working (2026-11-08, #15216). Fall back to the API asset route; the sha256
# check below applies to whichever route delivered the bytes.
if ! curl -fsSL --retry 2 --retry-delay 3 --max-time 120 -o "/tmp/${tarball}" \
    "https://github.com/prometheus/prometheus/releases/download/v${PROMETHEUS_VERSION}/${tarball}"; then
  echo "::notice::direct release download failed; fetching ${tarball} via the GitHub REST API"
  auth=()
  token="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
  [ -n "$token" ] && auth=(-H "Authorization: Bearer ${token}")
  asset_url=$(curl -fsSL --retry 2 --max-time 60 "${auth[@]}" \
    "https://api.github.com/repos/prometheus/prometheus/releases/tags/v${PROMETHEUS_VERSION}" \
    | python3 -c 'import json,sys; n=sys.argv[1]; print(next(a["url"] for a in json.load(sys.stdin)["assets"] if a["name"]==n))' "$tarball")
  curl -fsSL --retry 2 --max-time 300 "${auth[@]}" -H "Accept: application/octet-stream" \
    -o "/tmp/${tarball}" "$asset_url"
fi
echo "${PROMETHEUS_SHA256}  /tmp/${tarball}" | sha256sum -c -
tar -xzf "/tmp/${tarball}" -C /tmp "prometheus-${PROMETHEUS_VERSION}.linux-amd64/promtool"
sudo install -m 0755 "/tmp/prometheus-${PROMETHEUS_VERSION}.linux-amd64/promtool" /usr/local/bin/promtool
promtool --version
