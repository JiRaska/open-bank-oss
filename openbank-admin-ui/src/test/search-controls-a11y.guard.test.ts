// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const source = (name: string) => readFileSync(path.resolve(__dirname, `../app/${name}/page.tsx`), 'utf8')

describe('search controls accessibility contract', () => {
  it('names KYC and transaction primary search inputs', () => {
    const kyc = source('kyc')
    expect(kyc).toContain('PartySearch')
    expect(kyc).toContain('id="kyc-search"')
    expect(kyc).toContain('htmlFor="kyc-search"')
    const partySearch = readFileSync(path.resolve(__dirname, '../components/party/PartySearch.tsx'), 'utf8')
    expect(partySearch).toContain('aria-label={t(')
    expect(partySearch).toContain("'Search parties'")
    const transactions = source('transactions')
    for (const id of ['transaction-account-id', 'transaction-iban', 'transaction-bban']) {
      expect(transactions).toContain(`id="${id}"`)
      expect(transactions).toContain(`htmlFor="${id}"`)
    }
  })
})
