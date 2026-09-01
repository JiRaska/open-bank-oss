// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The credit desk's working surface (ADR-0230 D1).
//
// WHAT WAS WRONG WITH THE OLD SCREEN
// Two flat tables of raw enum strings. That answers "which applications exist" — a question a
// database answers. It does not answer the questions an underwriter, a credit-risk analyst or a
// lending officer actually open a console with:
//   - what is waiting on a decision, and how long has it waited
//   - where is the pipeline JAMMED
//   - how big is the book and what is going wrong in it
// So the primary object is now the STAGE, not the row, and every number is either actionable or
// labelled as not-a-total. Rows stay, one level down, filtered by clicking a stage.
//
// HONESTY ABOUT THE NUMBERS
// `/applications/recent` and `/loans/active` are capped lists (the server clamps limit to 1..100).
// Nothing here may present a capped count as a book total: "12 waiting" when 300 wait is a staffing
// decision made on a wrong number. The cap travels with the figure, and a full page is called out.
//
// Mutations stay absent, as before: decisions, disbursements and write-offs live in the approval
// inbox (ADR-0227 D4), and the per-application screen links there.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { RefreshCw, TrendingUp, Layers, Wallet, AlertTriangle, Clock } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { EntityChip } from '@/components/entities/EntityChip'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import { STATE_LABELS } from '@/components/lending/OriginationFlow'
import { OriginationPipeline, type PipelineItem } from '@/components/lending/OriginationPipeline'

type Application = PipelineItem & { partyId: string }

/** `/applications/summary` and `/loans/summary` (#3294). Money is a list per currency, never one
 *  number — the service refuses to add CZK to EUR and so must the console. */
type MoneyTotal = { currency: string; amount: number }
type StateSummary = {
  status: string
  count: number
  oldestCreatedAt?: string | null
  requested?: MoneyTotal[]
  principal?: MoneyTotal[]
}

type Loan = {
  id: string
  partyId: string
  status: string
  principal?: { amount: number; currency: string }
  disbursedAt?: string
}

/** The server clamps to 100; ask for it explicitly so the cap is a known number rather than a
 *  silent default we would then have to guess at when labelling the figures. */
const LIMIT = 100

/** Terminal origination states — an application here is not waiting on anybody. */
const TERMINAL = new Set(['DISBURSED', 'WITHDRAWN', 'DECLINED', 'EXPIRED'])

/** Loan states that are a problem rather than a stage. Kept small on purpose — a console that
 *  tints everything tints nothing. */
const LOAN_TROUBLE = new Set(['DELINQUENT', 'DEFAULTED', 'WRITTEN_OFF'])

const STALE_HOURS = 72

export default function LendingPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const dateLocale = numberLocale
  const [applications, setApplications] = useState<Application[]>([])
  const [loans, setLoans] = useState<Loan[]>([])
  // Absent = the aggregate endpoints are not in the deployed build yet. The page then falls back to
  // deriving from the capped lists AND keeps saying so, which is what it did before they existed.
  const [appSummary, setAppSummary] = useState<StateSummary[] | null>(null)
  const [loanSummary, setLoanSummary] = useState<StateSummary[] | null>(null)
  const [stage, setStage] = useState<string | null>(null)
  const [tab, setTab] = useState<'queue' | 'portfolio'>('queue')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      // The lists still load: the rows are the drill-down, and the summaries carry no identities.
      // The two summary calls are allowed to fail independently — they are newer than the deployed
      // service in an environment that has not rolled forward, and a missing aggregate must not
      // take the console with it.
      const okJson = (r: Response) => (r.ok ? r.json() : null)
      const [appsRes, loansRes, appSum, loanSum] = await Promise.all([
        fetch(svcUrl('lending-service', '/api/v1/lending/applications/recent', { limit: String(LIMIT) }), { cache: 'no-store' }),
        fetch(svcUrl('lending-service', '/api/v1/lending/loans/active', { limit: String(LIMIT) }), { cache: 'no-store' }),
        fetch(svcUrl('lending-service', '/api/v1/lending/applications/summary'), { cache: 'no-store' })
          .then(okJson).catch(() => null),
        fetch(svcUrl('lending-service', '/api/v1/lending/loans/summary'), { cache: 'no-store' })
          .then(okJson).catch(() => null),
      ])
      if (!appsRes.ok || !loansRes.ok) throw new Error(`${appsRes.status}/${loansRes.status}`)
      const apps = await appsRes.json()
      const ln = await loansRes.json()
      setApplications(Array.isArray(apps) ? apps : [])
      setLoans(Array.isArray(ln) ? ln : [])
      setAppSummary(Array.isArray(appSum) ? appSum : null)
      setLoanSummary(Array.isArray(loanSum) ? loanSum : null)
      setError(null)
    } catch {
      setError('unreachable')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const label = (s: string) => {
    const l = STATE_LABELS[s]
    return l ? (language === 'cs' ? l.cs : l.en) : s
  }

  const fmt = (m?: { amount: number; currency: string }) =>
    m ? `${m.amount.toLocaleString(numberLocale)} ${m.currency}` : '—'

  const money = (n: number, ccy: string) => `${Math.round(n).toLocaleString(numberLocale)} ${ccy}`

  /** Headline figures, all computed from the SAME capped lists the tables show — so the page can
   *  never claim more than it fetched. */
  /** Headline figures. When the aggregate endpoints answer, these are the WHOLE book; otherwise
   *  they are derived from the capped lists and the page says so. The two are never mixed — a
   *  half-real total is worse than an honestly capped one, because nothing on screen distinguishes
   *  them. */
  const kpi = useMemo(() => {
    const ccy = loans[0]?.principal?.currency ?? applications[0]?.requestedAmount?.currency ?? 'CZK'
    const sumFor = (rows: StateSummary[], pick: (r: StateSummary) => MoneyTotal[] | undefined) =>
      rows.flatMap(r => pick(r) ?? []).filter(m => m.currency === ccy).reduce((s, m) => s + m.amount, 0)

    if (appSummary && loanSummary) {
      const openStates = appSummary.filter(r => !TERMINAL.has(r.status))
      // eslint-disable-next-line react-hooks/purity -- staleness comparison is inherently time-relative; the timestamps are stable server data.
      const now = Date.now()
      return {
        exact: true,
        ccy,
        // The label says "active", so count ACTIVE — the aggregate carries every status, and
        // silently folding delinquent and defaulted loans in here would both change what the tile
        // means and double-count them against the "in trouble" tile beside it.
        loanCount: loanSummary.filter(r => !LOAN_TROUBLE.has(r.status)).reduce((s, r) => s + r.count, 0),
          // Exposure, in contrast, IS the whole book: a delinquent loan is still money lent out.
      book: sumFor(loanSummary, r => r.principal),
        openCount: openStates.reduce((s, r) => s + r.count, 0),
        requested: sumFor(openStates, r => r.requested),
        // Aging is per STATE here, not per application: the aggregate carries the oldest timestamp
        // per state, which is enough to say "something has been waiting too long" and is honest
        // about not being a row count.
        staleStates: openStates.filter(
          r => r.oldestCreatedAt && now - new Date(r.oldestCreatedAt).getTime() > STALE_HOURS * 3_600_000,
        ).length,
        trouble: loanSummary.filter(r => LOAN_TROUBLE.has(r.status)).reduce((s, r) => s + r.count, 0),
      }
    }

    const book = loans.reduce((s, l) => s + (l.principal?.amount ?? 0), 0)
    const trouble = loans.filter(l => LOAN_TROUBLE.has(l.status))
    // eslint-disable-next-line react-hooks/purity -- staleness comparison is inherently time-relative; the timestamps are stable server data.
    const now = Date.now()
    const open = applications.filter(a => !TERMINAL.has(a.status))
    const stale = open.filter(a => a.createdAt && now - new Date(a.createdAt).getTime() > STALE_HOURS * 3_600_000)
    return {
      exact: false,
      ccy,
      loanCount: loans.length,
      book,
      openCount: open.length,
      requested: open.reduce((s, a) => s + (a.requestedAmount?.amount ?? 0), 0),
      staleStates: stale.length,
      trouble: trouble.length,
    }
  }, [loans, applications, appSummary, loanSummary])

  const visibleApps = useMemo(
    () => (stage ? applications.filter(a => a.status === stage) : applications),
    [applications, stage],
  )

  const th = { padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' } as const
  const td = { padding: '10px 14px' } as const

  return (
    <div>
      <PageHeader
        title={t('Úvěrová konzole', 'Lending console')}
        subtitle={t(
          'Kde stojí pipeline, co čeká na rozhodnutí a jak je na tom portfolio. Rozhodnutí, čerpání a odpisy se schvalují ve frontě schvalování (ADR-0227).',
          'Where the pipeline stands, what waits on a decision, and how the book is doing. Decisions, disbursements and write-offs are approved in the approval inbox (ADR-0227).',
        )}
        icon={<TrendingUp size={18} style={{ color: 'var(--accent)' }} />}
        actions={
          <button onClick={load} disabled={loading} type="button" aria-busy={loading}
            aria-label={t('Obnovit lending', 'Refresh lending')} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
            <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
          </button>
        }
      />

      {error && (
        <div className="card" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--danger)', color: 'var(--danger)', fontSize: 13 }}>
          {t('lending-service je nedostupný.', 'lending-service is unreachable.')}
        </div>
      )}

      <div className="grid-4" style={{ marginBottom: 20 }}>
        <StatCard
          label={t('Aktivní úvěry', 'Active loans')}
          value={kpi.loanCount}
          hint={t(`jistina ${money(kpi.book, kpi.ccy)}`, `principal ${money(kpi.book, kpi.ccy)}`)}
          icon={<Wallet size={13} />}
        />
        <StatCard
          label={t('Žádosti v běhu', 'Applications in flight')}
          value={kpi.openCount}
          hint={t(`požadováno ${money(kpi.requested, kpi.ccy)}`, `requested ${money(kpi.requested, kpi.ccy)}`)}
          icon={<Layers size={13} />}
        />
        <StatCard
          label={t('Čeká přes 72 h', 'Waiting over 72h')}
          value={kpi.staleStates}
          tone={kpi.staleStates > 0 ? 'warning' : undefined}
          hint={kpi.exact
            ? t('stavů se stárnoucí frontou', 'states with an aging queue')
            : t('nerozhodnuté a stárnoucí', 'undecided and aging')}
          icon={<Clock size={13} />}
        />
        <StatCard
          label={t('Problémové úvěry', 'Loans in trouble')}
          value={kpi.trouble}
          tone={kpi.trouble > 0 ? 'danger' : undefined}
          hint={t('po splatnosti / default / odpis', 'delinquent / default / written off')}
          icon={<AlertTriangle size={13} />}
        />
      </div>

      <div style={{ marginBottom: 20 }}>
        <OriginationPipeline
          items={applications}
          cap={LIMIT}
          summary={appSummary}
          lang={language}
          selected={stage}
          onSelectStage={s => { setStage(s); setTab('queue') }}
        />
      </div>

      <div style={{ display: 'flex', gap: 4, marginBottom: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        {(['queue', 'portfolio'] as const).map(id => (
          <button
            key={id}
            type="button"
            onClick={() => setTab(id)}
            aria-pressed={tab === id}
            aria-label={id === 'queue' ? t('Zobrazit frontu žádostí', 'Show applications queue') : t('Zobrazit portfolio', 'Show portfolio')}
            style={{
              padding: '6px 12px', fontSize: 12, fontWeight: 600, borderRadius: 6, border: 'none', cursor: 'pointer',
              background: tab === id ? 'var(--accent)' : 'var(--surface-3)',
              color: tab === id ? '#fff' : 'var(--text-secondary)',
            }}
          >
            {id === 'queue' ? t('Fronta žádostí', 'Applications queue') : t('Portfolio', 'Portfolio')}
          </button>
        ))}
        {stage && tab === 'queue' && (
          <button type="button" onClick={() => setStage(null)} className="btn btn-secondary" style={{ fontSize: 11 }} data-testid="clear-stage" aria-label={t('Zrušit filtr fáze', 'Clear stage filter')}>
            {t('Filtr:', 'Filter:')} {label(stage)} ✕
          </button>
        )}
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'var(--surface-2)', textAlign: 'left' }}>
              <th style={th}>{t('Klient', 'Party')}</th>
              <th style={th}>{t('Částka', 'Amount')}</th>
              <th style={th}>{t('Stav', 'Status')}</th>
              <th style={th}>{tab === 'queue' ? t('Podáno', 'Submitted') : t('Čerpáno', 'Disbursed')}</th>
              {tab === 'queue' && <th style={th} />}
            </tr>
          </thead>
          <tbody>
            {tab === 'queue' && visibleApps.map(a => (
              <tr key={a.id} style={{ borderTop: '1px solid var(--border)' }}>
                <td style={td}><EntityChip type="party" id={a.partyId} /></td>
                <td style={{ ...td, fontWeight: 600 }}>{fmt(a.requestedAmount)}</td>
                {/* The human label is what a credit officer reads; the raw state stays as the title
                    so the screen and the machine can never be describing different things. */}
                <td style={td}>
                  <span title={a.status}><StatusBadge status={a.status} label={label(a.status)} /></span>
                </td>
                <td style={{ ...td, color: 'var(--text-tertiary)', fontSize: 12 }}>
                  {a.createdAt ? new Date(a.createdAt).toLocaleString(dateLocale) : '—'}
                </td>
                <td style={td}>
                  <Link href={`/lending/applications/${a.id}`} style={{ color: 'var(--accent)', fontSize: 12 }}>
                    {t('Průběh', 'Progress')} ›
                  </Link>
                </td>
              </tr>
            ))}
            {tab === 'portfolio' && loans.map(l => (
              <tr key={l.id} style={{ borderTop: '1px solid var(--border)' }}>
                <td style={td}><EntityChip type="party" id={l.partyId} /></td>
                <td style={{ ...td, fontWeight: 600 }}>{fmt(l.principal)}</td>
                <td style={td}>
                  <StatusBadge status={l.status} tone={LOAN_TROUBLE.has(l.status) ? 'danger' : undefined} />
                </td>
                <td style={{ ...td, color: 'var(--text-tertiary)', fontSize: 12 }}>
                  {l.disbursedAt ? new Date(l.disbursedAt).toLocaleString(dateLocale) : '—'}
                </td>
              </tr>
            ))}
            {!loading && ((tab === 'queue' && visibleApps.length === 0) || (tab === 'portfolio' && loans.length === 0)) && (
              <tr><td colSpan={tab === 'queue' ? 5 : 4} style={{ padding: 20, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>
                {stage && tab === 'queue'
                  ? t(`Ve stavu „${label(stage)}“ nic není.`, `Nothing in “${label(stage)}”.`)
                  : t('Žádné záznamy', 'No records')}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
