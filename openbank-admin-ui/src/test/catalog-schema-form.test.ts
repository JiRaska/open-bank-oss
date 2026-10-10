// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

import { describe, expect, it } from 'vitest'
import { catalogFieldValue, catalogSchemaFields, catalogUtcEffectiveInput, withCatalogFieldValue, withCatalogUtcEffective } from '@/lib/catalog-schema-form'

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

  it('exposes questionnaire class enum arrays and preserves other draft attributes', () => {
    const retirementSchema = { type: 'object', required: ['instrumentClasses'], properties: {
      instrumentClasses: { type: 'array', minItems: 1, uniqueItems: true,
        items: { type: 'string', enum: ['PENSION_FUNDS', 'BOND_FUNDS', 'EQUITY_FUNDS'] } },
    } }
    expect(catalogSchemaFields(retirementSchema)).toEqual([
      expect.objectContaining({ path: ['instrumentClasses'], type: 'enum-array', required: true,
        minItems: 1, choices: ['PENSION_FUNDS', 'BOND_FUNDS', 'EQUITY_FUNDS'] }),
    ])
    const draft = { schemaRef: { id: 'pension-savings', version: 2 }, attributes: {
      fundStrategy: 'BALANCED', instrumentClasses: ['BOND_FUNDS'], reviewStatus: 'ILLUSTRATIVE',
    } }
    const next = withCatalogFieldValue(draft, ['instrumentClasses'], ['BOND_FUNDS', 'EQUITY_FUNDS'])
    expect(catalogFieldValue(next, ['instrumentClasses'])).toEqual(['BOND_FUNDS', 'EQUITY_FUNDS'])
    expect(next.attributes).toEqual({ fundStrategy: 'BALANCED',
      instrumentClasses: ['BOND_FUNDS', 'EQUITY_FUNDS'], reviewStatus: 'ILLUSTRATIVE' })
    expect(draft.attributes.instrumentClasses).toEqual(['BOND_FUNDS'])
  })

  it('updates a nested attribute without changing unrelated expert JSON', () => {
    const original = { attributes: { coverage: { amount: '1000', currency: 'EUR' }, perils: [{ code: 'DEATH' }] } }
    const next = withCatalogFieldValue(original, ['coverage', 'amount'], '1200')
    expect(catalogFieldValue(next, ['coverage', 'amount'])).toBe('1200')
    expect(next).toEqual({ attributes: { coverage: { amount: '1200', currency: 'EUR' }, perils: [{ code: 'DEATH' }] } })
    expect(original.attributes.coverage.amount).toBe('1000')
  })

  it('schedules a revised composition in explicit UTC without changing its classes', () => {
    const draft = { attributes: { instrumentClasses: ['BOND_FUNDS'] }, effectiveFrom: null, effectiveTo: null }
    const scheduled = withCatalogUtcEffective(draft, 'effectiveFrom', '2026-11-15T00:00')
    expect(scheduled).toEqual({ ...draft, effectiveFrom: '2026-11-15T00:00:00Z' })
    expect(catalogUtcEffectiveInput(scheduled.effectiveFrom)).toBe('2026-11-15T00:00:00')
    expect(catalogUtcEffectiveInput('2026-11-15T00:00:00.123Z')).toBe('2026-11-15T00:00:00')
    expect(withCatalogUtcEffective(scheduled, 'effectiveTo', '')).toEqual(scheduled)
    expect(() => withCatalogUtcEffective(draft, 'effectiveFrom', '2026-02-30T00:00')).toThrow()
    expect(draft.effectiveFrom).toBeNull()
  })
})
