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
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'

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

export default function SddPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [status, setStatus] = useState('')
  const [rows, setRows] = useState<Mandate[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch(svcUrl('sdd-service', '/api/v1/sdd/mandates/recent', {
        limit: '50', ...(status ? { status } : {}),
      }), { cache: 'no-store' })
      if (!res.ok) {
        setUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const data = await res.json() as unknown
      setRows(Array.isArray(data) ? data as Mandate[] : [])
      setUnavailable(null)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [status])

  useEffect(() => { void load() }, [load])

  return (
    <div>
      <PageHeader
        title={t('Mandáty inkas', 'Direct debit mandates')}
        subtitle={t(
          'Čtecí přehled mandátů včetně B2B fronty čekajících na potvrzení. Změny stavu patří do řízených toků.',
          'Read-only mandate view including the B2B confirmation queue. Lifecycle changes belong to governed flows.',
        )}
        icon={<Repeat size={20} style={{ color: 'var(--accent)' }} />}
        actions={<button
          onClick={load}
          disabled={loading}
          type="button"
          aria-busy={loading}
          aria-label={t('Obnovit mandáty inkas', 'Refresh direct debit mandates')}
          className="btn btn-secondary btn-sm"
        >
          <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>}
      />

      {!unavailable && <div style={{ display: 'flex', gap: 4, marginBottom: 14 }}>
        <select
          value={status}
          onChange={e => setStatus(e.target.value)}
          style={{ fontSize: 12, padding: '4px 8px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)' }}
          aria-label={t('Filtr stavu', 'Status filter')}
        >
          {STATUSES.map(s => <option key={s} value={s}>{s === '' ? t('Všechny stavy', 'All statuses') : s}</option>)}
        </select>
      </div>}

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {unavailable && <DataUnavailable kind={unavailable.kind} service="sdd-service" feature={t('Mandáty inkas', 'Direct debit mandates')} lang={language} dense={rows.length > 0} />}
        {(!unavailable || rows.length > 0) && (
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
              return (
                <tr key={m.id} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ padding: '10px 14px', fontFamily: 'var(--font-mono)', fontSize: 12 }}>{m.umr ?? m.id.slice(0, 8)}</td>
                  <td style={{ padding: '10px 14px' }}>{m.creditorName ?? '—'}</td>
                  <td style={{ padding: '10px 14px', fontFamily: 'var(--font-mono)', fontSize: 12 }}>{m.debtorIban ?? '—'}</td>
                  <td style={{ padding: '10px 14px' }}>{m.scheme ?? '—'}</td>
                  <td style={{ padding: '10px 14px' }}>
                    <StatusBadge status={m.status} />
                  </td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-tertiary)', fontSize: 12 }}>
                    {m.createdAt ? new Date(m.createdAt).toLocaleString(dateLocale) : '—'}
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
        )}
      </div>
    </div>
  )
}
