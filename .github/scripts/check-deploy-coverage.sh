#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Guard: every released component with a gitops workload must be in auto-deploy's
# ALL_SERVICES (rules.yaml: deploy_coverage).
#
# THE FAILURE THIS CATCHES
# A service can be merged, released by release-please, and given an ArgoCD manifest, yet
# still never be built — because `ALL_SERVICES` in .github/workflows/auto-deploy.yml is a
# hand-maintained list and nothing kept it in sync with release-please's package list.
# Every push then "succeeds" while deploying nothing. There is no build failure, because
# there is no build. The gitops tag simply stays at whatever it was — for weeks.
#
# This has happened at least six times: the 6 ADR-0163/0164-0168 control agents,
# document-service, clearing-simulator (24 days of pushes deployed nothing, and it went
# unattested through the 2026-07-12 Enforce graduation — one reschedule from an outage),
# finrep-service and vop-service. Each was fixed by appending an entry and a comment
# to auto-deploy.yml. The comments are still there. The class kept recurring, which is
# the argument for a guard over prose (rules.yaml: knowledge_capture).
#
# WHY *THIS* RULE, AND NOT "every service needs a Dockerfile"
# A per-service Dockerfile is NOT required to build. auto-deploy and build-push-service.sh
# both generate their own from a canonical template and only grep the per-service file for
# its `EXPOSE` line, defaulting to 8080 when absent. `EXPOSE` is image metadata that
# Kubernetes ignores — the manifest's `containerPort` is what binds. Six healthy services
# (devops-agent, finops-agent, fraud-service, lending-service, sdd-service,
# settlement-service) have no Dockerfile and run fine, with containerPort matching their
# application.yaml exactly. A "must have a Dockerfile" guard would fail all six for no
# reason and be switched off within a week. The invariant that actually holds is the one
# below, and it flags exactly the two services that are down.
# ---------------------------------------------------------------------------------------
set -euo pipefail

ROOT="${DEPLOY_COVERAGE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$ROOT"

# --self-test drives this same script over fixture trees, including the case the pre-#4576
# subject set could not express. Kept below the ROOT resolution so the fixtures can point at it.
if [ "${1:-}" = "--self-test" ]; then
  SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  OK=0

  _fixture() { # $1=dir $2=ALL_SERVICES $3=gitops image line(s) $4=released json packages
    mkdir -p "$1/.github/workflows" "$1/.github/scripts" "$1/openbank-infra/gitops/components"
    printf "            ALL_SERVICES='%s'\n" "$2" > "$1/.github/workflows/auto-deploy.yml"
    printf '%s\n' "$3" > "$1/openbank-infra/gitops/components/w.yaml"
    printf '{"packages":{%s}}\n' "$4" > "$1/release-please-config.json"
  }
  # `set -o pipefail` is active for this whole script, so `_run ... | grep -q ...` would take its
  # exit status from the PIPELINE, not from grep — and the recursive run legitimately exits 1 on a
  # FAIL fixture, which would make every `if` below read as "grep did not match" regardless of
  # whether it did. Capture first, grep the captured text, so only grep's exit status is tested.
  _run() { DEPLOY_COVERAGE_ROOT="$1" bash "$SELF" > "$TMP/out" 2>&1; cat "$TMP/out"; }

  # A: the analytics-sink case — sandbox-tagged, deployed, NOT released, NOT built.
  # The old subject set (released components only) reported PASS here; that is the whole bug.
  _fixture "$TMP/a" "openbank-account-service" "  image: ecr/openbank-ghost-service:sandbox-abc123" '"openbank-account-service":{}'
  if _run "$TMP/a" | grep -q "openbank-ghost-service"; then :; else
    echo "SELF-TEST FAIL: an unbuilt, sandbox-tagged, unreleased workload was not flagged"; OK=1; fi

  # B: a version-pinned third-party image is not ours to build and must NOT be flagged.
  _fixture "$TMP/b" "openbank-account-service" "  image: ecr/openbank-thirdparty:9.9.9-pinned" '"openbank-account-service":{}'
  if _run "$TMP/b" | grep -q "openbank-thirdparty"; then
    echo "SELF-TEST FAIL: a version-pinned third-party image was flagged as a coverage gap"; OK=1; fi

  # C: a built service is clean.
  _fixture "$TMP/c" "openbank-account-service" "  image: ecr/openbank-account-service:sandbox-abc123" '"openbank-account-service":{}'
  if ! _run "$TMP/c" | grep -q "PASS"; then
    echo "SELF-TEST FAIL: a fully covered tree did not pass"; OK=1; fi

  # D: the ratchet — a baselined entry that is now built must FAIL, or debt becomes permanent.
  _fixture "$TMP/d" "openbank-account-service" "  image: ecr/openbank-account-service:sandbox-abc123" '"openbank-account-service":{}'
  printf 'openbank-account-service\n' > "$TMP/d/.github/scripts/deploy-coverage-baseline.txt"
  if ! _run "$TMP/d" | grep -q "no longer violating"; then
    echo "SELF-TEST FAIL: a stale baseline entry did not fail the ratchet"; OK=1; fi

  # E: an absent subject must not read as clean.
  _fixture "$TMP/e" "openbank-account-service" "  no images here" '"openbank-account-service":{}'
  if ! _run "$TMP/e" | grep -qE "no deployable workload|SUBJECTS=0"; then
    echo "SELF-TEST FAIL: an empty subject set did not announce itself"; OK=1; fi

  [ "$OK" -eq 0 ] && echo "SELF-TEST PASS: deploy-coverage is falsifiable (5 cases)"
  exit "$OK"
fi

WORKFLOW=".github/workflows/auto-deploy.yml"
RP_CONFIG="release-please-config.json"
GITOPS="openbank-infra/gitops"
ALLOWLIST=".github/scripts/deploy-coverage-allowlist.txt"
BASELINE=".github/scripts/deploy-coverage-baseline.txt"

for f in "$WORKFLOW" "$RP_CONFIG"; do
  [ -f "$f" ] || { echo "ERROR: ${f} not found — run from the repo root." >&2; exit 2; }
done
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required." >&2; exit 2; }

# --- inputs ---------------------------------------------------------------------------

# Released components: a module is a released component IFF it is registered with
# release-please (CLAUDE.md / ADR-0029).
# `while read` rather than `mapfile`: mapfile is bash 4+, and macOS ships bash 3.2. A guard
# that only runs on the CI runner is a guard nobody can reproduce locally.
RELEASED=()
while IFS= read -r line; do
  [ -n "$line" ] && RELEASED+=("$line")
done < <(jq -r '.packages | keys[]' "$RP_CONFIG" | grep '^openbank-' | sort)

# The hand-maintained deploy list.
ALL_SERVICES="$(grep -oE "ALL_SERVICES='[^']+'" "$WORKFLOW" | head -1 | sed "s/ALL_SERVICES='//; s/'$//")"
[ -n "$ALL_SERVICES" ] || { echo "ERROR: could not read ALL_SERVICES from ${WORKFLOW}." >&2; exit 2; }

# Components that ArgoCD actually deploys, i.e. something references their image.
# Position-blind on purpose: an initContainer or sidecar is still a workload image.
GITOPS_REFS="$(grep -rhoE 'openbank-[a-z0-9-]+:[A-Za-z0-9._-]+' "$GITOPS" 2>/dev/null \
  | sed -E 's/:.*$//' | sort -u || true)"

# Workloads pinned to a `sandbox-<sha>` tag: that tag shape IS this pipeline's output, so anything
# wearing one is something this repo is expected to build. Derived, not hand-maintained — and it is
# the same test auto-deploy applies to decide a service is deployable at all.
#
# This is what widens the gate past released components. The subject set used to be
# release-please's package list, so a service with no version.txt was invisible: openbank-analytics-
# sink has been deployed since 2026-07-26 and absent from ALL_SERVICES for its whole life, and this
# gate passed every run without ever looking at it (#4553 / #4576). A gate whose scope is derived
# from a DIFFERENT registry than the one it protects can only cover their intersection.
#
# Version-pinned third-party images (openbank-keycloak:26.6.3-optimized,
# openbank-pyroscope-agent:2.5.4) are excluded by construction rather than by an allowlist entry:
# they carry an upstream version, never a sandbox tag, so nobody has to remember to except them.
SANDBOX_REFS="$(grep -rhoE 'openbank-[a-z0-9-]+:sandbox-[A-Za-z0-9._-]+' "$GITOPS" 2>/dev/null \
  | sed -E 's/:.*$//' | sort -u || true)"

_read_list() {
  local f="$1" line
  [ -f "$f" ] || return 0
  while IFS= read -r line; do
    line="${line%%#*}"; line="$(echo "$line" | xargs || true)"
    [ -n "$line" ] && printf '%s\n' "$line"
  done < "$f"
}

ALLOWED=()
while IFS= read -r l; do [ -n "$l" ] && ALLOWED+=("$l"); done < <(_read_list "$ALLOWLIST")
BASELINED=()
while IFS= read -r l; do [ -n "$l" ] && BASELINED+=("$l"); done < <(_read_list "$BASELINE")

_in() { local n="$1"; shift; local x; for x in "$@"; do [ "$x" = "$n" ] && return 0; done; return 1; }

# --- check ----------------------------------------------------------------------------

NEW_VIOLATIONS=()   # not built, not allowlisted, not baselined -> the thing we exist to stop
STALE_BASELINE=()   # baselined but now fine -> ratchet: the entry must go
SKIPPED=()
KNOWN=0
CHECKED=0

# Subjects: every released component ArgoCD deploys (the original scope), PLUS every workload
# wearing a sandbox tag whether or not it is a released component (the widening).
SUBJECTS=()
for svc in "${RELEASED[@]}"; do _in "$svc" $GITOPS_REFS && SUBJECTS+=("$svc"); done
for svc in $SANDBOX_REFS; do _in "$svc" ${SUBJECTS[@]+"${SUBJECTS[@]}"} || SUBJECTS+=("$svc"); done

if [ "${#SUBJECTS[@]}" -eq 0 ]; then
  echo "SUBJECTS=0"
  echo "DEPLOY-COVERAGE GATE: no deployable workload found under ${GITOPS} — the gate cannot have"
  echo "checked anything. This is a path or parse problem, not a clean tree."
  exit 1
fi

for svc in "${SUBJECTS[@]}"; do
  CHECKED=$((CHECKED + 1))

  if _in "$svc" ${ALLOWED[@]+"${ALLOWED[@]}"}; then
    SKIPPED+=("$svc")
    continue
  fi

  if _in "$svc" $ALL_SERVICES; then
    # Built. If it is still baselined, the debt is paid — the ratchet must tighten.
    _in "$svc" ${BASELINED[@]+"${BASELINED[@]}"} && STALE_BASELINE+=("$svc")
    continue
  fi

  # Not built.
  if _in "$svc" ${BASELINED[@]+"${BASELINED[@]}"}; then
    KNOWN=$((KNOWN + 1))
  else
    NEW_VIOLATIONS+=("$svc")
  fi
done

echo "SUBJECTS=${CHECKED}"
echo "==> Deploy-coverage: ${CHECKED} deployable workload(s) (released components + sandbox-tagged); ${#SKIPPED[@]} allowlisted; ${KNOWN} baselined."
for svc in ${SKIPPED[@]+"${SKIPPED[@]}"}; do echo "  ALLOWLISTED ${svc}  (built by its own workflow)"; done
for svc in ${BASELINED[@]+"${BASELINED[@]}"};  do echo "  BASELINED   ${svc}  (known debt — see ${BASELINE})"; done

RC=0

if [ "${#NEW_VIOLATIONS[@]}" -gt 0 ]; then
  echo
  echo "DEPLOY-COVERAGE GATE: FAIL — ${#NEW_VIOLATIONS[@]} deployable workload(s) that ArgoCD deploys are"
  echo "not in auto-deploy's ALL_SERVICES, so every push would silently deploy nothing for them:"
  echo
  for svc in "${NEW_VIOLATIONS[@]}"; do echo "  - ${svc}"; done
  echo
  echo "Fix: add each to ALL_SERVICES in ${WORKFLOW}."
  echo "  - If something ELSE builds it (as admin-ui's own workflow does), add it to"
  echo "    ${ALLOWLIST} with the reason."
  echo "  - Do NOT add it to ${BASELINE} to make this pass. That file"
  echo "    is shrink-only debt, not an escape hatch; growing it is how ALL_SERVICES drifted"
  echo "    six times over. A component nobody builds has a gitops tag that rots in place —"
  echo "    silently, until a pod reschedules and cannot be admitted."
  RC=1
fi

if [ "${#STALE_BASELINE[@]}" -gt 0 ]; then
  echo
  echo "DEPLOY-COVERAGE GATE: FAIL — ${#STALE_BASELINE[@]} baseline entr(y/ies) are no longer violating."
  echo "The ratchet only tightens: delete them from ${BASELINE}."
  echo
  for svc in "${STALE_BASELINE[@]}"; do echo "  - ${svc}  (now in ALL_SERVICES — debt paid)"; done
  RC=1
fi

[ "$RC" -eq 0 ] || exit 1

if [ "$KNOWN" -gt 0 ]; then
  echo
  echo "DEPLOY-COVERAGE GATE: PASS — no NEW gaps. ${KNOWN} known gap(s) remain in ${BASELINE}."
else
  echo
  echo "DEPLOY-COVERAGE GATE: PASS — every deployable workload ArgoCD runs is built by auto-deploy."
fi
exit 0
