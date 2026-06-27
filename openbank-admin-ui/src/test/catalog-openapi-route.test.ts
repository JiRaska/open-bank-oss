// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { promises as fs } from 'fs'

// The catalog endpoint count used to read the live `/q/openapi`, which Quarkus
// serves on the management port (8085) the BFF cannot reach → "0 endpoints".
// This route serves the image-baked committed spec instead. These tests lock in
// the contract: parse YAML→JSON by default, raw passthrough for ?format=yaml,
// reject unsafe ids, and soft-404 when no spec is bundled.

const SPEC = `openapi: 3.0.3
info:
  title: Account Service
  version: 0.1.2
paths:
  /api/v1/accounts:
    get: { summary: list }
    post: { summary: create }
  /api/v1/accounts/{accountId}:
    get: { summary: get }
`

function ctx(service: string) {
  return { params: Promise.resolve({ service }) }
}

describe('GET /api/catalog/openapi/[service]', () => {
  afterEach(() => vi.restoreAllMocks())

  it('parses the baked YAML spec to JSON with all paths', async () => {
    vi.spyOn(fs, 'readFile').mockResolvedValue(SPEC as never)
    const { GET } = await import('../app/api/catalog/openapi/[service]/route')
    const res = await GET(new Request('http://localhost/api/catalog/openapi/account-service'), ctx('account-service'))
    expect(res.status).toBe(200)
    const doc = await res.json()
    expect(doc.info.version).toBe('0.1.2')
    expect(Object.keys(doc.paths)).toHaveLength(2)
    expect(doc.paths['/api/v1/accounts']).toHaveProperty('post')
  })

  it('streams raw YAML when ?format=yaml', async () => {
    vi.spyOn(fs, 'readFile').mockResolvedValue(SPEC as never)
    const { GET } = await import('../app/api/catalog/openapi/[service]/route')
    const res = await GET(new Request('http://localhost/api/catalog/openapi/account-service?format=yaml'), ctx('account-service'))
    expect(res.status).toBe(200)
    expect(res.headers.get('content-type')).toContain('yaml')
    expect(await res.text()).toBe(SPEC)
  })

  it('rejects unsafe service ids with 400 (no path traversal)', async () => {
    const spy = vi.spyOn(fs, 'readFile')
    const { GET } = await import('../app/api/catalog/openapi/[service]/route')
    const res = await GET(new Request('http://localhost/api/catalog/openapi/x'), ctx('../../etc/passwd'))
    expect(res.status).toBe(400)
    expect(spy).not.toHaveBeenCalled()
  })

  it('soft-404s with a stable JSON body when no spec is bundled', async () => {
    vi.spyOn(fs, 'readFile').mockRejectedValue(Object.assign(new Error('nope'), { code: 'ENOENT' }))
    const { GET } = await import('../app/api/catalog/openapi/[service]/route')
    const res = await GET(new Request('http://localhost/api/catalog/openapi/missing-service'), ctx('missing-service'))
    expect(res.status).toBe(404)
    await expect(res.json()).resolves.toMatchObject({ error: expect.stringContaining('missing-service') })
  })
})
