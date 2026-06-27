#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Raise fs.aio-max-nr on a self-hosted Linux GitHub Actions runner host so that
# Redpanda / Kafka Testcontainers can allocate their async-I/O contexts.
#
# WHY: the distro default (commonly 65536) is exhausted by several dind containers
# running concurrently on one host. Redpanda then dies at startup with
#   libc++abi: ... Could not setup Async I/O: 65536 ... /proc/sys/fs/aio-max-nr
#   (Resource temporarily unavailable)
# which surfaces as flaky *ApiIT / Pact provider-verification failures (it bit the
# `openbank-build` Hetzner pool repeatedly — the ARC pool already raises it via the
# `init-aio-sysctl` init container in arc-runners.tf; this is the equivalent for the
# manually-provisioned Linux hosts). fs.aio-max-nr is a NON-namespaced kernel
# parameter, so it must be set on the host, not inside a container. See ADR-0082.
#
# Idempotent and monotonic: it never lowers an already-higher value, persists a
# drop-in so it survives reboot, and is a safe no-op on macOS / when not root.
set -euo pipefail

TARGET=1048576
DROPIN=/etc/sysctl.d/99-openbank-runner-aio.conf

if [ "$(uname -s)" != "Linux" ]; then
  echo "tune-runner-host: $(uname -s) is not Linux — skipping (macOS runners use the Docker VM's own aio limit)."
  exit 0
fi

if [ "$(id -u)" != "0" ]; then
  echo "tune-runner-host: must run as root to set fs.aio-max-nr — skipping (re-run with sudo)." >&2
  exit 0
fi

current="$(cat /proc/sys/fs/aio-max-nr 2>/dev/null || echo 0)"
# Monotonic: keep whichever is larger so we never shrink a host that is already tuned higher.
value="$TARGET"
if [ "$current" -gt "$TARGET" ]; then
  value="$current"
fi

echo "fs.aio-max-nr = $value" > "$DROPIN"
sysctl -p "$DROPIN" >/dev/null
echo "tune-runner-host: fs.aio-max-nr is now $(cat /proc/sys/fs/aio-max-nr) (was $current; persisted in $DROPIN)."
