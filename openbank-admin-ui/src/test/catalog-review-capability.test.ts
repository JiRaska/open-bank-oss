// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

import { describe, expect, it } from 'vitest'
import { resolveCatalogReviewCapability } from '@/lib/catalog-review-capability'

describe('catalog review capability', () => {
  it('accepts only the server-returned allow-list and configured default', () => {
    expect(resolveCatalogReviewCapability(200, true, {
      available: true,
      mode: 'sandbox-hosted',
      defaultModel: 'configured-default',
      models: [{ id: 'configured-default', provider: 'openai-compat', sensitivity: 'HOSTED' }],
    })).toEqual({
      status: 'available',
      mode: 'sandbox-hosted',
      defaultModel: 'configured-default',
      models: [{ id: 'configured-default', provider: 'openai-compat', sensitivity: 'HOSTED' }],
    })
  })

  it('reports no approved model separately from a failed lookup', () => {
    expect(resolveCatalogReviewCapability(200, true, {
      available: false, mode: null, defaultModel: null, models: [],
    }).status).toBe('unavailable')
    expect(resolveCatalogReviewCapability(200, true, {
      available: true, mode: 'sandbox-hosted', defaultModel: 'missing',
      models: [{ id: 'configured-default', provider: 'openai-compat', sensitivity: 'HOSTED' }],
    }).status).toBe('error')
  })

  it.each([
    [401, 'unauthenticated'],
    [403, 'forbidden'],
    [502, 'error'],
  ] as const)('classifies HTTP %i without claiming the registry is empty', (status, expected) => {
    expect(resolveCatalogReviewCapability(status, false, null).status).toBe(expected)
  })

  it.each([
    null,
    {},
    { available: true, mode: 'sandbox-hosted', defaultModel: 'x' },
    { available: true, mode: 'unknown', defaultModel: 'x', models: [{ id: 'x', provider: 'p', sensitivity: 'HOSTED' }] },
    { available: true, mode: 'sandbox-hosted', defaultModel: 'x', models: [{ id: 'x', provider: '', sensitivity: 'HOSTED' }] },
    { available: false, mode: null, defaultModel: 'x', models: [] },
  ])('fails closed on malformed capability response', body => {
    expect(resolveCatalogReviewCapability(200, true, body).status).toBe('error')
  })
})
