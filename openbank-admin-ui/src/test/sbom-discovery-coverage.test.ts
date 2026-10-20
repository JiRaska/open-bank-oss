// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

vi.mock('@/lib/discovery', () => ({
  inCluster: vi.fn(() => true),
  resolveInClusterService: vi.fn(),
}))

import { resolveInClusterService } from '@/lib/discovery'

const params = { params: Promise.resolve({ name: 'demo' }) }
const sbom = {
  bomFormat: 'CycloneDX', specVersion: '1.5', version: 1,
  metadata: { timestamp: '2026-10-19T10:00:00Z', component: { name: 'demo', version: '1.0.0' } },
  components: [],
}

describe('SBOM discovery coverage', () => {
  let directory: string

  beforeEach(async () => {
    vi.resetModules()
    directory = await mkdtemp(path.join(os.tmpdir(), 'sbom-discovery-'))
    process.env.OPENBANK_SBOM_BUNDLE = directory
  })
  afterEach(async () => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    delete process.env.OPENBANK_SBOM_BUNDLE
    await rm(directory, { recursive: true, force: true })
  })

  it('labels the bundled fallback when incomplete discovery cannot locate the live service', async () => {
    await mkdir(path.join(directory, 'openbank-demo-service'), { recursive: true })
    await writeFile(path.join(directory, 'openbank-demo-service', 'bom.json'), JSON.stringify(sbom))
    vi.mocked(resolveInClusterService).mockResolvedValue({ state: 'unknown', coverage: 'partial' })
    const { GET } = await import('@/app/api/services/[name]/sbom/route')

    const response = await GET(new Request('http://localhost/api/services/demo/sbom?summary=true'), params)

    expect(response.status).toBe(200)
    expect(await response.json()).toMatchObject({ source: 'image-baked', discovery: 'unknown', service: 'demo' })
  })

  it('returns unavailable rather than not-found when discovery is incomplete and no bundle exists', async () => {
    vi.mocked(resolveInClusterService).mockResolvedValue({ state: 'unknown', coverage: 'unavailable' })
    const { GET } = await import('@/app/api/services/[name]/sbom/route')

    const response = await GET(new Request('http://localhost/api/services/demo/sbom?summary=true'), params)

    expect(response.status).toBe(503)
    expect(await response.json()).toMatchObject({ error: 'service discovery incomplete' })
  })

  it('keeps authoritative absence as not-found when discovery is complete', async () => {
    vi.mocked(resolveInClusterService).mockResolvedValue({ state: 'not_found', coverage: 'complete' })
    const { GET } = await import('@/app/api/services/[name]/sbom/route')

    const response = await GET(new Request('http://localhost/api/services/demo/sbom?summary=true'), params)

    expect(response.status).toBe(404)
    expect(await response.json()).toMatchObject({ error: 'SBOM not found' })
  })
})
