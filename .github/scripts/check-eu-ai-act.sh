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

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOC="docs/compliance/eu-ai-act.md"

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
