// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchAllServiceSnapshotsEvidence } from '@/lib/api'
import { fetchCves } from '@/lib/inventory/cveEvidence'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('service snapshot evidence', () => {
  it('keeps a verified all-down fleet distinct from collector failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ services: [{
      name: 'ledger-service', port: 8101, status: 'DOWN', reachable: false, latencyMs: null,
    }] })))

    await expect(fetchAllServiceSnapshotsEvidence()).resolves.toMatchObject({
      ok: true,
      snapshots: [{ name: 'ledger-service', reachable: false }],
    })
  })

  it('rejects malformed collector evidence instead of manufacturing offline services', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ services: [{ name: 'ledger-service' }] })))
    await expect(fetchAllServiceSnapshotsEvidence()).resolves.toEqual({ ok: false, failure: 'error' })
  })

  it('rejects duplicate services and malformed stack blocks', async () => {
    const malformed = { name: 'ledger-service', port: 8101, status: 'UP', reachable: true, latencyMs: 4, stack: { quarkus: {} } }
    const duplicate = { name: 'ledger-service', port: 8101, status: 'DOWN', reachable: false, latencyMs: null }
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ services: [malformed] }))
      .mockResolvedValueOnce(json({ services: [duplicate, duplicate] }))
    vi.stubGlobal('fetch', fetch)

    await expect(fetchAllServiceSnapshotsEvidence()).resolves.toEqual({ ok: false, failure: 'error' })
    await expect(fetchAllServiceSnapshotsEvidence()).resolves.toEqual({ ok: false, failure: 'error' })
  })

  it('classifies collector and OSV failures without claiming verified empty evidence', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'upstream_unreachable' }, 502)))
    await expect(fetchAllServiceSnapshotsEvidence()).resolves.toEqual({ ok: false, failure: 'unreachable' })
    await expect(fetchCves('Quarkus', '3.27.0')).resolves.toEqual({ state: 'unavailable', vulns: [] })
  })

  it('accepts a verified empty OSV result and rejects malformed vulnerability rows', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ vulns: [] }))
      .mockResolvedValueOnce(json({ vulns: [{ id: 'CVE-1' }] }))
    vi.stubGlobal('fetch', fetch)

    await expect(fetchCves('Quarkus', '3.27.0')).resolves.toEqual({ state: 'verified', vulns: [] })
    await expect(fetchCves('Quarkus', '3.27.0')).resolves.toEqual({ state: 'unavailable', vulns: [] })
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/sbom/cve?'), expect.objectContaining({
      cache: 'no-store',
      signal: expect.any(AbortSignal),
    }))
  })
})
