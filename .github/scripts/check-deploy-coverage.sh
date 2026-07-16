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

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

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

for svc in "${RELEASED[@]}"; do
  # Not deployed by ArgoCD at all (a library, or not yet registered) — out of scope.
  _in "$svc" $GITOPS_REFS || continue
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

echo "==> Deploy-coverage: ${CHECKED} released component(s) with a gitops workload; ${#SKIPPED[@]} allowlisted; ${KNOWN} baselined."
for svc in ${SKIPPED[@]+"${SKIPPED[@]}"}; do echo "  ALLOWLISTED ${svc}  (built by its own workflow)"; done
for svc in ${BASELINED[@]+"${BASELINED[@]}"};  do echo "  BASELINED   ${svc}  (known debt — see ${BASELINE})"; done

RC=0

if [ "${#NEW_VIOLATIONS[@]}" -gt 0 ]; then
  echo
  echo "DEPLOY-COVERAGE GATE: FAIL — ${#NEW_VIOLATIONS[@]} released component(s) that ArgoCD deploys are"
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
  echo "DEPLOY-COVERAGE GATE: PASS — every released component ArgoCD deploys is built by auto-deploy."
fi
exit 0
