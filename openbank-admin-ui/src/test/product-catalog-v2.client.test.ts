// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it, vi } from 'vitest'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import {
  catalogV2Operation, createCatalogV2Client,
} from '@/lib/product-catalog-v2'

describe('product catalog v2 production client', () => {
  it('uses the provider route literal and decodes the schema DTO', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({
      id: 'org.openbank.insurance.term-life', version: 1, document: {}, sha256: 'a'.repeat(64),
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })) as unknown as typeof fetch

    const result = await catalogV2Operation('getProductTypeVersionV2', {
      pathParameters: { id: 'org.openbank.insurance.term-life', version: 1 },
    }, { fetcher })

    expect(fetcher).toHaveBeenCalledWith(
      '/api/svc/product-catalog/api/v2/product-types/org.openbank.insurance.term-life/versions/1',
      expect.any(Object),
    )
    expect(result).toMatchObject({ id: 'org.openbank.insurance.term-life', version: 1 })
  })

  it('encodes optional-only offering, effective-date and cursor queries', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify([]), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })) as unknown as typeof fetch
    const transport = { fetcher }
    await catalogV2Operation('listOfferingsV2', {
      query: { specificationId: '10000000-0000-0000-0000-000000000001' },
    }, transport)
    await catalogV2Operation('getPublishedProductV2', {
      pathParameters: { offeringId: '20000000-0000-0000-0000-000000000001' },
      query: { effectiveAt: '2026-08-13T00:00:00Z' },
    }, transport)
    await catalogV2Operation('listCatalogEvents', { query: { after: '42', limit: 25 } }, transport)
    expect(fetcher.mock.calls.map(call => call[0])).toEqual([
      '/api/svc/product-catalog/api/v2/offerings?specificationId=10000000-0000-0000-0000-000000000001',
      '/api/svc/product-catalog/api/v2/products/20000000-0000-0000-0000-000000000001?effectiveAt=2026-08-13T00%3A00%3A00Z',
      '/api/svc/product-catalog/api/v2/events?after=42&limit=25',
    ])
  })

  it('drives the production client through the committed provider-replayed Pact interaction', async () => {
    const pact = JSON.parse(await readFile(
      resolve(process.cwd(), '../pacts/openbank-admin-ui-openbank-product-catalog.json'), 'utf8',
    )) as { interactions: Array<{ request: { path: string; headers: Record<string, string> }; response: { body: unknown } }> }
    const interaction = pact.interactions.find(item =>
      item.request.path === '/api/v2/product-types/org.openbank.insurance.term-life/versions/1')
    if (!interaction) throw new Error('schema interaction is missing from the committed Pact')
    let observedPath = ''
    let observedAccept = ''
    const server = createServer((request, response) => {
      observedPath = request.url ?? ''
      observedAccept = String(request.headers.accept ?? '')
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify(interaction.response.body))
    })
    server.listen(0, '127.0.0.1')
    await once(server, 'listening')
    try {
      const address = server.address()
      if (!address || typeof address === 'string') throw new Error('mock server did not bind')
      const client = createCatalogV2Client({ baseUrl: `http://127.0.0.1:${address.port}` })
      const schema = await client('getProductTypeVersionV2', {
        pathParameters: { id: 'org.openbank.insurance.term-life', version: 1 },
      })
      expect(schema).toMatchObject({ id: 'org.openbank.insurance.term-life', version: 1 })
      expect(observedPath).toBe(interaction.request.path)
      expect(observedAccept).toBe(interaction.request.headers.Accept)
    } finally {
      server.close()
      await once(server, 'close')
    }
  })

  it('executes the complete Product Studio author-to-publish Pact seam through generated operations', async () => {
    const pact = JSON.parse(await readFile(
      resolve(process.cwd(), '../pacts/openbank-admin-ui-openbank-product-catalog.json'), 'utf8',
    )) as { interactions: Array<{
      description: string
      request: { method: string; path: string; headers?: Record<string, string>; body?: unknown }
      response: { status: number; body: unknown }
    }> }
    const observed: Array<{ method: string; path: string; ifMatch: string; body?: unknown }> = []
    const byRequest = new Map(pact.interactions.map(interaction => [
      `${interaction.request.method} ${interaction.request.path}`, interaction,
    ]))
    const server = createServer(async (request, response) => {
      const key = `${request.method} ${request.url}`
      const interaction = byRequest.get(key)
      const chunks: Buffer[] = []
      for await (const chunk of request) chunks.push(Buffer.from(chunk))
      observed.push({
        method: request.method ?? '', path: request.url ?? '',
        ifMatch: String(request.headers['if-match'] ?? ''),
        ...(chunks.length ? { body: JSON.parse(Buffer.concat(chunks).toString('utf8')) } : {}),
      })
      if (!interaction) { response.writeHead(500); response.end(); return }
      response.writeHead(interaction.response.status, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify(interaction.response.body))
    })
    server.listen(0, '127.0.0.1')
    await once(server, 'listening')
    try {
      const address = server.address()
      if (!address || typeof address === 'string') throw new Error('mock server did not bind')
      const transport = { baseUrl: `http://127.0.0.1:${address.port}` }
      const schemaRef = { id: 'org.openbank.insurance.term-life', version: 1 }
      const revision = {
        schemaRef, name: { en: 'Term life' },
        attributes: {
          coverage: { amount: '100000', currency: 'EUR' },
          termYears: 20, premiumModel: 'CALCULATED',
        },
        prices: [], eligibility: [], relationships: [], documentCodes: [],
      }
      await catalogV2Operation('getProductTypeVersionV2', {
        pathParameters: { id: schemaRef.id, version: 1 },
      }, transport)
      await catalogV2Operation('createSpecificationV2', {
        body: { code: 'PACT_STUDIO_SPEC', schemaRef },
      }, transport)
      await catalogV2Operation('createOfferingV2', {
        body: {
          specificationId: '10000000-0000-0000-0000-000000000001', code: 'PACT_STUDIO_OFFER',
          market: { countries: ['CZ'], channels: ['WEB'], locales: ['en'] },
        },
      }, transport)
      await catalogV2Operation('createOfferingRevisionV2', {
        pathParameters: { id: '20000000-0000-0000-0000-000000000001' },
        body: revision,
      }, transport)
      await catalogV2Operation('replaceOfferingRevisionV2', {
        pathParameters: {
          offeringId: '20000000-0000-0000-0000-000000000002',
          revisionId: '30000000-0000-0000-0000-000000000001',
        },
        headers: { 'If-Match': '"0"' }, body: revision,
      }, transport)
      await catalogV2Operation('publishOfferingRevisionV2', {
        pathParameters: {
          offeringId: '20000000-0000-0000-0000-000000000003',
          revisionId: '30000000-0000-0000-0000-000000000002',
        },
        headers: { 'If-Match': '"0"' }, body: { reason: 'independent commercial approval' },
      }, transport)

      expect(new Set(observed.map(item => `${item.method} ${item.path}`))).toEqual(
        new Set(pact.interactions.map(item => `${item.request.method} ${item.request.path}`)),
      )
      expect(observed.filter(item => item.method === 'PUT' || item.path.endsWith('/publish'))
        .every(item => item.ifMatch === '"0"')).toBe(true)
      for (const item of observed) {
        const contract = byRequest.get(`${item.method} ${item.path}`)
        expect(item.body).toEqual(contract?.request.body)
      }
    } finally {
      server.close()
      await once(server, 'close')
    }
  })
})
