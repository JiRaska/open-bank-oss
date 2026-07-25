// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// A failed card WRITE has to be explainable. `classifyBffFailure` is a read-path
// classifier and calls every unusual 4xx `error`; these are the extra distinctions
// an operator acts on differently — "the card moved under you", "the product says
// no, and here is which rule", "your role may not do this".

import { describe, it, expect } from 'vitest'
import { classifyMutation, cardErrorCode } from '@/lib/cards/mutations'

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

describe('card mutation classification', () => {
  it('reads an aggregate refusal (bare 400 from CommonExceptionMappers) as a stale transition', async () => {
    expect(await classifyMutation(json(400, { message: 'Only PENDING cards can be activated' })))
      .toBe('illegal_transition')
    expect(await classifyMutation(json(422, {}))).toBe('illegal_transition')
  })

  it('names WHICH product rule refused a 409', async () => {
    expect(await classifyMutation(json(409, { code: 'CARD_QUOTA_EXCEEDED' }))).toBe('conflict:CARD_QUOTA_EXCEEDED')
    expect(await classifyMutation(json(409, { code: 'CARD_NETWORK_NOT_ALLOWED' }))).toBe('conflict:CARD_NETWORK_NOT_ALLOWED')
    expect(await classifyMutation(json(409, { code: 'CARD_VIRTUAL_NOT_ALLOWED' }))).toBe('conflict:CARD_VIRTUAL_NOT_ALLOWED')
    expect(await classifyMutation(json(409, { code: 'CARD_PRODUCT_DISABLED' }))).toBe('conflict:CARD_PRODUCT_DISABLED')
  })

  it('falls back to a plain conflict for an unknown or missing code', async () => {
    expect(await classifyMutation(json(409, { code: 'SOMETHING_NEW' }))).toBe('conflict')
    expect(await classifyMutation(new Response('not json', { status: 409 }))).toBe('conflict')
  })

  it('keeps 403 distinct from 401 — a role problem is not an expired session', async () => {
    expect(await classifyMutation(json(403, {}))).toBe('forbidden')
    expect(await classifyMutation(json(401, { error: 'unauthorized' }))).toBe('unauthorized')
  })

  it('still delegates the BFF-level outcomes to the shared read classifier', async () => {
    expect(await classifyMutation(json(404, { error: 'Unknown service: card-issuance-service' }))).toBe('not_deployed')
    expect(await classifyMutation(json(503, { error: 'scaled_to_zero' }))).toBe('scaled_to_zero')
    expect(await classifyMutation(json(502, { error: 'upstream_unreachable' }))).toBe('unreachable')
    expect(await classifyMutation(json(404, {}))).toBe('not_found')
    expect(await classifyMutation(json(500, {}))).toBe('error')
  })

  it('never throws on a body it cannot read', async () => {
    expect(await cardErrorCode(new Response('<html>', { status: 409 }))).toBeNull()
  })
})
