// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { catalogRevisionEditorDocument, diffCatalogDocuments } from '@/lib/catalog-structural-diff'

describe('catalog structural diff', () => {
  it('reports deterministic added removed and changed paths', () => {
    expect(diffCatalogDocuments(
      { name: { en: 'Live' }, obsolete: true, prices: [{ value: '1.00' }] },
      { name: { en: 'Draft' }, added: 'new', prices: [{ value: '1.10' }] },
    )).toEqual([
      { path: '$.added', kind: 'ADDED', after: 'new' },
      { path: '$.name.en', kind: 'CHANGED', before: 'Live', after: 'Draft' },
      { path: '$.obsolete', kind: 'REMOVED', before: true },
      { path: '$.prices[0].value', kind: 'CHANGED', before: '1.00', after: '1.10' },
    ])
  })

  it('returns an empty verdict for identical documents', () => {
    expect(diffCatalogDocuments({ nested: [1, 2] }, { nested: [1, 2] })).toEqual([])
  })

  it('compares a published revision through the same editor envelope without false additions', () => {
    const revision = {
      id: '30000000-0000-0000-0000-000000000001',
      offeringId: '20000000-0000-0000-0000-000000000001',
      number: 1, schemaRef: { id: 'org.openbank.insurance.term-life', version: 1 },
      state: 'PUBLISHED' as const,
      content: { name: { en: 'Term life' }, attributes: {}, prices: [], eligibility: [], relationships: [], documentCodes: [] },
      makerId: 'maker', checkerId: 'checker', reason: 'approved', contentHash: 'a'.repeat(64),
      createdAt: '2026-08-13T00:00:00Z', updatedAt: '2026-08-13T00:00:00Z',
      effectiveFrom: null, effectiveTo: null, revision: 1,
    }
    const live = catalogRevisionEditorDocument(revision)
    expect(diffCatalogDocuments(live, JSON.parse(JSON.stringify(live)))).toEqual([])
  })
})
