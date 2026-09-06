// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Credit risk & decisioning console (ADR-0230 D1 read view over ADR-0213 / ADR-0028 Phase 3).
//
// WHAT A RISK ANALYST OPENS THIS FOR
//   - what the engine decides, how often, and WHY (reason codes, the rule that fired)
//   - which policy it decided against, and whether anyone can change that policy
//   - where the affordability floor actually sits against the applications it saw
//   - how often the four-eyes checker went the other way
//   - how the book is staged under IFRS 9, what the ECL coverage is, and which vintage is souring
//
// HONESTY RULES, SAME AS /lending
//   - A number labelled a total comes from a DB-grouped summary; anything from the capped
//     `/risk/decisions` list is labelled "of the loaded N".
//   - Thresholds on the charts are READ from `/risk/policy`. The page never hard-codes 0.45.
//   - `codeSeeded` policy, placeholder PD/LGD (model version), no bureau feed: all said on the page,
//     because a chart that hides its provenance is what a risk committee later gets blamed for.
//   - No mutation. Disposal stays in the approval inbox (ADR-0227 D4).

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { RefreshCw, ShieldAlert, Activity, Scale, AlertTriangle, Layers, FileText } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { EntityChip } from '@/components/entities/EntityChip'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import {
  type Decision, type LoanRisk, type OutcomeSummary, type Policy,
  byJurisdiction, decisionsToCsv, outcomeTotals, overrideMatrix, overrideRate, portfolioMix,
  priceBandTotals, reasonPareto, ruleHits, threshold, vintage, weeklyOutcomes,
} from '@/components/lending/risk/model'
import { PolicyTables } from '@/components/lending/risk/PolicyTables'
import { AffordabilityScatter, BucketBars, C_STAGE, OutcomeTrend, ReasonPareto, StageMixPie } from '@/components/lending/risk/charts'

const UNKNOWN = '—'
/** The server clamps to 1000; ask for it so the cap is a known number on the labels. */
const DECISION_LIMIT = 1000
const PORTFOLIO_LIMIT = 1000

const OUTCOME_TONE: Record<string, Tone> = { APPROVE: 'success', REFER: 'warning', DECLINE: 'danger' }

type Loaded<T> = { data: T; ok: true } | { data: null; ok: false }

async function getJson<T>(url: string): Promise<Loaded<T>> {
  try {
    const res = await fetch(url, { cache: 'no-store' })
    if (!res.ok) return { data: null, ok: false }
    return { data: (await res.json()) as T, ok: true }
  } catch {
    return { data: null, ok: false }
  }
}

function pct(v: number | null, locale: string): string {
  return v === null ? UNKNOWN : `${(v * 100).toLocaleString(locale, { maximumFractionDigits: 1 })} %`
}

function download(name: string, mime: string, body: string) {
  const blob = new Blob([body], { type: mime })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

export default function CreditRiskPage() {
  return (
    <AuthGuard permission="lending:risk:view">
      <CreditRiskConsole />
    </AuthGuard>
  )
}

function CreditRiskConsole() {
  const { t, language } = useLanguage()
  const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const dateLocale = numberLocale
  const [decisions, setDecisions] = useState<Loaded<Decision[]> | null>(null)
  const [summary, setSummary] = useState<Loaded<OutcomeSummary[]> | null>(null)
  const [portfolio, setPortfolio] = useState<Loaded<LoanRisk[]> | null>(null)
  const [policy, setPolicy] = useState<Loaded<Policy> | null>(null)
  const [loading, setLoading] = useState(true)
  const [jurisdiction, setJurisdiction] = useState<string>('')
  const [totalDsti, setTotalDsti] = useState(false)
  const [tab, setTab] = useState<'decisions' | 'policy' | 'portfolio'>('decisions')

  const load = useCallback(async () => {
    setLoading(true)
    const [d, s, p, pol] = await Promise.all([
      getJson<Decision[]>(svcUrl('lending-service', '/api/v1/lending/risk/decisions', { limit: String(DECISION_LIMIT) })),
      getJson<OutcomeSummary[]>(svcUrl('lending-service', '/api/v1/lending/risk/decisions/summary')),
      getJson<LoanRisk[]>(svcUrl('lending-service', '/api/v1/lending/risk/portfolio', { limit: String(PORTFOLIO_LIMIT) })),
      getJson<Policy>(svcUrl('lending-service', '/api/v1/lending/risk/policy')),
    ])
    setDecisions(d); setSummary(s); setPortfolio(p); setPolicy(pol)
    setLoading(false)
  }, [])

  useEffect(() => { void load() }, [load])

  const allDecisions = useMemo(() => decisions?.data ?? [], [decisions])
  const jurisdictions = useMemo(() => [...new Set(allDecisions.map(d => d.jurisdiction ?? '—'))].sort(), [allDecisions])
  const visible = useMemo(
    () => (jurisdiction ? allDecisions.filter(d => (d.jurisdiction ?? '—') === jurisdiction) : allDecisions),
    [allDecisions, jurisdiction],
  )

  // Book-wide totals from the DB summary; a jurisdiction filter can only be applied to the loaded
  // list, so the tiles switch source and SAY so in the hint.
  const totals = useMemo(() => (summary?.data ? outcomeTotals(summary.data) : null), [summary])
  const filteredTotals = useMemo(() => {
    const acc = { APPROVE: 0, REFER: 0, DECLINE: 0, total: 0 }
    for (const d of visible) { if (d.engineOutcome in acc) acc[d.engineOutcome as 'APPROVE'] += 1; acc.total += 1 }
    return acc
  }, [visible])
  const kpiSource = jurisdiction ? filteredTotals : totals
  const kpiHint = jurisdiction
    ? t(`z ${visible.length} načtených (filtr ${jurisdiction})`, `of ${visible.length} loaded (filter ${jurisdiction})`)
    : t('celá kniha (agregováno v DB)', 'whole book (DB-grouped)')

  const weekly = useMemo(() => weeklyOutcomes(visible), [visible])
  const pareto = useMemo(() => reasonPareto(visible), [visible])
  const hits = useMemo(() => ruleHits(visible), [visible])
  const matrix = useMemo(() => overrideMatrix(visible), [visible])
  const overrides = useMemo(() => overrideRate(matrix), [matrix])
  const bands = useMemo(() => (summary?.data ? priceBandTotals(summary.data) : []), [summary])
  const perJurisdiction = useMemo(() => byJurisdiction(allDecisions), [allDecisions])
  const dstiLimit = threshold(policy?.data ?? null, 'AFFORDABILITY', 'DSTI')
  const dtiLimit = threshold(policy?.data ?? null, 'AFFORDABILITY', 'DTI')
  const mixes = useMemo(() => portfolioMix(portfolio?.data ?? []), [portfolio])
  const primary = mixes[0] ?? null
  const vintages = useMemo(() => vintage(portfolio?.data ?? []), [portfolio])
  const unavailable = useMemo(() => {
    const parts: string[] = []
    if (decisions && !decisions.ok) parts.push('/risk/decisions')
    if (summary && !summary.ok) parts.push('/risk/decisions/summary')
    if (portfolio && !portfolio.ok) parts.push('/risk/portfolio')
    if (policy && !policy.ok) parts.push('/risk/policy')
    return parts
  }, [decisions, summary, portfolio, policy])

  const rate = (n: number) => (kpiSource && kpiSource.total > 0 ? pct(n / kpiSource.total, numberLocale) : UNKNOWN)
  const money = (v: number, ccy: string) => v.toLocaleString(numberLocale, { style: 'currency', currency: ccy, maximumFractionDigits: 0 })
  const th = { padding: '8px 12px', fontSize: 11, color: 'var(--text-tertiary)', textAlign: 'left' } as const
  const td = { padding: '8px 12px', fontSize: 13 } as const

  return (
    <div>
      <PageHeader
        title={t('Kreditní riziko a decisioning', 'Credit risk & decisioning')}
        subtitle={t(
          'Co engine rozhoduje a proč, proti jaké politice, kde sedí bonitní práh a jak je kniha stagovaná podle IFRS 9. Jen čtení — rozhodnutí se schvalují ve frontě schvalování (ADR-0227).',
          'What the engine decides and why, against which policy, where the affordability floor sits, and how the book is staged under IFRS 9. Read-only — decisions are approved in the approval inbox (ADR-0227).',
        )}
        icon={<ShieldAlert size={18} style={{ color: 'var(--accent)' }} />}
        actions={
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <select value={jurisdiction} onChange={e => setJurisdiction(e.target.value)} aria-label={t('Filtr jurisdikce', 'Jurisdiction filter')} className="btn btn-secondary" style={{ fontSize: 12 }}>
              <option value="">{t('Všechny jurisdikce', 'All jurisdictions')}</option>
              {jurisdictions.map(j => <option key={j} value={j}>{j}</option>)}
            </select>
            <button type="button" className="btn btn-secondary" style={{ fontSize: 12, display: 'flex', gap: 6, alignItems: 'center' }}
              disabled={visible.length === 0}
              aria-label={t('Exportovat rozhodnutí jako CSV', 'Export decisions as CSV')}
              onClick={() => download(`credit-decisions-${new Date().toISOString().slice(0, 10)}.csv`, 'text/csv;charset=utf-8', decisionsToCsv(visible))}>
              <FileText size={14} aria-hidden="true" /> CSV
            </button>
            <button type="button" className="btn btn-secondary" style={{ fontSize: 12, display: 'flex', gap: 6, alignItems: 'center' }}
              disabled={visible.length === 0}
              aria-label={t('Exportovat rozhodnutí jako JSON pro notebook', 'Export decisions as JSON for a notebook')}
              onClick={() => download(`credit-decisions-${new Date().toISOString().slice(0, 10)}.json`, 'application/json', JSON.stringify({ policy: policy?.data ?? null, decisions: visible, portfolio: portfolio?.data ?? [] }, null, 2))}>
              <FileText size={14} aria-hidden="true" /> JSON
            </button>
            <button onClick={load} disabled={loading} type="button" aria-busy={loading}
              aria-label={t('Obnovit kreditní riziko', 'Refresh credit risk')} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
              <RefreshCw size={14} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
            </button>
          </div>
        }
      />

      {unavailable.length > 0 && (
        <div className="card" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--danger)', color: 'var(--danger)', fontSize: 13 }}>
          {t('lending-service neodpověděl na:', 'lending-service did not answer:')} {unavailable.join(', ')}
        </div>
      )}

      {/* Provenance first. A risk committee reading a table must know whether anyone can change it. */}
      {policy?.data && (
        <div className="card" style={{ padding: 12, marginBottom: 16, borderLeft: `3px solid ${policy.data.codeSeeded ? 'var(--warning)' : 'var(--success)'}`, fontSize: 13, display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
          <AlertTriangle size={14} style={{ color: policy.data.codeSeeded ? 'var(--warning)' : 'var(--success)' }} aria-hidden="true" />
          <span>
            {policy.data.codeSeeded
              ? t(`Politika je zadrátovaná v kódu (${policy.data.source}, ADR-0213 D3). Změna = PR + release, ne four-eyes aktivace v UI (D4 table store není postaven).`,
                  `Policy is code-seeded (${policy.data.source}, ADR-0213 D3). Changing it is a PR + release, not a four-eyes activation here (the D4 table store is not built).`)
              : t(`Politika z governovaného úložiště: ${policy.data.source}.`, `Policy from the governed store: ${policy.data.source}.`)}
          </span>
          <span style={{ color: 'var(--text-tertiary)' }}>
            {t('Bureau port je no-op → EXCLUSION nikdy nezasáhne. PD/LGD jsou placeholder konstanty (viz model version v portfoliu). ML (ADR-0142) nenasazeno.',
               'Bureau port is a no-op → EXCLUSION can never fire. PD/LGD are placeholder constants (see model version under portfolio). ML (ADR-0142) not deployed.')}
          </span>
        </div>
      )}

      <div className="grid-4" style={{ marginBottom: 12 }}>
        <StatCard label={t('Schváleno enginem', 'Engine approve rate')} value={kpiSource ? rate(kpiSource.APPROVE) : UNKNOWN} tone={kpiSource ? 'success' : undefined} hint={kpiSource ? `${kpiSource.APPROVE.toLocaleString(numberLocale)} · ${kpiHint}` : t('čeká na data', 'waiting for data')} icon={<Activity size={13} />} />
        <StatCard label={t('K lidskému posouzení', 'Engine refer rate')} value={kpiSource ? rate(kpiSource.REFER) : UNKNOWN} tone={kpiSource && kpiSource.REFER > 0 ? 'warning' : undefined} hint={kpiSource ? `${kpiSource.REFER.toLocaleString(numberLocale)} · ${kpiHint}` : t('čeká na data', 'waiting for data')} icon={<Scale size={13} />} />
        <StatCard label={t('Zamítnuto enginem', 'Engine decline rate')} value={kpiSource ? rate(kpiSource.DECLINE) : UNKNOWN} tone={kpiSource && kpiSource.DECLINE > 0 ? 'danger' : undefined} hint={kpiSource ? `${kpiSource.DECLINE.toLocaleString(numberLocale)} · ${kpiHint}` : t('čeká na data', 'waiting for data')} icon={<AlertTriangle size={13} />} />
        <StatCard label={t('Přebití člověkem', 'Human override rate')} value={decisions?.ok ? pct(overrides, numberLocale) : UNKNOWN} tone={overrides !== null && overrides > 0 ? 'warning' : undefined} hint={decisions?.ok ? t(`z ${visible.length} načtených, jen již rozhodnuté`, `of ${visible.length} loaded, disposed only`) : t('čeká na data', 'waiting for data')} icon={<Layers size={13} />} />
      </div>
      <div className="grid-4" style={{ marginBottom: 20 }}>
        <StatCard label={t('Stage 2+3 z expozice', 'Stage 2+3 share of exposure')} value={primary ? pct(primary.stage23Share, numberLocale) : UNKNOWN} tone={primary && (primary.stage23Share ?? 0) > 0 ? 'warning' : undefined} hint={primary ? `${primary.currency} · ${t(`${primary.assessed} posouzených úvěrů`, `${primary.assessed} assessed loans`)}` : t('čeká na data', 'waiting for data')} />
        <StatCard label={t('ECL krytí', 'ECL coverage')} value={primary ? pct(primary.coverage, numberLocale) : UNKNOWN} hint={primary ? `${money(primary.totalEcl, primary.currency)} / ${money(primary.totalOutstanding, primary.currency)}` : t('čeká na data', 'waiting for data')} />
        <StatCard label={t('90+ DPD z expozice', '90+ DPD share of exposure')} value={primary ? pct(primary.npl90Share, numberLocale) : UNKNOWN} tone={primary && (primary.npl90Share ?? 0) > 0 ? 'danger' : undefined} hint={primary ? t('podle posledního provisioning cyklu', 'per the latest provisioning cycle') : t('čeká na data', 'waiting for data')} />
        <StatCard label={t('Nikdy neposouzeno', 'Never assessed')} value={portfolio?.ok ? (primary?.unassessed ?? 0).toLocaleString(numberLocale) : UNKNOWN} tone={primary && primary.unassessed > 0 ? 'warning' : undefined} hint={portfolio?.ok ? t('úvěrů bez provisioning záznamu', 'loans with no provisioning record') : t('čeká na data', 'waiting for data')} />
      </div>

      <div style={{ display: 'flex', gap: 4, marginBottom: 12, flexWrap: 'wrap' }}>
        {(['decisions', 'policy', 'portfolio'] as const).map(id => (
          <button key={id} type="button" onClick={() => setTab(id)} aria-pressed={tab === id}
            aria-label={id === 'decisions' ? t('Zobrazit rozhodnutí', 'Show decisions') : id === 'policy' ? t('Zobrazit politiku', 'Show policy') : t('Zobrazit portfolio', 'Show portfolio')}
            style={{ padding: '6px 12px', fontSize: 12, fontWeight: 600, borderRadius: 6, border: 'none', cursor: 'pointer', background: tab === id ? 'var(--accent)' : 'var(--surface-3)', color: tab === id ? '#fff' : 'var(--text-secondary)' }}>
            {id === 'decisions' ? t('Rozhodnutí', 'Decisions') : id === 'policy' ? t('Politika', 'Policy') : t('Portfolio IFRS 9', 'IFRS 9 portfolio')}
          </button>
        ))}
      </div>

      {tab === 'decisions' && (
        <div style={{ display: 'grid', gap: 16 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: 16 }}>
            <section className="card" style={{ padding: 14 }} aria-label={t('Vývoj výsledků po týdnech', 'Outcome trend by week')}>
              <h3 style={{ fontSize: 13, margin: '0 0 8px' }}>{t('Výsledky enginu po týdnech', 'Engine outcomes by week')}</h3>
              {weekly.length ? <OutcomeTrend data={weekly} /> : <Empty />}
            </section>
            <section className="card" style={{ padding: 14 }} aria-label={t('Pareto důvodů', 'Reason Pareto')}>
              <h3 style={{ fontSize: 13, margin: '0 0 8px' }}>{t('Důvody REFER/DECLINE (reason code · pravidlo)', 'REFER/DECLINE reasons (reason code · rule)')}</h3>
              {pareto.length ? <ReasonPareto data={pareto} /> : <Empty />}
            </section>
          </div>
          <section className="card" style={{ padding: 14 }} aria-label={t('Bonita proti politice', 'Affordability against policy')}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
              <h3 style={{ fontSize: 13, margin: 0 }}>
                {t('DSTI × DTI, prahy čtené z politiky', 'DSTI × DTI, thresholds read from policy')}
                {dstiLimit !== null && <span style={{ color: 'var(--text-tertiary)', fontWeight: 400 }}> · DSTI ≤ {dstiLimit.toLocaleString(numberLocale)}</span>}
                {dtiLimit !== null && <span style={{ color: 'var(--text-tertiary)', fontWeight: 400 }}> · DTI ≤ {dtiLimit.toLocaleString(numberLocale)}</span>}
              </h3>
              <label style={{ fontSize: 12, display: 'flex', gap: 6, alignItems: 'center' }}>
                <input type="checkbox" checked={totalDsti} onChange={e => setTotalDsti(e.target.checked)} />
                {t('DSTI vč. stávající dluhové služby (ČNB definice; engine ji dnes NEČTE)', 'DSTI incl. existing debt service (CNB definition; the engine does NOT read it today)')}
              </label>
            </div>
            {visible.some(d => d.affordability) ? <AffordabilityScatter decisions={visible} dstiLimit={dstiLimit} dtiLimit={dtiLimit} includeExistingDebt={totalDsti} /> : <Empty />}
          </section>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: 16 }}>
            <section className="card" style={{ padding: 0, overflow: 'hidden' }} aria-label={t('Engine versus člověk', 'Engine versus human')}>
              <h3 style={{ fontSize: 13, margin: 0, padding: 14 }}>{t('Engine × konečný stav', 'Engine × final disposition')}</h3>
              <table style={{ width: '100%', borderCollapse: 'collapse' }} data-testid="override-matrix">
                <thead><tr style={{ background: 'var(--surface-2)' }}>
                  <th style={th}>{t('Engine', 'Engine')}</th><th style={th}>{t('Schváleno', 'Approved')}</th><th style={th}>{t('Zamítnuto', 'Declined')}</th><th style={th}>{t('Zaniklo', 'Lapsed')}</th><th style={th}>{t('V běhu', 'In flight')}</th><th style={th}>{t('Přebito', 'Overridden')}</th>
                </tr></thead>
                <tbody>{matrix.map(r => (
                  <tr key={r.engine} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={td}><StatusBadge status={r.engine} tone={OUTCOME_TONE[r.engine]} /></td>
                    <td style={td}>{r.approved}</td><td style={td}>{r.declined}</td><td style={td}>{r.lapsed}</td><td style={td}>{r.inFlight}</td>
                    <td style={{ ...td, fontWeight: 700, color: r.overridden > 0 ? 'var(--warning-text)' : undefined }}>{r.overridden}</td>
                  </tr>
                ))}</tbody>
              </table>
            </section>
            <section className="card" style={{ padding: 0, overflow: 'hidden' }} aria-label={t('Podle jurisdikce a pásma', 'By jurisdiction and band')}>
              <h3 style={{ fontSize: 13, margin: 0, padding: 14 }}>{t('Jurisdikce a cenová pásma', 'Jurisdictions and price bands')}</h3>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead><tr style={{ background: 'var(--surface-2)' }}>
                  <th style={th}>{t('Jurisdikce', 'Jurisdiction')}</th><th style={th}>APPROVE</th><th style={th}>REFER</th><th style={th}>DECLINE</th><th style={th}>{t('Celkem', 'Total')}</th>
                </tr></thead>
                <tbody>{perJurisdiction.map(r => (
                  <tr key={r.jurisdiction} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={td}>{r.jurisdiction}</td><td style={td}>{r.APPROVE}</td><td style={td}>{r.REFER}</td><td style={td}>{r.DECLINE}</td><td style={td}>{r.total}</td>
                  </tr>
                ))}</tbody>
              </table>
              <div style={{ padding: 14, display: 'flex', gap: 8, flexWrap: 'wrap', fontSize: 12 }}>
                {bands.length === 0 && <span style={{ color: 'var(--text-tertiary)' }}>{t('Žádné schválené pásmo', 'No approved band yet')}</span>}
                {bands.map(b => <span key={b.band}><StatusBadge status={b.band} tone="accent" /> {b.count.toLocaleString(numberLocale)}</span>)}
              </div>
            </section>
          </div>
          <section className="card" style={{ padding: 0, overflow: 'hidden' }} aria-label={t('Poslední rozhodnutí', 'Latest decisions')}>
            <h3 style={{ fontSize: 13, margin: 0, padding: 14 }}>{t(`Poslední rozhodnutí (${visible.length} z max. ${DECISION_LIMIT})`, `Latest decisions (${visible.length} of at most ${DECISION_LIMIT})`)}</h3>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead><tr style={{ background: 'var(--surface-2)' }}>
                  <th style={th}>{t('Klient', 'Party')}</th><th style={th}>{t('Částka', 'Amount')}</th><th style={th}>DSTI</th><th style={th}>DTI</th><th style={th}>{t('Engine', 'Engine')}</th><th style={th}>{t('Pásmo', 'Band')}</th><th style={th}>{t('Důvody', 'Reasons')}</th><th style={th}>{t('Stav', 'Status')}</th><th style={th}>{t('Vyhodnoceno', 'Evaluated')}</th><th style={th} />
                </tr></thead>
                <tbody>
                  {visible.slice(0, 100).map(d => (
                    <tr key={d.applicationId} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={td}><EntityChip type="party" id={d.partyId} /></td>
                      <td style={{ ...td, fontWeight: 600 }}>{money(d.requestedAmount, d.currency)}</td>
                      <td style={td}>{d.affordability ? d.affordability.dsti.toLocaleString(numberLocale, { maximumFractionDigits: 3 }) : UNKNOWN}</td>
                      <td style={td}>{d.affordability ? d.affordability.dti.toLocaleString(numberLocale, { maximumFractionDigits: 2 }) : UNKNOWN}</td>
                      <td style={td}><StatusBadge status={d.engineOutcome} tone={OUTCOME_TONE[d.engineOutcome] ?? 'neutral'} /></td>
                      <td style={td}>{d.priceBand ?? UNKNOWN}</td>
                      <td style={{ ...td, fontSize: 11, color: 'var(--text-secondary)' }}>{d.reasons.length ? d.reasons.map(r => `${r.code}${r.ruleId ? ` (${r.ruleId})` : ''}`).join(', ') : UNKNOWN}</td>
                      <td style={td}><StatusBadge status={d.status} /></td>
                      <td style={{ ...td, color: 'var(--text-tertiary)', fontSize: 12 }}>{d.decidedEngineAt ? new Date(d.decidedEngineAt).toLocaleString(dateLocale) : UNKNOWN}</td>
                      <td style={td}><Link href={`/lending/applications/${d.applicationId}`} style={{ color: 'var(--accent)', fontSize: 12 }}>{t('Evidence', 'Evidence')} ›</Link></td>
                    </tr>
                  ))}
                  {!loading && visible.length === 0 && <tr><td colSpan={10} style={{ padding: 20, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>{t('Engine zatím nic nevyhodnotil', 'The engine has evaluated nothing yet')}</td></tr>}
                </tbody>
              </table>
            </div>
          </section>
        </div>
      )}

      {tab === 'policy' && (
        policy?.data
          ? <PolicyTables policy={policy.data} hits={hits} sample={visible.length} />
          : <div className="card" style={{ padding: 20, color: 'var(--text-tertiary)', fontSize: 13 }}>{t('Politika nenačtena', 'Policy not loaded')}</div>
      )}

      {tab === 'portfolio' && (
        <div style={{ display: 'grid', gap: 16 }}>
          {mixes.length === 0 && <div className="card" style={{ padding: 20, color: 'var(--text-tertiary)', fontSize: 13 }}>{portfolio?.ok ? t('Prázdná kniha', 'Empty book') : t('Portfolio nenačteno', 'Portfolio not loaded')}</div>}
          {mixes.map(mix => (
            <div key={mix.currency} style={{ display: 'grid', gap: 16 }}>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 16 }}>
                <section className="card" style={{ padding: 14 }} aria-label={t(`Stage mix ${mix.currency}`, `Stage mix ${mix.currency}`)}>
                  <h3 style={{ fontSize: 13, margin: '0 0 8px' }}>{t(`Expozice podle IFRS 9 stage (${mix.currency})`, `Exposure by IFRS 9 stage (${mix.currency})`)}</h3>
                  {mix.assessed ? <StageMixPie stages={mix.stages} /> : <Empty />}
                </section>
                <section className="card" style={{ padding: 14 }} aria-label={t('DPD buckety', 'DPD buckets')}>
                  <h3 style={{ fontSize: 13, margin: '0 0 8px' }}>{t('Úvěry podle dnů po splatnosti', 'Loans by days past due')}</h3>
                  {mix.assessed ? <BucketBars buckets={mix.buckets} /> : <Empty />}
                </section>
                <section className="card" style={{ padding: 0, overflow: 'hidden' }} aria-label={t('ECL po stage', 'ECL by stage')}>
                  <h3 style={{ fontSize: 13, margin: 0, padding: 14 }}>{t('ECL a krytí po stage', 'ECL and coverage by stage')}</h3>
                  <table style={{ width: '100%', borderCollapse: 'collapse' }} data-testid="stage-table">
                    <thead><tr style={{ background: 'var(--surface-2)' }}><th style={th}>Stage</th><th style={th}>{t('Úvěry', 'Loans')}</th><th style={th}>{t('Expozice', 'Outstanding')}</th><th style={th}>ECL</th><th style={th}>{t('Krytí', 'Coverage')}</th></tr></thead>
                    <tbody>{mix.stages.map(s => (
                      <tr key={s.stage} style={{ borderTop: '1px solid var(--border)' }}>
                        <td style={td}><span style={{ display: 'inline-block', width: 8, height: 8, borderRadius: 4, background: C_STAGE[s.stage], marginRight: 6 }} />{s.stage.replace('_', ' ')}</td>
                        <td style={td}>{s.count}</td><td style={td}>{money(s.outstanding, mix.currency)}</td><td style={td}>{money(s.ecl, mix.currency)}</td><td style={td}>{pct(s.coverage, numberLocale)}</td>
                      </tr>
                    ))}</tbody>
                  </table>
                  <div style={{ padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' }}>
                    {t('PD/LGD model:', 'PD/LGD model:')} <code>{mix.modelVersions.join(', ') || UNKNOWN}</code> · {t(`${mix.unassessed} úvěrů bez posouzení`, `${mix.unassessed} loans not assessed`)}
                  </div>
                </section>
              </div>
            </div>
          ))}
          {vintages.length > 0 && (
            <section className="card" style={{ padding: 0, overflow: 'hidden' }} aria-label={t('Vintage', 'Vintage')}>
              <h3 style={{ fontSize: 13, margin: 0, padding: 14 }}>{t('Vintage: měsíc čerpání × aktuální stage', 'Vintage: disbursement month × current stage')}</h3>
              <table style={{ width: '100%', borderCollapse: 'collapse' }} data-testid="vintage-table">
                <thead><tr style={{ background: 'var(--surface-2)' }}><th style={th}>{t('Měsíc', 'Month')}</th><th style={th}>{t('Úvěry', 'Loans')}</th><th style={th}>Stage 1</th><th style={th}>Stage 2</th><th style={th}>Stage 3</th><th style={th}>{t('Neposouzeno', 'Unassessed')}</th></tr></thead>
                <tbody>{vintages.map(v => (
                  <tr key={v.month} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={td}>{v.month}</td><td style={td}>{v.loans}</td>
                    {(['STAGE_1', 'STAGE_2', 'STAGE_3'] as const).map(s => (
                      <td key={s} style={{ ...td, background: v[s] ? `${C_STAGE[s]}${Math.min(0.85, 0.15 + v[s] / v.loans).toString(16).slice(2, 4).padStart(2, '0')}` : undefined }}>{v[s]}</td>
                    ))}
                    <td style={{ ...td, color: v.unassessed ? 'var(--warning-text)' : undefined }}>{v.unassessed}</td>
                  </tr>
                ))}</tbody>
              </table>
            </section>
          )}
        </div>
      )}
    </div>
  )
}

function Empty() {
  const { t } = useLanguage()
  return <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>{t('Zatím žádná data', 'No data yet')}</div>
}
