#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# The canonical fast-jar Dockerfile is copied into every Gradle service image.
# A change to that exact recipe must rebuild its consumers, including modules
# without a per-service Dockerfile. Self-built images use their own recipe.
# Usage: auto-deploy-template-dependents.sh <all-services> <self-built-services>
# Changed paths arrive on stdin, one per line. Output is one service per line.
set -euo pipefail

if [[ ${1:-} == --self-test ]]; then
  script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  fixture="$(mktemp -d)"
  trap 'rm -rf "$fixture"' EXIT
  mkdir -p "$fixture/openbank-a" "$fixture/openbank-b" "$fixture/openbank-self"
  : > "$fixture/openbank-a/build.gradle.kts"
  : > "$fixture/openbank-b/build.gradle.kts"
  : > "$fixture/openbank-self/build.gradle.kts"
  all='openbank-a openbank-b openbank-self'
  self='openbank-self'
  actual="$(cd "$fixture" && printf '%s\n' '.github/workflows/Dockerfile.deploy' \
    | bash "$script" "$all" "$self")"
  [[ "$actual" == $'openbank-a\nopenbank-b' ]] || {
    echo "self-test: canonical recipe must select both Gradle consumers, excluding self-built" >&2
    exit 1
  }
  for unrelated in '.github/workflows/auto-deploy.yml' 'openbank-a/Dockerfile' \
    '.github/workflows/Dockerfile.deploy.backup'; do
    actual="$(cd "$fixture" && printf '%s\n' "$unrelated" | bash "$script" "$all" "$self")"
    [[ -z "$actual" ]] || { echo "self-test: unrelated change selected template consumers" >&2; exit 1; }
  done
  rm "$fixture/openbank-b/build.gradle.kts"
  if (cd "$fixture" && printf '%s\n' '.github/workflows/Dockerfile.deploy' \
      | bash "$script" "$all" "$self" >/dev/null 2>&1); then
    echo "self-test: missing Gradle recipe silently omitted a declared template consumer" >&2
    exit 1
  fi
  echo 'self-test OK: exact template change selects Gradle consumers and excludes self-built'
  exit 0
fi

all_services="${1:?all-services argument required}"
self_built="${2:?self-built-services argument required}"
changed="$(cat)"
if ! grep -qxF '.github/workflows/Dockerfile.deploy' <<< "$changed"; then
  exit 0
fi

for svc in $all_services; do
  case " $self_built " in
    *" $svc "*) continue ;;
  esac
  if [[ ! -f "$svc/build.gradle.kts" ]]; then
    echo "ERROR: canonical Dockerfile consumer $svc has no Gradle build file and is not self-built" >&2
    exit 1
  fi
  printf '%s\n' "$svc"
done
