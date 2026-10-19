// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury day completion (ADR-0344 D6): for a chosen date, the steps the platform runs to close
// the treasury day — fixings, accruals, payments, revaluation, corrections, maturities — each with
// its status, how many items it touched, and when. A step that did not run is shown as such. The
// revaluation of the day is listed under the steps (fair value from the risk engine's TIED_OUT
// snapshot, the change it posted, or the gap that held it), and back office may re-run it: owed
// postings are re-posted first, and a deal the engine did not value is a gap, never a zero.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSession } from 'next-auth/react'
import { hasPermission } from '@/lib/auth/roles'
import { ClipboardCheck } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader, StatusBadge } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import { bankToday } from '@/components/balance-sheet/model'
import { wbGet, wbPost, wbUrl, type WbRead } from '@/components/treasury/wb/client'
import { dayCompletionSchema, revaluationListSchema, revaluationRunSchema, type Revaluation } from '@/components/treasury/wb/types'
import type { z } from 'zod'
import { Field, Notice, ReadFailure, Section, td, tdNum, th, useFmt, writeFailureText } from '@/components/treasury/wb/ui'

export default function TreasuryDayCompletionPage() {
  return (
    <AuthGuard permission="treasury:workbench:view">
      <DayCompletion />
    </AuthGuard>
  )
}

const TONE: Record<string, Tone> = { DONE: 'success', SKIPPED: 'neutral', FAILED: 'danger', PENDING: 'warning', REFUSED: 'warning' }

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
    case 'REFUSED': return t('Odmítnuto (uzavřený den)', 'Refused (day closed)')
    default: return s
  }
}

function DayCompletion() {
  const { t, dateTime, money } = useFmt()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const canRun = hasPermission(roles, 'treasury:backoffice:write')
  const [date, setDate] = useState(() => bankToday())
  const [result, setResult] = useState<WbRead<z.infer<typeof dayCompletionSchema>> | null>(null)
  const [revaluations, setRevaluations] = useState<WbRead<Revaluation[]> | null>(null)
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [gaps, setGaps] = useState<{ dealId: string; reason: string }[]>([])
  const [busy, setBusy] = useState(false)
  const today = useMemo(() => bankToday(), [])

  const load = useCallback(async (d: string, isCancelled: () => boolean = () => false) => {
    const [res, rev] = await Promise.all([
      wbGet(wbUrl(`/day-completion/${encodeURIComponent(d)}`), dayCompletionSchema),
      wbGet(wbUrl('/revaluations', { asOf: d }), revaluationListSchema),
    ])
    if (!isCancelled()) { setResult(res); setRevaluations(rev) }
  }, [])

  useEffect(() => {
    let cancelled = false
    setResult(null)
    setRevaluations(null)
    setGaps([])
    void load(date, () => cancelled)
    return () => { cancelled = true }
  }, [date, load])

  const runRevaluation = async () => {
    setBusy(true)
    setNotice(null)
    const res = await wbPost(wbUrl('/revaluations/run', { asOf: date }), undefined, revaluationRunSchema)
    setBusy(false)
    if (res.ok) {
      setGaps(res.data.gaps)
      const problems = res.data.gaps.length + res.data.failures.length + (res.data.owed ?? 0)
      setNotice(problems === 0
        ? { tone: 'success', text: t(`Přeceněno ${res.data.revalued.length} obchodů, ${res.data.journals} zaúčtování.`, `Revalued ${res.data.revalued.length} deals, ${res.data.journals} journals.`) }
        : { tone: 'danger', text: t(`Přecenění neúplné: ${res.data.gaps.length} mezer v datech, ${res.data.failures.length} chyb, ${res.data.owed ?? 0} nezaúčtovaných.`, `Revaluation incomplete: ${res.data.gaps.length} data gaps, ${res.data.failures.length} failures, ${res.data.owed ?? 0} unposted.`) })
      void load(date)
    } else setNotice({ tone: 'danger', text: writeFailureText(res, t('Přecenění', 'Revaluation'), t) })
  }

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
      <Section title={t('Přecenění dne', 'Revaluation of the day')} id="day-revaluation">
        {canRun && <p><button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void runRevaluation()}>{t('Spustit přecenění', 'Run revaluation')}</button></p>}
        {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}
        {gaps.length > 0 && (
          <ul data-testid="revaluation-gaps" style={{ fontSize: 13 }}>
            {gaps.map(g => <li key={g.dealId}>{g.dealId}: {g.reason}</li>)}
          </ul>
        )}
        {revaluations === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
        {revaluations && !revaluations.ok && <ReadFailure result={revaluations} what={t('Přecenění', 'Revaluation')} />}
        {revaluations?.ok && (revaluations.data.length === 0
          ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Pro tento den není zaznamenáno žádné přecenění.', 'No revaluation recorded for this day.')}</p>
          : (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }} data-testid="revaluation-table">
              <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Přecenění', 'Revaluations')}</caption>
              <thead><tr><th scope="col" style={th}>{t('Obchod', 'Deal')}</th>{[t('Reálná hodnota', 'Fair value'), t('Účetní hodnota', 'Carrying'), t('Kumulativně', 'Cumulative'), t('Změna', 'Change')].map(h => <th key={h} scope="col" style={{ ...th, textAlign: 'right' }}>{h}</th>)}<th scope="col" style={th}>{t('Zaúčtování', 'Journal')}</th></tr></thead>
              <tbody>
                {revaluations.data.map(r => (
                  <tr key={r.dealId} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={td}>{r.dealId}</td>
                    <td style={tdNum}>{money(r.fairValue)}</td>
                    <td style={tdNum}>{money(r.carrying)}</td>
                    <td style={tdNum}>{money(r.cumulative)}</td>
                    <td style={tdNum}>{money(r.change)}</td>
                    <td style={td}>{r.journalId ?? (r.change === 0 ? t('beze změny', 'no change') : t('dluží se', 'owed'))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
      </Section>
    </div>
  )
}
