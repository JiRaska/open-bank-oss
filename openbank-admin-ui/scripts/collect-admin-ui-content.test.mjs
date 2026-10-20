// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtempSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { execFileSync } from 'node:child_process'
import {
  ADMIN_UI_CONTENT_MAX_BYTES,
  isAdminUiContentSnapshot,
} from '../src/lib/evidence/contentSnapshot.ts'
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
test('collector can publish topology in a bounded, validated reviewed-source snapshot', () => {
  const bytes = collectAdminUiContent({ repoRoot: ROOT, sourceSha: SOURCE_SHA, includeClusterTopology: true })
  const snapshot = JSON.parse(bytes.toString('utf8'))
  assert.ok(bytes.byteLength < ADMIN_UI_CONTENT_MAX_BYTES)
  assert.equal(snapshot.schemaVersion, 1)
  assert.equal(snapshot.sourceSha, SOURCE_SHA)
  assert.ok(Number.isFinite(Date.parse(snapshot.collectedAt)))
  assert.ok(isAdminUiContentSnapshot(snapshot))
  assert.equal(snapshot.content.clusterTopology.schema, 'openbank.cluster-topology/v1')
  assert.equal(snapshot.content.clusterTopology.counts.namespaces, snapshot.content.clusterTopology.namespaces.length)

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

test('schema v1 accepts older snapshots without cluster topology and rejects malformed topology when present', () => {
  const source = JSON.parse(collectAdminUiContent({ repoRoot: ROOT, sourceSha: SOURCE_SHA, includeClusterTopology: true }).toString('utf8'))
  const oldSnapshot = structuredClone(source)
  delete oldSnapshot.content.clusterTopology
  assert.equal(isAdminUiContentSnapshot(oldSnapshot), true)

  const malformed = structuredClone(source)
  malformed.content.clusterTopology.counts.namespaces++
  assert.equal(isAdminUiContentSnapshot(malformed), false)

  const malformedStatus = structuredClone(source)
  malformedStatus.content.clusterTopology.securityLayers[0].status = ['live']
  assert.equal(isAdminUiContentSnapshot(malformedStatus), false)
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
  const source = JSON.parse(collectAdminUiContent({ repoRoot: ROOT, sourceSha: SOURCE_SHA, includeClusterTopology: true }).toString('utf8'))
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
  bad(copy => { copy.content.clusterTopology.securityLayers[0].adr = '0030' })
  bad(copy => { copy.content.clusterTopology.securityLayers[0].detailRoute = { href: '/internal' } })
  bad(copy => { copy.content.clusterTopology.securityLayers[0].detailRoute = '//example.test' })
  bad(copy => { copy.content.clusterTopology.securityLayers[0].summary = { text: 'valid-looking' } })
  bad(copy => { copy.content.clusterTopology.imageAnatomy.steps[0].adr = { ref: '0030' } })
  bad(copy => { copy.content.clusterTopology.imageAnatomy.steps[0].detail = { text: 'valid-looking' } })
  bad(copy => { copy.content.clusterTopology.groups[0].color = { value: '#fff' } })
  bad(copy => { copy.content.clusterTopology.namespaces[0].role = { text: 'valid-looking' } })
})

test('collector refuses a source SHA that does not identify the checked-out tree', () => {
  assert.throws(() => collectAdminUiContent({ repoRoot: ROOT, sourceSha: 'a'.repeat(40) }), /does not match checked-out/)
})
