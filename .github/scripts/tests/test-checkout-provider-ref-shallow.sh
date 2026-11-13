#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# checkout-provider-ref.sh must find a provider commit that is on main but older than a shallow
# checkout's depth (2026-11-13: the sandbox transaction-service 9e765663 could not be replayed
# because `git fetch origin main` in a depth-1 checkout never brought it in). Builds a real
# origin, clones it at depth 1, and runs the step script against the oldest commit; also asserts
# a commit that is not on main is still refused.
set -euo pipefail

while IFS= read -r git_env; do
  unset "$git_env"
done < <(git rev-parse --local-env-vars)
unset GIT_INDEX_FILE GIT_DIR GIT_WORK_TREE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
step="$repo_root/.github/scripts/service-ci-steps/checkout-provider-ref.sh"
resolver="$repo_root/.github/scripts/resolve-historical-service-ref.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
g() { git -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false "$@"; }

g init --quiet --initial-branch=main "$tmp/src"
for i in 1 2 3 4; do
  printf '%s\n' "$i" > "$tmp/src/f"; g -C "$tmp/src" add f; g -C "$tmp/src" commit --quiet -m "c$i"
done
old="$(git -C "$tmp/src" rev-list --max-parents=0 HEAD)"
g -C "$tmp/src" checkout --quiet -b side "$old"
printf 'side\n' > "$tmp/src/f"; g -C "$tmp/src" commit --quiet -am side
offmain="$(git -C "$tmp/src" rev-parse HEAD)"
g -C "$tmp/src" checkout --quiet main
git clone --quiet --bare "$tmp/src" "$tmp/origin.git"

git clone --quiet --depth 1 --no-single-branch "file://$tmp/origin.git" "$tmp/ci"
[ "$(git -C "$tmp/ci" rev-parse --is-shallow-repository)" = true ]
if git -C "$tmp/ci" cat-file -e "$old^{commit}" 2>/dev/null; then
  echo "fixture broken: the old commit is already present in the shallow clone" >&2; exit 1
fi
mkdir -p "$tmp/rt"; cp "$resolver" "$tmp/rt/"

out="$(cd "$tmp/ci" && RUNNER_TEMP="$tmp/rt" REF="$old" bash "$step" 2>&1)" || {
  echo "FAIL: a main commit older than the shallow depth was not checked out:" >&2
  printf '%s\n' "$out" >&2; exit 1; }
[ "$(git -C "$tmp/ci" rev-parse HEAD)" = "$old" ] || { echo "FAIL: HEAD is not the requested commit" >&2; exit 1; }

if (cd "$tmp/ci" && RUNNER_TEMP="$tmp/rt" REF="$offmain" bash "$step" >/dev/null 2>&1); then
  echo "FAIL: a commit that is not on main was accepted" >&2; exit 1
fi
echo "checkout-provider-ref: shallow checkout resolves an old main commit; off-main commit refused"
