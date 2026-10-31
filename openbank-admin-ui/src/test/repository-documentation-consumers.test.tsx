// SPDX-License-Identifier: Apache-2.0
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import ThreatModelRegistryPage from '@/app/docs/threat-models/page'
import type { RepositoryDocumentation } from '@/lib/evidence/repositoryDocumentation'

const { readSnapshot } = vi.hoisted(() => ({ readSnapshot: vi.fn() }))
vi.mock('next/headers', () => ({ cookies: async () => ({ get: () => ({ value: 'en' }) }) }))
vi.mock('@/lib/evidence/contentSnapshot', () => ({ createAdminUiContentSnapshotReader: () => ({ read: readSnapshot }) }))
import { loadAdr, loadAdrRegistry, loadThreatModelRegistry, loadThreatModel, loadAgentCharterDoc } from '@/lib/governance/docs'
import { loadFlagCatalogContent } from '@/lib/governance/flags'
import { GET as getEvents } from '@/app/api/events/route'

const provenance = { sourceSha: 'a'.repeat(40), collectedAt: '2026-10-22T10:00:00.000Z', runId: 123, runAttempt: 1 }
const corpus = (): RepositoryDocumentation => ({
  schema: 'openbank.repository-documentation/v1',
  adrs: [{ path: 'docs/adr/0001-one.md', markdown: '# One\n\nStatus: Accepted\nDate: 2026-01-01\nAuthors: A\n\n## Decision\nKeep source.' }],
  threatModels: [{ path: 'docs/threat-models/openbank-new-service.md', markdown: '# New service\nRaw **source**.' }],
  agentCharters: [{ path: 'docs/agents/new-agent.md', markdown: '---\nid: new-agent\nplane: assurance\nadr: ADR-0001\n---\n# New agent\nOriginal body.' }],
  featureFlags: [{ service: 'new-service', key: 'new-flag', state: 'ENABLED', defaultVariant: 'on', variants: { on: true }, targeted: false, classification: 'money-path' }],
  events: { schema: 'openbank.events/v1', generator: 'generate-events.mjs', source: 'reviewed source', collectedAt: null, available: true,
    topics: [{ channel: 'new-channel', topic: 'new.topic', description: 'A new event', publishers: ['new-service'], consumers: [], color: '#abcdef' }], unmatched: [] },
})
function publish(value = corpus(), status = 'ready') {
  readSnapshot.mockResolvedValue({ status, snapshot: { content: { repositoryDocumentation: value,
    catalog: { services: [{ name: 'openbank-new-service', moneyPath: true }, { name: 'openbank-missing-service', moneyPath: true }] } } }, provenance })
}
let fixture: string | null = null
beforeEach(() => { readSnapshot.mockReset(); publish() })
afterEach(() => {
  cleanup()
  vi.unstubAllEnvs()
  if (fixture) { rmSync(fixture, { recursive: true, force: true }); fixture = null }
})

it('serves new ADR, threat, charter, flag and event declarations without an image rebuild', async () => {
  expect((await loadAdrRegistry()).items.map(item => item.slug)).toEqual(['0001-one'])
  const threats = await loadThreatModelRegistry()
  expect(threats.items[0]).toMatchObject({ service: 'new-service', moneyPath: true })
  expect(threats.missing).toEqual(['missing-service'])
  expect((await loadThreatModel('new-service'))?.markdown).toBe('# New service\nRaw **source**.')
  expect((await loadAgentCharterDoc('new-agent'))?.body).toBe('Original body.')
  expect((await loadFlagCatalogContent()).items[0]).toMatchObject({ key: 'new-flag', classification: 'money-path' })
  const events = await (await getEvents()).json()
  expect(events.topics[0].topic).toBe('new.topic')
  expect(events.contentSource).toMatchObject({ source: 'published', status: 'ready', sourceSha: provenance.sourceSha })
})

it('retains stale provenance and recovers to the next published revision', async () => {
  publish(corpus(), 'stale')
  expect((await loadAdrRegistry()).contentSource).toMatchObject({ source: 'published', status: 'stale', sourceCollectedAt: provenance.collectedAt })
  const next = corpus(); next.adrs.push({ path: 'docs/adr/0002-two.md', markdown: '# Two\nStatus: Proposed\n' })
  publish(next)
  expect((await loadAdrRegistry()).items.map(item => item.slug)).toEqual(['0001-one', '0002-two'])
  expect((await loadAdrRegistry()).contentSource.status).toBe('ready')
})

it('reads canonical ADR YAML metadata and excludes corpus maintenance files from the decision registry', async () => {
  const value = corpus()
  const repository = path.resolve(__dirname, '../../..')
  value.adrs = ['0002-hexagonal-architecture-per-service', '0339-tax-residency-information-exchange-and-product-taxation-are-party-and-event-attributes-not-product-properties']
    .map(slug => ({ path: `docs/adr/${slug}.md`, markdown: readFileSync(path.join(repository, 'docs/adr', `${slug}.md`), 'utf8') }))
  value.adrs.push({ path: 'docs/adr/CURRENT.md', markdown: '# Current decisions' })
  publish(value)
  const items = (await loadAdrRegistry()).items
  expect(items).toHaveLength(2)
  expect(items[0]).toMatchObject({ number: 2, status: 'Accepted', date: '2026-05-26', authors: expect.any(String) })
  expect(items[1]).toMatchObject({ number: 339, status: 'Proposed', date: '2026-10-14', authors: expect.any(String) })
  const document = await loadAdr(items[0].slug)
  expect(document?.body).toMatch(/^## Context/)
  expect(document?.body).not.toContain('decision-status:')
  expect(await loadAdr('CURRENT')).toBeNull()
  expect(await loadAdr('0001-one')).toBeNull()
})

it('preserves legacy metadata and authored body lines, rejects traversal and leaves malformed YAML unknown', async () => {
  const legacy = await loadAdr('0001-one')
  expect(legacy).toMatchObject({ status: 'Accepted', date: '2026-01-01', authors: 'A', body: '## Decision\nKeep source.' })
  const value = corpus()
  value.adrs[0].markdown = '---\ndecision-status: [broken\n---\n# One\n\n## Context\nStatus: Accepted\n'
  publish(value)
  const malformed = await loadAdr('0001-one')
  expect(malformed?.status).toBe('Unknown')
  expect(malformed?.body).toContain('Status: Accepted')
  expect(await loadAdr('../secret')).toBeNull()
  expect(await loadThreatModel('../secret')).toBeNull()
  expect(await loadAgentCharterDoc('../secret')).toBeNull()
})

it('bounds YAML alias expansion without promoting an unreadable ADR status', async () => {
  const value = corpus()
  value.adrs[0].markdown = '---\ndecision-status: accepted\na: &a [x, x, x, x, x, x]\nb: &b [*a, *a, *a, *a, *a, *a]\nauthors: [*b, *b, *b, *b, *b, *b]\n---\n# One\n\n## Context\nBody remains readable.'
  publish(value)
  const document = await loadAdr('0001-one')
  expect(document?.status).toBe('Unknown')
  expect(document?.body).toBe('## Context\nBody remains readable.')
})

it('labels legacy eight-field content fallbacks as image-baked stale', async () => {
  fixture = mkdtempSync(path.join(tmpdir(), 'repository-doc-fallback-'))
  mkdirSync(path.join(fixture, 'adr'))
  writeFileSync(path.join(fixture, 'adr/0001-fallback.md'), '# Fallback\nStatus: Accepted\n')
  vi.stubEnv('OPENBANK_GOVERNANCE_DOCS', fixture)
  readSnapshot.mockResolvedValue({ status: 'ready', snapshot: { content: { catalog: { services: [] } } }, provenance })
  const loaded = await loadAdrRegistry()
  expect(loaded.items[0].title).toBe('Fallback')
  expect(loaded.contentSource).toMatchObject({ source: 'image-baked', status: 'stale', sourceCollectedAt: null })
})

it.each(['missing', 'invalid-row', 'partial-roster', 'duplicate-identity', 'valid-feature', 'valid-money'])('preserves classification truth for %s fallback catalogs', async mode => {
  fixture = mkdtempSync(path.join(tmpdir(), 'repository-threat-fallback-'))
  mkdirSync(path.join(fixture, 'threat-models'))
  writeFileSync(path.join(fixture, 'threat-models/openbank-new-service.md'), '# Retained threat model')
  vi.stubEnv('OPENBANK_GOVERNANCE_DOCS', fixture)
  const catalogFile = path.join(fixture, 'catalog.json')
  if (mode !== 'missing') writeFileSync(catalogFile, JSON.stringify({ services: mode === 'invalid-row' ? [{ name: 'openbank-new-service' }]
    : mode === 'partial-roster' ? [{ name: 'openbank-new-service', moneyPath: false }, { name: 'openbank-other-service' }]
    : mode === 'duplicate-identity' ? [{ name: 'openbank-new-service', moneyPath: false }, { name: 'new-service', moneyPath: true }]
    : [{ name: 'openbank-new-service', moneyPath: mode === 'valid-money' }] }))
  vi.stubEnv('OPENBANK_CATALOG', catalogFile)
  const flagsDirectory = path.join(fixture, 'flags')
  mkdirSync(flagsDirectory)
  writeFileSync(path.join(flagsDirectory, 'new-service.yaml'), JSON.stringify({ kind: 'ConfigMap', metadata: { labels: { 'app.kubernetes.io/name': 'new-service' } },
    data: { 'flags.json': JSON.stringify({ flags: { example: { state: 'ENABLED', defaultVariant: 'on', variants: { on: true } } } }) } }))
  vi.stubEnv('OPENBANK_FLAGS_BUNDLE', flagsDirectory)
  readSnapshot.mockResolvedValue({ status: 'unavailable', snapshot: null, provenance: null })
  const registry = await loadThreatModelRegistry()
  const known = mode === 'valid-feature' || mode === 'valid-money'
  expect(registry.classificationAvailable).toBe(known)
  expect(registry.items).toHaveLength(1)
  expect((await loadFlagCatalogContent()).items[0].classification).toBe(known ? mode === 'valid-money' ? 'money-path' : 'feature' : 'unknown')
  render(<LanguageProvider>{await ThreatModelRegistryPage()}</LanguageProvider>)
  if (known) expect(screen.getByText(mode === 'valid-money' ? /Money-path coverage: 1\/1/ : /Money-path coverage: 0\/0/)).toBeVisible()
  else {
    expect(screen.getByText(/Money-path coverage is unknown: the classification catalog is unavailable/)).toBeVisible()
    expect(screen.queryByText(/coverage: 0\/0/)).not.toBeInTheDocument()
  }
})
