#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Prove the one deliberately narrow exception to provider-version equality.
#
# A Pact verification normally publishes against the exact commit whose provider code and tests
# ran.  A provider-state-only repair may need the current test fixture to verify an already
# deployed provider version.  That is safe only if the deployed version is an ancestor of the
# checked-out ref and every intervening change is confined to that provider's test tree.  This
# script makes that claim executable.  Any missing object, non-main history, reversed ancestry or
# path outside <service>/src/test/** is a refusal; callers must not publish or dispatch on failure.
#
# Usage:
#   prove-pact-provider-version.sh --resolve <revision>
#   prove-pact-provider-version.sh <service> <provider_version> <ref> <main_ref>
#   prove-pact-provider-version.sh --self-test
set -euo pipefail

refuse() {
  printf 'REFUSE\t%s\n' "$1" >&2
  exit 1
}

commit_exists() { git rev-parse -q --verify "$1^{commit}" >/dev/null 2>&1; }

canonical_commit() {
  local raw="$1" resolved
  resolved="$(git rev-parse -q --verify "$raw^{commit}")" \
    || refuse "provider version '$raw' is not a resolvable commit"
  [[ "$resolved" =~ ^[0-9a-f]{40}$ ]] \
    || refuse "provider version '$raw' did not resolve to a full object id"
  printf '%s\n' "$resolved"
}

prove() {
  local service="$1" provider_version="$2" ref="$3" main_ref="$4" path

  [[ "$service" =~ ^[a-z0-9][a-z0-9-]*$ ]] \
    || refuse "service '$service' is not a safe module directory name"
  [ "$provider_version" = "$(canonical_commit "$provider_version")" ] \
    || refuse "provider version must be a canonical 40-character SHA"
  commit_exists "$ref" || refuse "verification ref is not a commit"
  commit_exists "$main_ref" || refuse "main ref is not a commit"

  git merge-base --is-ancestor "$provider_version" "$main_ref" \
    || refuse "provider version is not an ancestor of main"
  git merge-base --is-ancestor "$ref" "$main_ref" \
    || refuse "verification ref is not an ancestor of main"
  git merge-base --is-ancestor "$provider_version" "$ref" \
    || refuse "provider version is not an ancestor of verification ref"
  git cat-file -e "$ref:$service/build.gradle.kts" 2>/dev/null \
    || refuse "service '$service' is not a Gradle module at verification ref"

  # Disable rename detection: a rename from src/main to src/test must expose both paths, not
  # become a misleading test-tree-only destination name. NUL delimiters keep unusual filenames
  # from changing the proof's path boundaries.
  while IFS= read -r -d '' path; do
    case "$path" in
      "$service"/src/test/*) ;;
      *) refuse "changed path '$path' is outside $service/src/test; refusing a verification for different provider code" ;;
    esac
  done < <(git diff --no-renames --name-only -z "$provider_version" "$ref")

  printf 'PROVEN\t%s may publish test-only verification against %s from %s\n' \
    "$service" "$provider_version" "$ref"
}

selftest() {
  local script repo_root workflow tmp base test_only runtime resources build dockerfile libs root other_service behind_main fail=0
  # A gate runner may export Git plumbing variables while inspecting the repository under test.
  # This fixture owns a different repository; inheriting its index/object directory can make
  # `git add` validate unrelated entries (for example a tracked Dockerfile) as fixture objects.
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
    mkdir -p provider/src/main/resources provider/src/test other/src/main openbank-libs-domain/src/main
    printf 'main\n' > provider/src/main/Provider.kt
    printf 'fixture\n' > provider/src/test/ProviderPactTest.kt
    printf 'config\n' > provider/src/main/resources/application.yaml
    printf 'plugins {}\n' > provider/build.gradle.kts
    printf 'FROM scratch\n' > provider/Dockerfile
    printf 'other\n' > other/src/main/Other.kt
    printf 'libs\n' > openbank-libs-domain/src/main/Library.kt
    printf 'root\n' > settings.gradle.kts
    git add provider other openbank-libs-domain settings.gradle.kts
    git commit -qm base
  ) || { echo 'selftest FAIL: fixture setup failed' >&2; return 1; }
  base="$(git -C "$tmp" rev-parse HEAD)"
  (
    cd "$tmp"
    printf 'fixture changed\n' > provider/src/test/ProviderPactTest.kt
    git add provider/src/test/ProviderPactTest.kt
    git commit -qm test-only
  )
  test_only="$(git -C "$tmp" rev-parse HEAD)"
  (
    cd "$tmp"
    printf 'runtime changed\n' > provider/src/main/Provider.kt
    git add provider/src/main/Provider.kt
    git commit -qm runtime
  )
  runtime="$(git -C "$tmp" rev-parse HEAD)"
  (
    cd "$tmp"
    printf 'resource changed\n' > provider/src/main/resources/application.yaml
    git add provider/src/main/resources/application.yaml
    git commit -qm resources
  )
  resources="$(git -C "$tmp" rev-parse HEAD)"
  (
    cd "$tmp"
    printf 'plugins { changed }\n' > provider/build.gradle.kts
    git add provider/build.gradle.kts
    git commit -qm build
  )
  build="$(git -C "$tmp" rev-parse HEAD)"
  (
    cd "$tmp"
    printf 'FROM busybox\n' > provider/Dockerfile
    git add provider/Dockerfile
    git commit -qm dockerfile
  )
  dockerfile="$(git -C "$tmp" rev-parse HEAD)"
  (
    cd "$tmp"
    printf 'libs changed\n' > openbank-libs-domain/src/main/Library.kt
    git add openbank-libs-domain/src/main/Library.kt
    git commit -qm libs
  )
  libs="$(git -C "$tmp" rev-parse HEAD)"
  (
    cd "$tmp"
    printf 'root changed\n' > settings.gradle.kts
    git add settings.gradle.kts
    git commit -qm root
  )
  root="$(git -C "$tmp" rev-parse HEAD)"
  (
    cd "$tmp"
    printf 'other changed\n' > other/src/main/Other.kt
    git add other/src/main/Other.kt
    git commit -qm other-service
  )
  other_service="$(git -C "$tmp" rev-parse HEAD)"
  git -C "$tmp" branch side "$base"
  (
    cd "$tmp"
    git checkout -q side
    printf 'side fixture\n' > provider/src/test/ProviderPactTest.kt
    git add provider/src/test/ProviderPactTest.kt
    git commit -qm side
  )
  behind_main="$(git -C "$tmp" rev-parse HEAD)"

  expect() {
    local label="$1" want="$2"; shift 2
    local out rc
    if out="$(git -C "$tmp" checkout -q main && git -C "$tmp" show-ref --verify --quiet refs/heads/main && (cd "$tmp" && bash "$script" "$@") 2>&1)"; then
      rc=0
    else
      rc=$?
    fi
    if [ "$want" = pass ] && [ "$rc" -ne 0 ]; then
      echo "selftest FAIL: $label refused: $out" >&2; fail=1
    elif [ "$want" = fail ] && [ "$rc" -eq 0 ]; then
      echo "selftest FAIL: $label unexpectedly passed: $out" >&2; fail=1
    fi
  }

  expect 'same revision is default-safe' pass provider "$base" "$base" main
  expect 'test-only ancestor exception' pass provider "$base" "$test_only" main
  expect 'runtime change is rejected' fail provider "$test_only" "$runtime" main
  expect 'main resources change is rejected' fail provider "$runtime" "$resources" main
  expect 'build file change is rejected' fail provider "$resources" "$build" main
  expect 'Dockerfile change is rejected' fail provider "$build" "$dockerfile" main
  expect 'libs change is rejected' fail provider "$dockerfile" "$libs" main
  expect 'root build configuration is rejected' fail provider "$libs" "$root" main
  expect 'another service change is rejected' fail provider "$root" "$other_service" main
  expect 'provider must be an ancestor of ref' fail provider "$test_only" "$base" main
  expect 'both revisions must be on main' fail provider "$base" "$behind_main" main
  expect 'unsafe service input is rejected' fail '../provider' "$base" "$base" main

  expect_resolve() {
    local label="$1" raw="$2" want="$3" out rc
    if out="$(cd "$tmp" && bash "$script" --resolve "$raw")"; then rc=0; else rc=$?; fi
    if [ "$rc" -ne 0 ] || [ "$out" != "$want" ] || ! [[ "$out" =~ ^[0-9a-f]{40}$ ]]; then
      echo "selftest FAIL: $label did not canonicalize '$raw' to $want (got '$out', rc=$rc)" >&2
      fail=1
    fi
  }
  expect_resolve 'short SHA is canonicalized' "${base:0:8}" "$base"
  expect_resolve 'named ref is canonicalized' main "$other_service"

  local provider_args
  [ -f "$workflow" ] || { echo "selftest FAIL: workflow not found: $workflow" >&2; fail=1; }
  provider_args="$(grep -F -- '-Dpact.provider.version=' "$workflow" || true)"
  if [ "$(printf '%s\n' "$provider_args" | sed '/^$/d' | wc -l | tr -d ' ')" != 2 ] \
     || [ "$(printf '%s\n' "$provider_args" | grep -Ec '^[[:space:]]*-Dpact\.provider\.version="\$\{PACT_PROVIDER_VERSION\}"[[:space:]]*\\?$')" != 2 ] \
     || ! grep -Fq -- 'PROVIDER_VERSION="$(bash .github/scripts/prove-pact-provider-version.sh --resolve "$REQUESTED_PROVIDER_VERSION")"' "$workflow"; then
    echo 'selftest FAIL: raw provider_version can reach Gradle or is not canonicalized first' >&2
    fail=1
  fi

  [ "$fail" -eq 0 ] && echo 'selftest OK: equality, canonical short/ref resolution, exact test-only exception, runtime, resources, build, Dockerfile, libs, root, sibling, ancestry, main membership, unsafe service and raw-to-Gradle cases.'
  return "$fail"
}

if [ "${1:-}" = --self-test ]; then
  selftest
  exit $?
fi

if [ "${1:-}" = --resolve ]; then
  [ "$#" -eq 2 ] || refuse "usage: $0 --resolve <revision>"
  canonical_commit "$2"
  exit 0
fi

[ "$#" -eq 4 ] || refuse "usage: $0 <service> <provider_version> <ref> <main_ref>"
prove "$@"
