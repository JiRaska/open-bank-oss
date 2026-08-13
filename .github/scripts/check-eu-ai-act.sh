#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: docs/compliance/eu-ai-act.md is DERIVED from agents.yaml (ADR-0148, rule #7).
# It must never be hand-edited and must never drift from a fresh generator run — a stale
# AI Act inventory is worse than none, because auditors read it as a live claim.
#
# Regenerate the doc, and fail if the committed copy differs. Deterministic generator =>
# a clean tree means no diff. Restores the working tree afterwards so the check is read-only.
set -euo pipefail

# --- self-test ------------------------------------------------------------------------
# This gate's claim is narrow and entirely mechanical: the committed
# docs/compliance/eu-ai-act.md equals what the generator produces from agents.yaml. That
# claim is worth checking because the document is an EU AI Act inventory read by people
# deciding whether a system is registered — a stale copy is a compliance statement about a
# fleet that has moved, and it is stale SILENTLY, since a generated file nobody regenerates
# looks exactly like one that is current.
#
# What can go wrong in the gate itself is the drift DETECTION, so that is what the fixture
# exercises — in a throwaway git repo, never in the real tree. Note the production path calls
# `git checkout --` on a finding, which is precisely why this must not run in place: a test
# that tampered with the working copy would have its evidence reverted by the thing it tests.
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d); trap 'rm -rf "$td"' EXIT
  fails=0
  SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

  mkfixture() { # mkfixture <dir> <generated-content> <committed-content>
    local d="$1" gen="$2" com="$3"
    mkdir -p "$d/.github/scripts" "$d/docs/compliance"
    cat > "$d/.github/scripts/gen-eu-ai-act.py" <<PYGEN
import pathlib
pathlib.Path("docs/compliance/eu-ai-act.md").write_text("""$gen""")
PYGEN
    printf '%s' "$com" > "$d/docs/compliance/eu-ai-act.md"
    git -C "$d" init -q 2>/dev/null
    git -C "$d" add -A >/dev/null 2>&1
    git -C "$d" -c user.email=t@t -c user.name=t commit -qm fixture >/dev/null 2>&1
    return 0
  }
  expect() { # expect <label> <dir> <want-rc> [substring]
    local label="$1" d="$2" want="$3" sub="${4:-}" out rc
    out=$(EU_AI_ACT_ROOT="$d" bash "$SELF" 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then
      echo "::error::self-test: $label — want rc=$want got $rc: $out" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong (no '$sub'): $out" >&2; fails=$((fails+1))
    fi
  }

  # IN SYNC: generator output equals the committed file. The only clean shape.
  a="$td/sync"; mkfixture "$a" "same content" "same content"
  expect "an in-sync document passes" "$a" 0 "is in sync"

  # DRIFT: the committed file no longer matches what agents.yaml produces. This is the whole
  # subject, and it must be RED — a gate that cannot see drift is a gate that certifies
  # whatever happens to be committed.
  b="$td/drift"; mkfixture "$b" "regenerated content" "stale content"
  expect "a stale document is caught" "$b" 1 "is stale"

  # A GENERATOR THAT CANNOT RUN is not "no drift". Without this, a broken generator silently
  # turns the gate into a no-op that reports success — the failure mode this whole batch of
  # work exists to remove.
  c="$td/brokengen"; mkfixture "$c" "x" "x"
  printf 'import sys\nsys.exit(3)\n' > "$c/.github/scripts/gen-eu-ai-act.py"
  expect "a generator that fails to run is an ERROR, not a pass" "$c" 1 "failed to run"

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: eu-ai-act drift detection is falsifiable (3 cases)"
  exit 0
fi

# EU_AI_ACT_ROOT lets the self-test point this at a throwaway git repo. Production never sets it.
ROOT="${EU_AI_ACT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
DOC="docs/compliance/eu-ai-act.md"

# `cd "$ROOT"` first: the generator writes its output to a RELATIVE path, so running it from
# some other directory regenerates a file somewhere else entirely while this script diffs the
# one under $ROOT — the check then compares an untouched file against itself and reports "in
# sync" whatever the truth is. Harmless while CI always runs from the repo root, and a live
# hazard the moment anything passes a root (the self-test below did exactly that, and wrote
# into the working tree it was supposed to leave alone).
cd "$ROOT" || { echo "::error title=EU AI Act::cannot cd to $ROOT" >&2; exit 1; }

python3 "$ROOT/.github/scripts/gen-eu-ai-act.py" >/dev/null 2>&1 || {
  echo "::error title=EU AI Act::gen-eu-ai-act.py failed to run" >&2
  exit 1
}

if ! git -C "$ROOT" diff --quiet -- "$DOC" 2>/dev/null; then
  echo "::error title=EU AI Act::${DOC} is stale — run 'python3 .github/scripts/gen-eu-ai-act.py' and commit the result." >&2
  echo "----- ${DOC} drift (committed vs regenerated) -----" >&2
  git -C "$ROOT" --no-pager diff -- "$DOC" >&2 || true
  git -C "$ROOT" checkout -- "$DOC" 2>/dev/null || true
  exit 1
fi
echo "eu-ai-act: ${DOC} is in sync with agents.yaml."
