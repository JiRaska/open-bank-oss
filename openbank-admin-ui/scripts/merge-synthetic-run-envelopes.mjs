#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
//
// Merge the per-journey, per-browser Test Intelligence run envelopes a browser-synthetic
// workflow produces into ONE envelope for the deployed build, so a single write-once
// publication per run lands in the commit-addressed evidence store (#13201). The collector
// (collect-test-run-evidence.py) emits one envelope per job; publishing them one by one
// would let the first writer win the write-once key and hide every other journey — so the
// merge happens BEFORE publish-test-evidence.sh, not by racing it.
//
//   node merge-synthetic-run-envelopes.mjs --commit <40-hex deployed commit> \
//     --out merged.json run-a.json run-b.json ...
//
// Merge rules (conservative, mirror the collector's own totals):
//   suites          merged by kind: counts and durationMs summed; state failed when ANY
//                   input failed, skipped when every input executed zero tests
//   testCases       concatenated, deduplicated by fingerprint (the NEWEST observedAt wins)
//   specializedEvidence concatenated verbatim — each journey/browser is its own row and
//                   the projection keys them by kind + variant
//   diagnostics     deduplicated by name
//   run             provenance of the newest observedAt envelope; commit is REWRITTEN to
//                   the deployed commit the caller attests (the envelope then satisfies
//                   componentPostureFromRun's identical-commit check)
import fs from 'node:fs'

const SUITE_KINDS = new Set(['unit', 'integration', 'contract', 'e2e', 'simulation'])
const SUITE_COUNT_FIELDS = ['discovered', 'executed', 'passed', 'failed', 'skipped', 'errors', 'durationMs']

export function assertValidEnvelope(envelope, label) {
  if (!envelope || typeof envelope !== 'object') throw new Error(`${label}: not a JSON object`)
  if (![1, 2].includes(envelope.schemaVersion)) throw new Error(`${label}: unsupported schemaVersion`)
  if (typeof envelope.component !== 'string' || !envelope.component.startsWith('openbank-')) {
    throw new Error(`${label}: invalid component`)
  }
  const run = envelope.run
  if (!run || typeof run !== 'object') throw new Error(`${label}: missing run provenance`)
  for (const field of ['id', 'commit', 'branch', 'workflow', 'observedAt']) {
    if (typeof run[field] !== 'string' || !run[field]) throw new Error(`${label}: run.${field} is required`)
  }
  if (!Number.isInteger(run.attempt) || run.attempt < 1) throw new Error(`${label}: run.attempt is invalid`)
  const observedAt = Date.parse(run.observedAt)
  if (!Number.isFinite(observedAt)) throw new Error(`${label}: run.observedAt is not a timestamp`)
  if (!Array.isArray(envelope.suites)) throw new Error(`${label}: suites must be an array`)
  for (const suite of envelope.suites) {
    if (!SUITE_KINDS.has(suite.kind)) throw new Error(`${label}: unknown suite kind ${suite.kind}`)
    for (const field of SUITE_COUNT_FIELDS) {
      if (!Number.isSafeInteger(suite[field]) || suite[field] < 0) {
        throw new Error(`${label}: suite ${suite.kind} ${field} is not a non-negative integer`)
      }
    }
  }
  return { ...run, observedAtMs: observedAt }
}

export function mergeSyntheticRunEnvelopes(envelopes, { commit }) {
  if (!/^[0-9a-f]{40}$/.test(String(commit))) {
    throw new Error(`--commit must be a full 40-hex commit, got: ${commit}`)
  }
  if (!Array.isArray(envelopes) || envelopes.length < 1) throw new Error('no envelopes to merge')

  const labeled = envelopes.map((envelope, index) => ({
    envelope,
    run: assertValidEnvelope(envelope, `envelope[${index}]`),
  }))
  const component = labeled[0].envelope.component
  for (const { envelope } of labeled) {
    if (envelope.component !== component) throw new Error(`mixed components: ${component} vs ${envelope.component}`)
  }
  // Newest last so fingerprint dedup keeps the freshest observation of a retried case.
  labeled.sort((a, b) => a.run.observedAtMs - b.run.observedAtMs)

  const suiteTotals = new Map()
  for (const { envelope } of labeled) {
    for (const suite of envelope.suites) {
      const row = suiteTotals.get(suite.kind) ?? {
        kind: suite.kind, discovered: 0, executed: 0, passed: 0, failed: 0, skipped: 0, errors: 0, durationMs: 0,
      }
      for (const field of SUITE_COUNT_FIELDS) row[field] += suite[field]
      suiteTotals.set(suite.kind, row)
    }
  }
  const suites = [...suiteTotals.values()].map(row => ({
    ...row,
    state: row.failed + row.errors > 0 ? 'failed' : row.executed === 0 ? 'skipped' : 'passed',
  })).sort((a, b) => a.kind.localeCompare(b.kind))

  const cases = new Map()
  for (const { envelope } of labeled) {
    for (const testCase of envelope.testCases ?? []) {
      cases.set(testCase.fingerprint, testCase)
    }
  }

  const diagnostics = new Map()
  for (const { envelope } of labeled) {
    for (const diagnostic of envelope.diagnostics ?? []) {
      diagnostics.set(diagnostic.name, diagnostic)
    }
  }

  const newest = labeled[labeled.length - 1]
  const declared = new Set()
  for (const { envelope } of labeled) {
    for (const resource of envelope.testInfrastructure?.declared ?? []) declared.add(resource)
  }

  return {
    schemaVersion: labeled[0].envelope.schemaVersion,
    component,
    run: {
      id: newest.run.id,
      attempt: newest.run.attempt,
      commit,
      branch: newest.run.branch,
      workflow: newest.run.workflow,
      url: newest.run.url,
      observedAt: newest.run.observedAt,
    },
    suites,
    coverage: labeled.map(({ envelope }) => envelope.coverage).find(coverage => coverage != null) ?? null,
    testInfrastructure: {
      declared: [...declared].sort(),
      observed: labeled.flatMap(({ envelope }) => envelope.testInfrastructure?.observed ?? []),
    },
    specializedEvidence: labeled.flatMap(({ envelope }) => envelope.specializedEvidence ?? []),
    testCases: [...cases.values()],
    diagnostics: [...diagnostics.values()],
    ...(labeled.map(({ envelope }) => envelope.testImpact).find(impact => impact != null)
      ? { testImpact: labeled.map(({ envelope }) => envelope.testImpact).find(impact => impact != null) } : {}),
  }
}

const main = () => {
  const args = process.argv.slice(2)
  const arg = (name, fallback) => {
    const index = args.indexOf(name)
    if (index < 0 || index + 1 >= args.length) {
      if (fallback !== undefined) return fallback
      throw new Error(`missing ${name}`)
    }
    return args[index + 1]
  }
  const commit = arg('--commit')
  const out = arg('--out')
  const operandPaths = []
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === '--commit' || args[index] === '--out') {
      index += 1
      continue
    }
    if (!args[index].startsWith('--')) operandPaths.push(args[index])
  }
  if (operandPaths.length < 1) throw new Error('no envelope files given')
  const envelopes = operandPaths.map(path => JSON.parse(fs.readFileSync(path, 'utf8')))
  const merged = mergeSyntheticRunEnvelopes(envelopes, { commit })
  fs.writeFileSync(out, `${JSON.stringify(merged)}\n`)
  process.stdout.write(`${out}: ${suitesSummary(merged.suites)} from ${envelopes.length} envelope(s)\n`)
}

const suitesSummary = suites => suites
  .map(suite => `${suite.kind}=${suite.passed}p/${suite.failed}f/${suite.errors}e`)
  .join(' ')

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    main()
  } catch (error) {
    console.error(`::error::merge failed: ${error.message}`)
    process.exit(1)
  }
}
