#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Counts the test FILES that exist in the repo (not results). Gives the console an inventory to
// compare against "tests that reported", so a gap is visible instead of hidden. Never fabricates:
// counts are directory walks, nothing else.
import { readdirSync, statSync, writeFileSync } from 'fs'
import path from 'path'

const args = process.argv.slice(2)
const arg = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d }
const repo = path.resolve(arg('--repo', path.resolve(process.cwd(), '..')))
const out = arg('--out', path.resolve(process.cwd(), 'test-inventory.json'))
const SKIP = new Set(['node_modules', 'build', '.git', '.next', 'dist'])

function walk(dir, visit) {
  let entries
  try { entries = readdirSync(dir) } catch { return }
  for (const e of entries) {
    if (SKIP.has(e)) continue
    const full = path.join(dir, e)
    let st
    try { st = statSync(full) } catch { continue }
    if (st.isDirectory()) walk(full, visit)
    else visit(full, e)
  }
}

const services = {}
let kotlinUnit = 0
let kotlinIntegration = 0
for (const d of readdirSync(repo)) {
  if (!d.startsWith('openbank-')) continue
  const testDir = path.join(repo, d, 'src', 'test')
  let unit = 0, integration = 0
  walk(testDir, (_f, name) => {
    if (name.endsWith('IT.kt')) integration += 1
    else if (name.endsWith('Test.kt')) unit += 1
  })
  if (unit + integration > 0) services[d] = { unit, integration }
  kotlinUnit += unit; kotlinIntegration += integration
}

let adminUi = 0, e2e = 0
const ui = path.join(repo, 'openbank-admin-ui')
walk(path.join(ui, 'src'), (_f, n) => { if (/\.test\.(ts|tsx|mjs)$/.test(n)) adminUi += 1 })
walk(path.join(ui, 'e2e'), (_f, n) => { if (/\.spec\.ts$/.test(n)) e2e += 1 })

const inventory = {
  collectedAt: new Date().toISOString(),
  kotlin: { unit: kotlinUnit, integration: kotlinIntegration, services },
  adminUi, e2e,
  totalFiles: kotlinUnit + kotlinIntegration + adminUi + e2e,
}
writeFileSync(out, JSON.stringify(inventory, null, 2))
console.log(`test inventory: ${inventory.totalFiles} files -> ${out}`)
