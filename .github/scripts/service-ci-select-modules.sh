#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Services CI scope selection: decides which Gradle modules the build matrix covers.
# Extracted verbatim from the `detect` step of .github/workflows/services-ci.yml, which sat
# exactly at the `workflow-run-step-size` limit (19000 chars) and tripped it on any one-line
# addition. Logic is unchanged; only the GitHub expression interpolations became env vars.
#
# Inputs (env):  EVENT_NAME  github.event_name
#                BASE_REF    github.base_ref (pull_request only)
#                SELECT_OUT  directory to write results into
#                GITHUB_OUTPUT  (push only) receives admin-ui-pact-changed=true
# Outputs:       $SELECT_OUT/changed.txt  space-separated module list (unsorted, may be empty)
#                $SELECT_OUT/files.txt    changed-file list used for the decision
# Run from the repository root.
#
#   service-ci-select-modules.sh             # select
#   service-ci-select-modules.sh --self-test # prove it can select, and can fail to
set -euo pipefail

select_modules() {
  # Canonical Gradle module list — DISCOVERED from the tree exactly like
  # settings.gradle.kts (every `openbank-*` dir with a build.gradle.kts), NOT hardcoded.
  # A hardcoded list silently drifts: a newly-added service is absent from it, so its
  # build job is skipped on the PR that introduces it AND in the nightly full-fleet run,
  # yet `all-green` passes (skipped != failed) — a branch-protection hole that let
  # openbank-fraud-service (and anacredit-service, analytics-sink) merge unbuilt (#1001).
  # Deriving it from the same rule as settings.gradle.kts keeps CI and the build in sync
  # and builds a new service on its introducing PR. `openbank-admin-ui` (Next.js) and
  # `openbank-infra` (OpenTofu) have no build.gradle.kts, so neither is picked up.
  ALL=$(for d in openbank-*/; do if [ -f "${d}build.gradle.kts" ]; then printf '%s ' "${d%/}"; fi; done)

  # The sourced selector is exercised by positive and negative controls before it
  # can select a build matrix. Unknown paths still take the conservative fallback.
  # shellcheck source=.github/scripts/service-ci-pact-modules.sh
  source .github/scripts/service-ci-pact-modules.sh
  pact_build_modules_self_test
  python3 .github/scripts/coverage-floor-only-diff.py --self-test

  if [ "$EVENT_NAME" = "schedule" ] \
     || [ "$EVENT_NAME" = "workflow_dispatch" ]; then
    echo "Event $EVENT_NAME -> build all modules (full-fleet)."
    CHANGED="$ALL"
  elif [ "$EVENT_NAME" = "push" ]; then
    # Scope service and Pact changes; inert release/docs/infra changes build nothing.
    # Unknown build inputs fall back to the full fleet below. Full history makes
    # HEAD~1 available on main (squash-merged pushes are single commits).
    DIFF_BASE=HEAD~1
    FILES=$(git diff --name-only "$DIFF_BASE" HEAD 2>/dev/null || true)
    if admin_ui_pact_changed "$FILES"; then
      echo "admin-ui-pact-changed=true" >> "$GITHUB_OUTPUT"
    fi
    echo "Push changed files:"; echo "$FILES"
    # Inert = provably cannot change a compile input or a contract. A service's
    # source, any build input, Pact, or unknown automation path remains relevant and
    # therefore keeps the conservative scoped/full-fleet behavior below.
    RELEVANT=""
    while IFS= read -r f; do
      [ -z "$f" ] && continue
      if ! is_inert_service_path "$f"; then RELEVANT="${RELEVANT}${RELEVANT:+$'\n'}${f}"; fi
    done <<< "$FILES"
    RELEVANT_COUNT=$(grep -c . <<< "$RELEVANT" || true)
    if [ "${RELEVANT_COUNT:-0}" -eq 0 ]; then
      echo "Decision: push changed only inert paths (release/docs/admin-ui/infra/external workflows) -> build nothing."
      CHANGED=""
    else
      # Scope the main-push build instead of always full-fleeting (#1208/#2039). Full-fleet
      # on ANY build-relevant change is ~30 jobs and, on the 6-runner pool, wedges the queue
      # for hours (a merged contract fix sits unverified behind it — the #1348 drain, #945).
      # The safe direction is still over-build: the moment a changed path is NOT cleanly
      # attributable to a module or a pact pair, fall back to full-fleet. Never under-build
      # the Pact graph.
      #   - code-global input (core libs, gradle, build-logic) -> full-fleet.
      #     libs-lending, libs-iso20022 and libs-finance follow their declared Gradle consumers.
      #     Lib test-only paths excepted (filter-lib-test-only-paths.py).
      #   - pacts/<consumer>-<provider>.json changed -> build both Gradle modules. The
      #     Admin UI consumer is not a Gradle module: its separate trusted Pact workflow
      #     publishes it, so only its provider belongs in this matrix.
      #   - <module>/... changed -> that module.
      #   - openbank-libs/governance/ -> data for CI checks/admin-ui, not a compile input.
      #   - the CI recipe itself -> canary below.
      #   - anything else -> unattributable -> full-fleet (safety).
      FILES_CODE=$(grep -vE '^openbank-libs/governance/' <<< "$RELEVANT" || true)
      FILES_GLOBAL=$(python3 .github/scripts/filter-lib-test-only-paths.py <<< "$FILES_CODE")
      # An exact raised-floor-only diff stays local to libs-domain.
      if grep -Fxq 'openbank-libs-domain/build.gradle.kts' <<< "$FILES_GLOBAL" && python3 .github/scripts/coverage-floor-only-diff.py --base "$DIFF_BASE"; then
        echo "Decision: shared domain build script changes only its raised coverage floor."
        FILES_GLOBAL=$(grep -vFx 'openbank-libs-domain/build.gradle.kts' <<< "$FILES_GLOBAL" || true)
      fi
      if global_service_build_input_changed "$FILES_GLOBAL"; then
        echo "Decision: shared build input changed -> full-fleet."
        CHANGED="$ALL"
      else
        CHANGED=""
        SCOPE_OK=1
        while IFS= read -r f; do
          [ -z "$f" ] && continue
          case "$f" in
            pacts/*.json)
              if ! modules=$(pact_build_modules "$f"); then SCOPE_OK=0; continue; fi
              while IFS= read -r p; do
                if grep -qE "(^| )${p}( |$)" <<< " $ALL "; then CHANGED="$CHANGED $p"; else SCOPE_OK=0; fi
              done <<< "$modules" ;;
            openbank-libs/governance/*) : ;;
            .github/workflows/services-ci.yml|.github/workflows/_service-ci.yml|.github/scripts/service-ci-pact-modules.sh|.github/scripts/service-ci-select-modules.sh|.github/scripts/service-ci-library-dependents.py|.github/scripts/await-admin-ui-pact-publication.py|.github/scripts/test-await-admin-ui-pact-publication.py) : ;;
            openbank-*/*)
              m="${f%%/*}"
              if grep -qE "(^| )${m}( |$)" <<< " $ALL "; then CHANGED="$CHANGED $m"; else SCOPE_OK=0; fi ;;
            *) SCOPE_OK=0 ;;
          esac
        done <<< "$RELEVANT"
        if [ "$SCOPE_OK" -eq 0 ]; then
          echo "Decision: a changed path is not cleanly attributable -> full-fleet (safety)."
          CHANGED="$ALL"
        else
          # Include direct and transitive consumers of the small shared libraries.
          CHANGED="$CHANGED $(python3 .github/scripts/service-ci-library-dependents.py "$ALL" <<< "$FILES_GLOBAL")"
          # (No dedup here: the matrix JSON below is built with `sort -u`, so a service
          # named by both its src and a pact edge collapses to one job downstream.)
          # DST-harness edge (ADR-0115/#267) — same as the PR path.
          if { grep -qE '(^| )openbank-(ledger|balance|transaction|billing|interest|statement)-service( |$)' <<< " $CHANGED " \
               || grep -qE '(^| )openbank-sepa-payment( |$)' <<< " $CHANGED " \
               || grep -qE '(^| )openbank-settlement-service( |$)' <<< " $CHANGED "; } \
             && ! grep -qE '(^| )openbank-simulation( |$)' <<< " $CHANGED "; then
            CHANGED="$CHANGED openbank-simulation"
          fi
          # Generated-client edge (ADR-0319) — same as the PR path.
          if grep -qE '(^| )openbank-product-catalog( |$)' <<< " $CHANGED " \
             && ! grep -qE '(^| )openbank-client-product-catalog( |$)' <<< " $CHANGED "; then
            CHANGED="$CHANGED openbank-client-product-catalog"
          fi
          if grep -qE '(^| )openbank-transaction-service( |$)' <<< " $CHANGED " \
             && ! grep -qE '(^| )openbank-client-transaction( |$)' <<< " $CHANGED "; then
            CHANGED="$CHANGED openbank-client-transaction"
          fi
          # Recipe-only change -> one canary (same as the PR path).
          if [ -z "${CHANGED// /}" ] \
             && grep -qE '^(\.github/workflows/(services-ci|_service-ci)\.yml|\.github/scripts/(service-ci-pact-modules\.sh|service-ci-select-modules\.sh|service-ci-library-dependents\.py|(test-)?await-admin-ui-pact-publication\.py))$' <<< "$FILES"; then
            CHANGED="openbank-libs"
          fi
          echo "Decision: scoped main-push build -> ${CHANGED:-<none>}"
        fi
      fi
    fi
  else
    BASE="origin/$BASE_REF"
    # Persistent self-hosted runners REUSE the git workdir between jobs,
    # so the `origin/<base>` remote-tracking ref can be stale from an
    # earlier run. Force it to the true remote tip with an explicit
    # refspec (a bare `git fetch origin main` updates FETCH_HEAD but does
    # NOT reliably move refs/remotes/origin/main). A stale base silently
    # mis-computes the diff and under-builds changed services — a
    # branch-protection hole. Full depth (no --depth=1): a shallow graft
    # would break merge-base once main advances past this PR's base.
    git fetch --no-tags --force origin \
      "+refs/heads/$BASE_REF:refs/remotes/origin/$BASE_REF" \
      >/dev/null 2>&1 || { echo "::error::Cannot refresh ${BASE}; build scope is unknown."; exit 1; }
    # Compute the merge-base explicitly rather than leaning on the
    # three-dot shorthand's ref resolution, then diff from it. Equivalent
    # to BASE...HEAD but observable and robust to odd ref state.
    MB=$(git merge-base "$BASE" HEAD 2>/dev/null) || { echo "::error::Cannot find merge-base with ${BASE}; build scope is unknown."; exit 1; }
    DIFF_BASE="$MB"
    FILES=$(git diff --name-only "$MB" HEAD)
    echo "Detect: base=$BASE merge-base=$MB changed-files=$(echo "$FILES" | grep -c . || true)"
    echo "Changed files:"; echo "$FILES" | sed 's/^/  /'

    # Code-global inputs every module compiles/tests against -> rebuild all.
    # Except a lib's own test sources (filter-lib-test-only-paths.py header).
    # (openbank-libs*, version catalog, root build, gradle.properties,
    # build-logic.) A change here can alter any service's compile or test
    # inputs, so the whole fleet must rebuild.
    # NB: feed grep via a here-string, NOT `echo "$FILES" | grep -q`: under
    # pipefail the SIGPIPE (141) racily reads a match as FALSE and drops modules.
    # Governance catalogs (openbank-libs/governance/) are data for CI
    # checks + admin-ui, not a compile/test input of any module — an
    # ADR/control-catalog edit must not cost a 28-module fleet run.
    #
    # `openbank-libs/` alone is stale since ADR-0122 split the module in two
    # (#33/#34): openbank-libs-domain and openbank-libs-runtime are compiled
    # into EVERY service exactly like openbank-libs was, and
    # openbank-libs-testing supplies the shared Pact/authz/outbox conformance
    # kits every service's test source set depends on (testImplementation) —
    # i.e. it can change *how* a provider's Pact verification test runs
    # without touching that provider's own src/ or any pacts/*.json, and
    # without a `pacts/` diff to catch it downstream (issue #1208 triage,
    # gap #2). A literal `^openbank-libs/` prefix does not match any of the
    # three split module names, so before this fix a change to any of them
    # fell through to the per-module fan-out below and rebuilt only that
    # module itself — a silent under-build of every other service's compile
    # and Pact-provider-test inputs. auto-deploy.yml's analogous SHARED_CHANGED
    # check already lists openbank-libs-domain/openbank-libs-runtime
    # explicitly (#376); this brings services-ci.yml's PR-path trigger in
    # line with it, plus openbank-libs-testing (auto-deploy correctly omits
    # it — it's test-only, never shipped in a runtime image).
    # Release-bookkeeping paths are inert here for the SAME reason the push path
    # above already treats them as inert (the `RELEVANT` filter): version.txt,
    # CHANGELOG.md and the release-please manifest cannot alter any module's
    # compile inputs or any pact. Without this the PR lane fanned out per-module
    # on `^<module>/`, so every release-please PR built the service it bumps —
    # 194 of them in the week of 2026-07-25, each a build of a two-line diff whose
    # post-merge push is then skipped as inert anyway. It also makes the single
    # aggregated Release PR (separate-pull-requests: false) build NOTHING rather
    # than full-fleet, which is what makes the aggregation affordable.
    # The version bump is not unchecked: check-app-version-override.sh holds the
    # quarkus.application.version invariant and check-admin-ui-version-sync.sh the
    # package.json one, both unconditionally in the gate job.
    FILES_CODE=$(grep -vE '^openbank-libs/governance/|^openbank-libs/docs/[^/]+\.md$|(/version\.txt$)|(/CHANGELOG\.md$)|(^\.release-please-manifest\.json$)|(^release-please-config\.json$)' <<< "$FILES" || true)
    FILES_GLOBAL=$(python3 .github/scripts/filter-lib-test-only-paths.py <<< "$FILES_CODE")
    # Match the push selector: an exact raised-floor-only diff stays local.
    if grep -Fxq 'openbank-libs-domain/build.gradle.kts' <<< "$FILES_GLOBAL" && python3 .github/scripts/coverage-floor-only-diff.py --base "$DIFF_BASE"; then
      echo "Decision: shared domain build script changes only its raised coverage floor."
      FILES_GLOBAL=$(grep -vFx 'openbank-libs-domain/build.gradle.kts' <<< "$FILES_GLOBAL" || true)
    fi
    if global_service_build_input_changed "$FILES_GLOBAL"; then
      echo "Decision: shared build input changed -> build all modules."
      CHANGED="$ALL"
    else
      echo "Decision: per-module fan-out (no code-global change)."
      CHANGED=""
      for m in $ALL; do
        if grep -qE "^${m}/" <<< "$FILES_CODE"; then
          CHANGED="$CHANGED $m"
        fi
      done
      CHANGED="$CHANGED $(python3 .github/scripts/service-ci-library-dependents.py "$ALL" <<< "$FILES_GLOBAL")"
      # A changed pact builds BOTH sides, exactly as the main-push path above already
      # does. Without this the PR path attributes files ONLY by `^<module>/`, so a
      # pact-only PR matched no module, built nothing, and merged green — then broke the
      # PROVIDER's build on main, where nobody is watching a service they did not touch.
      # That is #8697: three consumer pacts gained a "no valid M2M identity is presented"
      # interaction, transaction-service had no such provider state, and main went red
      # with the causing PR long merged (issue #8984).
      # Derivation matches the push path (split on the LAST '-openbank-'). A consumer that
      # is not a Gradle module is skipped rather than failing the scoping: openbank-admin-ui
      # authors five of the committed pacts and builds in its own workflow. A PROVIDER that
      # does not resolve to a module is not skippable — it is the side that verifies — so
      # that falls back to the full fleet, the safe direction this detector always takes.
      while IFS= read -r f; do
        [ -z "$f" ] && continue
        base=$(basename "$f" .json)
        case "$base" in
          *-openbank-*) ;;
          *) echo "Pact ${f} does not carry a '<consumer>-openbank-<provider>' name -> full-fleet."
             CHANGED="$ALL"; break ;;
        esac
        prov="openbank-${base##*-openbank-}"
        cons="${base%-openbank-*}"
        if ! grep -qE "(^| )${prov}( |\$)" <<< " $ALL "; then
          echo "Pact ${f} names provider ${prov}, which is not a module -> full-fleet."
          CHANGED="$ALL"; break
        fi
        CHANGED="$CHANGED $prov"
        if grep -qE "(^| )${cons}( |\$)" <<< " $ALL "; then CHANGED="$CHANGED $cons"; fi
      done <<< "$(grep -E '^pacts/.*\.json$' <<< "$FILES_CODE" || true)"
      # DST harness dependency edge (ADR-0115). openbank-simulation validates money-path
      # invariants against the ledger/balance/transaction domains, but this detector is
      # per-directory, NOT dependency-aware: a change to one of those services would build
      # only that service and silently skip the simulation that depends on it. Add the edge
      # explicitly so the invariant checker runs on every money-path change (it already runs
      # on its own edits, on push to main, and nightly). Code-global changes (libs etc.)
      # already build everything incl. the simulation, so this only matters per-module.
      # Issue #267 (ADR-0100 full-service adoption) extended the same project-dependency
      # binding to sepa-payment (directory openbank-sepa-payment, no "-service" suffix)
      # and settlement-service. Billing, interest and statement are also imported into
      # the DST runner: without these edges their domain changes could ship without
      # running the invariant/fault sweep that exercises them.
      if { grep -qE '(^| )openbank-(ledger|balance|transaction|billing|interest|statement)-service( |$)' <<< " $CHANGED " \
           || grep -qE '(^| )openbank-sepa-payment( |$)' <<< " $CHANGED " \
           || grep -qE '(^| )openbank-settlement-service( |$)' <<< " $CHANGED "; } \
         && ! grep -qE '(^| )openbank-simulation( |$)' <<< " $CHANGED "; then
        echo "Decision: money-path change -> add openbank-simulation (DST harness, ADR-0115/#267)."
        CHANGED="$CHANGED openbank-simulation"
      fi
      # Generated-client edge (ADR-0319): the client's input is the provider's openapi.yaml.
      if grep -qE '(^| )openbank-product-catalog( |$)' <<< " $CHANGED " \
         && ! grep -qE '(^| )openbank-client-product-catalog( |$)' <<< " $CHANGED "; then
        CHANGED="$CHANGED openbank-client-product-catalog"
      fi
      if grep -qE '(^| )openbank-transaction-service( |$)' <<< " $CHANGED " \
         && ! grep -qE '(^| )openbank-client-transaction( |$)' <<< " $CHANGED "; then
        CHANGED="$CHANGED openbank-client-transaction"
      fi
      # Tests reading OTHER modules' trees escape the fan-out above (#10010 -> #11513).
      # Derived from test sources; rationale in the script header.
      if [ -n "${CHANGED// /}" ]; then
        CHANGED="$CHANGED $(python3 .github/scripts/derive-fleet-scanning-test-modules.py --expand "$CHANGED")"
      fi
      # A change to the CI recipe itself (services-ci / _service-ci) can NOT
      # alter any service's compile/test inputs, so it does NOT warrant a
      # 28-module fan-out -- that is what turned the trivial #547 cache-flag
      # flip into a flaky full-fleet run. The recipe still needs validating
      # end-to-end, so build ONE canary: openbank-libs runs the same job
      # template -- checkout, toolchain, setup-gradle and :build (each service's
      # tests boot their own Testcontainers, #578; there is no shared infra boot
      # step left to exercise) -- exercising the entire recipe on a single job.
      # Skip the canary if a real service is already in the matrix (it validates
      # the recipe too).
      if [ -z "${CHANGED// /}" ] \
         && grep -qE '^(\.github/workflows/(services-ci|_service-ci)\.yml|\.github/scripts/(service-ci-pact-modules\.sh|service-ci-select-modules\.sh|service-ci-library-dependents\.py|(test-)?await-admin-ui-pact-publication\.py))$' <<< "$FILES"; then
        echo "Decision: recipe-only change -> build canary openbank-libs."
        CHANGED="openbank-libs"
      fi
    fi
  fi

  mkdir -p "$SELECT_OUT"
  printf '%s' "$CHANGED" > "$SELECT_OUT/changed.txt"
  printf '%s' "${FILES:-}" > "$SELECT_OUT/files.txt"
}

self_test() {
  local root repo out rc=0
  root=$(git rev-parse --show-toplevel)
  repo=$(mktemp -d)
  out=$(mktemp -d)
  trap 'rm -rf "$repo" "$out"' RETURN
  (
    cd "$repo"
    git init -q -b main . && git config user.email t@example.invalid && git config user.name t
    mkdir -p .github openbank-a-service/src openbank-b-service/src docs
    cp -R "$root/.github/scripts" .github/scripts
    : > openbank-a-service/build.gradle.kts; : > openbank-b-service/build.gradle.kts
    : > openbank-a-service/src/A.kt; : > openbank-b-service/src/B.kt; : > docs/x.md
    mkdir -p openbank-c-service/src/test/kotlin
    : > openbank-c-service/build.gradle.kts
    # A test that reads every sibling module (fleet scanner) must ride along with any change.
    printf '%s\n' 'val r = File("..").listFiles()!!.filter { it.name.startsWith("openbank-") }' > openbank-c-service/src/test/kotlin/Scan.kt
    git add -A && git commit -q -m base
    git remote add origin "$repo"
    git fetch -q origin main
    git checkout -q -b pr
    echo a >> openbank-a-service/src/A.kt && git commit -qam a
  ) >/dev/null 2>&1
  expect() { # name want got
    if [ "$2" != "$3" ]; then echo "SELF-TEST FAIL: $1: want '$2' got '$3'"; rc=1; else echo "ok: $1 -> '$3'"; fi
  }
  run() { # event; prints sorted module list
    ( cd "$repo" && EVENT_NAME="$1" BASE_REF=main SELECT_OUT="$out" GITHUB_OUTPUT="$out/gho" \
        bash "$root/.github/scripts/service-ci-select-modules.sh" >"$out/log" 2>&1 ) || { echo "SELF-TEST FAIL: run $1 exited non-zero"; cat "$out/log"; rc=1; }
    tr ' ' '\n' < "$out/changed.txt" | grep -v '^$' | sort -u | tr '\n' ' ' || true
  }
  expect schedule-builds-all "openbank-a-service openbank-b-service openbank-c-service " "$(run schedule)"
  expect pr-scopes-to-changed-module-plus-fleet-scanner "openbank-a-service openbank-c-service " "$(run pull_request)"
  # Negative control: a shared build input must widen to the full fleet, not stay scoped.
  ( cd "$repo" && echo x > build.gradle.kts && git add build.gradle.kts && git commit -qm g ) >/dev/null 2>&1
  expect pr-global-input-builds-all "openbank-a-service openbank-b-service openbank-c-service " "$(run pull_request)"
  # Push of only an inert path (docs) builds nothing.
  ( cd "$repo" && git checkout -q main && echo d >> docs/x.md && git commit -qam d ) >/dev/null 2>&1
  expect push-inert-builds-nothing "" "$(run push)"
  echo "SUBJECTS=4  # scenarios exercised"
  return $rc
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi
select_modules
