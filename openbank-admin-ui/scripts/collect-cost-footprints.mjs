// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Derive per-service resource footprints (the cost-allocation cost-driver) from the gitops
// Deployment manifests, baked into the admin-ui image at build time (ADR-0062, mirrors
// generate-service-graph.mjs). The admin-ui is a READ-ONLY consumer (root CLAUDE.md rule #3/#7):
// it never reaches into openbank-infra/ at runtime, so this collector flattens the authoritative
// `resources.requests` (cpu/memory) into cost-footprints.json, which the allocation route serves.
//
// The CANONICAL service key is the Deployment metadata.name, which equals the admin-ui manifest
// serviceName and the rules.yaml:finops_cost_groups ids — the stable join across all three.
//
// Honest by construction: a manifest with no requests contributes no footprint (never a
// fabricated 0); no gitops tree → an empty available:false document, so the build never fails
// and the panel degrades to a calm "unavailable" state.
//
// Usage: node scripts/collect-cost-footprints.mjs [--repo <path>] [--out <file>]

import { readdirSync, statSync, readFileSync, writeFileSync } from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { parseAllDocuments } from 'yaml'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}
const REPO = path.resolve(getArg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'cost-footprints.json')))
const COMPONENTS = path.join(REPO, 'openbank-infra', 'gitops', 'components')

// "250m" -> 250 ; "1" / "1.5" -> 1000 / 1500 millicores.
function cpuToMillis(v) {
  if (v == null) return 0
  const s = String(v).trim()
  if (s.endsWith('m')) return parseInt(s.slice(0, -1), 10) || 0
  return Math.round((parseFloat(s) || 0) * 1000)
}

// Kubernetes memory quantity -> MiB. Handles binary (Ki/Mi/Gi/Ti) and decimal (K/M/G/T) suffixes.
function memToMiB(v) {
  if (v == null) return 0
  const s = String(v).trim()
  const m = s.match(/^([0-9.]+)\s*([KMGTP]i?)?B?$/)
  if (!m) return Math.round((parseFloat(s) || 0) / 1048576) // bare bytes
  const n = parseFloat(m[1]) || 0
  const unit = m[2] ?? ''
  const MiB = 1048576
  const factor = {
    '': 1 / MiB, Ki: 1024 / MiB, Mi: 1, Gi: 1024, Ti: 1024 * 1024,
    K: 1000 / MiB, M: 1e6 / MiB, G: 1e9 / MiB, T: 1e12 / MiB,
  }[unit] ?? 1
  return Math.round(n * factor)
}

function listYaml(dir) {
  const out = []
  let entries
  try { entries = readdirSync(dir) } catch { return out }
  for (const e of entries) {
    const p = path.join(dir, e)
    let st
    try { st = statSync(p) } catch { continue }
    if (st.isDirectory()) out.push(...listYaml(p))
    else if (e.endsWith('.yaml') || e.endsWith('.yml')) out.push(p)
  }
  return out
}

// Sum requests across all containers of a Deployment pod template.
function footprintOf(deployment) {
  const containers = deployment?.spec?.template?.spec?.containers
  if (!Array.isArray(containers)) return null
  let cpuMillis = 0, memMiB = 0
  for (const c of containers) {
    const req = c?.resources?.requests
    if (!req) continue
    cpuMillis += cpuToMillis(req.cpu)
    memMiB += memToMiB(req.memory)
  }
  if (cpuMillis <= 0 && memMiB <= 0) return null
  return { cpuMillis, memMiB }
}

const found = new Map() // service -> {cpuMillis, memMiB}
for (const file of listYaml(COMPONENTS)) {
  let docs
  try { docs = parseAllDocuments(readFileSync(file, 'utf-8')) } catch { continue }
  for (const d of docs) {
    let obj
    try { obj = d.toJSON() } catch { continue }
    if (obj?.kind !== 'Deployment') continue
    const name = obj?.metadata?.name
    const fp = footprintOf(obj)
    if (!name || !fp) continue
    // Keep the larger request if a name appears twice (overlays).
    const prev = found.get(name)
    if (!prev || fp.cpuMillis + fp.memMiB > prev.cpuMillis + prev.memMiB) found.set(name, fp)
  }
}

const footprints = [...found.entries()]
  .map(([service, fp]) => ({ service, ...fp }))
  .sort((a, b) => b.cpuMillis + b.memMiB - (a.cpuMillis + a.memMiB))

const doc = {
  schema: 'openbank.cost-footprints/v1',
  generator: 'collect-cost-footprints.mjs',
  source: 'gitops Deployment resources.requests (openbank-infra/gitops/components) — ADR-0062',
  available: footprints.length > 0,
  collectedAt: null,
  footprints,
}

writeFileSync(OUT, JSON.stringify(doc, null, 2) + '\n')
console.log(`[collect-cost-footprints] ${footprints.length} service footprints from ${COMPONENTS} → ${OUT}`)
