// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import test from 'node:test'
import { mkdtempSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
import {
  ADMIN_UI_CONTENT_MAX_BYTES,
  isAdminUiContentSnapshot,
  createAdminUiContentSnapshotReader,
} from '../src/lib/evidence/contentSnapshot.ts'
import { collectRepositoryDocumentation } from './collect-repository-documentation.mjs'
import { collectAdminUiContent, readBoundedSourceFile } from './collect-admin-ui-content.mjs'

const ROOT = resolve(import.meta.dirname, '../..')
const SOURCE_SHA = execFileSync('git', ['-C', ROOT, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()

test('bounded source reads reject symlinks, oversized files and escaped paths', () => {
  const directory = mkdtempSync(join(tmpdir(), 'admin-ui-source-read-'))
  try {
    writeFileSync(join(directory, 'valid.md'), 'reviewed source')
    writeFileSync(join(directory, 'large.md'), 'x'.repeat(256 * 1024 + 1))
    symlinkSync(join(directory, 'valid.md'), join(directory, 'link.md'))

    assert.equal(readBoundedSourceFile(directory, 'valid.md'), 'reviewed source')
    assert.throws(() => readBoundedSourceFile(directory, 'link.md'))
    assert.throws(() => readBoundedSourceFile(directory, 'large.md'), /bounded regular file/)
    assert.throws(() => readBoundedSourceFile(directory, '../outside.md'), /escaped repository/)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
test('collector publishes every current source file in a bounded, validated reviewed-source snapshot', () => {
  const bytes = collectAdminUiContent({ repoRoot: ROOT, sourceSha: SOURCE_SHA })
  const snapshot = JSON.parse(bytes.toString('utf8'))
  assert.ok(bytes.byteLength < ADMIN_UI_CONTENT_MAX_BYTES)
  assert.equal(snapshot.schemaVersion, 1)
  assert.equal(Object.hasOwn(snapshot.content, 'repositoryDocumentation'), false)
  assert.equal(snapshot.sourceSha, SOURCE_SHA)
  assert.ok(Number.isFinite(Date.parse(snapshot.collectedAt)))
  assert.ok(isAdminUiContentSnapshot(snapshot))

  const bpmnFiles = readdirSync(resolve(ROOT, 'openbank-admin-ui/src/content/bpmn'))
    .filter(name => name.endsWith('.yaml')).map(name => name.slice(0, -5)).sort()
  const libDocFiles = readdirSync(resolve(ROOT, 'openbank-libs/docs'))
    .filter(name => name.endsWith('.md')).map(name => `openbank-libs/docs/${name}`).sort()
  assert.deepEqual(snapshot.content.bpmnManifests.map(item => item.slug), bpmnFiles)
  assert.deepEqual(snapshot.content.libsDocs.map(item => item.path), libDocFiles)

  // The top-level timestamp means collection time. Reproducible generated artifacts keep their
  // own producer timestamps (currently null for these three repository-derived artifacts).
  assert.equal(snapshot.content.catalog.collectedAt, null)
  assert.equal(snapshot.content.serviceGraph.collectedAt, null)
  assert.equal(snapshot.content.governance.collectedAt, null)
  assert.match(snapshot.content.securityGraph.collectedAt, /^\d{4}-\d\d-\d\dT/)
})

test('validator rejects mismatched provenance, malformed policy, unsafe inventory paths and oversize content', () => {
  const source = JSON.parse(collectAdminUiContent({ repoRoot: ROOT, sourceSha: SOURCE_SHA }).toString('utf8'))
  const bad = mutate => {
    const copy = structuredClone(source)
    mutate(copy)
    assert.equal(isAdminUiContentSnapshot(copy), false)
  }

  bad(copy => { copy.sourceSha = 'not-a-git-sha' })
  bad(copy => { copy.collectedAt = '2099-01-01T00:00:00.000Z' })
  bad(copy => { copy.content.bcpPolicyMarkdown = '# no policy table' })
  bad(copy => { copy.content.complianceControlsYaml = 'frameworks: []\ncontrols: []\n' })
  bad(copy => { copy.content.complianceControlsYaml = copy.content.complianceControlsYaml.replace(/title: "[^"]+"/, 'title: ""') })
  bad(copy => { copy.content.complianceControlsYaml = copy.content.complianceControlsYaml.replace(/references: \[[^\n]*\]/, 'references: false') })
  bad(copy => { copy.content.bpmnManifests[0].slug = '../outside' })
  bad(copy => { copy.content.libsDocs[0].path = 'openbank-libs/docs/../../private.md' })
  bad(copy => { copy.content.libsDocs[0].markdown = 'x'.repeat(256 * 1024 + 1) })
})

test('validator rejects malformed generated rows and totals instead of accepting an empty or crashing dataset', () => {
  const source = JSON.parse(collectAdminUiContent({ repoRoot: ROOT, sourceSha: SOURCE_SHA }).toString('utf8'))
  const bad = mutate => {
    const copy = structuredClone(source)
    mutate(copy)
    assert.equal(isAdminUiContentSnapshot(copy), false)
  }

  bad(copy => { copy.content.catalog.services[0].name = '' })
  bad(copy => { copy.content.catalog.totals.modules++ })
  bad(copy => { copy.content.serviceGraph.nodes[0].dependsOn = 'many' })
  bad(copy => { copy.content.serviceGraph.edges[0].from = 'unknown-service' })
  bad(copy => { copy.content.governance.services[0].serviceName = null })
  bad(copy => { copy.content.governance.totals.withGaps++ })
  bad(copy => { copy.content.securityGraph.network.ingressRules[0].ports = 'all' })
  bad(copy => { copy.content.catalog.services = [] })
})

test('collector refuses a source SHA that does not identify the checked-out tree', () => {
  assert.throws(() => collectAdminUiContent({ repoRoot: ROOT, sourceSha: 'a'.repeat(40) }), /does not match checked-out/)
})


test('repository documentation opt-in publishes the complete real corpora within content-only bounds', async () => {
  const bytes = collectAdminUiContent({ repoRoot: ROOT, sourceSha: SOURCE_SHA, includeRepositoryDocumentation: true })
  const source = JSON.parse(bytes)
  assert.ok(bytes.byteLength > 2 * 1024 * 1024)
  assert.ok(bytes.byteLength <= ADMIN_UI_CONTENT_MAX_BYTES)
  assert.ok(isAdminUiContentSnapshot(source))
  const hash = createHash('sha256').update(bytes).digest('hex')
  const pointer = { schemaVersion: 1, snapshot: `snapshots/1-1-${hash}.json`, sha256: hash, sourceSha: SOURCE_SHA,
    runId: 1, runAttempt: 1, collectedAt: source.collectedAt, expiresAt: new Date(Date.parse(source.collectedAt) + 60_000).toISOString(), complete: true }
  const reader = createAdminUiContentSnapshotReader({ now: () => Date.parse(source.collectedAt) + 1000,
    fetchImpl: async url => String(url).endsWith('/current.json') ? new Response(JSON.stringify(pointer)) : new Response(bytes) })
  // The public factory uses its environment-owned base URL; no request is sent outside the fixture.
  const previousUrl = process.env.OPENBANK_ADMIN_UI_CONTENT_SNAPSHOT_URL
  process.env.OPENBANK_ADMIN_UI_CONTENT_SNAPSHOT_URL = 'https://evidence.example/admin-ui/content'
  try { assert.equal((await reader.read()).status, 'ready') } finally {
    if (previousUrl === undefined) delete process.env.OPENBANK_ADMIN_UI_CONTENT_SNAPSHOT_URL
    else process.env.OPENBANK_ADMIN_UI_CONTENT_SNAPSHOT_URL = previousUrl
  }
  const documentation = source.content.repositoryDocumentation
  for (const [key, directory] of [['adrs', 'adr'], ['threatModels', 'threat-models'], ['agentCharters', 'agents']]) {
    const files = readdirSync(resolve(ROOT, `docs/${directory}`)).filter(file => file.endsWith('.md') && (directory !== 'adr' || /^\d{4}[-.]/.test(file)) && (directory !== 'agents' || file !== 'README.md')).sort()
    assert.deepEqual(documentation[key].map(row => row.path).sort(), files.map(file => `docs/${directory}/${file}`))
    for (const row of documentation[key]) assert.equal(row.markdown, readBoundedSourceFile(ROOT, row.path))
  }
  assert.ok(documentation.events.available)
  assert.ok(documentation.events.topics.length > 0)
  assert.ok(documentation.featureFlags.length > 0)
  for (const flag of documentation.featureFlags) {
    const owner = source.content.catalog.services.find(service => service.name.replace(/^openbank-/, '') === flag.service.replace(/^openbank-/, ''))
    assert.ok(owner)
    assert.equal(flag.classification, owner.moneyPath ? 'money-path' : 'feature')
  }
  const bad = mutate => { const copy = structuredClone(source); mutate(copy.content.repositoryDocumentation); assert.equal(isAdminUiContentSnapshot(copy), false) }
  for (const path of ['docs/agents/escape.md', '/private/file.md', '../file.md', 'docs/adr/a' + String.fromCharCode(0) + '.md', 'docs/adr/a' + String.fromCharCode(92) + '.md']) {
    bad(value => { value.adrs[0].path = path })
  }
  bad(value => { value.adrs.push(value.adrs[0]) })
  bad(value => { value.adrs[0].markdown = 'x'.repeat(256 * 1024 + 1) })
  bad(value => { value.featureFlags[0].classification = value.featureFlags[0].classification === 'feature' ? 'money-path' : 'feature' })
  bad(value => { value.featureFlags[0].defaultVariant = 'missing-variant' })
  bad(value => { value.featureFlags[0].state = 'UNKNOWN' })
  bad(value => { value.featureFlags[0].service = 'invented-service' })
  bad(value => { value.featureFlags[0].variants.invalid = Number.POSITIVE_INFINITY })
  bad(value => { value.events.topics.push(value.events.topics[0]) })
  bad(value => { value.events.collectedAt = 'not-a-date' })
  bad(value => { value.events.available = false; value.events.topics = [] })
  bad(value => { value.operationallyEnforced = true })
  const sharedTopic = structuredClone(source)
  sharedTopic.content.repositoryDocumentation.events.topics.push({ ...documentation.events.topics[0], channel: 'second-channel-same-topic' })
  assert.ok(isAdminUiContentSnapshot(sharedTopic))
})


test('documentation collection stops at its aggregate source budget before reading the whole corpus', () => {
  let calls = 0
  const catalog = { services: [{ name: 'openbank-party-service', moneyPath: false }] }
  assert.throws(() => collectRepositoryDocumentation({ root: ROOT, catalog, events: {}, readSource: (repo, path) => {
    calls++
    return path.endsWith('feature-flags.yaml') ? readBoundedSourceFile(repo, path) : 'x'.repeat(256 * 1024)
  } }), /sources exceed size limit/)
  assert.ok(calls < 40)
})
