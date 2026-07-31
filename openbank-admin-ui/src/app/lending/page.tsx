// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 D1: the lending console, read views first — the origination queue (recent
// applications fleet-wide) and the active-loan portfolio. Mutations deliberately absent:
// credit decisions, disbursements and write-offs live in the approval inbox (ADR-0227),
// never as direct writes from this page.

'use client'

import { useCallback, useEffect, useState } from 'react'
import { RefreshCw, TrendingUp } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { EntityChip } from '@/components/entities/EntityChip'

type Application = {
  id: string
  partyId: string
  status: string
  requestedAmount?: { amount: number; currency: string }
  createdAt?: string
}

type Loan = {
  id: string
  partyId: string
  status: string
  principal?: { amount: number; currency: string }
  disbursedAt?: string
}

const STATUSES = ['', 'PENDING_REVIEW', 'APPROVED', 'REJECTED'] as const

export default function LendingPage() {
  const { t } = useLanguage()
  const [tab, setTab] = useState<'queue' | 'portfolio'>('queue')
  const [status, setStatus] = useState<string>('')
  const [applications, setApplications] = useState<Application[]>([])
  const [loans, setLoans] = useState<Loan[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [appsRes, loansRes] = await Promise.all([
        fetch(svcUrl('lending-service', '/api/v1/lending/applications/recent', {
          limit: '50', ...(status ? { status } : {}),
        }), { cache: 'no-store' }),
        fetch(svcUrl('lending-service', '/api/v1/lending/loans/active', { limit: '50' }), { cache: 'no-store' }),
      ])
      if (!appsRes.ok || !loansRes.ok) throw new Error(`${appsRes.status}/${loansRes.status}`)
      setApplications(await appsRes.json())
      setLoans(await loansRes.json())
      setError(null)
    } catch {
      setError('unreachable')
    } finally {
      setLoading(false)
    }
  }, [status])

  useEffect(() => { void load() }, [load])

  const fmt = (m?: { amount: number; currency: string }) =>
    m ? `${m.amount.toLocaleString('cs-CZ')} ${m.currency}` : '—'

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Úvěry', 'Lending')}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <TrendingUp size={18} style={{ color: 'var(--accent)' }} />
            {t('Úvěrová konzole', 'Lending console')}
          </h1>
          <p className="page-subtitle">
            {t(
              'Čtecí přehled (ADR-0230). Rozhodnutí, čerpání a odpisy se řeší výhradně přes frontu schvalování (maker-checker).',
              'Read views (ADR-0230). Decisions, disbursements and write-offs are handled exclusively via the approval inbox (maker-checker).',
            )}
          </p>
        </div>
        <button onClick={load} disabled={loading} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>
      </div>

      {error && (
        <div className="card" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--danger)', color: 'var(--danger)', fontSize: 13 }}>
          {t('lending-service je nedostupný.', 'lending-service is unreachable.')}
        </div>
      )}

      <div style={{ display: 'flex', gap: 4, marginBottom: 14 }}>
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
            {id === 'queue' ? t('Fronta žádostí', 'Applications queue') : t('Aktivní úvěry', 'Active loans')}
          </button>
        ))}
        {tab === 'queue' && (
          <select
            value={status}
            onChange={e => setStatus(e.target.value)}
            style={{ marginLeft: 'auto', fontSize: 12, padding: '4px 8px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)' }}
            aria-label={t('Filtr stavu', 'Status filter')}
          >
            {STATUSES.map(s => <option key={s} value={s}>{s === '' ? t('Všechny stavy', 'All statuses') : s}</option>)}
          </select>
        )}
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'var(--surface-2)', textAlign: 'left' }}>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Klient', 'Party')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Částka', 'Amount')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Stav', 'Status')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>
                {tab === 'queue' ? t('Podáno', 'Submitted') : t('Čerpáno', 'Disbursed')}
              </th>
            </tr>
          </thead>
          <tbody>
            {tab === 'queue' && applications.map(a => (
              <tr key={a.id} style={{ borderTop: '1px solid var(--border)' }}>
                <td style={{ padding: '10px 14px' }}><EntityChip type="party" id={a.partyId} /></td>
                <td style={{ padding: '10px 14px', fontWeight: 600 }}>{fmt(a.requestedAmount)}</td>
                <td style={{ padding: '10px 14px' }}><span className="pill">{a.status}</span></td>
                <td style={{ padding: '10px 14px', color: 'var(--text-tertiary)', fontSize: 12 }}>
                  {a.createdAt ? new Date(a.createdAt).toLocaleString() : '—'}
                </td>
              </tr>
            ))}
            {tab === 'portfolio' && loans.map(l => (
              <tr key={l.id} style={{ borderTop: '1px solid var(--border)' }}>
                <td style={{ padding: '10px 14px' }}><EntityChip type="party" id={l.partyId} /></td>
                <td style={{ padding: '10px 14px', fontWeight: 600 }}>{fmt(l.principal)}</td>
                <td style={{ padding: '10px 14px' }}><span className="pill">{l.status}</span></td>
                <td style={{ padding: '10px 14px', color: 'var(--text-tertiary)', fontSize: 12 }}>
                  {l.disbursedAt ? new Date(l.disbursedAt).toLocaleString() : '—'}
                </td>
              </tr>
            ))}
            {!loading && ((tab === 'queue' && applications.length === 0) || (tab === 'portfolio' && loans.length === 0)) && (
              <tr><td colSpan={4} style={{ padding: 20, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>
                {t('Žádné záznamy', 'No records')}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
