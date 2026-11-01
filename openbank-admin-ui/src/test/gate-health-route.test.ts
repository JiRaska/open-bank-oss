// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createHash } from 'node:crypto'

vi.mock('fs', async importOriginal => {
  const actual = await importOriginal<typeof import('fs')>()
  const promises = { ...actual.promises, readFile: vi.fn().mockRejectedValue(new Error('missing')) }
  return { ...actual, promises, default: { ...actual, promises } }
})
import { promises as fs } from 'fs'

const data = () => ({
  available: true, source: 'github-actions', collectedAt: new Date().toISOString(),
  runsInspected: 1, gateDetailRunsInspected: 0, shardHistory: [], gates: [], estate: null,
})

describe('Gate Health BFF runtime evidence contract', () => {
  afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.resetModules(); vi.mocked(fs.readFile).mockReset() })

  it('serves a validated runtime snapshot with provenance and private no-store caching', async () => {
    vi.stubEnv('OPENBANK_GATE_HEALTH_STORE_URL', 'https://evidence.example/admin-ui/gate-health')
    const snapshot = data()
    const bytes = JSON.stringify(snapshot)
    const sha256 = createHash('sha256').update(bytes).digest('hex')
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(url.endsWith('/current.json')
      ? JSON.stringify({ schemaVersion: 1, snapshot: `snapshots/10-1-${sha256}.json`, sha256,
        sourceSha: 'a'.repeat(40), runId: 10, runAttempt: 1, collectedAt: snapshot.collectedAt,
        expiresAt: new Date(Date.now() + 60_000).toISOString(), complete: true }) : bytes)))
    const { GET } = await import('@/app/api/devops/gate-health/route')
    const response = await GET()
    expect(response.headers.get('Cache-Control')).toBe('private, no-store')
    expect(await response.json()).toMatchObject({ ...snapshot, evidence: { state: 'ready', provenance: { sha256, runId: 10 } } })
    expect(fs.readFile).not.toHaveBeenCalled()
  })

  it('uses the bundled sample with a stale marker when the runtime store fails', async () => {
    vi.stubEnv('OPENBANK_GATE_HEALTH_STORE_URL', 'https://evidence.example')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })))
    const snapshot = data()
    vi.mocked(fs.readFile).mockResolvedValue(JSON.stringify(snapshot))
    const { GET } = await import('@/app/api/devops/gate-health/route')
    expect(await (await GET()).json()).toMatchObject({ ...snapshot, evidence: { state: 'stale', reason: 'manifest HTTP 503' } })
  })

  it('does not turn malformed or missing data into an empty healthy estate', async () => {
    vi.stubEnv('OPENBANK_GATE_HEALTH_STORE_URL', 'https://evidence.example')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')))
    vi.mocked(fs.readFile).mockResolvedValue('{"available":true}')
    const { GET } = await import('@/app/api/devops/gate-health/route')
    expect(await (await GET()).json()).toMatchObject({ available: false, evidence: { state: 'unavailable' } })
  })
})
