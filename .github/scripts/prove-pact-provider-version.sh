#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Prepare the sole approved provider-fixture overlay for an already deployed Ledger version.
#
# Normal verification checks out and publishes one SHA.  The exceptional repair checks out the
# deployed provider SHA (P), then replaces exactly ONE broker-state fixture from a later main
# SHA (F).  Runtime, pacts, libraries, workflows, config and every other test file remain P.
# This is deliberately an allowlist of one path, not a diff allowlist: F can carry arbitrary
# unrelated work without changing the provider runtime the broker result attests to.
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

prepare_overlay() {
  local service="$1" provider="$2" fixture="$3" main_ref="$4" current
  [ "$service" = "$LEDGER_SERVICE" ] \
    || refuse "a distinct provider version has no approved fixture overlay for $service"
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
  regular_blob_at "$provider" "$LEDGER_FIXTURE"
  regular_blob_at "$fixture" "$LEDGER_FIXTURE"

  # git show reads one named blob from F; no checkout, merge or diff can import another F path.
  git show "$fixture:$LEDGER_FIXTURE" > "$LEDGER_FIXTURE"
  printf 'OVERLAY_READY\tprovider=%s fixture=%s path=%s\n' \
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
  if ! grep -Fq 'path: provider-proof-source' "$workflow" \
     || ! grep -Fq 'cp provider-proof-source/.github/scripts/prove-pact-provider-version.sh' "$workflow" \
     || ! grep -Fq '"$RUNNER_TEMP/prove-pact-provider-version.sh"' "$workflow" \
     || grep -Fq 'bash .github/scripts/prove-pact-provider-version.sh --prepare-overlay' "$workflow"; then
    echo 'selftest FAIL: workflow does not preserve a trusted proof runner outside the P checkout' >&2
    fail=1
  fi
  [ "$fail" -eq 0 ] && echo 'selftest OK: exact Ledger fixture overlay preserves P runtime, pacts, libs and workflow; wrong provider, ancestry, symlink and missing fixture reject; short SHA/ref canonicalize; trusted runner stays outside P.'
  return "$fail"
}

case "${1:-}" in
  --self-test) selftest ;;
  --resolve) [ "$#" -eq 2 ] || refuse "usage: $0 --resolve <revision>"; canonical_commit "$2" ;;
  --prepare-overlay) [ "$#" -eq 5 ] || refuse "usage: $0 --prepare-overlay <service> <provider_sha> <fixture_sha> <main_ref>"; prepare_overlay "$2" "$3" "$4" "$5" ;;
  *) refuse "usage: $0 --resolve|--prepare-overlay|--self-test" ;;
esac
