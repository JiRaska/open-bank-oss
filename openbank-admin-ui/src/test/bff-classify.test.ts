// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect } from 'vitest'
import { classifyBffFailure } from '@/lib/services/bff'

function res(status: number, body?: unknown): Response {
  return new Response(body === undefined ? '' : JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

describe('classifyBffFailure', () => {
  it('maps 404 "Unknown service" to not_deployed', async () => {
    expect(await classifyBffFailure(res(404, { error: 'Unknown service: foo' }))).toBe('not_deployed')
  })

  it('maps 503 "scaled_to_zero" to scaled_to_zero (KEDA idle, ADR-0057)', async () => {
    expect(await classifyBffFailure(res(503, { error: 'scaled_to_zero' }))).toBe('scaled_to_zero')
  })

  it('does not confuse a scaled-to-zero service with not_deployed', async () => {
    const kind = await classifyBffFailure(res(503, { error: 'scaled_to_zero' }))
    expect(kind).not.toBe('not_deployed')
    expect(kind).not.toBe('unreachable')
  })

  it('maps 401 to unauthorized', async () => {
    expect(await classifyBffFailure(res(401, { error: 'unauthorized' }))).toBe('unauthorized')
  })

  it('maps 502 "upstream_unreachable" to unreachable', async () => {
    expect(await classifyBffFailure(res(502, { error: 'upstream_unreachable' }))).toBe('unreachable')
  })

  it('maps a bare 404 (no known body) to not_found', async () => {
    expect(await classifyBffFailure(res(404, { error: 'no such id' }))).toBe('not_found')
  })

  it('falls back to error for anything else', async () => {
    expect(await classifyBffFailure(res(500, { error: 'boom' }))).toBe('error')
  })

  it('handles a non-JSON body without throwing', async () => {
    const html = new Response('<html>oops</html>', { status: 503, headers: { 'content-type': 'text/html' } })
    expect(await classifyBffFailure(html)).toBe('error')
  })
})
