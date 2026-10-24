// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury event calendar (ADR-0344): every upcoming refixing, interest and principal payment,
// maturity, option exercise, margin call and settlement for a period preset (yesterday, today,
// tomorrow, week, month, custom range), each actionable — open the deal, open the payment.

'use client'

import { useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { CalendarDays } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { addDays } from '@/components/treasury/positions'
import { presetRange, type Preset } from '@/components/treasury/wb/model'
import { bankToday } from '@/lib/format/bankDate'
import { eventTypeLabel } from '@/components/treasury/wb/catalogue'
import { wbGet, wbUrl, type WbRead } from '@/components/treasury/wb/client'
import { EVENT_TYPES, treasuryEventListSchema, type TreasuryEvent } from '@/components/treasury/wb/types'
import { Field, ReadFailure, Section, td, th, useFmt } from '@/components/treasury/wb/ui'

export default function TreasuryCalendarPage() {
  return (
    <AuthGuard permission="treasury:workbench:view">
      <Calendar />
    </AuthGuard>
  )
}

function Calendar() {
  const { t } = useFmt()
  const today = useMemo(() => bankToday(), [])
  const [preset, setPreset] = useState<Preset>('week')
  const [custom, setCustom] = useState({ from: today, to: addDays(today, 30) })
  const [bookId, setBookId] = useState('')
  const [types, setTypes] = useState<Set<string>>(new Set())
  const [result, setResult] = useState<WbRead<TreasuryEvent[]> | null>(null)

  const range = preset === 'custom' ? custom : presetRange(preset, today)
  const typeKey = [...types].sort().join(',')
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await wbGet(wbUrl('/calendar', { from: range.from, to: range.to, bookId, types: typeKey }), treasuryEventListSchema)
      if (!cancelled) setResult(res)
    })()
    return () => { cancelled = true }
  }, [range.from, range.to, bookId, typeKey])

  const presets: [Preset, string][] = [
    ['yesterday', t('Včera', 'Yesterday')], ['today', t('Dnes', 'Today')], ['tomorrow', t('Zítra', 'Tomorrow')],
    ['week', t('Týden', 'Week')], ['month', t('Měsíc', 'Month')], ['custom', t('Vlastní období', 'Custom range')],
  ]
  const toggle = (type: string) => setTypes(s => { const n = new Set(s); if (n.has(type)) n.delete(type); else n.add(type); return n })

  return (
    <div>
      <PageHeader
        title={t('Kalendář událostí', 'Event calendar')}
        subtitle={t('Refixingy, platby, splatnosti, uplatnění opcí a margin cally.', 'Refixings, payments, maturities, option exercises and margin calls.')}
        icon={<CalendarDays size={20} aria-hidden="true" />}
      />
      <Section title={t('Období a filtry', 'Period and filters')} id="calendar-filters">
        <div role="group" aria-label={t('Předvolby období', 'Period presets')} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
          {presets.map(([id, label]) => (
            <button key={id} type="button" className={preset === id ? 'btn btn-primary btn-sm' : 'btn btn-secondary btn-sm'} aria-pressed={preset === id} onClick={() => setPreset(id)}>{label}</button>
          ))}
        </div>
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
          {preset === 'custom' && (
            <>
              <Field label={t('Od', 'From')}><input className="input" type="date" value={custom.from} onChange={e => setCustom(c => ({ ...c, from: e.target.value }))} /></Field>
              <Field label={t('Do', 'To')}><input className="input" type="date" value={custom.to} onChange={e => setCustom(c => ({ ...c, to: e.target.value }))} /></Field>
            </>
          )}
          <Field label={t('Portfolio (kniha)', 'Portfolio (book)')}><input className="input" value={bookId} onChange={e => setBookId(e.target.value)} /></Field>
          <fieldset style={{ border: 0, padding: 0, display: 'flex', gap: 8, flexWrap: 'wrap', fontSize: 12 }}>
            <legend>{t('Typy událostí', 'Event types')}</legend>
            {EVENT_TYPES.map(type => (
              <label key={type}><input type="checkbox" checked={types.has(type)} onChange={() => toggle(type)} /> {eventTypeLabel(type, t)}</label>
            ))}
          </fieldset>
        </div>
        <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>{`${range.from} → ${range.to}`}</p>
      </Section>

      <Section title={t('Události', 'Events')} id="calendar-events">
        {result === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
        {result && !result.ok && <ReadFailure result={result} what={t('Kalendář událostí', 'The event calendar')} />}
        {result?.ok && (
          <>
            <p data-testid="record-count" style={{ fontSize: 12, marginBottom: 8 }} aria-live="polite">{t(`Záznamů: ${result.data.length}`, `Records: ${result.data.length}`)}</p>
            {result.data.length === 0 ? (
              <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('V tomto období nejsou žádné události.', 'There are no events in this period.')}</p>
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Události treasury', 'Treasury events')}</caption>
                <thead><tr>{[t('Datum', 'Date'), t('Typ', 'Type'), t('Popis', 'Description'), t('Obchod', 'Deal'), t('Platba', 'Payment'), t('Odpovědná role', 'Responsible role')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}</tr></thead>
                <tbody>
                  {result.data.map((e, i) => (
                    <tr key={`${e.dealId}-${e.flowId ?? i}-${e.type}-${e.date}`} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={td}>{e.date}</td>
                      <td style={td}>{eventTypeLabel(e.type, t)}</td>
                      <td style={td}>{e.description}</td>
                      <td style={td}><Link href={`/treasury/deals/${encodeURIComponent(e.dealId)}`}>{t('Otevřít obchod', 'Open deal')}</Link></td>
                      <td style={td}>{e.paymentId ? <Link href={`/treasury/payments?paymentId=${encodeURIComponent(e.paymentId)}`}>{t('Otevřít platbu', 'Open payment')}</Link> : '—'}</td>
                      <td style={td}>{e.responsibleRole ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </Section>
    </div>
  )
}
