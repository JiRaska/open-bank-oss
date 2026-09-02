// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0230 D2: the fraud analyst console — the REVIEW queue that used to vanish into the
// evidence store. Read-only: a verdict is evidence, and its resolution is a compliance
// decision, not a click on this page (maker-checker flow, ADR-0227).

'use client'

import { useCallback, useEffect, useState } from 'react'
import { RefreshCw, ShieldAlert, CircleAlert, Clock3 } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge, type Tone } from '@/components/ui'

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

function scoreTone(score: number): Tone {
  if (score >= 80) return 'danger'
  if (score >= 50) return 'warning'
  return 'success'
}

export default function FraudPage() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [rows, setRows] = useState<ScoredRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [hasSnapshot, setHasSnapshot] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch(svcUrl('fraud-service', '/api/v1/fraud/review-queue', { limit: '50' }), { cache: 'no-store' })
      if (!res.ok) {
        setUnavailable({ kind: await classifyBffFailure(res) })
        return
      }
      const data = await res.json() as unknown
      setRows(Array.isArray(data) ? data as ScoredRecord[] : [])
      setHasSnapshot(true)
      setLastUpdatedAt(new Date())
      setUnavailable(null)
    } catch {
      setUnavailable({ kind: 'unreachable' })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const critical = rows.filter(row => row.score >= 80).length
  const elevated = rows.filter(row => row.score >= 50 && row.score < 80).length
  const showingRetainedSnapshot = unavailable !== null && hasSnapshot

  return (
    <div>
      <PageHeader
        title={t('Fraud review fronta', 'Fraud review queue')}
        subtitle={t(
          'Platby označené enginem jako REVIEW. Rozhodnutí zůstává ve čtyřočkovém compliance toku.',
          'Payments flagged REVIEW by the engine. Resolution remains in the four-eyes compliance flow.',
        )}
        icon={<ShieldAlert size={20} aria-hidden="true" style={{ color: 'var(--accent)' }} />}
        actions={<button type="button" onClick={load} disabled={loading} aria-busy={loading} aria-label={t('Obnovit frontu podvodů', 'Refresh fraud queue')} className="btn btn-secondary btn-sm">
          <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>}
      />

      {(!unavailable || hasSnapshot) && <section style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 16, marginBottom: 16 }} aria-label={t('Souhrn fronty', 'Queue summary')}>
        <StatCard label={t('Čeká na posouzení', 'Awaiting review')} value={rows.length} hint={t('maximálně 50 nejnovějších záznamů', 'up to 50 most recent records')} icon={<Clock3 size={15} />} tone={rows.length ? 'warning' : 'neutral'} />
        <StatCard label={t('Kritické skóre', 'Critical score')} value={critical} hint={t('skóre 80 a více', 'score 80 and above')} icon={<CircleAlert size={15} />} tone={critical ? 'danger' : 'neutral'} />
        <StatCard label={t('Zvýšené skóre', 'Elevated score')} value={elevated} hint={t('skóre 50–79', 'score 50–79')} icon={<ShieldAlert size={15} />} tone={elevated ? 'warning' : 'neutral'} />
      </section>}

      {showingRetainedSnapshot && <div role="status" aria-live="polite" style={{ marginBottom: 16 }}>
        <DataUnavailable kind={unavailable.kind} service="fraud-service" feature={t('Aktualizace fraud review fronty', 'Fraud review queue refresh')} lang={language} dense />
        <p style={{ margin: '6px 0 0', color: 'var(--text-tertiary)', fontSize: 11 }}>
          {t(
            `Zobrazen je poslední úspěšný snapshot z ${lastUpdatedAt?.toLocaleString(numberLocale) ?? '—'}; stav se mohl změnit.`,
            `Showing the last successful snapshot from ${lastUpdatedAt?.toLocaleString(numberLocale) ?? '—'}; the queue may have changed.`,
          )}
        </p>
      </div>}

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        {unavailable && !hasSnapshot ? <DataUnavailable kind={unavailable.kind} service="fraud-service" feature={t('Fraud review fronta', 'Fraud review queue')} lang={language} dense /> : (
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
                    {r.amount.toLocaleString(numberLocale)} {r.currency}
                  </td>
                  <td style={{ padding: '10px 14px' }}><span className="pill">{r.rail}</span></td>
                  <td style={{ padding: '10px 14px', fontFamily: 'var(--font-mono)', fontSize: 11, color: 'var(--text-secondary)' }}>
                    {r.accountId ? `${r.accountId.slice(0, 8)}…` : '—'}
                  </td>
                  <td style={{ padding: '10px 14px' }}>
                    <StatusBadge status={String(r.score)} label={String(r.score)} tone={tone} />
                  </td>
                  <td style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)' }}>
                    {r.ruleVersion}
                  </td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-tertiary)', fontSize: 12 }}>
                    {new Date(r.createdAt).toLocaleString(numberLocale)}
                  </td>
                </tr>
              )
            })}
            {!loading && rows.length === 0 && !showingRetainedSnapshot && (
              <tr><td colSpan={6} style={{ padding: 24, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>
                {t('Fronta je prázdná — žádné REVIEW verdikty.', 'Queue is empty — no REVIEW verdicts.')}
              </td></tr>
            )}
          </tbody>
        </table>
        )}
      </div>
    </div>
  )
}
