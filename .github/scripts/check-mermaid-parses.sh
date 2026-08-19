#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Every ```mermaid block in the tree must PARSE with the mermaid the admin-ui renders with.
#
# WHY THIS EXISTS
#   The Service Docs viewer renders the per-service docs client-side with mermaid. A block
#   that does not parse is not a degraded diagram — it is a red "Mermaid render failed:
#   Parse error" box where the architecture diagram should be, on a page nothing else in CI
#   looks at. Measured 2026-08-19: 40 of 248 blocks across 21 services were in that state,
#   in both language variants, and the oldest had been broken since the docs were written.
#   Nothing was going to find them except a human opening the page (which is how this one
#   was found).
#
# THE TWO DEFECTS IT WOULD HAVE CAUGHT, AND WHY NEITHER IS OBVIOUS
#   1. A bare `@` in an UNQUOTED node label. mermaid 11 lexes `@` as the node-metadata
#      shorthand (`id@{...}`), so `outbox[Outbox<br/>Dispatcher<br/>@Scheduled every 5s]`
#      is a syntax error. The parse error's caret points at unrelated EARLIER text on the
#      line — in the original sighting, `Hibernate Reactive / Panache`, which parses fine on
#      its own — so reading the error message leads away from the cause. Quote the label.
#   2. A literal `;` in a sequenceDiagram message or Note. `;` is a statement separator
#      there. Write `#59;`, which renders as `;`.
#
# WHY IT IS A GATE AND NOT AN ADMIN-UI TEST
#   The renderer lives in openbank-admin-ui, so the instinct is to put this in its vitest
#   suite. That would be the #4083 shape verbatim: the `ui-build` job fires on
#   `openbank-admin-ui/` paths, and a broken mermaid block arrives in a DOCS-only PR that
#   touches none of them. The job would be ABSENT, not skipped, and nothing would say a
#   check had not been consulted. gates.yaml runs unconditionally by construction.
#
# WHY IT USES ADMIN-UI'S OWN mermaid
#   The only claim worth making is "this parses in the thing that renders it". Resolving
#   mermaid from openbank-admin-ui/node_modules means the gate cannot drift from the
#   renderer's version — a second, separately pinned copy in .github/tools would be two
#   artefacts free to disagree, and the disagreement would be silent in the direction that
#   matters (gate green, page red).
#
# WHY IT NEEDS jsdom
#   mermaid.parse() sanitises through DOMPurify, which throws `DOMPurify.addHook is not a
#   function` with no DOM present — for VALID input. Without a DOM the gate would be red on
#   everything, and its "correctly rejected" cases would be red for the wrong reason, which
#   is the worst possible failure for a checker. jsdom is an admin-ui devDependency, so the
#   dependency install below is the full `npm ci`, not `--omit=dev`.
#
# Usage:  check-mermaid-parses.sh [--self-test]
# Env:    MERMAID_SCAN_ROOT   tree to scan (default: this repo). Set ONLY by the self-test.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
UI_DIR="$REPO_ROOT/openbank-admin-ui"
SCAN_ROOT="${MERMAID_SCAN_ROOT:-$REPO_ROOT}"

# Shared with governance-manifest, and shared deliberately: both gates run in the `lint` shard,
# which run-gates.py executes concurrently, and two simultaneous `npm ci` calls in one directory
# destroy each other's node_modules. See the helper's header.
# shellcheck source=.github/scripts/ensure-admin-ui-deps.sh
source "$SCRIPT_DIR/ensure-admin-ui-deps.sh"

ensure_deps() { ensure_admin_ui_deps "mermaid-parses"; }

run_check() {
  UI_DIR="$UI_DIR" SCAN_ROOT="$SCAN_ROOT" node --input-type=module <<'NODE'
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const uiDir = process.env.UI_DIR;
const scanRoot = process.env.SCAN_ROOT;
const req = createRequire(path.join(uiDir, 'package.json'));

const { JSDOM } = await import(pathToFileURL(req.resolve('jsdom')).href);
const dom = new JSDOM('<!doctype html><html><body></body></html>');
globalThis.window = dom.window;
globalThis.document = dom.window.document;

const mermaidPkg = req('mermaid/package.json');
const mermaid = (await import(pathToFileURL(req.resolve('mermaid')).href)).default;
mermaid.initialize({ startOnLoad: false });

// Skip trees that are not ours to lint. node_modules and build output carry vendored
// markdown whose diagrams we neither own nor render.
const SKIP = new Set(['node_modules', '.git', '.next', 'build', 'dist', 'target', '.gradle']);
const files = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) { if (!SKIP.has(e.name)) walk(path.join(dir, e.name)); }
    else if (e.isFile() && e.name.endsWith('.md')) files.push(path.join(dir, e.name));
  }
})(scanRoot);

let blocks = 0;
const failures = [];
for (const f of files.sort()) {
  const src = fs.readFileSync(f, 'utf8');
  // Fenced mermaid blocks only. An indented or ~~~-fenced block is not what the viewer
  // renders, so widening this would flag text the page never parses.
  for (const [i, m] of [...src.matchAll(/```mermaid\r?\n([\s\S]*?)```/g)].entries()) {
    blocks++;
    try {
      await mermaid.parse(m[1]);
    } catch (e) {
      const [first, second = ''] = String(e?.message ?? e).split('\n');
      failures.push({ file: path.relative(scanRoot, f), block: i, msg: `${first} ${second}`.trim() });
    }
  }
}

// A gate whose subject set is empty must not read as a pass: no mermaid blocks means the
// walk is broken or pointed at the wrong tree, not that every diagram is fine (#2165).
if (blocks === 0) {
  console.error(`::error::[mermaid-parses] found NO mermaid blocks under ${scanRoot} — the scan, not the tree, is what this proves.`);
  process.exit(1);
}

for (const f of failures) {
  console.error(`::error file=${f.file}::[mermaid-parses] block #${f.block} does not parse: ${f.msg}`);
}
console.log(`[mermaid-parses] mermaid ${mermaidPkg.version}: ${failures.length} failing / ${blocks} blocks in ${files.length} markdown files`);
// The subject count run-gates.py checks against min_subjects: a gate that examined nothing
// passes everything (#4339).
console.log(`SUBJECTS=${blocks}`);
if (failures.length > 0) {
  console.error('[mermaid-parses] These render as a red "Mermaid render failed" box in admin-ui Service Docs.');
  console.error('[mermaid-parses] Common causes: a bare `@` in an unquoted node label (quote the label);');
  console.error('[mermaid-parses] a literal `;` in a sequenceDiagram message or Note (write `#59;`).');
  process.exit(1);
}
NODE
}

# --- falsification -----------------------------------------------------------------------
# A gate that has only ever passed is unfalsified (#2165, #2154, #2177). Both historical
# defect shapes are replayed here as fixtures the gate MUST reject, plus their fixed forms,
# plus the empty-tree case — because "no blocks found" is the way this particular checker
# would go green while measuring nothing.
if [ "${1:-}" = "--self-test" ]; then
  ensure_deps
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  self="${BASH_SOURCE[0]}"
  fails=0

  write_doc() {  # $1 filename, $2 body of the mermaid block
    { echo '```mermaid'; printf '%s\n' "$2"; echo '```'; } > "$tmp/$1"
  }

  probe() {  # $1 label, $2 expected rc, $3 must-appear substring ("" = none)
    local label="$1" want="$2" needle="$3" rc=0 out
    out="$(MERMAID_SCAN_ROOT="$tmp" bash "$self" 2>&1)" || rc=$?
    if [ "$rc" != "$want" ]; then
      echo "  BAD  $label: want rc=$want, got rc=$rc"; echo "$out" | sed 's/^/       | /'
      fails=$((fails + 1)); return
    fi
    if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then
      echo "  BAD  $label: rc=$want as expected, but the output never named '$needle'"
      echo "$out" | sed 's/^/       | /'
      fails=$((fails + 1)); return
    fi
    echo "  ok   $label"
  }

  write_doc good.md 'graph TB
  a["Outbox<br/>Dispatcher<br/>@Scheduled every 5s"]
  b[Sink]
  a --> b'
  probe "a quoted @-label parses" 0 ""

  # Named, because a gate that goes red without saying WHICH file is the #2165 failure.
  write_doc good.md 'graph TB
  a[Outbox<br/>Dispatcher<br/>@Scheduled every 5s]
  b[Sink]
  a --> b'
  probe "an unquoted @-label is flagged, by file" 1 "good.md"

  write_doc good.md 'sequenceDiagram
  A->>B: status=ACTIVE#59; outbox written'
  probe "an escaped ; in a sequence message parses" 0 ""

  write_doc good.md 'sequenceDiagram
  A->>B: status=ACTIVE; outbox written'
  probe "a literal ; in a sequence message is flagged, by file" 1 "good.md"

  rm -f "$tmp"/*.md
  probe "an empty tree FAILS rather than reporting a vacuous pass" 1 "found NO mermaid blocks"

  if [ "$fails" -ne 0 ]; then
    echo "[mermaid-parses] SELF-TEST FAILED ($fails)"; exit 1
  fi
  echo "[mermaid-parses] self-test passed: both defect shapes are detected and the empty case fails closed."
  exit 0
fi

ensure_deps
run_check
