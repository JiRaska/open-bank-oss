// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseMerchantCataloguePage, parseUnmatchedMerchantDescriptors } from '@/lib/merchants/merchantCatalogueContract'

const row = { descriptorKey: 'BILLA', cleanName: 'Billa', logoUrl: null, logoContentHash: null, category: 'GROCERIES', lat: 50.0755, lon: 14.4378, city: 'Praha', country: 'CZ', updatedAt: '2026-09-09T08:00:00Z' }

describe('merchant catalogue response contract', () => {
  it('preserves the complete catalogue page and worklist evidence', () => {
    expect(parseMerchantCataloguePage({ data: [row], total: 51 }, 50)).toMatchObject({ total: 51, data: [{ descriptorKey: 'BILLA' }] })
    expect(parseUnmatchedMerchantDescriptors([{ descriptorKey: 'ALZACZ', occurrences: 42 }])).toEqual([{ descriptorKey: 'ALZACZ', occurrences: 42 }])
  })
  it.each([[{ ...row, cleanName: '' }, 'cleanName'], [{ ...row, lon: null }, 'location'], [{ ...row, lat: 91 }, 'location'], [{ ...row, updatedAt: 'not-a-date' }, 'updatedAt']])('rejects malformed catalogue rows', (candidate, message) => {
    expect(() => parseMerchantCataloguePage({ data: [candidate], total: 1 }, 50)).toThrow(message)
  })
  it('rejects impossible page windows and unmatched counts', () => {
    expect(() => parseMerchantCataloguePage({ data: [row], total: 0 }, 50)).toThrow('window')
    expect(() => parseUnmatchedMerchantDescriptors([{ descriptorKey: 'ALZACZ', occurrences: 0 }])).toThrow('occurrences')
  })
})
