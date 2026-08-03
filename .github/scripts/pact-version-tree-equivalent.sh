#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Are two commits BYTE-IDENTICAL in everything that builds one service's image? (issue #3432)
#
# THE DEADLOCK THIS OPENS, WITHOUT WEAKENING THE GATE
# Pacts are published by each service's own services-ci PUSH lane. A `workflow_dispatch`
# reconcile builds `sandbox-${GITHUB_SHA::8}` from the CURRENT main tip — a commit services-ci
# never built for that service — so no pact version exists for it, and #3318 correctly REFUSES:
# asking the broker about a different commit is not a gate. Measured on run 30761923908:
# 54 of 54 services refused, `deployable=[]`. Reconcile is the ONLY mechanism for re-driving
# services that missed their push deploy, and it is needed exactly when many are stranded —
# which is exactly when it is guaranteed to deploy nothing.
#
# #3318's objection is to answering about a DIFFERENT commit. It does not apply when the two
# commits are not different in any way that could reach this service's artifact. So instead of
# borrowing a verdict on faith, PROVE the equivalence from git and only then ask the broker
# about the sha that actually has a published version. Different in any relevant byte ⇒ today's
# REFUSE, unchanged. This strengthens the argument rather than widening the exception.
#
# WHAT "RELEVANT" MEANS — MEASURED, NOT ASSUMED
# auto-deploy's image is assembled (auto-deploy.yml, `Docker push (parallel)`) from exactly:
#   * `<svc>/build/quarkus-app`      — the output of `:<svc>:quarkusBuild`
#   * `<svc>/build/reports/bom.json` — the output of `:<svc>:cyclonedxBom`
#   * `.github/workflows/Dockerfile.deploy` — copied in verbatim as the Dockerfile
#   * an `EXPOSE` line grepped out of `<svc>/Dockerfile`
# So the in-scope set is the Gradle build inputs of that module plus those two files.
#
# THE DEFAULT IS THE DESIGN: UNRECOGNISED ⇒ NOT EQUIVALENT
# An include-list of "things that matter" fails silently in the dangerous direction — a shared
# input nobody listed is ignored, the trees compare equal, and the verdict is borrowed for a
# commit that really did change the artifact. So every changed path is classified into exactly
# three buckets: IN SCOPE (compared), KNOWN-IRRELEVANT (justified below), or UNRECOGNISED —
# and an UNRECOGNISED path is a refusal. A directory added to this repo tomorrow blocks the
# equivalence until someone classifies it, instead of being waved through.
#
# WHY THESE PATHS ARE KNOWN-IRRELEVANT
#   openbank-infra/  gitops manifests + terraform. Not a Gradle module (absent from
#                    settings.gradle.kts) and not copied into any image, so it cannot change the
#                    artifact. It is also the one tree that moves on EVERY deploy, so counting it
#                    would make equivalence essentially unreachable — but the reason it is
#                    excluded is that it is not a build input, not that excluding it is
#                    convenient. Runtime CONFIG has never been within can-i-deploy's scope on any
#                    path, push included; this does not narrow the gate.
#   .github/         CI definitions. The two files that DO reach the image — Dockerfile.deploy
#                    and auto-deploy.yml, which carries the Gradle flags — are in scope by name.
#   docs/ pacts/ perf/ fuzz/ scripts/ LICENSES/ .security/ .devcontainer/ .clusterfuzzlite/
#                    documentation, committed pact artefacts, load tests, fuzz harnesses,
#                    operator scripts, licence texts — none is read by `:<svc>:quarkusBuild`.
#                    (A `pacts/` change for THIS service always rides with a `<svc>/src/test`
#                    change, which is in scope.)
#   openbank-admin-ui/ openbank-api-gateway/ openbank-developer-portal/
#   openbank-document-renderer/
#                    not Gradle modules; separate build + deploy pipelines.
#   another service's module directory
#                    a sibling module this one does not declare a `project(":…")` dependency on
#                    cannot enter its compile classpath. The dependency graph is derived from the
#                    build files here, not listed, for the same reason libs-change-dependents.sh
#                    derives it (#2983) — a hand-kept mapping rots.
#   root *.md and repo metadata (LICENSE, NOTICE, REUSE.toml, CITATION.cff, CODEOWNERS,
#                    codecov.yml, .gitignore, .gitattributes, .editorconfig, .gitleaks.toml,
#                    .trivyignore, .pre-commit-config.yaml, release-please config + manifest)
#                    prose and tooling config. Every OTHER root-level file is in scope: root is
#                    where build.gradle.kts, settings.gradle.kts, gradle.properties and gradlew
#                    live, so the safe default there is "counts".
#
# WHY OVER-BROAD ON THE SHARED MODULES
# Every `openbank-libs*` module is compared for every service, whether or not this service
# declares it. libs-change-dependents.sh's narrower derivation is right for deciding what to
# REBUILD; here a miss is a false green, so the cheap direction is the safe one.
#
# WHY ANCESTRY IS REQUIRED
# Tree equality alone is symmetric and would be satisfied by a revert or a rewritten history.
# Requiring the pact commit to be an ANCESTOR of the commit being deployed keeps the claim to
# what it is meant to be — "main moved, but not through anything this service is built from".
#
# WHY THE EXIT CODE IS THE ANSWER
# Exit 0 means EQUIVALENT and nothing else does. A usage error, a missing object, an
# unclassifiable path or an outright crash all exit non-zero, so a caller that keys on the exit
# status fails closed to today's REFUSE without needing to parse anything.
#
# Usage:
#   pact-version-tree-equivalent.sh <service> <pact_sha> <dispatch_sha>
#   pact-version-tree-equivalent.sh --self-test
#
# Prints one TAB-separated line: EQUIVALENT|DIFFERENT<TAB><human reason>.
set -uo pipefail

verdict() { printf '%s\t%s\n' "$1" "$2"; }
differs() { verdict DIFFERENT "$1"; exit 1; }

# Root-level files that are prose or tooling metadata, never a build input. Everything else at
# root counts — see the header: root is where the build config lives.
ROOT_IGNORE_RE='^([^/]+\.md|LICENSE|NOTICE|REUSE\.toml|CITATION\.cff|CODEOWNERS|codecov\.yml|\.gitignore|\.gitattributes|\.editorconfig|\.gitleaks\.toml|\.trivyignore|\.pre-commit-config\.yaml|release-please-config\.json|\.release-please-manifest\.json)$'

# Top-level directories that cannot reach a service image. Justified one by one in the header.
IGNORED_DIR_RE='^(docs|pacts|perf|fuzz|scripts|LICENSES|openbank-contracts|openbank-infra|openbank-admin-ui|openbank-api-gateway|openbank-developer-portal|openbank-document-renderer|\.security|\.devcontainer|\.clusterfuzzlite|\.github)/'

# The one `.github/` file that is literally COPY'd into the image, so it is re-admitted by name.
# auto-deploy.yml itself is deliberately NOT here even though it carries the Gradle flags: the
# image is built by the DISPATCH run under the dispatch sha's workflow either way, and the pact
# verification never ran under auto-deploy.yml at all (that is _service-ci.yml's job). Demanding
# it be unchanged would be asking for a guarantee the ordinary push path does not provide either —
# and it is the single most-edited file in this pipeline, so it refused all 32 services on its own
# when it was in scope (measured 2026-08-03).
GITHUB_IN_SCOPE=(.github/workflows/Dockerfile.deploy)

# Non-module directories that ARE build inputs for every module.
GLOBAL_IN_SCOPE_DIRS=(gradle config build-logic)

# Which subpaths of a DEPENDENCY module are compile inputs. Not a new opinion: this is the repo's
# own definition, from libs-change-dependents.sh (`^<module>/(src/main|build.gradle.kts)` plus the
# global `openbank-libs/gradle/` version catalog), and auto-deploy's own change detection excludes
# `openbank-libs/governance/` in as many words — "data for CI checks + admin-ui, not a compile
# input". Comparing the whole directory instead is not free caution: measured 2026-08-03, all 32
# services with a main-tagged pact version were refused, and 12 of them ONLY because four files
# under openbank-libs/governance/ had moved. The service's OWN directory is still compared whole.
DEP_SUBPATHS=(src/main build.gradle.kts gradle)

# ── git helpers ────────────────────────────────────────────────────────────────────────
commit_exists() { git rev-parse -q --verify "$1^{commit}" >/dev/null 2>&1; }

# Tree/blob object id of a path at a commit, or the literal ABSENT when the path is not there.
# ABSENT vs ABSENT compares equal, which is correct: a path missing from both sides did not move.
obj_at() { git rev-parse -q --verify "$1:$2" 2>/dev/null || echo ABSENT; }

# Top-level directories that are Gradle modules at this commit.
module_dirs_at() {
  local sha="$1" d
  git ls-tree -d --name-only "$sha" 2>/dev/null | while read -r d; do
    git cat-file -e "$sha:$d/build.gradle.kts" 2>/dev/null && printf '%s\n' "$d"
  done
}

# `project(":x")` declarations in one module's build file, as bare module names.
project_deps_at() {
  git show "$1:$2/build.gradle.kts" 2>/dev/null \
    | command grep -oE 'project\("[:][A-Za-z0-9._-]+"\)' \
    | sed -E 's/project\("://; s/"\)//' \
    | sort -u
}

# Transitive closure of `project(":…")` from one module, plus every openbank-libs* module.
# Over-broad on the libs by design (see header).
in_scope_modules() {
  # Two statements, not one: bash 3.2 (the macOS system bash) expands every word of a `local`
  # before performing any of the assignments, so `local root="$2" queue="$root"` reads an unset
  # `root` and dies under `set -u`.
  local sha="$1" root="$2" seen="" cur dep m
  local queue="$root"
  while [ -n "$queue" ]; do
    cur="${queue%%$'\n'*}"; queue="${queue#"$cur"}"; queue="${queue#$'\n'}"
    case $'\n'"$seen"$'\n' in *$'\n'"$cur"$'\n'*) continue ;; esac
    seen="${seen:+$seen$'\n'}$cur"
    for dep in $(project_deps_at "$sha" "$cur"); do
      git cat-file -e "$sha:$dep/build.gradle.kts" 2>/dev/null && queue="${queue:+$queue$'\n'}$dep"
    done
  done
  for m in $(module_dirs_at "$sha"); do
    case "$m" in openbank-libs*) case $'\n'"$seen"$'\n' in *$'\n'"$m"$'\n'*) ;; *) seen="$seen"$'\n'"$m" ;; esac ;; esac
  done
  printf '%s\n' "$seen" | command grep -v '^$' | sort -u
}

# ── the decision ───────────────────────────────────────────────────────────────────────
equivalent() {
  local svc="$1" pact_sha="$2" dispatch_sha="$3"

  commit_exists "$pact_sha" \
    || differs "pact version ${pact_sha} is not a commit in this checkout — nothing to compare against, so the verdict cannot be transferred"
  commit_exists "$dispatch_sha" \
    || differs "dispatch sha ${dispatch_sha} is not a commit in this checkout"

  git cat-file -e "$dispatch_sha:$svc/build.gradle.kts" 2>/dev/null \
    || differs "${svc} is not a Gradle module at ${dispatch_sha} — cannot derive what it is built from"

  git merge-base --is-ancestor "$pact_sha" "$dispatch_sha" 2>/dev/null \
    || differs "pact version ${pact_sha} is not an ancestor of ${dispatch_sha} — history diverged or was rewritten, so 'main moved forward without touching this service' is not what happened"

  # 1. Build the list of in-scope PATHS: this service whole, each dependency module's compile
  #    inputs, the global build directories, and the two `.github/` files that reach the image.
  local scope="" m d a b
  scope="$svc"
  for m in $(in_scope_modules "$dispatch_sha" "$svc"); do
    [ "$m" = "$svc" ] && continue
    for d in "${DEP_SUBPATHS[@]}"; do scope="$scope"$'\n'"$m/$d"; done
  done
  for d in "${GLOBAL_IN_SCOPE_DIRS[@]}" "${GITHUB_IN_SCOPE[@]}"; do scope="$scope"$'\n'"$d"; done

  # 2. Each of them must be the same git object at both commits. A tree object id is a recursive
  #    hash of the whole subtree, so this is byte-equality of every file under it — including mode
  #    changes and renames, which a name-only diff would not show.
  for d in $(printf '%s\n' "$scope" | command grep -v '^$' | sort -u); do
    a="$(obj_at "$pact_sha" "$d")"; b="$(obj_at "$dispatch_sha" "$d")"
    [ "$a" = "$b" ] || differs "${d} differs between ${pact_sha:0:8} and ${dispatch_sha:0:8} (${a:0:8} vs ${b:0:8}) — it is a build input of ${svc}"
  done

  # 3. Classify every remaining changed path. Anything this script cannot place is a refusal.
  local p top changed covered s
  changed="$(git diff --name-only "$pact_sha" "$dispatch_sha" 2>/dev/null)" \
    || differs "could not diff ${pact_sha} against ${dispatch_sha}"
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    case "$p" in */*) top="${p%%/*}" ;; *) top="" ;; esac
    if [ -z "$top" ]; then
      # A root-level file. Default is IN SCOPE, so a build-config file cannot slip past; step 1
      # does not cover root files, so compare it here.
      command grep -qE "$ROOT_IGNORE_RE" <<< "$p" && continue
      a="$(obj_at "$pact_sha" "$p")"; b="$(obj_at "$dispatch_sha" "$p")"
      [ "$a" = "$b" ] || differs "root file ${p} differs — root is where the build configuration lives, so it counts"
      continue
    fi
    # Under an in-scope path: already compared byte-for-byte in step 2 (and equal, or we exited).
    covered=0
    while IFS= read -r s; do
      [ -n "$s" ] || continue
      case "$p" in "$s"|"$s"/*) covered=1; break ;; esac
    done <<< "$scope"
    [ "$covered" = 1 ] && continue
    command grep -qE "$IGNORED_DIR_RE" <<< "$p" && continue
    # Inside a Gradle module directory, but not one of its compile inputs: either a sibling module
    # this service does not depend on, or a dependency's src/test, docs, governance data, version.txt
    # or CHANGELOG.md — none of which is read by `:<svc>:quarkusBuild`.
    if git cat-file -e "$dispatch_sha:$top/build.gradle.kts" 2>/dev/null \
       || git cat-file -e "$pact_sha:$top/build.gradle.kts" 2>/dev/null; then
      continue
    fi
    differs "changed path ${p} is in neither the build inputs of ${svc} nor the justified known-irrelevant set — refusing rather than guessing what a new top-level directory does"
  done <<< "$changed"

  verdict EQUIVALENT \
    "${svc} and every build input of it are byte-identical at ${pact_sha:0:8} and ${dispatch_sha:0:8} (same git tree objects), and ${pact_sha:0:8} is an ancestor — the published verdict is about the same source, so it is not a verdict about a different commit"
  exit 0
}

# ── self-test ──────────────────────────────────────────────────────────────────────────
# Built on REAL git objects in a throwaway repo, because the whole script is git plumbing: a
# mocked `git` would test the mock. Each case asserts the DIRECTION as well as the verdict — a
# harness that only ever sees the equivalent case cannot notice the rule being deleted.
selftest() {
  local me tmp fail=0
  me="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  (
    set -e
    cd "$tmp"
    git init -q .
    git config user.email selftest@example.com
    git config user.name selftest
    git config commit.gpgsign false
    mkdir -p svc-a/src/main openbank-libs-domain/src/main svc-b/src/main docs openbank-infra/gitops \
             gradle config build-logic openbank-contracts .github/workflows
    printf 'implementation(project(":openbank-libs-domain"))\n' > svc-a/build.gradle.kts
    printf 'x\n' > svc-b/build.gradle.kts
    printf 'x\n' > openbank-libs-domain/build.gradle.kts
    printf 'x\n' > build.gradle.kts
    printf 'x\n' > settings.gradle.kts
    printf 'x\n' > gradle/verification-metadata.xml
    printf 'x\n' > config/detekt.yml
    printf 'x\n' > build-logic/build.gradle.kts
    printf 'x\n' > openbank-contracts/api.yaml
    printf 'x\n' > .github/workflows/Dockerfile.deploy
    printf 'x\n' > .github/workflows/auto-deploy.yml
    printf 'x\n' > svc-a/src/main/A.kt
    printf 'x\n' > svc-b/src/main/B.kt
    printf 'x\n' > openbank-libs-domain/src/main/D.kt
    printf 'x\n' > docs/README.md
    printf 'x\n' > README.md
    printf 'x\n' > openbank-infra/gitops/app.yaml
    git add -A && git commit -qm base
  ) || { echo "selftest FAIL: could not build the fixture repo" >&2; return 1; }

  local base; base="$(git -C "$tmp" rev-parse HEAD)"

  # Helper: commit a change and return the new sha.
  _commit() { ( cd "$tmp" && mkdir -p "$(dirname "$1")" && printf '%s\n' "$2" > "$1" && git add -A && git commit -qm "$1" ) >/dev/null && git -C "$tmp" rev-parse HEAD; }
  _run() { ( cd "$tmp" && bash "$me" "$@" ); }

  _expect() { # <label> <expected EQUIVALENT|DIFFERENT> <args...>
    local label="$1" want="$2"; shift 2
    local out rc got
    out="$(_run "$@" 2>&1)"; rc=$?
    got="$(printf '%s' "$out" | head -1 | cut -f1)"
    if [ "$got" != "$want" ]; then
      echo "selftest FAIL: ${label}: expected ${want}, got '${got}' (rc=${rc})" >&2; fail=1; return
    fi
    # Verdict and exit code must agree, or a caller keying on either one is reading a
    # different answer from the one printed.
    if [ "$want" = EQUIVALENT ] && [ "$rc" -ne 0 ]; then
      echo "selftest FAIL: ${label}: printed EQUIVALENT but exited ${rc}" >&2; fail=1
    fi
    if [ "$want" = DIFFERENT ] && [ "$rc" -eq 0 ]; then
      echo "selftest FAIL: ${label}: printed DIFFERENT but exited 0" >&2; fail=1
    fi
  }

  # 1. Nothing changed at all.
  _expect "identical commits" EQUIVALENT svc-a "$base" "$base"

  # 2. Only another service, docs, gitops and a root .md moved — the reconcile case this exists
  #    for. It MUST be equivalent or the fix delivers nothing.
  local s; s="$(_commit svc-b/src/main/B.kt changed)"
  s="$(_commit docs/README.md changed)"
  s="$(_commit openbank-infra/gitops/app.yaml changed)"
  s="$(_commit README.md changed)"
  _expect "unrelated service + docs + gitops + root markdown" EQUIVALENT svc-a "$base" "$s"

  # Every later case compares against its IMMEDIATE predecessor, so each one isolates exactly one
  # changed path. Chaining them all back to `base` would make every case DIFFERENT for the reason
  # the previous case introduced, and the assertions would pass while proving nothing.
  local prev="$s"

  # 3. This service's own source moved.
  local s3; s3="$(_commit svc-a/src/main/A.kt changed)"
  _expect "the service's own source changed" DIFFERENT svc-a "$prev" "$s3"
  # ...and the equivalence for the OTHER service still holds over the same pair, which proves the
  # comparison is per-service and not a blanket "did anything change".
  _expect "svc-b is unaffected by svc-a's change" EQUIVALENT svc-b "$prev" "$s3"

  # 4. A shared library moved. This is the case an under-broad scope gets wrong.
  local s4; s4="$(_commit openbank-libs-domain/src/main/D.kt changed)"
  _expect "shared libs module changed" DIFFERENT svc-a "$s3" "$s4"
  # ...including for a service that does NOT declare it, because libs is deliberately over-broad.
  _expect "shared libs change counts even without a declaration" DIFFERENT svc-b "$s3" "$s4"

  # 4b. A shared module's NON-compile subtree. This is the narrowing that makes the whole feature
  #     reachable — measured 2026-08-03, 12 of 32 services were refused for nothing but four files
  #     under openbank-libs/governance/ — so it needs a case in both directions, not just the
  #     src/main one above.
  local s4b; s4b="$(_commit openbank-libs-domain/governance/data.yaml changed)"
  _expect "shared module governance data changed" EQUIVALENT svc-a "$s4" "$s4b"
  local s4c; s4c="$(_commit openbank-libs-domain/src/test/T.kt changed)"
  _expect "shared module test source changed" EQUIVALENT svc-a "$s4b" "$s4c"
  # ...but the service's OWN tree is compared whole, tests included: its consumer pact is generated
  # from those tests, so a change there is a change to what was published.
  local s4d; s4d="$(_commit svc-a/src/test/T.kt changed)"
  _expect "the service's own test source changed" DIFFERENT svc-a "$s4c" "$s4d"

  # 5. Root build configuration.
  local s5; s5="$(_commit settings.gradle.kts changed)"
  _expect "root settings.gradle.kts changed" DIFFERENT svc-a "$s4d" "$s5"

  # 6. The Dockerfile that is copied into the image.
  local s6; s6="$(_commit .github/workflows/Dockerfile.deploy changed)"
  _expect "Dockerfile.deploy changed" DIFFERENT svc-a "$s5" "$s6"

  # 7. A `.github/` path that is NOT a build input must not block.
  local s7; s7="$(_commit .github/workflows/security.yml changed)"
  _expect "an unrelated workflow changed" EQUIVALENT svc-a "$s6" "$s7"

  # 8. A top-level directory this script has never heard of — the default that keeps an
  #    include-list from failing silently.
  local s8; s8="$(_commit brand-new-tree/thing.txt changed)"
  _expect "unrecognised new top-level directory" DIFFERENT svc-a "$s7" "$s8"

  # 9. A sha that does not exist.
  _expect "pact sha absent from the checkout" DIFFERENT svc-a 0000000000000000000000000000000000000000 "$base"
  _expect "dispatch sha absent from the checkout" DIFFERENT svc-a "$base" 0000000000000000000000000000000000000000

  # 10. Ancestry. The pair MUST be one that case 2 already proved EQUIVALENT forwards, or the
  #     assertion is vacuous: any pair that differs in scope would refuse for that reason and
  #     stay red with the ancestry rule deleted. Reversed, only ancestry can reject it.
  _expect "pact sha is not an ancestor" DIFFERENT svc-a "$s" "$base"

  # 11. Usage errors fail closed.
  _expect "missing arguments" DIFFERENT svc-a "$base"

  [ "$fail" -eq 0 ] && echo "selftest OK: 17 cases on real git trees — identical, unrelated-elsewhere, own-source, own tests, shared-libs src/main (declared and not), a shared module's governance data and tests, root build config, baked-in Dockerfile, unrelated workflow, unknown directory, both missing shas, reversed ancestry, and a usage error; verdict and exit code asserted to agree in both directions."
  return "$fail"
}

if [ "${1:-}" = "--self-test" ] || [ "${1:-}" = "--selftest" ]; then
  selftest
  exit $?
fi

if [ $# -ne 3 ]; then
  verdict DIFFERENT "usage: $0 <service> <pact_sha> <dispatch_sha> — refusing rather than assuming equivalence"
  exit 2
fi

equivalent "$1" "$2" "$3"
