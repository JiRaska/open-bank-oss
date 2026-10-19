#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
resolver="$repo_root/.github/scripts/resolve-historical-service-ref.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

git -C "$tmp" init --quiet --initial-branch=work
git -C "$tmp" config user.name 'Resolver test'
git -C "$tmp" config user.email 'resolver-test@example.invalid'
printf 'base\n' > "$tmp/file"
git -C "$tmp" add file
git -C "$tmp" -c commit.gpgsign=false commit --quiet -m base
main_sha="$(git -C "$tmp" rev-parse HEAD)"
git -C "$tmp" update-ref refs/remotes/origin/main "$main_sha"

# Match Actions: checkout is detached and there is no local `main` branch.
printf 'trigger\n' >> "$tmp/file"
git -C "$tmp" -c commit.gpgsign=false commit --quiet -am trigger
git -C "$tmp" checkout --quiet --detach HEAD

resolved_branch="$(cd "$tmp" && bash "$resolver" main)"
[ "$resolved_branch" = "$main_sha" ]
resolved_sha="$(cd "$tmp" && bash "$resolver" "$main_sha")"
[ "$resolved_sha" = "$main_sha" ]

if (cd "$tmp" && bash "$resolver" missing-branch >/dev/null 2>&1); then
  echo 'unfetched historical branch unexpectedly resolved' >&2
  exit 1
fi

echo 'resolve-historical-service-ref: detached branch, SHA, and missing-ref controls passed'
