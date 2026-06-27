#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Re-register an ALREADY-INSTALLED self-hosted GitHub Actions runner whose
# server-side registration was lost, and (re)install it as a managed service so
# it self-heals on reboot / broker drop.
#
# Why this exists: a broker disconnect (broker.actions.githubusercontent.com,
# runner v2 flow) drops the runner's session; if it can't reconnect, GitHub
# auto-deletes the registration ("Runner registrations are automatically deleted
# for runners that have not connected recently"). The local daemon then crash-
# loops with "registration has been deleted, please re-configure", and a runner
# started as a bare `./run.sh` in a terminal just stays dead. This script fixes
# that without re-downloading the runner binary.
#
# GOTCHA baked in: the v2 migration leaves a `.runner_migrated` marker; without
# removing it `config.sh` refuses with "already configured" even after .runner is
# gone. We remove it here.
#
# Works on macOS (launchd) and Linux (systemd) — svc.sh abstracts both.
#
# Usage (run as the runner's own user, from anywhere):
#   openbank-infra/scripts/reregister-runner.sh
#
# Env overrides (all optional; defaults match the OpenBank fleet):
#   RUNNER_DIR     runner install dir            (default: $HOME/actions-runner)
#   RUNNER_NAME    runner name on GitHub         (default: hostname)
#   RUNNER_LABELS  custom labels (self-hosted/OS/arch are auto-added)
#                                  (default: openbank-build,openbank-batch,openbank-sandbox)
#   RUNNER_TOKEN   registration token            (default: minted via `gh`)
#   SVC_USER       Linux-only run-as user for svc install when running as root
#                                                (default: current user)
set -euo pipefail

REPO_URL="https://github.com/JiRaska/open-bank"
RUNNER_DIR="${RUNNER_DIR:-$HOME/actions-runner}"
RUNNER_NAME="${RUNNER_NAME:-$(hostname -s 2>/dev/null || hostname)}"
# Pool labels for a persistent accelerator host. `openbank-build` and
# `openbank-batch` are the SAME trust level (no cloud-write creds, both PR-allowed —
# rules.yaml: ci_runners.pr_jobs_allowed_pools); the build/batch split is an ARC
# CAPACITY lever (ADR-0053/0082: no job preemption, so a separate low-capped batch
# scale set is the only way a scan/cron burst can't starve the merge-required build
# lane). That isolation is an ARC-scheduler concern — a single static host schedules
# one job at a time, so it can safely sit in BOTH pools. Carrying `openbank-batch`
# here is what keeps the batch lane (Trivy in security.yml, FinOps in
# finops-lifecycle.yml — all `runs-on: openbank-batch`) served when the ARC batch
# scale set + Mac-mini are offline; a live GitHub-API label add is NOT durable (a
# re-register resets to these `--labels`). Hetzner hosts add the `hetzner` host-id
# tag: RUNNER_LABELS=openbank-build,openbank-batch,openbank-sandbox,hetzner
RUNNER_LABELS="${RUNNER_LABELS:-openbank-build,openbank-batch,openbank-sandbox}"

[ -x "$RUNNER_DIR/config.sh" ] || {
  echo "❌ No runner install at $RUNNER_DIR (config.sh missing). Set RUNNER_DIR=..." >&2
  exit 1
}

# Best-effort host tuning so Redpanda/Kafka Testcontainers don't flake on this host
# (ADR-0082). No-op on macOS / when not root; needs sudo on a Linux host to take effect.
TUNE_SCRIPT="$(cd "$(dirname "$0")" && pwd)/tune-runner-host.sh"
if [ -x "$TUNE_SCRIPT" ]; then
  "$TUNE_SCRIPT" || echo "⚠️  tune-runner-host.sh skipped/failed (non-root?) — run it with sudo to raise fs.aio-max-nr."
fi

cd "$RUNNER_DIR"

# Short-lived (~1h) registration token. Prefer an explicit RUNNER_TOKEN; else mint
# one via the GitHub CLI (must be authenticated with repo admin scope).
TOKEN="${RUNNER_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  command -v gh >/dev/null || {
    echo "❌ No RUNNER_TOKEN and gh CLI not found. Either install/login gh, or run:" >&2
    echo "   RUNNER_TOKEN=<token> $0   (mint at repo Settings → Actions → Runners → New)" >&2
    exit 1
  }
  echo "==> minting registration token via gh"
  TOKEN="$(gh api -X POST repos/JiRaska/open-bank/actions/runners/registration-token -q .token)"
fi
[ -n "$TOKEN" ] || { echo "empty registration token" >&2; exit 1; }

echo "==> stopping any existing service + stray listener"
./svc.sh stop 2>/dev/null || true
./svc.sh uninstall 2>/dev/null || true
pkill -f "$RUNNER_DIR/bin/Runner.Listener" 2>/dev/null || true
sleep 2

echo "==> clearing stale config (incl. the .runner_migrated v2 marker)"
rm -f .runner .runner_migrated .credentials .credentials_rsaparams

echo "==> registering '$RUNNER_NAME' [$RUNNER_LABELS]"
./config.sh --unattended --replace \
  --url "$REPO_URL" \
  --token "$TOKEN" \
  --name "$RUNNER_NAME" \
  --labels "$RUNNER_LABELS" \
  --work "_work"
unset TOKEN

echo "==> installing + starting as a managed service (self-heal)"
# On Linux, svc.sh install run as root takes the run-as user; on macOS it manages
# a per-user LaunchAgent and takes no user argument.
if [ "$(uname -s)" = "Linux" ] && [ "$(id -u)" = "0" ]; then
  ./svc.sh install "${SVC_USER:-$(logname 2>/dev/null || echo runner)}"
else
  ./svc.sh install
fi
./svc.sh start
sleep 4
./svc.sh status || true

echo "✅ done — verify at ${REPO_URL}/settings/actions/runners"
