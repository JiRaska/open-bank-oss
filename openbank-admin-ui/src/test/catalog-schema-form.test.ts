// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

import { describe, expect, it } from 'vitest'
import { catalogFieldValue, catalogSchemaFields, withCatalogFieldValue } from '@/lib/catalog-schema-form'

describe('catalog schema form', () => {
  const schema = {
    type: 'object', required: ['coverage', 'premiumModel'], properties: {
      coverage: { type: 'object', required: ['amount'], properties: {
        amount: { type: 'string' }, currency: { type: 'string', enum: ['EUR', 'CZK'] },
      } },
      premiumModel: { type: 'string', enum: ['FIXED', 'CALCULATED'] },
      perils: { type: 'array', items: { type: 'string' } },
    },
  }

  it('exposes only scalar controls from trusted schema objects', () => {
    expect(catalogSchemaFields(schema)).toEqual([
      expect.objectContaining({ path: ['coverage', 'amount'], required: true, type: 'string' }),
      expect.objectContaining({ path: ['coverage', 'currency'], choices: ['EUR', 'CZK'], required: false }),
      expect.objectContaining({ path: ['premiumModel'], choices: ['FIXED', 'CALCULATED'], required: true }),
    ])
  })

  it('updates a nested attribute without changing unrelated expert JSON', () => {
    const original = { attributes: { coverage: { amount: '1000', currency: 'EUR' }, perils: [{ code: 'DEATH' }] } }
    const next = withCatalogFieldValue(original, ['coverage', 'amount'], '1200')
    expect(catalogFieldValue(next, ['coverage', 'amount'])).toBe('1200')
    expect(next).toEqual({ attributes: { coverage: { amount: '1200', currency: 'EUR' }, perils: [{ code: 'DEATH' }] } })
    expect(original.attributes.coverage.amount).toBe('1000')
  })
})
