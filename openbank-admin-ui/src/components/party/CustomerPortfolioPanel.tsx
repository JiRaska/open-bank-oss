// SPDX-License-Identifier: Apache-2.0

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { Landmark, RefreshCw, ShieldAlert, WalletCards } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { BffFailure } from '@/lib/services/bff'
import type { PortfolioSummary } from '@/lib/party/portfolioContract'
import { clearSelectedCustomerGraphFacts, loadCustomerGraphFacts } from '@/lib/context/customerGraphClient'

type Source = 'accounts' | 'lending' | 'aml'
type SourceState = { kind: 'loading' } | ({ kind: 'ok' } & PortfolioSummary) | { kind: 'unknown'; why: BffFailure }
type State = Record<Source, SourceState>
const initial = (): State => ({ accounts: { kind: 'loading' }, lending: { kind: 'loading' }, aml: { kind: 'loading' } })

export function CustomerPortfolioPanel({ partyId }: { partyId: string }) {
  const { t } = useLanguage()
  const [state, setState] = useState<State>(initial)
  const [retryKey, setRetryKey] = useState(0)

  useEffect(() => {
    let live = true
    void loadCustomerGraphFacts(partyId).then(facts => {
      if (!live) return
      const values = {
        accounts: facts.accounts,
        lending: facts.lendingApplications,
        aml: facts.amlCases,
      }
      setState(Object.fromEntries((Object.keys(values) as Source[]).map(source => [source,
        facts.unavailable.includes(source)
          ? { kind: 'unknown', why: 'unreachable' }
          : {
              kind: 'ok', count: values[source].length,
              statuses: Array.from(new Set(values[source].map(item => item.status).filter(Boolean))),
              lowerBound: facts.truncated.includes(source),
            },
      ])) as State)
    }).catch(() => {
      if (live) setState({
        accounts: { kind: 'unknown', why: 'unreachable' },
        lending: { kind: 'unknown', why: 'unreachable' },
        aml: { kind: 'unknown', why: 'unreachable' },
      })
    })
    return () => { live = false }
  }, [partyId, retryKey])

  const cards: { source: Source; title: string; href: string; Icon: typeof WalletCards }[] = [
    { source: 'accounts', title: t('Účty', 'Accounts'), href: '/accounts', Icon: WalletCards },
    { source: 'lending', title: t('Úvěrové žádosti', 'Loan applications'), href: '/lending', Icon: Landmark },
    { source: 'aml', title: t('AML případy', 'AML cases'), href: '/aml', Icon: ShieldAlert },
  ]

  const hasUnavailable = Object.values(state).some(value => value.kind === 'unknown')

  return <div className="card" data-customer-portfolio style={{ padding: '16px 20px', marginBottom: 20 }}>
    <div style={{ display: 'flex', alignItems: 'start', justifyContent: 'space-between', gap: 12 }}>
      <h2 className="section-title" style={{ marginBottom: 4 }}>{t('Autoritativní portfolio a riziko', 'Authoritative portfolio and risk')}</h2>
      {hasUnavailable && (
        <button type="button" className="btn btn-secondary" onClick={() => {
          clearSelectedCustomerGraphFacts(partyId)
          setState(initial())
          setRetryKey(key => key + 1)
        }}>
          <RefreshCw size={13} aria-hidden="true" />
          {t('Načíst znovu', 'Retry')}
        </button>
      )}
    </div>
    <p style={{ margin: '0 0 12px', fontSize: 11, color: 'var(--text-secondary)' }}>{t('Každá karta se načítá přímo z vlastnící služby a degraduje nezávisle; nejde o analytickou projekci.', 'Each card loads from its owning service and degrades independently; this is not an analytics projection.')}</p>
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 210px), 1fr))', gap: 12 }}>
      {cards.map(({ source, title, href, Icon }) => {
        const value = state[source]
        return <Link href={href} key={source} className="card" style={{ padding: 14, textDecoration: 'none', color: 'inherit' }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 12, color: 'var(--text-secondary)' }}><Icon size={15} /> {title}</div>
          {value.kind === 'loading' && <div style={{ marginTop: 8, color: 'var(--text-tertiary)' }}>{t('Načítám…', 'Loading…')}</div>}
          {value.kind === 'ok' && <>
            <div aria-label={value.lowerBound ? t(`Nejméně ${value.count}`, `At least ${value.count}`) : undefined} style={{ fontSize: 24, fontWeight: 800, marginTop: 6 }}>{value.count}{value.lowerBound ? '+' : ''}</div>
            <div style={{ fontSize: 10, color: 'var(--text-tertiary)' }}>{value.statuses.join(' · ') || t('bez stavů', 'no statuses')}</div>
            {value.lowerBound && <div style={{ marginTop: 4, fontSize: 10, color: 'var(--text-tertiary)' }}>{t('Nejméně tento počet; služba vrátila plné stránkované okno.', 'At least this many; the service returned a full paginated window.')}</div>}
          </>}
          {value.kind === 'unknown' && <div role="status" style={{ marginTop: 8, fontSize: 11, color: 'var(--warning-text)' }}>{t('Nelze zjistit', 'Unavailable')} · {value.why}</div>}
        </Link>
      })}
    </div>
  </div>
}
