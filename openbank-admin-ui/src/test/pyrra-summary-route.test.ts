// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { parse } from 'yaml'

const repo = path.resolve(__dirname, '../../..')
const catalog = JSON.parse(readFileSync(path.join(repo, 'openbank-admin-ui/catalog.json'), 'utf8')) as {
  services: { name: string; moneyPath: boolean }[]
}
const declaredNames = catalog.services.filter(service => service.moneyPath)
  .map(service => `${service.name.replace(/-service$/, '')}-availability`).sort()
const sloObjects = readFileSync(path.join(repo, 'openbank-infra/gitops/components/observability/pyrra-slo-money-path.yaml'), 'utf8')
  .split(/^---\s*$/m).map(document => parse(document) as { metadata?: { name?: string } } | null)
const deployedNames = sloObjects.map(object => object?.metadata?.name)
  .filter((name): name is string => typeof name === 'string' && name.endsWith('-availability')).sort()

const response = (rows: { slo: string; value: number }[]) => Response.json({
  status: 'success',
  data: { result: rows.map(row => ({ metric: { slo: row.slo }, value: [1_789_000_000, String(row.value)] })) },
})

describe('Pyrra evidence summary route', () => {
  it('the code-derived money-path catalog exactly matches declared Pyrra availability SLOs', () => {
    expect(declaredNames.length).toBeGreaterThan(6)
    expect(declaredNames).toEqual(deployedNames)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    delete process.env.PROMETHEUS_URL
    delete process.env.OPENBANK_CATALOG
  })

  it('derives availability and remaining budget from Pyrra recording rules', async () => {
    process.env.PROMETHEUS_URL = 'https://prometheus.test'
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response([{ slo: 'openbank-transaction-availability', value: 10_000 }]))
      .mockResolvedValueOnce(response([{ slo: 'openbank-transaction-availability', value: 2 }]))
    vi.stubGlobal('fetch', fetchMock)

    const { GET } = await import('@/app/api/pyrra/summary/route')
    const result = await GET()

    expect(result.status).toBe(200)
    const body = await result.json()
    expect(body).toMatchObject({ available: true, configured: declaredNames.length, monitored: 1 })
    expect(body.objectives.map((objective: { name: string }) => objective.name).sort()).toEqual(declaredNames)
    expect(body.objectives).toContainEqual(expect.objectContaining({
      name: 'openbank-transaction-availability',
      target: 0.999,
      window: '30d',
      availability: 0.9998,
      budgetRemaining: expect.closeTo(0.8, 10),
      requestCount: 10_000,
    }))
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.every(([url]) => String(url).startsWith('https://prometheus.test/api/v1/query'))).toBe(true)
    for (const name of declaredNames) {
      expect(String(fetchMock.mock.calls[0][0])).toContain(name)
    }
  })

  it('treats absent error series as zero but does not call zero traffic healthy', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response([
        { slo: 'openbank-ledger-availability', value: 2_500 },
        { slo: 'openbank-fraud-availability', value: 0 },
      ]))
      .mockResolvedValueOnce(response([]))
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/pyrra/summary/route')

    const result = await GET()
    const body = await result.json()
    expect(body.objectives).toContainEqual(expect.objectContaining({
      name: 'openbank-ledger-availability', availability: 1, budgetRemaining: 1,
    }))
    expect(body.objectives).toContainEqual(expect.objectContaining({
      name: 'openbank-fraud-availability', availability: null, budgetRemaining: null, requestCount: 0,
    }))
  })

  it('returns a typed failure instead of fabricating healthy evidence', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const { GET } = await import('@/app/api/pyrra/summary/route')

    const result = await GET()
    expect(result.status).toBe(502)
    await expect(result.json()).resolves.toMatchObject({ available: false, error: 'pyrra_evidence_unreachable', configured: declaredNames.length, objectives: [] })
  })

  it('fails closed when the baked catalog has no valid money-path set', async () => {
    const dir = mkdtempSync(path.join(os.tmpdir(), 'pyrra-catalog-'))
    try {
      const file = path.join(dir, 'catalog.json')
      writeFileSync(file, JSON.stringify({ services: [{ name: 'openbank-ledger-service', moneyPath: false }] }))
      process.env.OPENBANK_CATALOG = file
      const fetchMock = vi.fn()
      vi.stubGlobal('fetch', fetchMock)
      const { GET } = await import('@/app/api/pyrra/summary/route')
      const result = await GET()
      expect(result.status).toBe(502)
      await expect(result.json()).resolves.toMatchObject({ available: false, error: 'catalog_unavailable', configured: 0, objectives: [] })
      expect(fetchMock).not.toHaveBeenCalled()
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })
})
