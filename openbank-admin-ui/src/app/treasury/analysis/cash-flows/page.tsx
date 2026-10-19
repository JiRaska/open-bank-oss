// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury cash-flow analysis (ADR-0344): dated flows by currency for a calculation date and a
// portfolio node, in gross, net or cumulative form, under a contractual, behavioural or stress
// payment scenario, optionally converted into a reference currency and split monthly. Each date
// drills down to the deals and legs behind it. Behavioural and stress scenarios come from the risk
// engine; when a deployment does not serve the analysis the page says so and shows nothing.

'use client'

import { Fragment, useState } from 'react'
import Link from 'next/link'
import { BarChart3 } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui'
import { bankToday } from '@/components/balance-sheet/model'
import { wbPost, wbUrl } from '@/components/treasury/wb/client'
import { CURRENCY_OPTIONS } from '@/components/treasury/wb/catalogue'
import { groupByDate } from '@/components/treasury/wb/analysis'
import { normaliseAnalysis, type AnalysisFlow } from '@/components/treasury/wb/types'
import { z } from 'zod'
import { Field, Notice, NotAvailable, Section, td, tdNum, th, useFmt, writeFailureText, gridStyle } from '@/components/treasury/wb/ui'

export default function TreasuryCashFlowAnalysisPage() {
  return (
    <AuthGuard permission="treasury:workbench:view">
      <Analysis />
    </AuthGuard>
  )
}

type Result = { currencies: { currency: string; flows: AnalysisFlow[] }[] }

function Analysis() {
  const { t, money } = useFmt()
  const [calculationDate, setCalculationDate] = useState(() => bankToday())
  const [bookId, setBookId] = useState('')
  const [referenceCurrency, setReferenceCurrency] = useState('CZK')
  const [convertToReference, setConvert] = useState(false)
  const [expression, setExpression] = useState('GROSS')
  const [scenario, setScenario] = useState('CONTRACTUAL')
  const [splitMonthly, setSplitMonthly] = useState(false)
  const [futureBusinessScenarioId, setFuture] = useState('')
  const [bankAccountId, setBankAccount] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<Result | null | 'empty'>(null)
  const [state, setState] = useState<'idle' | 'not_available' | 'error'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<Set<string>>(new Set())

  const run = async () => {
    setBusy(true)
    setError(null)
    setState('idle')
    const res = await wbPost(wbUrl('/analysis/cash-flows'), {
      calculationDate, bookId: bookId.trim() || null, referenceCurrency, convertToReference, expression, scenario, splitMonthly,
      futureBusinessScenarioId: futureBusinessScenarioId.trim() || null, bankAccountId: bankAccountId.trim() || null,
    }, z.unknown())
    setBusy(false)
    if (res.ok) {
      const normalised = normaliseAnalysis(res.data)
      if (normalised === null) { setState('error'); setResult(null); return }
      setResult(normalised.currencies.length === 0 ? 'empty' : normalised)
      setOpen(new Set())
    } else if (res.kind === 'not_available') { setState('not_available'); setResult(null) }
    else { setError(writeFailureText(res, t('Analýza', 'Analysis'), t)); setResult(null) }
  }
  const toggle = (k: string) => setOpen(s => { const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n })

  return (
    <div>
      <PageHeader title={t('Analýza peněžních toků', 'Cash-flow analysis')} subtitle={t('Datované toky podle měn s rozpadem na obchod a nohu.', 'Dated flows by currency with drill-down to deal and leg.')} icon={<BarChart3 size={20} aria-hidden="true" />} />
      <Section title={t('Parametry', 'Parameters')} id="analysis-params">
        <div style={gridStyle}>
          <Field label={t('Datum výpočtu', 'Calculation date')}><input className="input" type="date" value={calculationDate} onChange={e => setCalculationDate(e.target.value)} /></Field>
          <Field label={t('Uzel portfolia (kniha)', 'Portfolio node (book)')} hint={t('Prázdné = celé portfolio.', 'Empty = the whole portfolio.')}><input className="input" value={bookId} onChange={e => setBookId(e.target.value)} /></Field>
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
          <Field label={t('Scénář budoucího obchodu', 'Future-business scenario')}><input className="input" value={futureBusinessScenarioId} onChange={e => setFuture(e.target.value)} /></Field>
          <Field label={t('Bankovní účet', 'Bank account')}><input className="input" value={bankAccountId} onChange={e => setBankAccount(e.target.value)} /></Field>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, justifyContent: 'end' }}>
            <label><input type="checkbox" checked={convertToReference} onChange={e => setConvert(e.target.checked)} /> {t('Převést do referenční měny', 'Convert to reference currency')}</label>
            <label><input type="checkbox" checked={splitMonthly} onChange={e => setSplitMonthly(e.target.checked)} /> {t('Rozdělit po měsících', 'Split monthly')}</label>
          </div>
        </div>
        <button type="button" className="btn btn-primary btn-sm" style={{ marginTop: 12 }} disabled={busy} onClick={() => void run()}>{t('Spustit analýzu', 'Run analysis')}</button>
      </Section>

      {error && <Notice tone="danger">{error}</Notice>}
      {state === 'not_available' && <NotAvailable what={t('Analýza peněžních toků', 'The cash-flow analysis')} />}
      {state === 'error' && <Notice tone="danger">{t('Odpověď analýzy má neočekávaný tvar — nic se nezobrazuje.', 'The analysis answered in an unexpected shape — nothing is shown.')}</Notice>}
      {result === 'empty' && <Section title={t('Výsledek', 'Result')}><p style={{ fontSize: 13 }}>{t('Pro zadané parametry nejsou žádné toky.', 'There are no flows for these parameters.')}</p></Section>}
      {result && result !== 'empty' && result.currencies.map(c => {
        const rows = groupByDate(c.flows, splitMonthly)
        return (
          <Section key={c.currency} title={t(`Měna ${c.currency}`, `Currency ${c.currency}`)} id={`analysis-${c.currency}`}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t(`Toky v měně ${c.currency}`, `Flows in ${c.currency}`)}</caption>
              <thead><tr><th scope="col" style={th}>{splitMonthly ? t('Měsíc', 'Month') : t('Datum', 'Date')}</th><th scope="col" style={{ ...th, textAlign: 'right' }}>{expression === 'CUMULATIVE' ? t('Kumulativně', 'Cumulative') : expression === 'NET' ? t('Netto', 'Net') : t('Hrubě', 'Gross')}</th><th scope="col" style={th}>{t('Detail', 'Detail')}</th></tr></thead>
              <tbody>
                {rows.map(r => {
                  const key = `${c.currency}:${r.key}`
                  return (
                    <Fragment key={key}>
                      <tr style={{ borderTop: '1px solid var(--border)' }}>
                        <td style={td}>{r.key}</td>
                        <td style={tdNum}>{money(expression === 'CUMULATIVE' && r.cumulative !== null ? r.cumulative : r.total)} {c.currency}</td>
                        <td style={td}>
                          <button type="button" className="btn btn-ghost btn-sm" aria-expanded={open.has(key)} onClick={() => toggle(key)}>
                            {open.has(key) ? t('Skrýt', 'Hide') : t(`Zobrazit toky (${r.flows.length})`, `Show flows (${r.flows.length})`)}
                          </button>
                        </td>
                      </tr>
                      {open.has(key) && r.flows.map((f, i) => (
                        <tr key={`${key}-${i}`} data-drill style={{ background: 'var(--bg-subtle, transparent)' }}>
                          <td style={td}>{f.date}</td>
                          <td style={tdNum}>{money(f.amount)} {f.currency}</td>
                          <td style={td}>
                            {f.dealId ? <Link href={`/treasury/deals/${encodeURIComponent(f.dealId)}`}>{t('Obchod', 'Deal')}</Link> : '—'}
                            {f.legIndex !== null ? ` · ${t('noha', 'leg')} ${f.legIndex + 1}` : ''}{f.kind ? ` · ${f.kind}` : ''}
                          </td>
                        </tr>
                      ))}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </Section>
        )
      })}
    </div>
  )
}
