// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
import { afterEach, describe, expect, it } from 'vitest'
import {
  DEPLOY_TAG_SHA_LENGTH, componentPostureFromRun, evidenceStoreKey,
} from '@/lib/test-intelligence-run-projection.mjs'
import type { ComponentTestPosture } from '@/lib/types/test-intelligence'

const SCRIPT = path.resolve(__dirname, '../../scripts/publish-test-evidence.mjs')
const AUTO_DEPLOY = path.resolve(__dirname, '../../../.github/workflows/auto-deploy.yml')
const SHA = '0123456789abcdef0123456789abcdef01234567'
const OTHER = 'fedcba9876543210fedcba9876543210fedcba98'
const dirs: string[] = []
afterEach(() => dirs.splice(0).forEach(dir => rmSync(dir, { recursive: true, force: true })))

const envelope = (commit = SHA, component = 'openbank-alpha-service') => ({
  schemaVersion: 2,
  run: { id: '42', attempt: 1, commit, branch: 'main', workflow: 'Services CI', url: 'https://github.com/JiRaska/open-bank-oss/actions/runs/42', observedAt: new Date().toISOString() },
  component,
  suites: [{ kind: 'unit', state: 'passed', discovered: 3, executed: 3, passed: 3, failed: 0, skipped: 0, errors: 0, durationMs: 900 }],
  coverage: { lines: { covered: 8, missed: 2, percentage: 80 }, branches: { covered: 3, missed: 1, percentage: 75 } },
  testInfrastructure: { declared: ['postgres'], observed: [] },
})
const opts = (commit = SHA) => ({
  component: 'openbank-alpha-service', commit, released: true, moneyPath: false,
  collectedAt: new Date(), staleAfterMs: 14 * 86_400_000,
})

describe('commit-addressed evidence store key (#13201)', () => {
  it('uses exactly the commit prefix auto-deploy writes into the image tag', () => {
    // The reader derives the commit from `sandbox-<prefix>`; a key of any other length is a
    // permanent 404 that reads as "not yet published".
    const workflow = readFileSync(AUTO_DEPLOY, 'utf8')
    const match = workflow.match(/SHA="\$\{GITHUB_SHA::(\d+)\}"\s*\n\s*TAG="sandbox-\$\{SHA\}"/)
    expect(match, 'auto-deploy.yml no longer derives the tag as sandbox-${GITHUB_SHA::N}').not.toBeNull()
    expect(DEPLOY_TAG_SHA_LENGTH).toBe(Number(match![1]))
    expect(evidenceStoreKey('openbank-alpha-service', SHA)).toBe(`openbank-alpha-service/${SHA.slice(0, Number(match![1]))}/test-intelligence.json`)
  })

  it('refuses a short commit or an unsafe component', () => {
    expect(() => evidenceStoreKey('openbank-alpha-service', SHA.slice(0, 8))).toThrow(/full 40-hex/)
    expect(() => evidenceStoreKey('openbank-alpha-service', SHA.toUpperCase())).toThrow(/full 40-hex/)
    expect(() => evidenceStoreKey('../openbank-alpha-service', SHA)).toThrow(/invalid component/)
    expect(() => evidenceStoreKey('openbank-alpha-service/x', SHA)).toThrow(/invalid component/)
  })
})

describe('evidence posture projection (#13201)', () => {
  it('projects a run envelope to the ComponentTestPosture the reader accepts', () => {
    const { posture } = componentPostureFromRun(envelope(), opts()) as { posture: ComponentTestPosture }
    expect(posture).toMatchObject({
      component: 'openbank-alpha-service', released: true, moneyPath: false,
      coverage: { lines: { percentage: 80 } }, testInfrastructure: { declared: ['postgres'] },
    })
    expect(posture.evidence).toHaveLength(1)
    expect(posture.evidence[0]).toMatchObject({ kind: 'unit', state: 'passed', run: { commit: SHA } })
    // the reader's acceptance rule: every observation naming a run names the keyed commit
    const keyed = evidenceStoreKey('openbank-alpha-service', SHA).split('/')[1]
    expect(posture.evidence.every(item => !item.run || item.run.commit.startsWith(keyed))).toBe(true)
  })

  it('refuses an envelope from another commit', () => {
    expect(() => componentPostureFromRun(envelope(OTHER), opts())).toThrow(/envelope is for commit/)
  })

  it('refuses an envelope for another component', () => {
    expect(() => componentPostureFromRun(envelope(SHA, 'openbank-beta-service'), opts())).toThrow(/not a v1\/v2 envelope/)
  })

  it('refuses an unknown schema version', () => {
    expect(() => componentPostureFromRun({ ...envelope(), schemaVersion: 9 }, opts())).toThrow(/not a v1\/v2 envelope/)
  })

  it('declares which commit the tests ran on, and keys a tree-equivalent posture by the image commit', () => {
    const { posture } = componentPostureFromRun(envelope(), opts())
    expect(posture.evidenceSource).toEqual({ commit: SHA, equivalence: 'identical' })
    const equivalent = componentPostureFromRun(envelope(), { ...opts(), keyCommit: OTHER, equivalence: 'tree-equivalent' })
    expect(equivalent.posture.evidenceSource).toEqual({ commit: SHA, equivalence: 'tree-equivalent' })
    expect(equivalent.posture.evidence.every(item => !item.run || item.run.commit === SHA)).toBe(true)
  })

  it('refuses an "identical" claim keyed by another commit, and unknown equivalence kinds', () => {
    expect(() => componentPostureFromRun(envelope(), { ...opts(), keyCommit: OTHER })).toThrow(/identical evidence must be keyed/)
    expect(() => componentPostureFromRun(envelope(), { ...opts(), keyCommit: OTHER, equivalence: 'probably' })).toThrow(/unknown equivalence/)
    expect(() => componentPostureFromRun(envelope(), { ...opts(), keyCommit: 'abc', equivalence: 'tree-equivalent' })).toThrow(/full 40-hex/)
  })

  it('keeps missing coverage explicit instead of inventing a zero', () => {
    const { posture } = componentPostureFromRun({ ...envelope(), coverage: null }, opts())
    expect(posture.coverage).toMatchObject({ state: 'not-run', lines: { percentage: null } })
  })

  it('the CLI writes the posture and prints the key, and writes nothing on refusal', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'evidence-publisher-'))
    dirs.push(dir)
    const run = path.join(dir, 'run.json')
    const out = path.join(dir, 'posture.json')
    writeFileSync(run, JSON.stringify(envelope()))
    const base = ['--run', run, '--component', 'openbank-alpha-service', '--released', 'true', '--money-path', 'true', '--out', out]
    const key = execFileSync('node', [SCRIPT, ...base, '--commit', SHA], { encoding: 'utf8' }).trim()
    expect(key).toBe(`openbank-alpha-service/${SHA.slice(0, 8)}/test-intelligence.json`)
    expect(JSON.parse(readFileSync(out, 'utf8'))).toMatchObject({ component: 'openbank-alpha-service', moneyPath: true })

    const refused = path.join(dir, 'refused.json')
    expect(() => execFileSync('node', [SCRIPT, ...base.slice(0, -1), refused, '--commit', OTHER], { stdio: 'pipe' })).toThrow()
    expect(() => readFileSync(refused)).toThrow()
  })
})
