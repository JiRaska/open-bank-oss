#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Guard: every released component with a gitops workload must be in auto-deploy's
# ALL_SERVICES, release markers must reach DIRECT_SERVICES, and recovery PRs must target
# main (rules.yaml: deploy_coverage).
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

# DEPLOY_COVERAGE_ROOT lets the self-test point this at a fixture repo. Production never sets
# it, so the default stays "the repo this script lives in".
ROOT="${DEPLOY_COVERAGE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
# --- self-test ------------------------------------------------------------------------
# A service that release-please RELEASES and gitops REFERENCES, but auto-deploy does not
# build, is a service whose new version never reaches the cluster. Nothing fails: the release
# PR merges, the tag is cut, the changelog is written, and the running pod keeps serving the
# old image. The only symptom is a version number that stops moving, which nobody watches.
#
# The gate is a three-way set comparison, and every one of the three inputs is parsed out of a
# different file by a different means — so each can silently come back EMPTY, and an empty
# input makes the comparison agree.
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d); trap 'rm -rf "$td"' EXIT
  fails=0

  mkrepo() { # mkrepo <dir> <released-csv> <all-services-csv> <gitops-csv> [allowlist-csv] [baseline-csv]
    local d="$1" rel="$2" all="$3" gito="$4" allow="${5:-}" base="${6:-}"
    mkdir -p "$d/.github/workflows" "$d/.github/scripts" "$d/openbank-infra/gitops"
    printf '%s\n' "{\"packages\": {$(echo "$rel" | tr ',' '\n' | sed 's/.*/"&": {}/' | paste -sd, -)}}" \
      > "$d/release-please-config.json"
    printf "jobs:\n  x:\n    steps:\n      - run: |\n          ALL_SERVICES='%s'\n          if grep -qE \"^\${svc}/(src/main|build\\.gradle\\.kts|version\\.txt)\"; then true; fi\n      - uses: peter-evans/create-pull-request@pinned\n        with:\n          base: main\n" "$(echo "$all" | tr ',' ' ')" \
      > "$d/.github/workflows/auto-deploy.yml"
    : > "$d/openbank-infra/gitops/apps.yaml"
    for s in $(echo "$gito" | tr ',' ' '); do printf '  image: %s:1.0.0\n' "$s" >> "$d/openbank-infra/gitops/apps.yaml"; done
    [ -n "$allow" ] && echo "$allow" | tr ',' '\n' > "$d/.github/scripts/deploy-coverage-allowlist.txt"
    [ -n "$base" ]  && echo "$base"  | tr ',' '\n' > "$d/.github/scripts/deploy-coverage-baseline.txt"
    return 0
  }
  expect() { # expect <label> <dir> <want-rc> [substring]
    local label="$1" d="$2" want="$3" sub="${4:-}" out rc
    out=$(DEPLOY_COVERAGE_ROOT="$d" bash "$0" 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then
      echo "::error::self-test: $label — want rc=$want got $rc: $out" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong (no '$sub'): $out" >&2; fails=$((fails+1))
    fi
  }

  # Released, referenced by gitops, AND built: the only fully covered shape.
  a="$td/ok"; mkrepo "$a" openbank-a openbank-a openbank-a
  expect "a released+referenced+built service is clean" "$a" 0

  # THE DEFECT: released and referenced, but auto-deploy never builds it. The release lands
  # and the cluster keeps the old image.
  b="$td/gap"; mkrepo "$b" openbank-a openbank-other openbank-a
  expect "a released service auto-deploy does not build is a violation" "$b" 1

  # A baselined gap is known debt, not a new violation — the ratchet's whole purpose.
  c="$td/baselined"; mkrepo "$c" openbank-a openbank-other openbank-a "" openbank-a
  expect "a baselined gap is not a new violation" "$c" 0

  # ...and a baseline entry that is no longer needed must be reported, or the list only ever
  # grows and becomes permanent by being invisible.
  d="$td/stale"; mkrepo "$d" openbank-a openbank-a openbank-a "" openbank-a
  expect "a stale baseline entry is reported" "$d" 1 "no longer violating"

  # An allowlisted service is a declared exception and stays silent.
  e="$td/allowed"; mkrepo "$e" openbank-a openbank-other openbank-a openbank-a
  expect "an allowlisted service is skipped" "$e" 0

  # SCOPE: a released service gitops never references is not deployed by this pipeline at all,
  # so it is not this gate's business.
  f="$td/nogitops"; mkrepo "$f" openbank-a openbank-other openbank-b
  expect "a service gitops does not reference is out of scope" "$f" 0

  # A missing input must ABORT, not compare against nothing. An empty ALL_SERVICES would make
  # every released service look unbuilt; an unreadable config would make none of them checked.
  g="$td/noworkflow"; mkrepo "$g" openbank-a openbank-a openbank-a; rm "$g/.github/workflows/auto-deploy.yml"
  expect "a missing workflow aborts rather than comparing" "$g" 2
  h="$td/noallservices"; mkrepo "$h" openbank-a openbank-a openbank-a
  printf 'jobs:\n  x:\n    steps:\n      - run: echo nothing\n' > "$h/.github/workflows/auto-deploy.yml"
  expect "an unreadable ALL_SERVICES aborts" "$h" 2

  # A version-only release commit triggers the workflow but must also enter the service set.
  # Otherwise the run is green while build-push/gitops-pr are skipped (observed on #7077).
  i="$td/noversionmarker"; mkrepo "$i" openbank-a openbank-a openbank-a
  sed -i.bak 's/|version\\\.txt//' "$i/.github/workflows/auto-deploy.yml"; rm -f "$i/.github/workflows/auto-deploy.yml.bak"
  expect "version-only releases are detected as direct service changes" "$i" 1 "version.txt"

  # A recovery dispatch checks out a non-main ref. Without an explicit PR base, the generated
  # deploy PR contains every main commit since that ref (observed on #7121/#7127).
  j="$td/nomainbase"; mkrepo "$j" openbank-a openbank-a openbank-a
  sed -i.bak '/base: main/d' "$j/.github/workflows/auto-deploy.yml"; rm -f "$j/.github/workflows/auto-deploy.yml.bak"
  expect "recovery deploy PRs explicitly target main" "$j" 1 "base: main"

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: deploy-coverage guard is falsifiable (10 cases)"
  exit 0
fi

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
# `|| true` is load-bearing, not defensive noise: under `set -euo pipefail` a no-match grep
# exits 1, the pipeline inherits it, and the script dies HERE — before the explicit check one
# line below, which is therefore unreachable. Measured: a workflow with no ALL_SERVICES
# produced rc=1 and completely EMPTY output — no diagnosis at all. Same shape as
# check-agent-charter-registry.sh (batch 6) and check-adr-registry.sh (batch 16).
#
# Regressed once already by a merge-conflict resolution that picked the pre-fix side of this
# exact line — the fix lived outside the conflicted hunk, so the diff never showed it as
# changed, and taking HEAD silently un-fixed it. Caught by the self-test going UNFALSIFIED,
# which is what that state exists to catch. If you are resolving a conflict here again: keep
# the `|| true`.
ALL_SERVICES="$(grep -oE "ALL_SERVICES='[^']+'" "$WORKFLOW" | head -1 | sed "s/ALL_SERVICES='//; s/'$//" || true)"
[ -n "$ALL_SERVICES" ] || { echo "ERROR: could not read ALL_SERVICES from ${WORKFLOW}." >&2; exit 2; }

# The workflow trigger already includes openbank-*/version.txt. The detector must agree or a
# release-only commit starts a successful no-op run: no image, no PR, stale pod.
DIRECT_SERVICE_DETECTOR="$(grep -F 'if grep -qE "^${svc}/' "$WORKFLOW" | head -1 || true)"
if [[ "$DIRECT_SERVICE_DETECTOR" != *'version\.txt'* ]]; then
  echo "ERROR: auto-deploy DIRECT_SERVICES does not detect version.txt release markers." >&2
  exit 1
fi

# create-pull-request otherwise defaults to the checked-out workflow_dispatch ref. Recovery
# runs originate at historical refs, so an implicit base creates a PR containing main history.
CREATE_PR_BLOCK="$(awk '
  /uses: peter-evans\/create-pull-request@/ { in_block=1 }
  in_block && /^[[:space:]]+- name:/ { exit }
  in_block { print }
' "$WORKFLOW")"
if ! grep -qE '^[[:space:]]+base:[[:space:]]+main([[:space:]]|$)' <<< "$CREATE_PR_BLOCK"; then
  echo "ERROR: auto-deploy create-pull-request must set base: main for recovery refs." >&2
  exit 1
fi

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

echo "SUBJECTS=${CHECKED}"
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
