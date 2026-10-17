// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

// Day-close participants (ADR-0342): every calendar-driven product service that must report on an
// accounting day before the ledger ties it out. Read from ledger-service through the generic
// /api/svc proxy; a declared participant with no report is shown as MISSING — the state that holds
// the day in CUTOFF once the ledger's participant check is enforced.

import { useCallback, useEffect, useState } from 'react'
import { RefreshCw, Users } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl, type BffFailure } from '@/lib/services/bff'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'

export type ParticipantStatus = 'RAN' | 'SKIPPED' | 'REFUSED' | 'WAIVED' | 'MISSING'

export interface DayCloseParticipant {
  participant: string
  status: ParticipantStatus
  journalsPosted: number
  itemsProcessed: number
  reason: string | null
  reportedBy: string | null
  reportedAt: string | null
  late: boolean
}

export interface DayCloseCompleteness {
  businessDate: string
  complete: boolean
  missing: string[]
  participants: DayCloseParticipant[]
}

const STATUSES: readonly ParticipantStatus[] = ['RAN', 'SKIPPED', 'REFUSED', 'WAIVED', 'MISSING']

/** Narrow an untrusted upstream body; anything malformed is `null` (rendered as an error state). */
export function parseDayCloseCompleteness(raw: unknown): DayCloseCompleteness | null {
  if (!raw || typeof raw !== 'object') return null
  const body = raw as Record<string, unknown>
  if (typeof body.businessDate !== 'string' || typeof body.complete !== 'boolean') return null
  if (!Array.isArray(body.participants) || !Array.isArray(body.missing)) return null
  const participants: DayCloseParticipant[] = []
  for (const row of body.participants) {
    if (!row || typeof row !== 'object') return null
    const r = row as Record<string, unknown>
    if (typeof r.participant !== 'string' || !STATUSES.includes(r.status as ParticipantStatus)) return null
    participants.push({
      participant: r.participant,
      status: r.status as ParticipantStatus,
      journalsPosted: typeof r.journalsPosted === 'number' ? r.journalsPosted : 0,
      itemsProcessed: typeof r.itemsProcessed === 'number' ? r.itemsProcessed : 0,
      reason: typeof r.reason === 'string' ? r.reason : null,
      reportedBy: typeof r.reportedBy === 'string' ? r.reportedBy : null,
      reportedAt: typeof r.reportedAt === 'string' ? r.reportedAt : null,
      late: r.late === true,
    })
  }
  return {
    businessDate: body.businessDate,
    complete: body.complete,
    missing: body.missing.filter((m): m is string => typeof m === 'string'),
    participants,
  }
}

function yesterday(): string {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  return d.toISOString().slice(0, 10)
}

const PILL: Record<ParticipantStatus, string> = {
  RAN: 'pill pill-success',
  SKIPPED: 'pill',
  WAIVED: 'pill',
  REFUSED: 'pill pill-danger',
  MISSING: 'pill pill-danger',
}

export function DayCloseParticipantsPanel() {
  const { t, language } = useLanguage()
  const [date, setDate] = useState(yesterday)
  const [data, setData] = useState<DayCloseCompleteness | null>(null)
  const [failure, setFailure] = useState<BffFailure | null>(null)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async (day: string) => {
    setLoading(true)
    try {
      const res = await fetch(
        svcUrl('ledger-service', `/api/v1/ledger/accounting-days/${encodeURIComponent(day)}/participants`),
        { cache: 'no-store', signal: AbortSignal.timeout(8000) },
      )
      if (!res.ok) {
        setData(null)
        setFailure(await classifyBffFailure(res))
        return
      }
      const parsed = parseDayCloseCompleteness(await res.json())
      setData(parsed)
      setFailure(parsed ? null : 'error')
    } catch {
      setData(null)
      setFailure('unreachable')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    const id = window.setTimeout(() => { void load(date) }, 0)
    return () => window.clearTimeout(id)
  }, [date, load])

  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const fmtTs = (iso: string | null) => {
    if (!iso) return '—'
    try { return new Date(iso).toLocaleString(locale) } catch { return iso }
  }
  const statusLabel = (s: ParticipantStatus) => ({
    RAN: t('Proběhlo', 'Ran'),
    SKIPPED: t('Přeskočeno', 'Skipped'),
    REFUSED: t('Odmítnuto', 'Refused'),
    WAIVED: t('Prominuto', 'Waived'),
    MISSING: t('Chybí', 'Missing'),
  })[s]

  return (
    <section aria-labelledby="day-close-participants-heading" style={{ marginTop: '24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', marginBottom: '10px', flexWrap: 'wrap' }}>
        <h2 id="day-close-participants-heading" style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '15px', fontWeight: 700, margin: 0 }}>
          <Users size={16} aria-hidden="true" />
          {t('Účastníci závěrky dne', 'Day-close participants')}
        </h2>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <label style={{ fontSize: '12px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: '6px' }}>
            {t('Účetní den', 'Accounting day')}
            <input type="date" className="input" value={date} max={new Date().toISOString().slice(0, 10)}
              onChange={e => { if (e.target.value) setDate(e.target.value) }} />
          </label>
          <button type="button" className="btn btn-secondary" aria-label={t('Obnovit účastníky závěrky', 'Refresh day-close participants')}
            aria-busy={loading} disabled={loading} onClick={() => { void load(date) }}>
            <RefreshCw size={13} aria-hidden="true" className={loading ? 'animate-spin' : undefined} />
            {t('Obnovit', 'Refresh')}
          </button>
        </div>
      </div>

      {failure ? (
        <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)' }}>
          <DataUnavailable kind={failure} service="Ledger-service" feature={t('Účastníci závěrky dne', 'Day-close participants')} lang={language} dense />
        </div>
      ) : loading && !data ? (
        <div className="skeleton" style={{ height: '96px' }} />
      ) : data ? (
        <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--r-lg)', overflowX: 'auto' }}>
          <p role="status" style={{ margin: 0, padding: '12px 16px', fontSize: '13px', fontWeight: 600,
            color: data.complete ? 'var(--success-text)' : 'var(--danger-text)' }}>
            {data.complete
              ? t('Všichni účastníci nahlásili výsledek — den může být uzavřen.', 'Every participant has reported — the day can tie out.')
              : t(`Chybí hlášení: ${data.missing.join(', ')} — den zůstane v CUTOFF, dokud nenahlásí nebo nejsou prominuti.`,
                  `Missing: ${data.missing.join(', ')} — the day stays in CUTOFF until they report or are waived.`)}
          </p>
          <table className="table" style={{ width: '100%' }}>
            <thead>
              <tr>
                <th scope="col">{t('Účastník', 'Participant')}</th>
                <th scope="col">{t('Stav', 'Status')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Zaúčtováno', 'Journals')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Položek', 'Items')}</th>
                <th scope="col">{t('Důvod', 'Reason')}</th>
                <th scope="col">{t('Nahlášeno', 'Reported')}</th>
              </tr>
            </thead>
            <tbody>
              {data.participants.map(p => (
                <tr key={p.participant}>
                  <td>{p.participant}</td>
                  <td>
                    <span className={PILL[p.status]}>{statusLabel(p.status)}</span>
                    {p.late && <span className="pill pill-danger" style={{ marginLeft: '6px' }}>{t('pozdě', 'late')}</span>}
                  </td>
                  <td style={{ textAlign: 'right' }}>{p.journalsPosted}</td>
                  <td style={{ textAlign: 'right' }}>{p.itemsProcessed}</td>
                  <td>{p.reason ?? '—'}</td>
                  <td>{fmtTs(p.reportedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </section>
  )
}
