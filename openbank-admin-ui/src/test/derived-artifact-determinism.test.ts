// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { execFileSync } from 'child_process'
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
import { fileURLToPath } from 'url'

// @ts-expect-error - plain .mjs build script, no type declarations by design
import { sourceDate } from '../../scripts/lib/source-date.mjs'

// Regression guard for issue #2621.
//
// Three artifacts are GENERATED and COMMITTED (cluster-topology.json, infra-lifecycle.json,
// infra-vulns.json). Each stamped `new Date().toISOString()`, so regeneration rewrote that
// line every time — which makes any two concurrent PRs conflict BY CONSTRUCTION, with no
// content disagreement whatsoever. #2247 was rebased three times over it and #2618 went
// DIRTY on the timestamp hunk, not on the image tag everyone assumed.
//
// The invariant these tests lock in: a committed derived artifact is a pure function of its
// inputs. Note the shape of the first test — it regenerates twice with REAL time passing in
// between. Two runs inside the same millisecond agree even under the old code, so an
// idempotency check without a measurable gap proves nothing; the sleep IS the test.

const HERE = path.dirname(fileURLToPath(import.meta.url))
const ADMIN_UI = path.resolve(HERE, '..', '..')
const REPO = path.resolve(ADMIN_UI, '..')

// Generators that write a COMMITTED artifact. `generate-security-graph.mjs` also stamps a
// clock time but its output is not in git, so it cannot conflict anything.
const COMMITTED_GENERATORS = [
  'generate-cluster-topology.mjs',
  'generate-infra-lifecycle.mjs',
  'scan-infra-vulns.mjs',
]

describe('committed derived artifacts are a pure function of their inputs (#2621)', () => {
  it(
    'regenerating cluster-topology.json after real time passes yields byte-identical output',
    () => {
      const dir = mkdtempSync(path.join(tmpdir(), 'derived-determinism-'))
      try {
        const gen = (out: string) =>
          execFileSync('node', [
            path.join(ADMIN_UI, 'scripts', 'generate-cluster-topology.mjs'),
            '--repo', REPO,
            '--out', out,
          ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })

        const a = path.join(dir, 'a.json')
        const b = path.join(dir, 'b.json')
        gen(a)
        // Block for longer than the ISO-8601 millisecond resolution the old code stamped.
        // Sleeping synchronously keeps this honest: the second run genuinely observes a
        // different wall clock, which is the only condition under which the bug is visible.
        const until = Date.now() + 1_200
        while (Date.now() < until) { /* busy-wait: no async escape hatch to mis-order the runs */ }

        gen(b)
        const first = readFileSync(a, 'utf8')
        const second = readFileSync(b, 'utf8')
        expect(second).toBe(first)

        // ...and the stamp is present, not silently dropped to dodge the diff. `generatedAt`
        // is part of the served contract (the topology route's unavailable-fallback carries
        // it) and the staleness of a derived view is an operator signal, so the fix had to
        // keep the field and change where it comes from.
        const parsed = JSON.parse(first) as { generatedAt: string | null }
        expect(parsed).toHaveProperty('generatedAt')
        expect(parsed.generatedAt).not.toBeNull()
      } finally {
        rmSync(dir, { recursive: true, force: true })
      }
    },
    60_000,
  )

  it('stamps the newest input commit, not the moment the generator ran', () => {
    const expected = execFileSync(
      'git',
      ['-C', REPO, 'log', '-1', '--format=%cI', '--', 'openbank-infra/gitops'],
      { encoding: 'utf8' },
    ).trim()
    expect(expected).not.toBe('')
    expect(sourceDate(REPO, ['openbank-infra/gitops'])).toBe(new Date(expected).toISOString())
  })

  it('honours SOURCE_DATE_EPOCH and reports an unknowable date as null, never as "now"', () => {
    const prev = process.env.SOURCE_DATE_EPOCH
    try {
      process.env.SOURCE_DATE_EPOCH = '1700000000'
      expect(sourceDate(REPO, ['openbank-infra/gitops'])).toBe('2023-11-14T22:13:20.000Z')
    } finally {
      if (prev === undefined) delete process.env.SOURCE_DATE_EPOCH
      else process.env.SOURCE_DATE_EPOCH = prev
    }
    // A pathspec that matches nothing tracked must yield null — the value every consumer
    // already renders as "unknown". Falling back to the clock here would quietly restore
    // the non-determinism for exactly the builds that have no git context.
    expect(sourceDate(REPO, ['no/such/path/ever'])).toBeNull()
  })

  it('no generator of a committed artifact reads the wall clock', () => {
    for (const g of COMMITTED_GENERATORS) {
      const src = readFileSync(path.join(ADMIN_UI, 'scripts', g), 'utf8')
      // Strip line comments so the prose above each call site (which necessarily names the
      // banned construct) cannot flag itself — the code-about-code precedence, decided here
      // rather than discovered on the first red run.
      const code = src.replace(/^\s*\/\/.*$/gm, '')
      expect(code, `${g} must derive its timestamp from its inputs, not from new Date()`)
        .not.toMatch(/new Date\(\)/)
      expect(code, `${g} must not call Date.now()`).not.toMatch(/Date\.now\(\)/)
    }
  })

  it('the generator list above still matches the committed artifacts on disk', () => {
    // Guards the blind spot in the check itself: a fourth committed artifact added later
    // would be unchecked, and a hand-kept scope reads as passing when it is short.
    const committed = execFileSync(
      'git',
      // `:(glob)` keeps `*` from crossing a directory separator — a plain `*.json`
      // pathspec is recursive in git and would sweep in every src/ data file.
      ['-C', REPO, 'ls-files', '--', ':(glob)openbank-admin-ui/*.json'],
      { encoding: 'utf8' },
    )
      .split('\n')
      .filter(Boolean)
      .map((p) => path.basename(p))
      .sort()
    expect(committed).toEqual([
      // Generated by .github/scripts/gen-ai-governance-snapshot.py, not an admin-ui scripts/*.mjs,
      // so it is deliberately absent from COMMITTED_GENERATORS above — its determinism is enforced
      // by the `ai-governance-snapshot-drift` gate (`--check`), not by re-running it here.
      'ai-governance-snapshot.json',
      'app-status.json', // generator deliberately omits any timestamp
      'cluster-topology.json',
      'infra-lifecycle.json',
      'infra-vulns.json',
      'package-lock.json',
      'package.json',
      // Weekly CI snapshot from .github/scripts/security-kpis.py (ADR-0279 #15/#17/#18):
      // deliberately absent from COMMITTED_GENERATORS — its content carries a timestamp by
      // design (it IS a snapshot), and its provenance is the publisher workflow + refresh PR,
      // not re-run determinism here.
      'security-kpis.json',
      'tsconfig.json',
      'tsconfig.test.json',
    ])
    // and every script referenced above actually exists
    const present = readdirSync(path.join(ADMIN_UI, 'scripts'))
    for (const g of COMMITTED_GENERATORS) expect(present).toContain(g)
  })
})
