// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury day completion (ADR-0344 D6): for a chosen date, the steps the platform runs to close
// the treasury day — fixings, accruals, payments, revaluation, corrections, maturities — each with
// its status, how many items it touched, and when. A step that did not run is shown as such.

'use client'

import { useEffect, useMemo, useState } from 'react'
import { ClipboardCheck } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader, StatusBadge } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import { bankToday } from '@/components/balance-sheet/model'
import { wbGet, wbUrl, type WbRead } from '@/components/treasury/wb/client'
import { dayCompletionSchema } from '@/components/treasury/wb/types'
import type { z } from 'zod'
import { Field, ReadFailure, Section, td, th, useFmt } from '@/components/treasury/wb/ui'

export default function TreasuryDayCompletionPage() {
  return (
    <AuthGuard permission="treasury:workbench:view">
      <DayCompletion />
    </AuthGuard>
  )
}

const TONE: Record<string, Tone> = { DONE: 'success', SKIPPED: 'neutral', FAILED: 'danger', PENDING: 'warning' }

function stepLabel(step: string, t: (cs: string, en: string) => string): string {
  switch (step) {
    case 'FIXINGS': return t('Fixingy', 'Fixings')
    case 'ACCRUALS': return t('Akruály', 'Accruals')
    case 'PAYMENTS': return t('Platby', 'Payments')
    case 'REVALUATION': return t('Přecenění', 'Revaluation')
    case 'CORRECTIONS': return t('Opravy', 'Corrections')
    case 'MATURITIES': return t('Splatnosti', 'Maturities')
    default: return step
  }
}
function statusLabel(s: string, t: (cs: string, en: string) => string): string {
  switch (s) {
    case 'DONE': return t('Hotovo', 'Done')
    case 'SKIPPED': return t('Přeskočeno', 'Skipped')
    case 'FAILED': return t('Selhalo', 'Failed')
    case 'PENDING': return t('Čeká', 'Pending')
    default: return s
  }
}

function DayCompletion() {
  const { t, dateTime } = useFmt()
  const [date, setDate] = useState(() => bankToday())
  const [result, setResult] = useState<WbRead<z.infer<typeof dayCompletionSchema>> | null>(null)
  const today = useMemo(() => bankToday(), [])

  useEffect(() => {
    let cancelled = false
    setResult(null)
    void (async () => {
      const res = await wbGet(wbUrl(`/day-completion/${encodeURIComponent(date)}`), dayCompletionSchema)
      if (!cancelled) setResult(res)
    })()
    return () => { cancelled = true }
  }, [date])

  return (
    <div>
      <PageHeader title={t('Uzavření dne treasury', 'Treasury day completion')} subtitle={t('Kroky uzavření dne a jejich stav.', 'The steps that close the treasury day and their status.')} icon={<ClipboardCheck size={20} aria-hidden="true" />} />
      <Section title={t('Datum', 'Date')} id="day-date">
        <Field label={t('Den', 'Day')}><input className="input" type="date" value={date} max={today} onChange={e => setDate(e.target.value)} /></Field>
      </Section>
      <Section title={t('Kroky', 'Steps')} id="day-steps">
        {result === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
        {result && !result.ok && <ReadFailure result={result} what={t('Uzavření dne', 'Day completion')} />}
        {result?.ok && (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Kroky uzavření dne', 'Day-completion steps')}</caption>
            <thead><tr>{[t('Krok', 'Step'), t('Stav', 'Status'), t('Položek', 'Items'), t('Detail', 'Detail'), t('Čas', 'When')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}</tr></thead>
            <tbody>
              {result.data.steps.map(s => (
                <tr key={s.step} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={td}>{stepLabel(s.step, t)}</td>
                  <td style={td}><StatusBadge status={s.status} tone={TONE[s.status] ?? 'neutral'} label={statusLabel(s.status, t)} /></td>
                  <td style={td}>{s.count ?? '—'}</td>
                  <td style={td}>{s.detail ?? '—'}</td>
                  <td style={td}>{dateTime(s.at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
    </div>
  )
}
