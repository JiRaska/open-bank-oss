// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 D3: the SDD console — the fleet-wide mandate queue (newest first, optional
// status filter incl. the B2B PENDING_CONFIRMATION confirmations). Read-only: confirm,
// suspend, resume and cancel are lifecycle decisions for the governed flows, not buttons
// on this page.

'use client'

import { useCallback, useEffect, useState } from 'react'
import { RefreshCw, Repeat } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'

type Mandate = {
  id: string
  umr?: string
  creditorName?: string
  debtorIban?: string
  status: string
  scheme?: string
  createdAt?: string
}

// Mirrors MandateStatus in sdd-service — an omitted member is a filter an operator cannot select.
const STATUSES = ['', 'PENDING_CONFIRMATION', 'ACTIVE', 'SUSPENDED', 'CANCELLED', 'EXPIRED'] as const

const STATUS_TONE: Record<string, { color: string; bg: string }> = {
  ACTIVE: { color: '#059669', bg: '#ecfdf5' },
  PENDING_CONFIRMATION: { color: '#d97706', bg: '#fffbeb' },
  SUSPENDED: { color: '#6b7280', bg: '#f9fafb' },
  CANCELLED: { color: '#dc2626', bg: '#fef2f2' },
}

export default function SddPage() {
  const { t } = useLanguage()
  const [status, setStatus] = useState('')
  const [rows, setRows] = useState<Mandate[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch(svcUrl('sdd-service', '/api/v1/sdd/mandates/recent', {
        limit: '50', ...(status ? { status } : {}),
      }), { cache: 'no-store' })
      if (!res.ok) throw new Error(String(res.status))
      setRows(await res.json())
      setError(null)
    } catch {
      setError('unreachable')
    } finally {
      setLoading(false)
    }
  }, [status])

  useEffect(() => { void load() }, [load])

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Inkasa (SDD)', 'Direct Debits (SDD)')}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Repeat size={18} style={{ color: 'var(--accent)' }} />
            {t('Mandáty inkas', 'Direct debit mandates')}
          </h1>
          <p className="page-subtitle">
            {t(
              'Čtecí přehled mandátů (ADR-0230) včetně B2B fronty čekajících na potvrzení. Změny stavu patří do řízených toků, ne na klik.',
              'Read-only mandate view (ADR-0230) incl. the B2B confirmation queue. Lifecycle changes belong to governed flows, not to a click.',
            )}
          </p>
        </div>
        <button onClick={load} disabled={loading} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>
      </div>

      {error && (
        <div className="card" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--danger)', color: 'var(--danger)', fontSize: 13 }}>
          {t('sdd-service je nedostupný.', 'sdd-service is unreachable.')}
        </div>
      )}

      <div style={{ display: 'flex', gap: 4, marginBottom: 14 }}>
        <select
          value={status}
          onChange={e => setStatus(e.target.value)}
          style={{ fontSize: 12, padding: '4px 8px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)' }}
          aria-label={t('Filtr stavu', 'Status filter')}
        >
          {STATUSES.map(s => <option key={s} value={s}>{s === '' ? t('Všechny stavy', 'All statuses') : s}</option>)}
        </select>
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'var(--surface-2)', textAlign: 'left' }}>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>UMR</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Věřitel', 'Creditor')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Dlužník (IBAN)', 'Debtor (IBAN)')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Schéma', 'Scheme')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Stav', 'Status')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Vytvořeno', 'Created')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(m => {
              const tone = STATUS_TONE[m.status] ?? { color: 'var(--text-secondary)', bg: 'var(--surface-3)' }
              return (
                <tr key={m.id} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ padding: '10px 14px', fontFamily: 'var(--font-mono)', fontSize: 12 }}>{m.umr ?? m.id.slice(0, 8)}</td>
                  <td style={{ padding: '10px 14px' }}>{m.creditorName ?? '—'}</td>
                  <td style={{ padding: '10px 14px', fontFamily: 'var(--font-mono)', fontSize: 12 }}>{m.debtorIban ?? '—'}</td>
                  <td style={{ padding: '10px 14px' }}>{m.scheme ?? '—'}</td>
                  <td style={{ padding: '10px 14px' }}>
                    <span style={{ fontWeight: 700, color: tone.color, background: tone.bg, padding: '2px 8px', borderRadius: 10, fontSize: 11 }}>
                      {m.status}
                    </span>
                  </td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-tertiary)', fontSize: 12 }}>
                    {m.createdAt ? new Date(m.createdAt).toLocaleString() : '—'}
                  </td>
                </tr>
              )
            })}
            {!loading && rows.length === 0 && (
              <tr><td colSpan={6} style={{ padding: 20, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>
                {t('Žádné mandáty', 'No mandates')}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
