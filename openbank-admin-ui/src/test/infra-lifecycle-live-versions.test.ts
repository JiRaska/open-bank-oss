// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

const liveSnapshot = vi.hoisted(() => {
  const observedAt = new Date().toISOString()
  return {
    available: true,
    fetchedAt: observedAt,
    components: {
      valkey: [{ version: '8.1.10', count: 1, observedAt }],
      kafka: [{ version: '4.2.0', count: 1, observedAt }],
      clickhouse: [{ version: '26.8', count: 1, observedAt }],
      'schema-registry': [{ version: '2.6.2', count: 2, observedAt }],
    },
  }
})

vi.mock('@/lib/discovery', () => ({ inCluster: () => true }))
vi.mock('@/lib/live-platform-versions', () => ({
  IMAGE_SAMPLE_MAX_AGE_MS: 5 * 60_000,
  getLiveSnapshot: vi.fn(async () => liveSnapshot),
}))

afterEach(() => {
  liveSnapshot.components.valkey = [
    { version: '8.1.10', count: 1, observedAt: liveSnapshot.fetchedAt },
  ]
  liveSnapshot.components.clickhouse = [
    { version: '26.8', count: 1, observedAt: liveSnapshot.fetchedAt },
  ]
  for (const id of ['postgres', 'keycloak', 'openbao']) {
    delete (liveSnapshot.components as Record<string, unknown>)[id]
  }
  vi.unstubAllGlobals()
})

describe('/api/infra/lifecycle runtime version evidence', () => {
  it('reports Prometheus-observed workload images separately from GitOps declarations', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ version: '1.2.3' })))
    const { GET } = await import('@/app/api/infra/lifecycle/route')
    const res = await GET()
    const payload = await res.json()
    const valkey = payload.components.find((component: { id: string }) => component.id === 'valkey')
    const kafka = payload.components.find((component: { id: string }) => component.id === 'kafka')
    const clickhouse = payload.components.find((component: { id: string }) => component.id === 'clickhouse')

    expect(valkey.running).toEqual({
      version: '8.1.10', source: 'prometheus-workload-image', observedAt: liveSnapshot.fetchedAt,
      versions: [{ version: '8.1.10', count: 1, observedAt: liveSnapshot.fetchedAt }], mixed: false,
    })
    expect(kafka.running).toEqual({
      version: '4.2.0', source: 'prometheus-workload-image', observedAt: liveSnapshot.fetchedAt,
      versions: [{ version: '4.2.0', count: 1, observedAt: liveSnapshot.fetchedAt }], mixed: false,
    })
    expect(clickhouse.running).toEqual({
      version: '26.8', source: 'prometheus-workload-image', observedAt: liveSnapshot.fetchedAt,
      versions: [{ version: '26.8', count: 1, observedAt: liveSnapshot.fetchedAt }], mixed: false,
    })
    const registry = payload.components.find((component: { id: string }) => component.id === 'schema-registry')
    expect(registry.running.version).toBe('2.6.2')
    expect([null, 'gitops-image-tag']).toContain(valkey.declared.source)
    if (valkey.declared.version !== null) expect(valkey.declared.version).not.toBe(valkey.running.version)
  })

  it('does not invent a ClickHouse runtime version when the fresh image sample is absent', async () => {
    liveSnapshot.components.clickhouse = []
    const { GET } = await import('@/app/api/infra/lifecycle/route')
    const res = await GET()
    const payload = await res.json()
    const clickhouse = payload.components.find((component: { id: string }) => component.id === 'clickhouse')
    expect(clickhouse.running.version).toBeNull()
    expect(clickhouse.urgency).toBe('unknown')
  })

  it('withholds a single running version and lifecycle verdict while different versions coexist', async () => {
    const observedAt = new Date().toISOString()
    liveSnapshot.components.valkey = [
      { version: '8.1.10', count: 2, observedAt },
      { version: '8.1.9', count: 1, observedAt },
    ]
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ version: '1.2.3' })))
    const { GET } = await import('@/app/api/infra/lifecycle/route')
    const res = await GET()
    const payload = await res.json()
    const valkey = payload.components.find((component: { id: string }) => component.id === 'valkey')
    expect(valkey.running).toEqual({
      version: null,
      source: 'mixed-running-images',
      observedAt: null,
      versions: [
        { version: '8.1.10', count: 2, observedAt },
        { version: '8.1.9', count: 1, observedAt },
      ],
      mixed: true,
    })
    expect(valkey.urgency).toBe('unknown')
    expect(valkey.upgrade.target).toBeNull()
  })

  it('returns digest-only image identity without deriving lifecycle advice from it', async () => {
    const observedAt = new Date().toISOString()
    const digest = 'sha256:' + 'a'.repeat(64)
    liveSnapshot.components.valkey = [
      { version: digest, count: 1, observedAt },
    ]
    vi.stubGlobal('fetch', vi.fn(async () => Response.json({ version: '1.2.3' })))
    const { GET } = await import('@/app/api/infra/lifecycle/route')
    const res = await GET()
    const payload = await res.json()
    const valkey = payload.components.find((component: { id: string }) => component.id === 'valkey')
    expect(valkey.running.version).toBe(digest)
    expect(valkey.running.source).toBe('prometheus-workload-image-digest')
    expect(valkey.urgency).toBe('unknown')
    expect(valkey.upgrade.target).toBeNull()
  })

  it('uses a validated installed or upgrade version for templated release notes only', async () => {
    const scenarios = [
      {
        id: 'postgres', url: 'https://www.postgresql.org/docs/release/{version}/',
        cycle: '18', latest: '18.6', running: '18.6', expected: 'https://www.postgresql.org/docs/release/18.6/',
      },
      {
        id: 'postgres', url: 'https://www.postgresql.org/docs/release/{version}/',
        cycle: '18', latest: '18.6', running: '18.5', expected: 'https://www.postgresql.org/docs/release/18.6/',
      },
      {
        id: 'keycloak', url: 'https://github.com/keycloak/keycloak/releases/tag/{version}',
        cycle: '26.4', latest: '26.4.7', running: '26.4.7', expected: 'https://github.com/keycloak/keycloak/releases/tag/26.4.7',
      },
      {
        id: 'openbao', url: 'https://github.com/openbao/openbao/releases/tag/v{version}',
        cycle: '2.7', latest: '2.7.1', running: '2.7.1', expected: 'https://github.com/openbao/openbao/releases/tag/v2.7.1',
      },
      {
        id: 'postgres', url: 'https://example.test/{version}/mirror/{version}',
        cycle: '18', latest: '18.6', running: '18.5', expected: 'https://example.test/18.6/mirror/18.6',
      },
      {
        id: 'postgres', url: 'https://www.postgresql.org/docs/release/{version}/',
        cycle: '18', latest: '18.6', running: 'sha256:' + 'b'.repeat(64), expected: null,
      },
      {
        id: 'postgres', url: 'https://www.postgresql.org/docs/release/{version}/',
        cycle: '18', latest: '18.6', running: '18.6,18.5', expected: null, mixed: true,
      },
      {
        id: 'postgres', url: 'https://www.postgresql.org/docs/release/{version}/',
        cycle: '18', latest: '18.6', running: 'unknown', expected: null,
      },
      {
        id: 'postgres', url: 'https://www.postgresql.org/docs/current/',
        cycle: '18', latest: '18.6', running: null, expected: 'https://www.postgresql.org/docs/current/',
      },
    ] as const

    const originalLifecyclePath = process.env.OPENBANK_INFRA_LIFECYCLE
    const fixtureDir = await mkdtemp(path.join(os.tmpdir(), 'infra-lifecycle-release-links-'))
    const fixturePath = path.join(fixtureDir, 'lifecycle.json')
    process.env.OPENBANK_INFRA_LIFECYCLE = fixturePath
    try {
      const { GET } = await import('@/app/api/infra/lifecycle/route')
      for (const scenario of scenarios) {
        await writeFile(fixturePath, JSON.stringify({
          components: [{
            id: scenario.id,
            eolProduct: scenario.id,
            versionSource: 'unknown',
            gitopsVersion: null,
            releaseNotes: scenario.url,
            lifecycle: { cycles: [{ cycle: scenario.cycle, latest: scenario.latest, lts: false, eol: null }] },
          }],
        }))
        const versions = (liveSnapshot as { components: Record<string, unknown> }).components
        versions[scenario.id] = scenario.mixed
          ? [
              { version: scenario.running.split(',')[0], count: 1, observedAt: liveSnapshot.fetchedAt },
              { version: scenario.running.split(',')[1], count: 1, observedAt: liveSnapshot.fetchedAt },
            ]
          : scenario.running
            ? [{ version: scenario.running, count: 1, observedAt: liveSnapshot.fetchedAt }]
            : []

        const response = await GET()
        const payload = await response.json()
        expect(payload.components[0].upgrade.releaseNotesUrl).toBe(scenario.expected)
      }
    } finally {
      if (originalLifecyclePath === undefined) delete process.env.OPENBANK_INFRA_LIFECYCLE
      else process.env.OPENBANK_INFRA_LIFECYCLE = originalLifecyclePath
      await rm(fixtureDir, { recursive: true, force: true })
    }
  })
})
