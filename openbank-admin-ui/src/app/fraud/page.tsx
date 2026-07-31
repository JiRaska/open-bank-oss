// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 D2: the fraud analyst console — the REVIEW queue that used to vanish into the
// evidence store. Read-only: a verdict is evidence, and its resolution is a compliance
// decision, not a click on this page (maker-checker flow, ADR-0227).

'use client'

import { useCallback, useEffect, useState } from 'react'
import { RefreshCw, ShieldAlert } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'

type ScoredRecord = {
  scoreId: string
  amount: number
  currency: string
  rail: string
  accountId: string | null
  counterpartyId: string | null
  verdict: string
  score: number
  ruleVersion: string
  createdAt: string
}

function scoreTone(score: number): { color: string; bg: string } {
  if (score >= 80) return { color: '#dc2626', bg: '#fef2f2' }
  if (score >= 50) return { color: '#d97706', bg: '#fffbeb' }
  return { color: '#059669', bg: '#ecfdf5' }
}

export default function FraudPage() {
  const { t } = useLanguage()
  const [rows, setRows] = useState<ScoredRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch(svcUrl('fraud-service', '/api/v1/fraud/review-queue', { limit: '50' }), { cache: 'no-store' })
      if (!res.ok) throw new Error(String(res.status))
      setRows(await res.json())
      setError(null)
    } catch {
      setError('unreachable')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  return (
    <div>
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Fraud', 'Fraud')}</span>
          </div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <ShieldAlert size={18} style={{ color: 'var(--accent)' }} />
            {t('Fraud review fronta', 'Fraud review queue')}
          </h1>
          <p className="page-subtitle">
            {t(
              'Platby označené enginem jako REVIEW (ADR-0230). Čtecí přehled — rozhodnutí patří do compliance toku, ne na klik.',
              'Payments flagged REVIEW by the engine (ADR-0230). Read-only — resolution belongs to the compliance flow, not to a click.',
            )}
          </p>
        </div>
        <button onClick={load} disabled={loading} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>
      </div>

      {error && (
        <div className="card" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--danger)', color: 'var(--danger)', fontSize: 13 }}>
          {t('fraud-service je nedostupný.', 'fraud-service is unreachable.')}
        </div>
      )}

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'var(--surface-2)', textAlign: 'left' }}>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Částka', 'Amount')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Rail', 'Rail')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Účet', 'Account')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Skóre', 'Score')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Pravidla', 'Rules')}</th>
              <th style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>{t('Čas', 'Time')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => {
              const tone = scoreTone(r.score)
              return (
                <tr key={r.scoreId} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ padding: '10px 14px', fontWeight: 700 }}>
                    {r.amount.toLocaleString('cs-CZ')} {r.currency}
                  </td>
                  <td style={{ padding: '10px 14px' }}><span className="pill">{r.rail}</span></td>
                  <td style={{ padding: '10px 14px', fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-secondary)' }}>
                    {r.accountId ? `${r.accountId.slice(0, 8)}…` : '—'}
                  </td>
                  <td style={{ padding: '10px 14px' }}>
                    <span style={{ fontWeight: 800, color: tone.color, background: tone.bg, padding: '2px 8px', borderRadius: 10, fontSize: 12 }}>
                      {r.score}
                    </span>
                  </td>
                  <td style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)' }}>
                    {r.ruleVersion}
                  </td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-tertiary)', fontSize: 12 }}>
                    {new Date(r.createdAt).toLocaleString()}
                  </td>
                </tr>
              )
            })}
            {!loading && rows.length === 0 && (
              <tr><td colSpan={6} style={{ padding: 24, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>
                {t('Fronta je prázdná — žádné REVIEW verdikty.', 'Queue is empty — no REVIEW verdicts.')}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
