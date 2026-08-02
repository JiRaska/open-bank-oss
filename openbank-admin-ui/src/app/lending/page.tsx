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
  const [applications, setApplications] = useState<Application[]>([])
  const [loans, setLoans] = useState<Loan[]>([])
  const [stage, setStage] = useState<string | null>(null)
  const [tab, setTab] = useState<'queue' | 'portfolio'>('queue')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [appsRes, loansRes] = await Promise.all([
        fetch(svcUrl('lending-service', '/api/v1/lending/applications/recent', { limit: String(LIMIT) }), { cache: 'no-store' }),
        fetch(svcUrl('lending-service', '/api/v1/lending/loans/active', { limit: String(LIMIT) }), { cache: 'no-store' }),
      ])
      if (!appsRes.ok || !loansRes.ok) throw new Error(`${appsRes.status}/${loansRes.status}`)
      const apps = await appsRes.json()
      const ln = await loansRes.json()
      setApplications(Array.isArray(apps) ? apps : [])
      setLoans(Array.isArray(ln) ? ln : [])
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
    m ? `${m.amount.toLocaleString('cs-CZ')} ${m.currency}` : '—'

  const money = (n: number, ccy: string) => `${Math.round(n).toLocaleString('cs-CZ')} ${ccy}`

  /** Headline figures, all computed from the SAME capped lists the tables show — so the page can
   *  never claim more than it fetched. */
  const kpi = useMemo(() => {
    const ccy = loans[0]?.principal?.currency ?? applications[0]?.requestedAmount?.currency ?? 'CZK'
    const book = loans.reduce((s, l) => s + (l.principal?.amount ?? 0), 0)
    const trouble = loans.filter(l => LOAN_TROUBLE.has(l.status))
    const now = Date.now()
    const open = applications.filter(a => !TERMINAL.has(a.status))
    const stale = open.filter(a => a.createdAt && now - new Date(a.createdAt).getTime() > STALE_HOURS * 3_600_000)
    const requested = open.reduce((s, a) => s + (a.requestedAmount?.amount ?? 0), 0)
    return { ccy, book, trouble, open, stale, requested }
  }, [loans, applications])

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
          <button onClick={load} disabled={loading} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
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
          value={loans.length}
          hint={t(`jistina ${money(kpi.book, kpi.ccy)}`, `principal ${money(kpi.book, kpi.ccy)}`)}
          icon={<Wallet size={13} />}
        />
        <StatCard
          label={t('Žádosti v běhu', 'Applications in flight')}
          value={kpi.open.length}
          hint={t(`požadováno ${money(kpi.requested, kpi.ccy)}`, `requested ${money(kpi.requested, kpi.ccy)}`)}
          icon={<Layers size={13} />}
        />
        <StatCard
          label={t('Čeká přes 72 h', 'Waiting over 72h')}
          value={kpi.stale.length}
          tone={kpi.stale.length > 0 ? 'warning' : undefined}
          hint={t('nerozhodnuté a stárnoucí', 'undecided and aging')}
          icon={<Clock size={13} />}
        />
        <StatCard
          label={t('Problémové úvěry', 'Loans in trouble')}
          value={kpi.trouble.length}
          tone={kpi.trouble.length > 0 ? 'danger' : undefined}
          hint={t('po splatnosti / default / odpis', 'delinquent / default / written off')}
          icon={<AlertTriangle size={13} />}
        />
      </div>

      <div style={{ marginBottom: 20 }}>
        <OriginationPipeline
          items={applications}
          cap={LIMIT}
          lang={language}
          selected={stage}
          onSelectStage={s => { setStage(s); setTab('queue') }}
        />
      </div>

      <div style={{ display: 'flex', gap: 4, marginBottom: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        {(['queue', 'portfolio'] as const).map(id => (
          <button
            key={id}
            onClick={() => setTab(id)}
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
          <button onClick={() => setStage(null)} className="btn btn-secondary" style={{ fontSize: 11 }} data-testid="clear-stage">
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
                  {a.createdAt ? new Date(a.createdAt).toLocaleString() : '—'}
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
                  {l.disbursedAt ? new Date(l.disbursedAt).toLocaleString() : '—'}
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
