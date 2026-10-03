// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { newIdempotencyKey, tokenStatusRequest } from '@/lib/cards/lifecycleRequests'

// card-processing answers 400 to a token status change or a dispute refresh sent without an
// Idempotency-Key, and replays — never re-sends to the network — a retry under the same key.

describe('card lifecycle actions send an Idempotency-Key', () => {
  it('a token status change carries a key, the status body and an encoded path', () => {
    const request = tokenStatusRequest('tok/1 a', 'SUSPENDED', 'key-1')
    expect(request.path).toBe('/api/v1/card-tokens/tok%2F1%20a/status')
    expect(request.init.method).toBe('POST')
    expect(request.init.headers).toEqual({ 'Content-Type': 'application/json', 'Idempotency-Key': 'key-1' })
    expect(request.init.body).toBe(JSON.stringify({ status: 'SUSPENDED' }))
  })

  it('generates a fresh key per action when none is given', () => {
    const a = (tokenStatusRequest('tok-1', 'DELETED').init.headers as Record<string, string>)['Idempotency-Key']
    const b = (tokenStatusRequest('tok-1', 'DELETED').init.headers as Record<string, string>)['Idempotency-Key']
    expect(a).toMatch(/^[0-9a-f-]{36}$/)
    expect(a).not.toBe(b)
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey())
  })

  it('both pages route the network-reaching POSTs through a generated key', () => {
    const tokens = readFileSync(path.resolve(__dirname, '../app/cards/tokens/page.tsx'), 'utf8')
    const disputes = readFileSync(path.resolve(__dirname, '../app/cards/disputes/page.tsx'), 'utf8')
    expect(tokens).toContain('tokenStatusRequest(tokenReference, status)')
    expect(tokens).not.toMatch(/fetch\(\s*svcUrl\('card-processing-service', `\/api\/v1\/card-tokens\/\$\{/)
    expect(disputes).toMatch(
      /post\(\s*`\/api\/v1\/card-disputes\/\$\{dispute\.id\}\/refresh`,\s*undefined,\s*newIdempotencyKey\(\)/,
    )
  })
})
