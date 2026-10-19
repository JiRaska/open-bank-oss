// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury market data (ADR-0344 D5): the factor browser with history, the SUSPICIOUS factors
// (stale, or jumped beyond the threshold) and MISSING factors (defined in configuration but absent
// for a date) the quality gate holds back, the bank's internal rate sheets, and import / export.
// Treasury owns the quality gate; the risk engine owns the curves built from these factors.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { Gauge } from 'lucide-react'
import { z } from 'zod'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader, Tabs, type TabItem } from '@/components/ui'
import { bankToday } from '@/components/balance-sheet/model'
import { addDays } from '@/components/treasury/positions'
import { hasPermission } from '@/lib/auth/roles'
import { useSession } from 'next-auth/react'
import { wbGet, wbGetText, wbPost, wbUrl, type WbRead } from '@/components/treasury/wb/client'
import { parseCsv } from '@/components/treasury/wb/model'
import { factorListSchema, qualitySchema, rateSheetListSchema, rateSheetSchema } from '@/components/treasury/wb/types'
import { downloadText, Field, Notice, ReadFailure, Section, td, tdNum, th, useFmt, writeFailureText } from '@/components/treasury/wb/ui'

export default function TreasuryMarketDataPage() {
  return (
    <AuthGuard permission="treasury:workbench:view">
      <MarketData />
    </AuthGuard>
  )
}

type TabId = 'factors' | 'suspicious' | 'missing' | 'sheets' | 'io'

function MarketData() {
  const { t, num } = useFmt()
  const { data: session } = useSession()
  const canWrite = hasPermission(session?.user?.roles ?? [], 'treasury:reference:propose')
  const [tab, setTab] = useState<TabId>('factors')
  const [date, setDate] = useState(() => bankToday())
  const [quality, setQuality] = useState<WbRead<z.infer<typeof qualitySchema>> | null>(null)
  const [index, setIndex] = useState('')
  const [range, setRange] = useState(() => ({ from: addDays(bankToday(), -30), to: bankToday() }))
  const [factors, setFactors] = useState<WbRead<z.infer<typeof factorListSchema>> | null>(null)
  const [sheets, setSheets] = useState<WbRead<z.infer<typeof rateSheetListSchema>> | null>(null)
  const [sheetForm, setSheetForm] = useState({ name: '', effectiveFrom: bankToday(), rates: '' })
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)

  useEffect(() => { void (async () => setQuality(await wbGet(wbUrl('/market-data/quality', { date }), qualitySchema)))() }, [date])
  useEffect(() => { void (async () => setFactors(await wbGet(wbUrl('/market-data/factors', { from: range.from, to: range.to, index }), factorListSchema)))() }, [range.from, range.to, index])
  const loadSheets = async () => setSheets(await wbGet(wbUrl('/market-data/internal-rate-sheets'), rateSheetListSchema))
  useEffect(() => { void loadSheets() }, [])

  const tabs: TabItem<TabId>[] = [
    { id: 'factors', label: t('Prohlížeč faktorů', 'Factor browser') },
    { id: 'suspicious', label: t('Podezřelé faktory', 'Suspicious factors') },
    { id: 'missing', label: t('Chybějící faktory', 'Missing factors') },
    { id: 'sheets', label: t('Interní sazebníky', 'Internal rate sheets') },
    { id: 'io', label: t('Import a export', 'Import & export') },
  ]

  const publish = async () => {
    const rates = sheetForm.rates.split('\n').map(l => l.trim()).filter(Boolean).map(l => { const [label, value] = l.split(/[;=]/); return { label: (label ?? '').trim(), value: Number((value ?? '').trim()) } })
    if (!sheetForm.name.trim() || rates.length === 0 || rates.some(r => !r.label || !Number.isFinite(r.value))) {
      setNotice({ tone: 'danger', text: t('Zadejte název a řádky ve tvaru „popisek;hodnota“.', 'Enter a name and lines in the form “label;value”.') }); return
    }
    const res = await wbPost(wbUrl('/market-data/internal-rate-sheets'), { name: sheetForm.name.trim(), effectiveFrom: sheetForm.effectiveFrom, rates }, rateSheetSchema)
    if (res.ok) { setNotice({ tone: 'success', text: t('Sazebník navržen — čeká na schválení jinou osobou.', 'Rate sheet proposed — a different person must approve it.') }); setSheetForm({ name: '', effectiveFrom: bankToday(), rates: '' }); void loadSheets() }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Publikace sazebníku', 'Publish rate sheet'), t) })
  }

  const importFile = async (file: File) => {
    const text = await file.text()
    let rows: unknown
    try { rows = file.name.toLowerCase().endsWith('.json') ? JSON.parse(text) : parseCsv(text) } catch { rows = null }
    if (!Array.isArray(rows) || rows.length === 0) { setNotice({ tone: 'danger', text: t('Soubor neobsahuje žádné řádky (očekává se CSV s hlavičkou nebo JSON pole).', 'The file has no rows (expected a CSV with a header or a JSON array).') }); return }
    const res = await wbPost(wbUrl('/market-data/import'), rows, z.unknown())
    if (res.ok) { setNotice({ tone: 'success', text: t(`Import odeslán: ${rows.length} řádků.`, `Import submitted: ${rows.length} rows.`) }); setQuality(await wbGet(wbUrl('/market-data/quality', { date }), qualitySchema)) }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Import', 'Import'), t) })
  }
  const exportData = async () => {
    const res = await wbGetText(wbUrl('/market-data/export'))
    if (res.ok) downloadText('treasury-market-data.csv', res.text, res.contentType || 'text/csv')
    else setNotice({ tone: 'danger', text: res.kind === 'not_available' ? t('Export zatím toto nasazení nenabízí.', 'This deployment does not offer export yet.') : t('Export se nepodařil.', 'The export failed.') })
  }

  const dealLink = (n: number | null | undefined) => (n ? <Link href="/treasury/deals">{t(`${n} obchodů`, `${n} deals`)}</Link> : '—')

  return (
    <div>
      <PageHeader title={t('Tržní data', 'Market data')} subtitle={t('Faktory, kontrola kvality a interní sazebníky.', 'Factors, the quality gate and internal rate sheets.')} icon={<Gauge size={20} aria-hidden="true" />} />
      {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}
      <Tabs items={tabs} value={tab} onChange={setTab} label={t('Tržní data', 'Market data')} idPrefix="md-tab" style={{ marginBottom: 12 }} />

      {tab === 'factors' && (
        <Section title={t('Historie faktorů', 'Factor history')} id="md-factors">
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
            <Field label={t('Index / faktor', 'Index / factor')}><input className="input" value={index} onChange={e => setIndex(e.target.value)} /></Field>
            <Field label={t('Od', 'From')}><input className="input" type="date" value={range.from} onChange={e => setRange(r => ({ ...r, from: e.target.value }))} /></Field>
            <Field label={t('Do', 'To')}><input className="input" type="date" value={range.to} onChange={e => setRange(r => ({ ...r, to: e.target.value }))} /></Field>
          </div>
          {factors === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
          {factors && !factors.ok && <ReadFailure result={factors} what={t('Historie faktorů', 'The factor history')} />}
          {factors?.ok && (factors.data.length === 0 ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('V období nejsou žádné hodnoty.', 'There are no values in the period.')}</p> : (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Hodnoty faktorů', 'Factor values')}</caption>
              <thead><tr><th scope="col" style={th}>{t('Index', 'Index')}</th><th scope="col" style={th}>{t('Datum', 'Date')}</th><th scope="col" style={{ ...th, textAlign: 'right' }}>{t('Hodnota', 'Value')}</th><th scope="col" style={th}>{t('Zdroj', 'Source')}</th></tr></thead>
              <tbody>{factors.data.map((f, i) => <tr key={`${f.index}-${f.date}-${i}`} style={{ borderTop: '1px solid var(--border)' }}><td style={td}>{f.index}</td><td style={td}>{f.date}</td><td style={tdNum}>{num(f.value, 6)}</td><td style={td}>{f.source ?? '—'}</td></tr>)}</tbody>
            </table>
          ))}
        </Section>
      )}

      {(tab === 'suspicious' || tab === 'missing') && (
        <Section title={tab === 'suspicious' ? t('Podezřelé faktory (neaktuální nebo skok nad práh)', 'Suspicious factors (stale or jump beyond threshold)') : t('Chybějící faktory (definované, ale bez hodnoty)', 'Missing factors (defined but absent)')} id="md-quality"
          actions={<Field label={t('Datum', 'Date')}><input className="input" type="date" value={date} onChange={e => setDate(e.target.value)} /></Field>}>
          {quality === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
          {quality && !quality.ok && <ReadFailure result={quality} what={t('Kontrola kvality tržních dat', 'The market-data quality check')} />}
          {quality?.ok && tab === 'suspicious' && (quality.data.suspicious.length === 0 ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Žádné podezřelé faktory.', 'No suspicious factors.')}</p> : (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Podezřelé faktory', 'Suspicious factors')}</caption>
              <thead><tr>{[t('Index', 'Index'), t('Datum', 'Date')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}{[t('Hodnota', 'Value'), t('Předchozí', 'Previous'), t('Skok (bp)', 'Jump (bp)'), t('Dnů bez změny', 'Stale days')].map(h => <th key={h} scope="col" style={{ ...th, textAlign: 'right' }}>{h}</th>)}<th scope="col" style={th}>{t('Důvod', 'Reason')}</th></tr></thead>
              <tbody>{quality.data.suspicious.map((s, i) => <tr key={i} style={{ borderTop: '1px solid var(--border)' }}><td style={td}>{s.index}</td><td style={td}>{s.date}</td><td style={tdNum}>{num(s.value, 6)}</td><td style={tdNum}>{s.previous != null ? num(s.previous, 6) : '—'}</td><td style={tdNum}>{s.jumpBp != null ? num(s.jumpBp, 1) : '—'}</td><td style={tdNum}>{s.staleDays ?? '—'}</td><td style={td}>{s.reason ?? '—'}</td></tr>)}</tbody>
            </table>
          ))}
          {quality?.ok && tab === 'missing' && (quality.data.missing.length === 0 ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Žádné chybějící faktory.', 'No missing factors.')}</p> : (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Chybějící faktory', 'Missing factors')}</caption>
              <thead><tr>{[t('Index', 'Index'), t('Datum', 'Date'), t('Dotčené obchody', 'Affected deals')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}</tr></thead>
              <tbody>{quality.data.missing.map((m, i) => <tr key={i} style={{ borderTop: '1px solid var(--border)' }}><td style={td}>{m.index}</td><td style={td}>{m.date}</td><td style={td}>{dealLink(m.dealCount)}</td></tr>)}</tbody>
            </table>
          ))}
        </Section>
      )}

      {tab === 'sheets' && (
        <Section title={t('Interní sazebníky banky', 'Bank-published rate sheets')} id="md-sheets">
          {sheets === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
          {sheets && !sheets.ok && <ReadFailure result={sheets} what={t('Interní sazebníky', 'Internal rate sheets')} />}
          {sheets?.ok && (sheets.data.length === 0 ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Zatím nebyl publikován žádný sazebník.', 'No rate sheet has been published yet.')}</p> : (
            <ul style={{ fontSize: 13, marginBottom: 12 }}>
              {sheets.data.map((s, i) => <li key={`${s.name}-${i}`}>{`${s.name} · ${t('platí od', 'effective from')} ${s.effectiveFrom ?? '—'} · ${s.status ?? '—'} · ${(s.rates ?? []).map(r => `${r.label} ${r.value}`).join(', ')}`}</li>)}
            </ul>
          ))}
          {canWrite && (
            <div className="card">
              <h3 style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>{t('Navrhnout sazebník', 'Propose a rate sheet')}</h3>
              <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                <Field label={t('Název sazebníku', 'Rate-sheet name')}><input className="input" value={sheetForm.name} onChange={e => setSheetForm(f => ({ ...f, name: e.target.value }))} /></Field>
                <Field label={t('Platí od', 'Effective from')}><input className="input" type="date" value={sheetForm.effectiveFrom} onChange={e => setSheetForm(f => ({ ...f, effectiveFrom: e.target.value }))} /></Field>
                <Field label={t('Sazby (popisek;hodnota na řádek)', 'Rates (label;value per line)')}><textarea className="input" rows={3} value={sheetForm.rates} onChange={e => setSheetForm(f => ({ ...f, rates: e.target.value }))} /></Field>
              </div>
              <button type="button" className="btn btn-primary btn-sm" style={{ marginTop: 8 }} onClick={() => void publish()}>{t('Navrhnout', 'Propose')}</button>
            </div>
          )}
        </Section>
      )}

      {tab === 'io' && (
        <Section title={t('Import a export', 'Import and export')} id="md-io">
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'end' }}>
            <Field label={t('Importovat soubor (CSV nebo JSON)', 'Import a file (CSV or JSON)')}>
              <input className="input" type="file" accept=".csv,.json,text/csv,application/json" disabled={!canWrite} onChange={e => { const f = e.target.files?.[0]; if (f) void importFile(f) }} />
            </Field>
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => void exportData()}>{t('Exportovat tržní data', 'Export market data')}</button>
          </div>
          {!canWrite && <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>{t('Import smí provést jen editor referenčních dat nebo back office.', 'Only a reference editor or back office may import.')}</p>}
        </Section>
      )}
    </div>
  )
}
