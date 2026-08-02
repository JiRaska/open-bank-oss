// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Derive the ADR-0211 loan-origination state graph from the Kotlin that actually runs it
// (CLAUDE.md rule #6: derived data is never hand-edited; the same bake pattern as
// generate-catalog.mjs / generate-governance.mjs).
//
// WHY THIS IS GENERATED AND NOT TYPED INTO THE UI
// The origination console draws the state machine. If the UI carried its own copy of the
// state list, that copy would be a second hand-maintained enumeration of something that
// lives in `openbank-libs-domain` — and it would drift silently, because a diagram that
// disagrees with the machine still renders perfectly. The repo has been bitten by exactly
// that class of bug (a published NOTICE listing 4 AGPL modules against 12 in the tree). So
// the states and the edges come from:
//
//   OriginationState.kt              → the state list, their order, and the terminal set
//   OriginationTransitionPolicy.kt   → standard()'s allowedTransitions map (the edges)
//
// FAIL LOUD, NEVER EMPTY
// Every extraction below asserts what it must find. A regex that silently matches nothing
// would emit a valid-looking `{states: [], edges: []}` and the console would render an empty
// diagram that reads like "this application has no lifecycle" — the failure mode this repo
// calls a broken probe reporting clean. Counts are checked against the source, not assumed.
//
// Usage: node scripts/generate-origination-graph.mjs [--repo <path>] [--out <file>]

import { readFileSync, writeFileSync } from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}

const REPO = path.resolve(getArg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'origination-graph.json')))

const ORIGIN = 'openbank-libs-domain/src/main/kotlin/com/openbank/libs/lending/origination'
const STATE_KT = path.join(REPO, ORIGIN, 'OriginationState.kt')
const POLICY_KT = path.join(REPO, ORIGIN, 'OriginationTransitionPolicy.kt')

function die(msg) {
  console.error(`[generate-origination-graph] ${msg}`)
  process.exit(1)
}

function read(p) {
  try {
    return readFileSync(p, 'utf8')
  } catch {
    die(`cannot read ${path.relative(REPO, p)} — the ADR-0211 source of truth moved; fix this script, do not hand-write the graph`)
  }
}

/** Kotlin block comments NEST, so strip them the way the compiler does — a KDoc containing
 *  `/*` would otherwise close early and swallow real declarations. Line comments after. */
function stripComments(src) {
  let out = ''
  let depth = 0
  for (let i = 0; i < src.length; i++) {
    if (src.startsWith('/*', i)) { depth++; i++; continue }
    if (depth > 0 && src.startsWith('*/', i)) { depth--; i++; continue }
    if (depth === 0) out += src[i]
  }
  return out.replace(/\/\/[^\n]*/g, '')
}

// --- states -------------------------------------------------------------------------------

const stateSrc = stripComments(read(STATE_KT))
const enumBody = stateSrc.match(/enum\s+class\s+OriginationState\s*\{([\s\S]*?)\n\s*;/)
if (!enumBody) die('could not locate the OriginationState enum body')

const states = [...enumBody[1].matchAll(/^\s*([A-Z][A-Z0-9_]*)\s*,/gm)].map(m => m[1])
if (states.length < 2) die(`extracted ${states.length} states — the enum shape changed`)

const terminalMatch = stateSrc.match(/TERMINAL\s*:\s*Set<OriginationState>\s*=\s*setOf\(([^)]*)\)/)
if (!terminalMatch) die('could not locate OriginationState.TERMINAL')
const terminal = terminalMatch[1].split(',').map(s => s.trim()).filter(Boolean)
if (terminal.length === 0) die('TERMINAL parsed as empty')

for (const t of terminal) {
  if (!states.includes(t)) die(`TERMINAL names ${t}, which is not an enum constant`)
}

// --- edges --------------------------------------------------------------------------------

const policySrc = stripComments(read(POLICY_KT))
const mapBody = policySrc.match(/allowedTransitions\s*=\s*mapOf\(([\s\S]*?)\n\s*\),?\s*\n\s*\)/)
if (!mapBody) die('could not locate standard()\'s allowedTransitions map')

const edges = {}
for (const m of mapBody[1].matchAll(/([A-Z][A-Z0-9_]*)\s+to\s+setOf\(([^)]*)\)/g)) {
  const from = m[1]
  const to = m[2].split(',').map(s => s.trim()).filter(Boolean)
  if (!states.includes(from)) die(`transition source ${from} is not an enum constant`)
  for (const t of to) if (!states.includes(t)) die(`transition target ${t} is not an enum constant`)
  edges[from] = to
}
if (Object.keys(edges).length === 0) die('allowedTransitions parsed as empty')

// The invariant that makes the two files agree: every NON-terminal state must have outgoing
// edges, and every terminal state must have none. If the machine ever violates this the
// console would draw a dead end that the service can actually leave (or vice versa).
for (const s of states) {
  const isTerminal = terminal.includes(s)
  const hasEdges = (edges[s] ?? []).length > 0
  if (isTerminal && hasEdges) die(`${s} is TERMINAL but has outgoing transitions`)
  if (!isTerminal && !hasEdges) die(`${s} is non-terminal but has no outgoing transition`)
}

const graph = {
  generatedFrom: [path.relative(REPO, STATE_KT), path.relative(REPO, POLICY_KT)],
  adr: 'ADR-0211 D1',
  states,
  terminal,
  edges,
}

writeFileSync(OUT, `${JSON.stringify(graph, null, 2)}\n`)
console.log(
  `[generate-origination-graph] ${states.length} states (${terminal.length} terminal), ` +
  `${Object.values(edges).flat().length} edges → ${OUT}`,
)
