// SPDX-License-Identifier: MPL-2.0
import { describe, it, expect } from 'vitest'

const makeT = (language: 'cs' | 'en') => (csText: string, enText: string) =>
  language === 'cs' ? csText : enText

describe('t() translation function', () => {
  it('returns Czech string when language is cs', () => {
    const t = makeT('cs')
    expect(t('Přehled', 'Dashboard')).toBe('Přehled')
  })

  it('returns English string when language is en', () => {
    const t = makeT('en')
    expect(t('Přehled', 'Dashboard')).toBe('Dashboard')
  })

  it('returns empty string when matching language string is empty', () => {
    const t = makeT('cs')
    expect(t('', 'Fallback')).toBe('')
  })

  it('returns English when cs text is provided but language is en', () => {
    const t = makeT('en')
    expect(t('Platby', 'Payments')).toBe('Payments')
  })

  it('handles special Czech characters correctly', () => {
    const t = makeT('cs')
    expect(t('Účty', 'Accounts')).toBe('Účty')
    expect(t('Transakce', 'Transactions')).toBe('Transakce')
  })
})
