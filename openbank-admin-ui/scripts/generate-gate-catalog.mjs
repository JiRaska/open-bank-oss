// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Build-time, read-only projection of the authoritative CI gate manifest.
// No GitHub token or runtime checkout is needed by the admin UI.
import { readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseDocument } from 'yaml'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = path.resolve(here, '../../.github/gates/gates.yaml')
const output = path.resolve(here, '../gate-catalog.json')
const raw = readFileSync(source, 'utf8')
const document = parseDocument(raw, { uniqueKeys: true })
if (document.errors.length) throw new Error(`Invalid gate manifest: ${document.errors.join('; ')}`)
const entries = document.toJS().gates
if (!Array.isArray(entries) || entries.length === 0) throw new Error('Gate manifest has no gates')

const ids = new Set()
const lines = new Map()
raw.split('\n').forEach((line, index) => {
  const match = /^  - id: (\S+)/.exec(line)
  if (match) lines.set(match[1], index + 1)
})
const gates = entries.map(gate => {
  if (!gate.id || !gate.name || !gate.group || !gate.mode || !gate.run || ids.has(gate.id) || !lines.has(gate.id)) {
    throw new Error(`Incomplete or duplicate gate: ${gate.id}`)
  }
  if (!['enforced', 'advisory'].includes(gate.mode) || !['always', 'pull_request'].includes(gate.when ?? 'always')) {
    throw new Error(`Unknown gate mode or trigger: ${gate.id}`)
  }
  ids.add(gate.id)
  return {
    id: gate.id,
    name: gate.name,
    group: gate.group,
    mode: gate.mode,
    when: gate.when ?? 'always',
    needsBase: gate.needs_base ?? null,
    selftest: Boolean(gate.selftest),
    selftestExempt: Boolean(gate.selftest_exempt),
    rationale: gate.rationale ?? null,
    reviewAfter: gate.review_after ?? null,
    minSubjects: gate.min_subjects ?? null,
    budgetSeconds: gate.budget_seconds ?? null,
    run: gate.run.trim(),
    line: lines.get(gate.id),
  }
})

const sha = process.env.BUILD_GIT_SHA
const ref = /^[a-f0-9]{40}$/.test(sha ?? '') ? sha : 'main'
const groups = Object.fromEntries([...new Set(gates.map(gate => gate.group))].sort()
  .map(group => [group, gates.filter(gate => gate.group === group).length]))
const catalog = {
  source: '.github/gates/gates.yaml', ref,
  totals: {
    all: gates.length,
    enforced: gates.filter(gate => gate.mode === 'enforced').length,
    advisory: gates.filter(gate => gate.mode === 'advisory').length,
    selftested: gates.filter(gate => gate.selftest).length,
    selftestExempt: gates.filter(gate => gate.selftestExempt).length,
    prOnly: gates.filter(gate => gate.when === 'pull_request').length,
    withRationale: gates.filter(gate => gate.rationale).length,
    groups,
  },
  gates,
}
writeFileSync(output, `${JSON.stringify(catalog)}\n`)
console.log(`Generated gate catalog: ${gates.length} gates in ${Object.keys(groups).length} execution shards`)
