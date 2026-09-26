// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('transactions search contract', () => {
  it('exposes localized busy semantics without changing search flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/transactions/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("t('Vyhledávání transakcí', 'Searching transactions')")
    expect(source).toContain("t('Vyhledat transakce', 'Search transactions')")
    expect(source).toContain('onClick={() => search()}')
  })

  it('does not offer or send the unsupported channel filter', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/transactions/page.tsx'), 'utf8')
    expect(source).not.toContain('transaction-channel')
    expect(source).not.toContain("params.set('channel'")
  })

  it('does not invent an account-relative sign in the global ledger', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/transactions/page.tsx'), 'utf8')
    expect(source).not.toContain("tx.type === 'DEBIT' ? '-' : '+'")
    expect(source).toContain("fontVariantNumeric: 'tabular-nums'")
  })
})
