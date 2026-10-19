// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury cash-flow analysis (ADR-0344, contract 8.4): the contractual flows of the stored cash-flow
// ledger by currency for a calculation date and a portfolio node (the book and its descendants),
// gross, net or cumulative, daily or monthly, optionally converted into a reference currency at the
// approved FX fixing of that date. A missing fixing, or a flow without an amount, is shown as a
// data gap — the reference series is then withheld, never computed at a guessed rate. Each bucket
// drills down to the deals and legs behind it. Behavioural and stress scenarios are refused by the
// service until a scenario model is published; the page says so.

'use client'

import { Fragment, useState } from 'react'
import Link from 'next/link'
import { BarChart3 } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { bankToday } from '@/components/balance-sheet/model'
import { wbPost, wbUrl } from '@/components/treasury/wb/client'
import { CURRENCY_OPTIONS } from '@/components/treasury/wb/catalogue'
import { flowsOfBucket } from '@/components/treasury/wb/analysis'
import { cashFlowAnalysisSchema, type CashFlowAnalysis } from '@/components/treasury/wb/types'
import { Field, Notice, NotAvailable, Section, td, tdNum, th, useFmt, writeFailureText, gridStyle } from '@/components/treasury/wb/ui'

export default function TreasuryCashFlowAnalysisPage() {
  return (
    <AuthGuard permission="treasury:workbench:view">
      <Analysis />
    </AuthGuard>
  )
}

type Series = CashFlowAnalysis['series'][number]

function Analysis() {
  const { t, money } = useFmt()
  const [calculationDate, setCalculationDate] = useState(() => bankToday())
  const [bookId, setBookId] = useState('')
  const [referenceCurrency, setReferenceCurrency] = useState('CZK')
  const [convertToReference, setConvert] = useState(false)
  const [expression, setExpression] = useState('GROSS')
  const [scenario, setScenario] = useState('CONTRACTUAL')
  const [splitMonthly, setSplitMonthly] = useState(false)
  const [includePlanned, setIncludePlanned] = useState(false)
  const [horizonDays, setHorizonDays] = useState('366')
  const [bankAccountId, setBankAccount] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<CashFlowAnalysis | null>(null)
  const [state, setState] = useState<'idle' | 'not_available'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<Set<string>>(new Set())

  const run = async () => {
    setBusy(true)
    setError(null)
    setState('idle')
    const res = await wbPost(wbUrl('/analysis/cash-flows'), {
      calculationDate, bookId: bookId.trim() || null, referenceCurrency, convertToReference, expression, scenario, splitMonthly,
      includePlanned, horizonDays: Number(horizonDays) || 366, bankAccountId: bankAccountId.trim() || null,
    }, cashFlowAnalysisSchema)
    setBusy(false)
    setOpen(new Set())
    if (res.ok) { setResult(res.data); return }
    setResult(null)
    if (res.kind === 'not_available') setState('not_available')
    else if (res.code === 'SCENARIO_NOT_AVAILABLE') setError(t('Behaviorální a stresové scénáře zatím nejsou k dispozici — žádný model scénářů není publikován. Použijte smluvní scénář.', 'Behavioural and stress scenarios are not available yet — no scenario model is published. Use the contractual scenario.'))
    else if (res.kind === 'unavailable' && res.status === 200) setError(t('Odpověď analýzy má neočekávaný tvar — nic se nezobrazuje.', 'The analysis answered in an unexpected shape — nothing is shown.'))
    else setError(writeFailureText(res, t('Analýza', 'Analysis'), t))
  }
  const toggle = (k: string) => setOpen(s => { const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n })
  const valueHeader = expression === 'CUMULATIVE' ? t('Kumulativně', 'Cumulative') : expression === 'NET' ? t('Netto', 'Net') : t('Hrubě', 'Gross')

  const seriesTable = (s: Series, converted: boolean) => (
    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
      <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t(`Toky v měně ${s.currency}`, `Flows in ${s.currency}`)}</caption>
      <thead><tr>
        <th scope="col" style={th}>{splitMonthly ? t('Měsíc', 'Month') : t('Datum', 'Date')}</th>
        {[t('Příjmy', 'Inflow'), t('Výdaje', 'Outflow'), t('Netto', 'Net'), t('Kumulativně', 'Cumulative'), valueHeader].map((h, i) => <th key={i} scope="col" style={{ ...th, textAlign: 'right' }}>{h}</th>)}
        {!converted && <th scope="col" style={th}>{t('Detail', 'Detail')}</th>}
      </tr></thead>
      <tbody>
        {s.buckets.map(b => {
          const key = `${s.currency}:${b.date}`
          const flows = result && !converted ? flowsOfBucket(result.flows, s.currency, b.date, splitMonthly) : []
          return (
            <Fragment key={key}>
              <tr style={{ borderTop: '1px solid var(--border)' }}>
                <td style={td}>{splitMonthly ? b.date.slice(0, 7) : b.date}</td>
                <td style={tdNum}>{money(b.inflow)}</td>
                <td style={tdNum}>{money(b.outflow)}</td>
                <td style={tdNum}>{money(b.net)}</td>
                <td style={tdNum}>{money(b.cumulative)}</td>
                <td style={{ ...tdNum, fontWeight: 600 }}>{money(b.value)} {s.currency}</td>
                {!converted && (
                  <td style={td}>
                    <button type="button" className="btn btn-ghost btn-sm" aria-expanded={open.has(key)} onClick={() => toggle(key)}>
                      {open.has(key) ? t('Skrýt', 'Hide') : t(`Zobrazit toky (${flows.length})`, `Show flows (${flows.length})`)}
                    </button>
                  </td>
                )}
              </tr>
              {open.has(key) && flows.map(f => (
                <tr key={f.flowId} data-drill style={{ background: 'var(--bg-subtle, transparent)' }}>
                  <td style={td}>{f.date}</td>
                  <td style={tdNum} colSpan={4}>{money(f.amount)} {f.currency}{f.referenceAmount !== null && f.referenceAmount !== undefined ? ` (${money(f.referenceAmount)} ${referenceCurrency})` : ''}</td>
                  <td style={td} colSpan={2}>
                    <Link href={`/treasury/deals/${encodeURIComponent(f.dealId)}`}>{t('Obchod', 'Deal')}</Link>
                    {` · ${t('noha', 'leg')} ${f.legIndex + 1} · ${f.kind}${f.dealKind === 'PLANNED' ? ` · ${t('plánovaný', 'planned')}` : ''}`}
                  </td>
                </tr>
              ))}
            </Fragment>
          )
        })}
      </tbody>
    </table>
  )

  return (
    <div>
      <PageHeader title={t('Analýza peněžních toků', 'Cash-flow analysis')} subtitle={t('Smluvní toky z uložené knihy toků podle měn s rozpadem na obchod a nohu.', 'Contractual flows of the stored cash-flow ledger by currency, with drill-down to deal and leg.')} icon={<BarChart3 size={20} aria-hidden="true" />} />
      <Section title={t('Parametry', 'Parameters')} id="analysis-params">
        <div style={gridStyle}>
          <Field label={t('Datum výpočtu', 'Calculation date')}><input className="input" type="date" value={calculationDate} onChange={e => setCalculationDate(e.target.value)} /></Field>
          <Field label={t('Uzel portfolia (kniha)', 'Portfolio node (book)')} hint={t('Prázdné = celé portfolio; kniha včetně podřízených.', 'Empty = the whole portfolio; a book includes its descendants.')}><input className="input" value={bookId} onChange={e => setBookId(e.target.value)} /></Field>
          <Field label={t('Referenční měna', 'Reference currency')}>
            <select className="input" value={referenceCurrency} onChange={e => setReferenceCurrency(e.target.value)}>{CURRENCY_OPTIONS.map(c => <option key={c} value={c}>{c}</option>)}</select>
          </Field>
          <Field label={t('Forma výsledku', 'Result expression')}>
            <select className="input" value={expression} onChange={e => setExpression(e.target.value)}>
              <option value="GROSS">{t('Hrubě', 'Gross')}</option><option value="NET">{t('Netto', 'Net')}</option><option value="CUMULATIVE">{t('Kumulativně', 'Cumulative')}</option>
            </select>
          </Field>
          <Field label={t('Scénář plateb', 'Payment scenario')}>
            <select className="input" value={scenario} onChange={e => setScenario(e.target.value)}>
              <option value="CONTRACTUAL">{t('Smluvní', 'Contractual')}</option><option value="BEHAVIOURAL">{t('Behaviorální', 'Behavioural')}</option><option value="STRESS">{t('Stresový', 'Stress')}</option>
            </select>
          </Field>
          <Field label={t('Horizont (dny)', 'Horizon (days)')} hint="1–3660"><input className="input" type="number" min={1} max={3660} value={horizonDays} onChange={e => setHorizonDays(e.target.value)} /></Field>
          <Field label={t('Bankovní účet', 'Bank account')} hint={t('Peněžní účet vypořádání obchodu.', 'The deal’s settlement cash account.')}><input className="input" value={bankAccountId} onChange={e => setBankAccount(e.target.value)} /></Field>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, justifyContent: 'end' }}>
            <label><input type="checkbox" checked={convertToReference} onChange={e => setConvert(e.target.checked)} /> {t('Převést do referenční měny', 'Convert to reference currency')}</label>
            <label><input type="checkbox" checked={splitMonthly} onChange={e => setSplitMonthly(e.target.checked)} /> {t('Rozdělit po měsících', 'Split monthly')}</label>
            <label><input type="checkbox" checked={includePlanned} onChange={e => setIncludePlanned(e.target.checked)} /> {t('Včetně plánovaných obchodů', 'Include planned deals')}</label>
          </div>
        </div>
        <button type="button" className="btn btn-primary btn-sm" style={{ marginTop: 12 }} disabled={busy} onClick={() => void run()}>{t('Spustit analýzu', 'Run analysis')}</button>
      </Section>

      {error && <Notice tone="danger">{error}</Notice>}
      {state === 'not_available' && <NotAvailable what={t('Analýza peněžních toků', 'The cash-flow analysis')} />}
      {result && result.dataGaps.length > 0 && (
        <Notice tone="danger">
          <span data-testid="data-gaps">{t('Mezery v datech (nic se nedopočítává): ', 'Data gaps (nothing is filled in): ')}{result.dataGaps.map(g => `${g.currency ? `${g.currency}: ` : ''}${g.reason}`).join('; ')}</span>
        </Notice>
      )}
      {result && result.series.length === 0 && <Section title={t('Výsledek', 'Result')}><p style={{ fontSize: 13 }}>{t('Pro zadané parametry nejsou žádné toky.', 'There are no flows for these parameters.')}</p></Section>}
      {result && result.fxRates.length > 0 && (
        <p style={{ fontSize: 12, color: 'var(--text-secondary)' }} data-testid="fx-rates">
          {t('Kurzy: ', 'Rates: ')}{result.fxRates.map(r => `${r.index} ${r.rate} (${r.date})`).join(' · ')}
        </p>
      )}
      {result?.reference && (
        <Section title={t(`V referenční měně ${result.reference.currency}`, `In reference currency ${result.reference.currency}`)} id="analysis-reference">
          {seriesTable(result.reference, true)}
        </Section>
      )}
      {result && result.series.map(s => (
        <Section key={s.currency} title={t(`Měna ${s.currency}`, `Currency ${s.currency}`)} id={`analysis-${s.currency}`}>
          {seriesTable(s, false)}
        </Section>
      ))}
      {result?.flowsTruncated && <Notice tone="danger">{t('Rozpad toků je zkrácen na 2000 řádků; součty platí pro všechny toky.', 'The flow drill-down is truncated at 2000 rows; the totals cover every flow.')}</Notice>}
    </div>
  )
}
