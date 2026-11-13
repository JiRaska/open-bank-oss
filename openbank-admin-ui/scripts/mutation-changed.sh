#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# ADR-0370 U5 — mutate only the decision-module files this change touched.
# Usage: scripts/mutation-changed.sh <base-ref>   (run from openbank-admin-ui/)
#    or: MUTATION_FILES="<repo-root paths, one per line>" scripts/mutation-changed.sh
# CI passes MUTATION_FILES from `gh pr diff --name-only`: its checkout is shallow, so the merge
# base a three-dot diff needs is not there.
set -euo pipefail
base="${1:-}"
if [[ -z "${MUTATION_FILES:-}" && -z "$base" ]]; then
  echo "usage: mutation-changed.sh <base-ref>, or set MUTATION_FILES" >&2
  exit 2
fi
scope='^openbank-admin-ui/src/lib/(aml|approvals|auth|closings|consents|delegations|disputes|fees|format|fraud|interest|ledger|lending|mutations|payments|sanctions|settlement)/.*\.ts$'
files=()
while IFS= read -r f; do
  [[ "$f" == *.d.ts ]] && continue
  [[ -f "../$f" ]] && files+=("${f#openbank-admin-ui/}")
done < <(
  if [[ -n "${MUTATION_FILES:-}" ]]; then printf '%s\n' "$MUTATION_FILES"
  else git diff --name-only --diff-filter=AMR "$base"...HEAD -- ':/openbank-admin-ui/src/lib'
  fi | grep -E "$scope" || true
)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "mutation: no decision-module file changed — nothing to mutate (base: ${base:-MUTATION_FILES})"
  exit 0
fi
echo "mutation: ${#files[@]} changed file(s): ${files[*]}"
mutate=$(IFS=,; echo "${files[*]}")
if [[ "${MUTATION_DRY_RUN:-}" == 1 ]]; then echo "mutation: dry run, would mutate $mutate"; exit 0; fi
exec npx stryker run --mutate "$mutate"
