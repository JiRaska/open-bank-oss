// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { DATASETS, parseArgs, publishDataset } from './publish-admin-ui-evidence.mjs'
import { collectAdminUiContent } from '../../openbank-admin-ui/scripts/collect-admin-ui-content.mjs'

const sha = 'b'.repeat(40)
const collectedAt = '2026-10-19T10:00:00.000Z'
const now = () => Date.parse('2026-10-19T10:05:00.000Z')
const env = { GITHUB_SHA: 'a'.repeat(40), EVIDENCE_SOURCE_SHA: sha, GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '1' }
const dimensions = Array.from({ length: 9 }, (_, index) => ({ code: `C${index + 1}`, name: `Dimension ${index + 1}` }))
const readiness = {
  schema_version: 1, generated_for: '2026-10-19', collected_at: collectedAt, source_sha: sha,
  scope: { kind: 'service-module-matrix-v1', service_count: 1, money_path_count: 0,
    roster_sha256: createHash('sha256').update('example:false').digest('hex') },
  dimensions,
  services: [{ service: 'example', money_path: false, gate: 'GO',
    scores: Object.fromEntries(dimensions.map(({ code }) => [code, 2])),
    evidence: Object.fromEntries(dimensions.map(({ code }) => [code, 'source evidence'])) }],
}
const security = { generatedAt: '2026-10-16T10:00:00.000Z', ...Object.fromEntries(
  ['netpol', 'freshness', 'credentials', 'fuzz', 'threatModels', 'mttr'].map(key => [key, { available: false, reason: 'unavailable' }]),
) }

function harness(body, publishEnv = env) {
  const directory = mkdtempSync(join(tmpdir(), 'admin-evidence-dataset-test-'))
  const file = join(directory, 'input.json')
  writeFileSync(file, JSON.stringify(body))
  const puts = []
  const execFileSync = (_command, args) => {
    assert.equal(args[0], 's3api')
    if (args[1] === 'list-objects-v2') return 'null'
    assert.equal(args[1], 'put-object')
    const value = flag => args[args.indexOf(flag) + 1]
    puts.push({ key: value('--key'), conditional: value('--if-none-match'), body: JSON.parse(readFileSync(value('--body'), 'utf8')) })
    return '{}'
  }
  return { puts, publish: (dataset, overrides = {}) => publishDataset({ dataset, file, bucket: 'fixture-evidence', env: publishEnv, now, execFileSync, ...overrides }), cleanup: () => rmSync(directory, { recursive: true, force: true }) }
}

test('closed dataset registry rejects arbitrary path and duplicate CLI dataset', () => {
  assert.deepEqual(Object.keys(DATASETS).sort(), ['content', 'gate-health', 'readiness', 'security-kpis', 'test-intelligence'])
  assert.throws(() => parseArgs(['--dataset', '../other']), /supported/)
  assert.throws(() => parseArgs(['--dataset', 'readiness', '--dataset', 'security-kpis']), /duplicate/)
})

test('content dataset is bound to its reviewed-source collection time and SHA fields', () => {
  assert.equal(DATASETS.content.collectedAt({ collectedAt }), collectedAt)
  assert.equal(DATASETS.content.snapshotSourceSha({ sourceSha: sha }), sha)
})

test('content publication uses only the admin-ui/content prefix and preserves source provenance', () => {
  const sourceSha = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()
  const snapshot = JSON.parse(collectAdminUiContent({ sourceSha }).toString('utf8'))
  const h = harness(snapshot, { ...env, EVIDENCE_SOURCE_SHA: sourceSha })
  try {
    h.publish('content', { now: () => Date.now() + 1_000 })
    assert.equal(h.puts.length, 2)
    assert.match(h.puts[0].key, /^admin-ui\/content\/snapshots\/123-1-[a-f0-9]{64}\.json$/)
    assert.equal(h.puts[1].key, 'admin-ui/content/current.json')
    assert.equal(h.puts[1].body.collectedAt, snapshot.collectedAt)
    assert.equal(h.puts[1].body.sourceSha, sourceSha)
    assert.ok(h.puts.every(put => put.conditional === '*'))
  } finally { h.cleanup() }
})

for (const [dataset, body, timestamp] of [['readiness', readiness, collectedAt], ['security-kpis', security, security.generatedAt]]) {
  test(`${dataset} preserves producer time and checked-out source SHA with a fixed object prefix`, () => {
    const h = harness(body)
    try {
      h.publish(dataset, { prefix: 'other/prefix' })
      assert.equal(h.puts.length, 2)
      assert.match(h.puts[0].key, new RegExp(`^admin-ui/${dataset}/snapshots/123-1-[a-f0-9]{64}\\.json$`))
      assert.equal(h.puts[1].key, `admin-ui/${dataset}/current.json`)
      assert.equal(h.puts[1].body.collectedAt, timestamp)
      assert.equal(h.puts[1].body.sourceSha, sha)
      assert.ok(h.puts.every(put => put.conditional === '*'))
    } finally { h.cleanup() }
  })
}

test('readiness source mismatch fails before any write', () => {
  const h = harness({ ...readiness, source_sha: 'c'.repeat(40) })
  try {
    assert.throws(() => h.publish('readiness'), /source SHA does not match/)
    assert.equal(h.puts.length, 0)
  } finally { h.cleanup() }
})

test('malformed data cannot publish into any dataset', () => {
  const h = harness({})
  try {
    for (const dataset of Object.keys(DATASETS)) assert.throws(() => h.publish(dataset), /schema validation/)
    assert.equal(h.puts.length, 0)
  } finally { h.cleanup() }
})
