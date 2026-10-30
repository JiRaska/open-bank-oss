// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The participant's own changes to a contract (pension-service API 1.2.0, F1), staff read-only:
// the contribution schedule in force, a pending change and its history, and the beneficiary
// designation with its history. Every change here was SCA-signed by the participant; the back
// office reads, it never edits (GET /operator/contracts/{id}/contribution-schedule|beneficiaries).

'use client'

import { useCallback, useEffect, useState } from 'react'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { getJson, PENSION, pensionUrl } from './api'
import { fieldOf, openRecordSchema, type QueueRow } from './contracts'
import { statusLabel } from './model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type Loaded = { data: QueueRow | null; kind: UnavailableKind | null }

function useRecord(url: string): Loaded {
  const [state, setState] = useState<Loaded>({ data: null, kind: null })
  const load = useCallback(async () => {
    const res = await getJson(url, openRecordSchema)
    setState(res.ok ? { data: res.data, kind: null } : { data: null, kind: res.kind })
  }, [url])
  useEffect(() => { void load() }, [load])
  return state
}

const rows = (v: unknown): QueueRow[] => (Array.isArray(v) ? v.filter((x): x is QueueRow => !!x && typeof x === 'object') : [])

export function ContractChanges({ contractId }: { contractId: string }) {
  const { t, language } = useLanguage()
  const base = `/operator/contracts/${encodeURIComponent(contractId)}`
  const schedule = useRecord(pensionUrl(`${base}/contribution-schedule`))
  const beneficiaries = useRecord(pensionUrl(`${base}/beneficiaries`))

  const version = (v: unknown) => {
    if (!v || typeof v !== 'object') return '—'
    const r = v as QueueRow
    const amount = fieldOf(r, 'amount')
    const status = fieldOf(r, 'status')
    return [
      `${String(amount ?? '—')} ${String(fieldOf(r, 'currency') ?? '')}`.trim(),
      String(fieldOf(r, 'frequency') ?? ''),
      fieldOf(r, 'dayOfMonth') != null ? t(`den ${String(fieldOf(r, 'dayOfMonth'))}`, `day ${String(fieldOf(r, 'dayOfMonth'))}`) : '',
      fieldOf(r, 'effectiveFrom') ? t(`od ${String(fieldOf(r, 'effectiveFrom'))}`, `from ${String(fieldOf(r, 'effectiveFrom'))}`) : '',
      typeof status === 'string' ? statusLabel(status, t) : '',
    ].filter(Boolean).join(' · ')
  }
  const people = (v: unknown) => rows(v).map(b => `${String(fieldOf(b, 'name') ?? '—')} ${String(fieldOf(b, 'sharePercent') ?? '')} %`).join(', ') || '—'

  return (
    <section className="card" style={{ marginBottom: 16 }}>
      <h2 style={{ fontSize: 15, marginTop: 0 }}>{t('Změny účastníka', 'Participant changes')}</h2>
      <h3 style={{ fontSize: 13 }}>{t('Pravidelný příspěvek', 'Contribution schedule')}</h3>
      {schedule.kind ? (
        <DataUnavailable kind={schedule.kind} service={PENSION} feature={t('pravidelný příspěvek', 'contribution schedule')} lang={language} dense />
      ) : schedule.data && (
        <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '4px 16px', fontSize: 13, margin: 0 }}>
          <dt>{t('Platný', 'In force')}</dt><dd>{version(fieldOf(schedule.data, 'inForce'))}</dd>
          <dt>{t('Čekající změna', 'Pending change')}</dt><dd>{version(fieldOf(schedule.data, 'pending'))}</dd>
          <dt>{t('Původní', 'Original')}</dt><dd>{version(fieldOf(schedule.data, 'original'))}</dd>
          <dt>{t('Historie', 'History')}</dt>
          <dd>{rows(fieldOf(schedule.data, 'history')).length === 0 ? '—' : (
            <ol style={{ margin: 0, paddingLeft: 18 }}>
              {rows(fieldOf(schedule.data, 'history')).map((h, i) => <li key={String(fieldOf(h, 'seq') ?? i)}>{version(h)}</li>)}
            </ol>
          )}</dd>
        </dl>
      )}
      <h3 style={{ fontSize: 13 }}>{t('Obmyšlené osoby', 'Beneficiaries')}</h3>
      {beneficiaries.kind ? (
        <DataUnavailable kind={beneficiaries.kind} service={PENSION} feature={t('obmyšlené osoby', 'beneficiaries')} lang={language} dense />
      ) : beneficiaries.data && (
        <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '4px 16px', fontSize: 13, margin: 0 }}>
          <dt>{t('Platné určení', 'Current designation')}</dt><dd>{people(fieldOf(beneficiaries.data, 'current'))}</dd>
          <dt>{t('Historie', 'History')}</dt>
          <dd>{rows(fieldOf(beneficiaries.data, 'history')).length === 0 ? '—' : (
            <ol style={{ margin: 0, paddingLeft: 18 }}>
              {rows(fieldOf(beneficiaries.data, 'history')).map((h, i) => (
                <li key={String(fieldOf(h, 'seq') ?? i)}>{`${String(fieldOf(h, 'changedAt') ?? '—')}: ${people(fieldOf(h, 'beneficiaries'))}`}</li>
              ))}
            </ol>
          )}</dd>
        </dl>
      )}
    </section>
  )
}
