#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Prepare a provider-test overlay for an already deployed provider version P.
#
# Normal verification checks out and publishes one SHA.  The exceptional repair checks out the
# deployed provider SHA (P) and takes provider TEST code from a later main SHA (F).  Two modes,
# tried in this order:
#
# 1. EQUIVALENT-TREE OVERLAY (any provider, #11597).  Allowed only when P and F are byte-identical
#    in every production build input of <service>, as decided by the SAME function the reconcile
#    uses to transfer a pact verdict (pact-version-tree-equivalent.sh, run with
#    --own-test-overlay): the rest of `<svc>/` (src/main, resources, build.gradle.kts, Dockerfile,
#    version.txt, CHANGELOG.md), every shared openbank-libs* module, build-logic, gradle/ and the
#    root build files.  Then `<svc>/src/test` — and only that subtree — is replaced wholesale by
#    F's.  The runtime under test is P's by checkout AND equal to F's by proof, so F's provider
#    states were reviewed and run on main against the very code the broker result attests to.
#    Why a whole subtree and not a file list: new @State handlers routinely arrive with new
#    seed helpers in sibling files (#11071, #11113), so a one-file allowlist cannot compile.
# 2. SINGLE-FIXTURE OVERLAY (Ledger only, the original path).  When mode 1 is refused, Ledger may
#    still replace exactly ONE broker-state fixture from F with no equivalence requirement —
#    the narrower overlay carries its own argument (one reviewed file, runtime stays P).  It is
#    kept because mode 1 does not subsume it: mode 1 refuses whenever ANY production input moved,
#    which the one-file path tolerates.
#
# Pacts, workflows, config and every path outside `<svc>/src/test` remain P in both modes.
#
# Usage:
#   prove-pact-provider-version.sh --resolve <revision>
#   prove-pact-provider-version.sh --prepare-overlay <service> <provider_sha> <fixture_sha> <main_ref>
#   prove-pact-provider-version.sh --self-test
set -euo pipefail

LEDGER_SERVICE='openbank-ledger-service'
LEDGER_FIXTURE='openbank-ledger-service/src/test/kotlin/com/openbank/ledger/contract/LedgerPactBrokerProviderVerificationTest.kt'

refuse() { printf 'REFUSE\t%s\n' "$1" >&2; exit 1; }
commit_exists() { git rev-parse -q --verify "$1^{commit}" >/dev/null 2>&1; }

canonical_commit() {
  local raw="$1" resolved
  resolved="$(git rev-parse -q --verify "$raw^{commit}")" \
    || refuse "revision '$raw' is not a resolvable commit"
  [[ "$resolved" =~ ^[0-9a-f]{40}$ ]] || refuse "revision '$raw' did not resolve to a full object id"
  printf '%s\n' "$resolved"
}

regular_blob_at() {
  local sha="$1" path="$2" entry mode type
  entry="$(git ls-tree "$sha" -- "$path")"
  [ -n "$entry" ] || refuse "approved fixture is missing at ${sha:0:8}"
  mode="${entry%% *}"; entry="${entry#* }"; type="${entry%% *}"
  [ "$mode" = 100644 ] && [ "$type" = blob ] \
    || refuse "approved fixture must be a regular 100644 blob at ${sha:0:8}"
}

# The trusted equivalence function lives next to this script (the workflow copies both out of the
# trusted checkout before P replaces the workspace — P's own copy may predate --own-test-overlay).
EQUIVALENCE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/pact-version-tree-equivalent.sh"

# Mode 1.  Returns 0 after overlaying, 1 (with the reason on stderr) when equivalence is refused.
# Any refusal AFTER equivalence held is fatal, never a fall-through.
overlay_equivalent_test_tree() {
  local service="$1" provider="$2" fixture="$3" verdict bad
  [ -r "$EQUIVALENCE" ] || refuse "trusted equivalence script is absent at $EQUIVALENCE"
  if ! verdict="$(bash "$EQUIVALENCE" --own-test-overlay "$service" "$provider" "$fixture")"; then
    printf 'NOT_EQUIVALENT\t%s\n' "$verdict" >&2
    return 1
  fi
  git cat-file -e "$fixture:$service/src/test" 2>/dev/null \
    || refuse "$service/src/test is absent at ${fixture:0:8}"
  # Regular files only: no symlink (could point outside the tree) and no submodule.
  bad="$(git ls-tree -r "$fixture" -- "$service/src/test" | awk '$1 != "100644" && $1 != "100755"')"
  [ -z "$bad" ] || refuse "$service/src/test at ${fixture:0:8} holds a non-regular entry: ${bad%%$'\n'*}"
  # Wholesale: delete P's test tree first so a file F removed cannot survive, then restore F's.
  rm -rf -- "$service/src/test"
  git checkout --quiet "$fixture" -- "$service/src/test"
  printf 'OVERLAY_READY\tmode=equivalent-test-tree provider=%s fixture=%s path=%s/src/test\n' \
    "$provider" "$fixture" "$service"
  printf '%s\n' "$verdict" >&2
}

prepare_overlay() {
  local service="$1" provider="$2" fixture="$3" main_ref="$4" current
  [[ "$service" =~ ^openbank-[a-z0-9-]+$ ]] || refuse "service '$service' is not an openbank-* module name"
  [ "$provider" = "$(canonical_commit "$provider")" ] \
    || refuse "provider version must be a canonical 40-character SHA"
  [ "$fixture" = "$(canonical_commit "$fixture")" ] \
    || refuse "fixture version must be a canonical 40-character SHA"
  commit_exists "$main_ref" || refuse "main ref is not a commit"
  git merge-base --is-ancestor "$provider" "$main_ref" \
    || refuse "provider version is not an ancestor of main"
  git merge-base --is-ancestor "$fixture" "$main_ref" \
    || refuse "fixture version is not an ancestor of main"
  git merge-base --is-ancestor "$provider" "$fixture" \
    || refuse "provider version is not an ancestor of fixture version"
  current="$(git rev-parse HEAD)"
  [ "$current" = "$provider" ] \
    || refuse "checkout ${current:0:8} is not provider version ${provider:0:8}; refusing to attest different runtime"

  overlay_equivalent_test_tree "$service" "$provider" "$fixture" && return 0
  [ "$service" = "$LEDGER_SERVICE" ] \
    || refuse "$service is not byte-identical in its production build inputs at ${provider:0:8} and ${fixture:0:8} (see NOT_EQUIVALENT above), and no single-fixture overlay is approved for it"
  regular_blob_at "$provider" "$LEDGER_FIXTURE"
  regular_blob_at "$fixture" "$LEDGER_FIXTURE"

  # git show reads one named blob from F; no checkout, merge or diff can import another F path.
  git show "$fixture:$LEDGER_FIXTURE" > "$LEDGER_FIXTURE"
  printf 'OVERLAY_READY\tmode=single-fixture provider=%s fixture=%s path=%s\n' \
    "$provider" "$fixture" "$LEDGER_FIXTURE"
}

selftest() {
  local script repo_root workflow tmp provider fixture side symlink missing fail=0 runtime_before pacts_before libs_before workflow_before
  unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES
  script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  workflow="$repo_root/.github/workflows/_service-ci.yml"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  (
    set -e
    cd "$tmp"
    git init -q -b main
    git config user.email selftest@example.invalid
    git config user.name selftest
    git config commit.gpgsign false
    mkdir -p "$(dirname "$LEDGER_FIXTURE")" openbank-ledger-service/src/main/resources \
      openbank-libs-domain/src/main pacts .github/workflows config
    printf 'old fixture\n' > "$LEDGER_FIXTURE"
    printf 'provider runtime P\n' > openbank-ledger-service/src/main/Ledger.kt
    printf 'provider config P\n' > openbank-ledger-service/src/main/resources/application.yaml
    printf 'libs P\n' > openbank-libs-domain/src/main/Domain.kt
    printf 'pact P\n' > pacts/openbank-finrep-service-openbank-ledger-service.json
    printf 'workflow P\n' > .github/workflows/provider.yml
    printf 'config P\n' > config/detekt.yml
    printf 'plugins {}\n' > openbank-ledger-service/build.gradle.kts
    git add openbank-ledger-service openbank-libs-domain pacts .github config
    git commit -qm provider
  ) || { echo 'selftest FAIL: fixture setup failed' >&2; return 1; }
  provider="$(git -C "$tmp" rev-parse HEAD)"
  runtime_before="$(git -C "$tmp" show "$provider:openbank-ledger-service/src/main/Ledger.kt")"
  pacts_before="$(git -C "$tmp" show "$provider:pacts/openbank-finrep-service-openbank-ledger-service.json")"
  libs_before="$(git -C "$tmp" show "$provider:openbank-libs-domain/src/main/Domain.kt")"
  workflow_before="$(git -C "$tmp" show "$provider:.github/workflows/provider.yml")"
  (
    cd "$tmp"
    printf 'approved fixture F\n' > "$LEDGER_FIXTURE"
    printf 'runtime F must not enter P\n' > openbank-ledger-service/src/main/Ledger.kt
    printf 'pact F must not enter P\n' > pacts/openbank-finrep-service-openbank-ledger-service.json
    printf 'libs F must not enter P\n' > openbank-libs-domain/src/main/Domain.kt
    printf 'workflow F must not enter P\n' > .github/workflows/provider.yml
    printf 'config F must not enter P\n' > config/detekt.yml
    git add openbank-ledger-service openbank-libs-domain pacts .github config
    git commit -qm fixture
  )
  fixture="$(git -C "$tmp" rev-parse HEAD)"
  git -C "$tmp" branch side "$provider"
  (
    cd "$tmp" && git checkout -q side
    printf 'side fixture\n' > "$LEDGER_FIXTURE"
    git add "$LEDGER_FIXTURE" && git commit -qm side
  )
  side="$(git -C "$tmp" rev-parse HEAD)"
  git -C "$tmp" checkout -q main
  rm "$tmp/$LEDGER_FIXTURE"
  ln -s /tmp/not-a-fixture "$tmp/$LEDGER_FIXTURE"
  git -C "$tmp" add -A "$LEDGER_FIXTURE"
  git -C "$tmp" commit -qm symlink
  symlink="$(git -C "$tmp" rev-parse HEAD)"
  rm "$tmp/$LEDGER_FIXTURE"
  git -C "$tmp" add -u "$LEDGER_FIXTURE"
  git -C "$tmp" commit -qm missing
  missing="$(git -C "$tmp" rev-parse HEAD)"
  git -C "$tmp" checkout -q "$provider"

  expect() {
    local label="$1" want="$2"; shift 2
    local out rc
    if out="$(cd "$tmp" && bash "$script" "$@" 2>&1)"; then rc=0; else rc=$?; fi
    if [ "$want" = pass ] && [ "$rc" -ne 0 ]; then echo "selftest FAIL: $label: $out" >&2; fail=1; fi
    if [ "$want" = fail ] && [ "$rc" -eq 0 ]; then echo "selftest FAIL: $label unexpectedly passed" >&2; fail=1; fi
  }
  expect 'approved Ledger overlay' pass --prepare-overlay "$LEDGER_SERVICE" "$provider" "$fixture" main
  [ "$(<"$tmp/$LEDGER_FIXTURE")" = 'approved fixture F' ] \
    || { echo 'selftest FAIL: approved fixture not overlaid' >&2; fail=1; }
  [ "$(<"$tmp/openbank-ledger-service/src/main/Ledger.kt")" = "$runtime_before" ] \
    || { echo 'selftest FAIL: runtime from F entered P' >&2; fail=1; }
  [ "$(<"$tmp/pacts/openbank-finrep-service-openbank-ledger-service.json")" = "$pacts_before" ] \
    || { echo 'selftest FAIL: pacts from F entered P' >&2; fail=1; }
  [ "$(<"$tmp/openbank-libs-domain/src/main/Domain.kt")" = "$libs_before" ] \
    || { echo 'selftest FAIL: libs from F entered P' >&2; fail=1; }
  [ "$(<"$tmp/.github/workflows/provider.yml")" = "$workflow_before" ] \
    || { echo 'selftest FAIL: workflow from F entered P' >&2; fail=1; }
  git -C "$tmp" checkout -q "$provider"
  expect 'other provider is denied' fail --prepare-overlay openbank-swift-service "$provider" "$fixture" main
  expect 'fixture off main is denied' fail --prepare-overlay "$LEDGER_SERVICE" "$provider" "$side" main
  expect 'reversed ancestry is denied' fail --prepare-overlay "$LEDGER_SERVICE" "$fixture" "$provider" main
  expect 'symlink fixture is denied' fail --prepare-overlay "$LEDGER_SERVICE" "$provider" "$symlink" main
  expect 'missing fixture is denied' fail --prepare-overlay "$LEDGER_SERVICE" "$provider" "$missing" main
  expect_resolve() { local raw="$1" want="$2" out; out="$(cd "$tmp" && bash "$script" --resolve "$raw")" || { fail=1; return; }; [ "$out" = "$want" ] || fail=1; }
  expect_resolve "${provider:0:8}" "$provider"
  expect_resolve main "$missing"
  # ── mode 1, generic equivalent-tree overlay (#11597), on its own repo ─────────────────
  # Linear history; each refusal case uses its IMMEDIATE predecessor as P so it isolates one
  # changed input (chaining back to the first commit would refuse for an earlier reason).
  local g="$tmp/generic" D=openbank-demo-service gp gf gm gl gv gs
  mkdir -p "$g"
  (
    set -e
    cd "$g"
    git init -q -b main
    git config user.email selftest@example.invalid
    git config user.name selftest
    git config commit.gpgsign false
    mkdir -p "$D/src/main" "$D/src/test" openbank-libs-domain/src/main build-logic docs
    printf 'implementation(project(":openbank-libs-domain"))\n' > "$D/build.gradle.kts"
    printf 'runtime P\n' > "$D/src/main/A.kt"
    printf 'old\n' > "$D/src/test/Old.kt"
    printf 'keep P\n' > "$D/src/test/Keep.kt"
    printf '1.0.0\n' > "$D/version.txt"
    printf 'x\n' > openbank-libs-domain/build.gradle.kts
    printf 'libs\n' > openbank-libs-domain/src/main/D.kt
    printf 'x\n' > build-logic/build.gradle.kts
    printf 'x\n' > settings.gradle.kts
    printf 'x\n' > docs/a.md
    git add -A && git commit -qm P
  ) || { echo 'selftest FAIL: generic fixture setup failed' >&2; return 1; }
  gp="$(git -C "$g" rev-parse HEAD)"
  _gcommit() { ( cd "$g" && "$@" && git add -A && git commit -qm step ) >/dev/null && git -C "$g" rev-parse HEAD; }
  gf="$(_gcommit sh -c "printf 'keep F\n' > $D/src/test/Keep.kt; printf 'new\n' > $D/src/test/New.kt; rm $D/src/test/Old.kt; printf 'y\n' > docs/a.md")"
  gm="$(_gcommit sh -c "printf 'runtime F\n' > $D/src/main/A.kt")"
  gl="$(_gcommit sh -c "printf 'libs F\n' > openbank-libs-domain/src/main/D.kt")"
  gv="$(_gcommit sh -c "printf '1.0.1\n' > $D/version.txt")"
  gs="$(_gcommit sh -c "ln -s /etc/passwd $D/src/test/Link.kt")"
  gexpect() { # <label> <pass|fail> <provider> <fixture>
    local out rc
    git -C "$g" checkout -f -q "$3" && git -C "$g" clean -fdq
    if out="$(cd "$g" && bash "$script" --prepare-overlay "$D" "$3" "$4" main 2>&1)"; then rc=0; else rc=$?; fi
    if [ "$2" = pass ] && [ "$rc" -ne 0 ]; then echo "selftest FAIL: $1: $out" >&2; fail=1; fi
    if [ "$2" = fail ] && [ "$rc" -eq 0 ]; then echo "selftest FAIL: $1 unexpectedly passed" >&2; fail=1; fi
  }
  # KNOWN-POSITIVE: P and F differ only in src/test (+ docs) => allowed, whole test tree is F's.
  gexpect 'generic: test-only difference is overlaid' pass "$gp" "$gf"
  [ "$(<"$g/$D/src/test/Keep.kt")" = 'keep F' ] || { echo 'selftest FAIL: changed test file not taken from F' >&2; fail=1; }
  [ -f "$g/$D/src/test/New.kt" ] || { echo 'selftest FAIL: test file added in F not overlaid' >&2; fail=1; }
  [ ! -e "$g/$D/src/test/Old.kt" ] || { echo 'selftest FAIL: test file deleted in F survived' >&2; fail=1; }
  [ "$(<"$g/$D/src/main/A.kt")" = 'runtime P' ] || { echo 'selftest FAIL: runtime changed by overlay' >&2; fail=1; }
  [ "$(<"$g/docs/a.md")" = 'x' ] || { echo 'selftest FAIL: non-test path from F entered P' >&2; fail=1; }
  # KNOWN-NEGATIVES: each production input refuses.
  gexpect 'generic: src/main difference is refused' fail "$gf" "$gm"
  gexpect 'generic: shared libs difference is refused' fail "$gm" "$gl"
  gexpect 'generic: version.txt difference is refused' fail "$gl" "$gv"
  gexpect 'generic: symlink in F test tree is refused' fail "$gv" "$gs"
  gexpect 'generic: reversed ancestry is refused' fail "$gf" "$gp"

  if ! grep -Fq 'path: provider-proof-source' "$workflow" \
     || ! grep -Fq 'cp provider-proof-source/.github/scripts/prove-pact-provider-version.sh' "$workflow" \
     || ! grep -Fq '"$RUNNER_TEMP/prove-pact-provider-version.sh"' "$workflow" \
     || ! grep -Fq 'cp provider-proof-source/.github/scripts/pact-version-tree-equivalent.sh' "$workflow" \
     || grep -Fq 'bash .github/scripts/prove-pact-provider-version.sh --prepare-overlay' "$workflow"; then
    echo 'selftest FAIL: workflow does not preserve a trusted proof runner outside the P checkout' >&2
    fail=1
  fi
  [ "$fail" -eq 0 ] && echo 'selftest OK: equivalent-tree overlay takes F test tree wholesale (changed/added/deleted) and only it; src/main, libs, version.txt, symlink and reversed ancestry refuse; Ledger single-fixture fallback preserves P runtime, pacts, libs and workflow; wrong provider, ancestry, symlink and missing fixture reject; short SHA/ref canonicalize; trusted runner + equivalence stay outside P.'
  return "$fail"
}

case "${1:-}" in
  --self-test) selftest ;;
  --resolve) [ "$#" -eq 2 ] || refuse "usage: $0 --resolve <revision>"; canonical_commit "$2" ;;
  --prepare-overlay) [ "$#" -eq 5 ] || refuse "usage: $0 --prepare-overlay <service> <provider_sha> <fixture_sha> <main_ref>"; prepare_overlay "$2" "$3" "$4" "$5" ;;
  *) refuse "usage: $0 --resolve|--prepare-overlay|--self-test" ;;
esac
