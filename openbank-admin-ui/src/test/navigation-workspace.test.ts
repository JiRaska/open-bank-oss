// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { ALL_NAV, currentNavigation, navigationAreas, searchNavigation, visibleNavigation } from '@/lib/navigation/catalog'
import { parsePreferences } from '@/lib/navigation/preferences'

describe('shared workspace navigation', () => {
  it('finds Czech, English and operator vocabulary without diacritics', () => {
    for (const query of ['ucty', 'ÚČTY', 'accounts', 'iban']) {
      expect(searchNavigation(query, ['ROLE_ADMIN']).some(item => item.href === '/accounts')).toBe(true)
    }
    expect(searchNavigation('klienti', ['ROLE_OPERATOR'])[0].href).toBe('/parties')
    expect(searchNavigation('ledger', ['ROLE_ADMIN'])[0].href).toBe('/ledger')
  })
  it('ranks exact names first and never searches inaccessible or demo-locked destinations', () => {
    expect(searchNavigation('Účty', ['ROLE_ADMIN'])[0].href).toBe('/accounts')
    expect(searchNavigation('Konfigurace', ['ROLE_VIEWER'])).toEqual([])
    expect(searchNavigation('Alertmanager', ['ROLE_ADMIN', 'ROLE_DEMO'])).toEqual([])
  })
  it('keeps every visible destination discoverable through canonical ownership or the overview', () => {
    const areas = navigationAreas(['ROLE_ADMIN'], { account: 'core', party: 'identity' })
    const represented = new Set(areas.flatMap(area => area.items.map(item => item.href)))
    represented.add('/dashboard')
    expect(visibleNavigation(['ROLE_ADMIN']).filter(item => !represented.has(item.href))).toEqual([])
    expect(areas.find(area => area.id === 'core')?.items.some(item => item.href === '/accounts')).toBe(true)
    expect(currentNavigation('/lending/risk/example')?.href).toBe('/lending/risk')
    expect(new Set(ALL_NAV.map(item => item.href)).size).toBe(ALL_NAV.length)
  })
  it('rejects malformed storage, external redirects and pins outside the catalog', () => {
    expect(parsePreferences('oops').pins).toEqual([])
    const parsed = parsePreferences(JSON.stringify({ width: 999, pins: [
      { href: 'https://example.test', pathname: '/accounts', label: 'External' },
      { href: '/accounts/other', pathname: '/accounts', label: 'Wrong path' },
      { href: '/unknown', pathname: '/unknown', label: 'Unknown' },
      { href: '/accounts?status=ACTIVE&view=saved', pathname: '/accounts', label: 'Active accounts' },
    ], recentPages: ['/accounts', '/unknown'] }))
    expect(parsed.pins.map(pin => pin.label)).toEqual(['Active accounts'])
    expect(parsed.width).toBe(360)
    expect(parsed.recentPages).toEqual(['/accounts'])
  })
})
