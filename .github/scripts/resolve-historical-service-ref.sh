#!/usr/bin/env bash
set -euo pipefail

ref="${1:?usage: resolve-historical-service-ref.sh <commit-or-branch>}"

resolve_commit() {
  git rev-parse --verify --quiet --end-of-options "${1}^{commit}" 2>/dev/null || true
}

# Actions checks out the triggering SHA detached, so a fetched branch is available
# as origin/<branch>, not as a local branch name. Keep exact commit and explicit-ref
# support, then resolve an ordinary symbolic branch through the fetched remote ref.
resolved_ref="$(resolve_commit "$ref")"
if [ -z "$resolved_ref" ]; then
  case "$ref" in
    refs/heads/*)
      remote_ref="refs/remotes/origin/${ref#refs/heads/}"
      ;;
    refs/remotes/origin/*|refs/tags/*|refs/*|origin/*)
      remote_ref="$ref"
      ;;
    *)
      remote_ref="refs/remotes/origin/$ref"
      ;;
  esac
  resolved_ref="$(resolve_commit "$remote_ref")"
fi

if [ -z "$resolved_ref" ]; then
  echo "::error::Historical service ref '$ref' does not resolve to a commit." >&2
  exit 1
fi

printf '%s\n' "$resolved_ref"
