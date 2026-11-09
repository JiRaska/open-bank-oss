#!/usr/bin/env bash
# Install the pinned, checksum-verified promtool used by the alert-rule unit-test gate.
#
# Version and sha256 live in ci/artifacts.lock (entry `promtool`, #15216); the archive is fetched
# through the resolver, which verifies it against the lock from every tier (cache, mirror,
# upstream). An unavailable artifact exits 75 with `UNVERIFIED (artifact unavailable)`, which this
# script passes through unchanged so the gate log says "could not fetch", never "rules broken".
#
# Both the gate's `run` and its `selftest` need the binary and run in the same job, so this is
# idempotent and returns early when the pinned version is already on PATH.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
resolver=(python3 -I "${here}/fetch-artifact.py")
version="$("${resolver[@]}" promtool --platform linux-amd64 --show version)"

if command -v promtool >/dev/null 2>&1 && promtool --version 2>&1 | grep -qF "${version}"; then
  echo "promtool ${version} already installed"
  exit 0
fi

tarball="/tmp/prometheus-${version}.linux-amd64.tar.gz"
rc=0
"${resolver[@]}" promtool --platform linux-amd64 --out "${tarball}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  exit "${rc}"
fi
tar -xzf "${tarball}" -C /tmp "prometheus-${version}.linux-amd64/promtool"
sudo install -m 0755 "/tmp/prometheus-${version}.linux-amd64/promtool" /usr/local/bin/promtool
promtool --version
