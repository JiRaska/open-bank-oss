#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Snapshot only these fixed action manifests before a historical source checkout.
# Restore them from the same trusted directory as the service CI shell helpers.
set -euo pipefail
mode="${1:?stage or restore required}"
helpers="${2:?trusted helper directory required}"
case "$mode" in stage|restore) ;; *) exit 2 ;; esac
for directory in .github .github/actions "$helpers"; do
  [[ ! -L "$directory" && ( ! -e "$directory" || -d "$directory" ) ]] || exit 1
done
for action in service-ci-gradle-profile service-ci-coverage-evidence; do
  directory=".github/actions/$action"
  manifest="$directory/action.yml"
  snapshot="$helpers/$action.yml"
  [[ ! -L "$directory" && ( ! -e "$directory" || -d "$directory" ) ]] || exit 1
  for file in "$manifest" "$snapshot"; do
    [[ ! -L "$file" && ( ! -e "$file" || -f "$file" ) ]] || exit 1
  done
  if [[ "$mode" == stage ]]; then
    [[ -f "$manifest" ]] || exit 1
    cp "$manifest" "$snapshot"
    chmod 600 "$snapshot"
  else
    [[ -f "$snapshot" ]] || exit 1
    mkdir -p "$directory"
    cp "$snapshot" "$manifest"
  fi
done
