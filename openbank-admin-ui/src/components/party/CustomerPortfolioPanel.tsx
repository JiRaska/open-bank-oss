// SPDX-License-Identifier: Apache-2.0

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { Landmark, ShieldAlert, WalletCards } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl, type BffFailure } from '@/lib/services/bff'

type Source = 'accounts' | 'lending' | 'aml'
type SourceState = { kind: 'loading' } | { kind: 'ok'; count: number; statuses: string[] } | { kind: 'unknown'; why: BffFailure }
type State = Record<Source, SourceState>

const initial = (): State => ({ accounts: { kind: 'loading' }, lending: { kind: 'loading' }, aml: { kind: 'loading' } })

function rows(body: unknown): Record<string, unknown>[] {
  if (Array.isArray(body)) return body as Record<string, unknown>[]
  const obj = body as { data?: unknown[]; items?: unknown[]; content?: unknown[] }
  return (obj?.data ?? obj?.items ?? obj?.content ?? []) as Record<string, unknown>[]
}

export function CustomerPortfolioPanel({ partyId }: { partyId: string }) {
  const { t } = useLanguage()
  const [state, setState] = useState<State>(initial)

  useEffect(() => {
    let live = true
    const sources: { source: Source; url: string }[] = [
      { source: 'accounts', url: svcUrl('account-service', '/api/v1/accounts', { partyId, limit: '100' }) },
      { source: 'lending', url: svcUrl('lending-service', '/api/v1/lending/applications', { partyId }) },
      { source: 'aml', url: svcUrl('aml-service', '/api/v1/aml/cases', { partyId, limit: '100', offset: '0' }) },
    ]
    void Promise.all(sources.map(async ({ source, url }) => {
      try {
        const res = await fetch(url, { cache: 'no-store' })
        if (!res.ok) {
          const why = await classifyBffFailure(res)
          if (live) setState(s => ({ ...s, [source]: { kind: 'unknown', why } }))
          return
        }
        const list = rows(await res.json())
        const statuses = [...new Set(list.map(r => String(r.status ?? r.state ?? '')).filter(Boolean))]
        if (live) setState(s => ({ ...s, [source]: { kind: 'ok', count: list.length, statuses } }))
      } catch {
        if (live) setState(s => ({ ...s, [source]: { kind: 'unknown', why: 'unreachable' } }))
      }
    }))
    return () => { live = false }
  }, [partyId])

  const cards: { source: Source; title: string; href: string; Icon: typeof WalletCards }[] = [
    { source: 'accounts', title: t('Účty', 'Accounts'), href: '/accounts', Icon: WalletCards },
    { source: 'lending', title: t('Úvěrové žádosti', 'Loan applications'), href: '/lending', Icon: Landmark },
    { source: 'aml', title: t('AML případy', 'AML cases'), href: '/aml', Icon: ShieldAlert },
  ]

  return <div className="card" style={{ padding: '16px 20px', marginBottom: 20 }}>
    <h2 className="section-title" style={{ marginBottom: 4 }}>{t('Autoritativní portfolio a riziko', 'Authoritative portfolio and risk')}</h2>
    <p style={{ margin: '0 0 12px', fontSize: 11, color: 'var(--text-secondary)' }}>{t('Každá karta se načítá přímo z vlastnící služby a degraduje nezávisle; nejde o analytickou projekci.', 'Each card loads from its owning service and degrades independently; this is not an analytics projection.')}</p>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: 12 }}>
      {cards.map(({ source, title, href, Icon }) => {
        const value = state[source]
        return <Link href={href} key={source} className="card" style={{ padding: 14, textDecoration: 'none', color: 'inherit' }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 12, color: 'var(--text-secondary)' }}><Icon size={15} /> {title}</div>
          {value.kind === 'loading' && <div style={{ marginTop: 8, color: 'var(--text-tertiary)' }}>{t('Načítám…', 'Loading…')}</div>}
          {value.kind === 'ok' && <><div style={{ fontSize: 24, fontWeight: 800, marginTop: 6 }}>{value.count}</div><div style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>{value.statuses.join(' · ') || t('bez stavů', 'no statuses')}</div></>}
          {value.kind === 'unknown' && <div style={{ marginTop: 8, fontSize: 11, color: '#b45309' }}>{t('Nelze zjistit', 'Unavailable')} · {value.why}</div>}
        </Link>
      })}
    </div>
  </div>
}
