// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 D3: the SDD console — the fleet-wide mandate queue (newest first, optional
// status filter incl. the B2B PENDING_CONFIRMATION confirmations). Read-only: confirm,
// suspend, resume and cancel are lifecycle decisions for the governed flows, not buttons
// on this page.

'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { RefreshCw, Repeat } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { parseSddMandates, type SddMandate } from '@/lib/sdd/sddMandateContract'
import { AuthGuard } from '@/components/auth/AuthGuard'

// Mirrors MandateStatus in sdd-service — an omitted member is a filter an operator cannot select.
const STATUSES = ['', 'PENDING_CONFIRMATION', 'ACTIVE', 'SUSPENDED', 'CANCELLED', 'EXPIRED'] as const

export default function SddPage() {
  const { t, language } = useLanguage()
  const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [status, setStatus] = useState('')
  const [rows, setRows] = useState<SddMandate[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const requestRef = useRef<AbortController | null>(null)
  const snapshotStatusRef = useRef<string | null>(null)

  const load = useCallback(async () => {
    requestRef.current?.abort()
    const controller = new AbortController()
    requestRef.current = controller
    setLoading(true)
    if (snapshotStatusRef.current !== status) {
      setRows([])
      setUnavailable(null)
    }
    const deadline = setTimeout(() => controller.abort(), 8_000)
    try {
      const res = await fetch(svcUrl('sdd-service', '/api/v1/sdd/mandates/recent', {
        limit: '50', ...(status ? { status } : {}),
      }), { cache: 'no-store', signal: controller.signal })
      if (requestRef.current !== controller) return
      if (!res.ok) {
        const kind = res.status === 401 || res.status === 403 ? 'unauthorized' : await classifyBffFailure(res)
        if (requestRef.current !== controller) return
        if (kind === 'unauthorized') {
          setRows([])
          snapshotStatusRef.current = null
        }
        setUnavailable({ kind })
        return
      }
      const data = await res.json() as unknown
      if (requestRef.current !== controller) return
      setRows(parseSddMandates(data))
      snapshotStatusRef.current = status
      setUnavailable(null)
    } catch {
      if (requestRef.current === controller) setUnavailable({ kind: 'unreachable' })
    } finally {
      clearTimeout(deadline)
      if (requestRef.current === controller) {
        requestRef.current = null
        setLoading(false)
      }
    }
  }, [status])

  useEffect(() => {
    void load()
    return () => {
      const activeRequest = requestRef.current
      requestRef.current = null
      activeRequest?.abort()
    }
  }, [load])

  return (
    <AuthGuard permission="payment-rails:view">
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

      {!unavailable && <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 14 }}>
        <label htmlFor="sdd-status-filter" style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-secondary)' }}>
          {t('Stav mandátu', 'Mandate status')}
        </label>
        <select
          id="sdd-status-filter"
          value={status}
          onChange={e => setStatus(e.target.value)}
          style={{ fontSize: 12, padding: '4px 8px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)' }}
          aria-label={t('Filtr stavu', 'Status filter')}
        >
          {STATUSES.map(s => <option key={s} value={s}>{s === '' ? t('Všechny stavy', 'All statuses') : s}</option>)}
        </select>
      </div>}

      <div className="card" style={{ padding: 0, overflowX: 'auto' }} tabIndex={0} role="region" aria-label={t('Posuvná tabulka mandátů', 'Scrollable mandate table')}>
        {unavailable && <>
          <DataUnavailable kind={unavailable.kind} service="sdd-service" feature={t('Mandáty inkas', 'Direct debit mandates')} lang={language} dense={rows.length > 0} />
          {rows.length > 0 && <p role="status" aria-live="polite" style={{ margin: '6px 14px 12px', color: 'var(--text-tertiary)', fontSize: 11 }}>
            {t('Zobrazen je poslední ověřený snapshot pro tento filtr; stav mandátů se mohl změnit.', 'Showing the last verified snapshot for this filter; mandate status may have changed.')}
          </p>}
        </>}
        {(!unavailable || rows.length > 0) && (
        <table style={{ width: '100%', minWidth: 900, borderCollapse: 'collapse', fontSize: 13 }}>
          <caption className="sr-only">{t('Přehled mandátů inkas', 'Direct debit mandate overview')}</caption>
          <thead>
            <tr style={{ background: 'var(--surface-2)', textAlign: 'left' }}>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>UMR</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Věřitel', 'Creditor')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Dlužník', 'Debtor')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Schéma / sekvence', 'Scheme / sequence')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Stav', 'Status')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Aktivita', 'Activity')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(m => {
              return (
                <tr key={m.id} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ padding: '10px 14px', fontFamily: 'var(--font-mono)', fontSize: 12 }}>{m.umr}</td>
                  <td style={{ padding: '10px 14px' }}>
                    <div style={{ fontWeight: 600 }}>{m.creditorName}</div>
                    <div style={{ marginTop: 2, fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-tertiary)' }}>{m.creditorIdentifier}</div>
                  </td>
                  <td style={{ padding: '10px 14px' }}>
                    <div>{m.debtorName}</div>
                    <div style={{ marginTop: 2, fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-tertiary)' }}>{m.debtorIban}</div>
                  </td>
                  <td style={{ padding: '10px 14px' }}>
                    <div style={{ fontWeight: 600 }}>{m.scheme} · {m.sequenceType}</div>
                    {m.scheme === 'B2B' && <div style={{ marginTop: 3, fontSize: 11, color: m.b2bConfirmed ? 'var(--success)' : 'var(--warning)' }}>
                      {m.b2bConfirmed ? t('B2B potvrzeno', 'B2B confirmed') : t('B2B čeká na potvrzení', 'B2B confirmation pending')}
                    </div>}
                  </td>
                  <td style={{ padding: '10px 14px' }}>
                    <StatusBadge status={m.status} />
                  </td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-tertiary)', fontSize: 12 }}>
                    <div>{t('Podepsáno', 'Signed')}: {formatDate(m.signatureDate, dateLocale)}</div>
                    <div style={{ marginTop: 2 }}>{t('Poslední inkaso', 'Last collection')}: {m.lastCollectionDate ? formatDate(m.lastCollectionDate, dateLocale) : '—'}</div>
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
    </AuthGuard>
  )
}

function formatDate(value: string, locale: string): string {
  return new Date(`${value}T00:00:00Z`).toLocaleDateString(locale, { timeZone: 'UTC' })
}
