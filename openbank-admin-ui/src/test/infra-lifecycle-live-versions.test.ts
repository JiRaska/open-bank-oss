// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'

const liveSnapshot = vi.hoisted(() => {
  const observedAt = new Date().toISOString()
  return {
    available: true,
    fetchedAt: observedAt,
    components: {
      valkey: [{ version: '8.1.10', count: 1, observedAt }],
      kafka: [{ version: '4.2.0', count: 1, observedAt }],
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

    expect(valkey.running).toEqual({
      version: '8.1.10', source: 'prometheus-workload-image', observedAt: liveSnapshot.fetchedAt,
      versions: [{ version: '8.1.10', count: 1, observedAt: liveSnapshot.fetchedAt }], mixed: false,
    })
    expect(kafka.running).toEqual({
      version: '4.2.0', source: 'prometheus-workload-image', observedAt: liveSnapshot.fetchedAt,
      versions: [{ version: '4.2.0', count: 1, observedAt: liveSnapshot.fetchedAt }], mixed: false,
    })
    const registry = payload.components.find((component: { id: string }) => component.id === 'schema-registry')
    expect(registry.running.version).toBe('2.6.2')
    expect([null, 'gitops-image-tag']).toContain(valkey.declared.source)
    if (valkey.declared.version !== null) expect(valkey.declared.version).not.toBe(valkey.running.version)
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
})
