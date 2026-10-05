// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('next/headers', () => ({ cookies: vi.fn() }))
vi.mock('@/lib/services/docs', () => ({ loadDocsDocumentResult: vi.fn() }))

import { cookies } from 'next/headers'
import { loadDocsDocumentResult } from '@/lib/services/docs'
import { GET } from '@/app/api/services/[name]/docs/[slug]/route'

const request = new Request('http://localhost/api/services/example/docs/00-build')
const context = { params: Promise.resolve({ name: 'example', slug: '00-build' }) }

describe('GET /api/services/[name]/docs/[slug]', () => {
  beforeEach(() => {
    vi.mocked(cookies).mockResolvedValue({ get: vi.fn() } as never)
  })

  it('answers 503 when the document endpoint does not respond', async () => {
    vi.mocked(loadDocsDocumentResult).mockResolvedValue({ status: 'unavailable' })
    const response = await GET(request, context)
    expect(response.status).toBe(503)
    expect(await response.json()).toMatchObject({ error: 'docs endpoint unavailable' })
  })

  it('answers 404 when a reachable service has no such document', async () => {
    vi.mocked(loadDocsDocumentResult).mockResolvedValue({ status: 'missing' })
    const response = await GET(request, context)
    expect(response.status).toBe(404)
    expect(await response.json()).toMatchObject({ error: 'doc not found' })
  })
})
