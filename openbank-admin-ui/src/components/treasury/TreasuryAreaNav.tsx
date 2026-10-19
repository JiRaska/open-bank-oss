// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { useSession } from 'next-auth/react'
import { hasPermission, type Permission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type Item = { href: string; cs: string; en: string; permission: Permission }
type Area = { id: string; cs: string; en: string; items: Item[] }

/** The workbench's areas (ADR-0344): front office, back office, market data, reference data, reports. */
export const TREASURY_AREAS: Area[] = [
  { id: 'front', cs: 'Front office', en: 'Front office', items: [
    { href: '/treasury/deals', cs: 'Přehled obchodů', en: 'Deal overview', permission: 'treasury:view' },
    { href: '/treasury/deals/new', cs: 'Zadání obchodu', en: 'Deal ticket', permission: 'treasury:deal:create' },
    { href: '/treasury/calendar', cs: 'Kalendář', en: 'Calendar', permission: 'treasury:workbench:view' },
    { href: '/treasury/analysis/cash-flows', cs: 'Analýza toků', en: 'Flow analysis', permission: 'treasury:workbench:view' },
    { href: '/treasury/counterparties', cs: 'Protistrany', en: 'Counterparties', permission: 'treasury:view' },
    { href: '/treasury/limits', cs: 'Limity', en: 'Limits', permission: 'treasury:view' },
  ] },
  { id: 'back', cs: 'Back office', en: 'Back office', items: [
    { href: '/treasury/approvals', cs: 'Fronta čtyř očí', en: 'Four-eyes queue', permission: 'treasury:deal:approve' },
    { href: '/treasury/payments', cs: 'Platby', en: 'Payments', permission: 'treasury:workbench:view' },
    { href: '/treasury/fixings', cs: 'Fixingy', en: 'Fixings', permission: 'treasury:workbench:view' },
    { href: '/treasury/day-completion', cs: 'Uzavření dne', en: 'Day completion', permission: 'treasury:workbench:view' },
    { href: '/treasury/positions', cs: 'Pozice', en: 'Positions', permission: 'treasury:view' },
    { href: '/treasury/nostro', cs: 'Nostro', en: 'Nostro', permission: 'treasury:nostro:read' },
  ] },
  { id: 'market', cs: 'Tržní data', en: 'Market data', items: [
    { href: '/treasury/market-data', cs: 'Faktory a kvalita', en: 'Factors and quality', permission: 'treasury:workbench:view' },
  ] },
  { id: 'reference', cs: 'Referenční data', en: 'Reference data', items: [
    { href: '/treasury/reference', cs: 'Číselníky', en: 'Code lists', permission: 'treasury:workbench:view' },
  ] },
  { id: 'reports', cs: 'Reporty', en: 'Reports', items: [
    { href: '/treasury/reports', cs: 'Reporty a analýzy', en: 'Reports and analyses', permission: 'treasury:workbench:view' },
  ] },
]

export function TreasuryAreaNav() {
  const { t } = useLanguage()
  const { data: session } = useSession()
  const pathname = usePathname() ?? ''
  const roles = session?.user?.roles ?? []
  const areas = TREASURY_AREAS.map(a => ({ ...a, items: a.items.filter(i => hasPermission(roles, i.permission)) })).filter(a => a.items.length > 0)
  if (areas.length === 0) return null
  const current = areas.flatMap(a => a.items).filter(i => pathname === i.href || pathname.startsWith(`${i.href}/`)).sort((a, b) => b.href.length - a.href.length)[0]?.href
  return (
    <nav aria-label={t('Oblasti treasury', 'Treasury areas')} data-testid="treasury-area-nav" style={{ display: 'flex', gap: 16, flexWrap: 'wrap', fontSize: 12, marginBottom: 16, paddingBottom: 8, borderBottom: '1px solid var(--border)' }}>
      {areas.map(a => (
        <div key={a.id} role="group" aria-label={t(a.cs, a.en)} style={{ display: 'flex', gap: 8, alignItems: 'baseline' }}>
          <span style={{ color: 'var(--text-secondary)', textTransform: 'uppercase', fontSize: 10 }}>{t(a.cs, a.en)}</span>
          {a.items.map(i => (
            <Link key={i.href} href={i.href} aria-current={current === i.href ? 'page' : undefined} style={{ fontWeight: current === i.href ? 600 : 400 }}>{t(i.cs, i.en)}</Link>
          ))}
        </div>
      ))}
    </nav>
  )
}
