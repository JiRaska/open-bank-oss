// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// IRRBB of one balance-sheet snapshot run (risk-engine GET /snapshots/{id}/irrbb, ADR-0313 phase 1).
//
// HONESTY RULES
//   - Tier 1 is never fetched or guessed: the outlier ratio appears ONLY when the user typed a
//     Tier 1 figure and the engine computed a ratio from it.
//   - A currency without configured shock sizes is listed as NOT CONFIGURED, never shown as zero.
//   - The assumptions the engine used (model, shock source, floor, aggregation) are shown with the
//     figures, and provenance is on the page (synthetic data labelled).

'use client'

import { use, useEffect, useState } from 'react'
import dynamic from 'next/dynamic'
import Link from 'next/link'
import { ArrowLeft, Activity } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl } from '@/components/balance-sheet/api'
import { curveSetListSchema, irrbbSchema, type CurveSetSummary, type Irrbb, type ScenarioName } from '@/components/balance-sheet/contracts'
import { gapRows, outlierRatio, parseTier1 } from '@/components/balance-sheet/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const RepricingGapChart = dynamic(
  () => import('@/components/balance-sheet/charts').then(module => module.RepricingGapChart),
  { ssr: false, loading: () => <div style={{ height: 280 }} aria-hidden="true" /> },
)

const SCENARIO_LABEL: Record<ScenarioName, [string, string]> = {
  'parallel-up': ['Paralelní posun nahoru', 'Parallel up'],
  'parallel-down': ['Paralelní posun dolů', 'Parallel down'],
  steepener: ['Zestrmění', 'Steepener'],
  flattener: ['Zploštění', 'Flattener'],
  'short-up': ['Krátké sazby nahoru', 'Short rates up'],
  'short-down': ['Krátké sazby dolů', 'Short rates down'],
}

export default function SnapshotIrrbbPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return (
    <AuthGuard permission="balance-sheet:view">
      <SnapshotIrrbb id={id} />
    </AuthGuard>
  )
}

function SnapshotIrrbb({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const [curveSets, setCurveSets] = useState<CurveSetSummary[]>([])
  const [curveSetId, setCurveSetId] = useState('')
  const [tier1Input, setTier1Input] = useState('')
  const [tier1, setTier1] = useState<string | null>(null)
  const [data, setData] = useState<Irrbb | null>(null)
  const [kind, setKind] = useState<UnavailableKind | null>(null)
  const [setsKind, setSetsKind] = useState<UnavailableKind | null>(null)

  useEffect(() => {
    void (async () => {
      const sets = await getJson(riskUrl('/api/v1/risk/curve-sets', { limit: '25' }), curveSetListSchema)
      if (!sets.ok) { setSetsKind(sets.kind); return }
      setCurveSets(sets.data.curveSets)
      if (sets.data.curveSets.length > 0) setCurveSetId(prev => prev || sets.data.curveSets[0].id)
    })()
  }, [])

  useEffect(() => {
    if (!curveSetId) return
    let cancelled = false
    void (async () => {
      const query: Record<string, string> = { curveSetId }
      if (tier1) query.tier1Capital = tier1
      const res = await getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(id)}/irrbb`, query), irrbbSchema)
      if (cancelled) return
      if (res.ok) { setData(res.data); setKind(null) } else { setData(null); setKind(res.kind) }
    })()
    return () => { cancelled = true }
  }, [id, curveSetId, tier1])

  const back = (
    <Link href={`/balance-sheet/snapshots/${encodeURIComponent(id)}`} className="btn btn-secondary btn-sm" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <ArrowLeft size={14} aria-hidden="true" /> {t('Zpět na snímek', 'Back to snapshot')}
    </Link>
  )
  const selectedSet = curveSets.find(s => s.id === curveSetId)
  const ratio = data ? outlierRatio(data) : null
  const tier1Invalid = tier1Input.trim() !== '' && parseTier1(tier1Input) === null

  return (
    <div>
      <PageHeader
        title={t('Úrokové riziko bankovní knihy (IRRBB)', 'Interest-rate risk in the banking book (IRRBB)')}
        subtitle={t(`Běh ${id}`, `Run ${id}`)}
        icon={<Activity size={20} aria-hidden="true" />}
        actions={back}
      />

      <div className="card" style={{ marginBottom: 16, display: 'flex', gap: 12, alignItems: 'end', flexWrap: 'wrap' }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
          {t('Sada křivek', 'Curve set')}
          <select className="input" value={curveSetId} onChange={e => setCurveSetId(e.target.value)} aria-label={t('Sada výnosových křivek', 'Yield-curve set')}>
            {curveSets.map(s => <option key={s.id} value={s.id}>{`${s.asOf} · ${s.source} · ${s.provenance}`}</option>)}
          </select>
        </label>
        <form
          onSubmit={e => { e.preventDefault(); setTier1(parseTier1(tier1Input)) }}
          style={{ display: 'flex', gap: 8, alignItems: 'end' }}
        >
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
            {t('Kapitál Tier 1 (volitelné)', 'Tier 1 capital (optional)')}
            <input className="input" inputMode="decimal" value={tier1Input} onChange={e => setTier1Input(e.target.value)} aria-invalid={tier1Invalid} aria-label={t('Kapitál Tier 1', 'Tier 1 capital')} />
          </label>
          <button type="submit" className="btn btn-secondary btn-sm" disabled={tier1Invalid}>{t('Použít', 'Apply')}</button>
        </form>
        {selectedSet && <ProvenanceBadge provenance={selectedSet.provenance} />}
        {data && <ProvenanceBadge provenance={data.provenance} />}
      </div>

      {setsKind ? (
        <DataUnavailable kind={setsKind} service="risk-engine" feature={t('sady výnosových křivek', 'curve sets')} lang={language} />
      ) : curveSets.length === 0 ? (
        <DataUnavailable kind="no_data" service="risk-engine" feature={t('sady výnosových křivek — nahrajte sadu v sekci Výnosové křivky', 'curve sets — upload one under Curve sets')} lang={language} />
      ) : kind ? (
        <DataUnavailable kind={kind} service="risk-engine" feature="IRRBB" lang={language} />
      ) : data ? (
        <>
          {data.shockNotConfigured.length > 0 && (
            <div role="note" className="card" style={{ marginBottom: 12, borderColor: 'var(--warning, var(--border))' }}>
              <StatusBadge status="NOT_CONFIGURED" tone="warning" label={t('Šoky nenastaveny', 'Shocks not configured')} />{' '}
              <span style={{ fontSize: 12 }}>
                {t(
                  `Pro tyto měny nejsou nastaveny velikosti šoků, scénáře se nepočítají: ${data.shockNotConfigured.join(', ')}`,
                  `No shock sizes are configured for these currencies, so no scenarios are computed: ${data.shockNotConfigured.join(', ')}`,
                )}
              </span>
            </div>
          )}
          {data.unpriced.length > 0 && (
            <div role="note" className="card" style={{ marginBottom: 12, borderColor: 'var(--warning, var(--border))' }}>
              <StatusBadge status="UNPRICED" tone="warning" label={t('Neoceněno', 'Unpriced')} />{' '}
              <span style={{ fontSize: 12 }}>{data.unpriced.join(', ')}</span>
            </div>
          )}

          <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
            <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{t('Scénáře šoků: ΔEVE a ΔNII', 'Shock scenarios: ΔEVE and ΔNII')}</h2>
            <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
              {t('ΔEVE = současná hodnota po šoku − výchozí; záporná hodnota je ztráta. ΔNII za 12 měsíců jen pro paralelní scénáře.', 'ΔEVE = shocked PV − base PV; negative is a loss. ΔNII over 12 months for the parallel scenarios only.')}
            </p>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Scénář', 'Scenario')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>ΔEVE</th>
                  <th scope="col" style={{ textAlign: 'right' }}>ΔNII</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Agregovaná ztráta', 'Aggregate loss')}</th>
                </tr>
              </thead>
              <tbody>
                {data.scenarios.flatMap(s => s.currencies.map(c => {
                  const worst = data.worstCase.scenario === s.scenario || data.worstCase.byCurrency[c.currency] === s.scenario
                  return (
                    <tr key={`${s.scenario}-${c.currency}`} data-worst={worst ? 'true' : undefined} style={worst ? { fontWeight: 600, background: 'var(--danger-bg, transparent)' } : undefined}>
                      <td>
                        {t(SCENARIO_LABEL[s.scenario][0], SCENARIO_LABEL[s.scenario][1])}
                        {worst && <> <StatusBadge status="WORST" tone="danger" label={t('Nejhorší', 'Worst')} /></>}
                      </td>
                      <td>{c.currency}</td>
                      <td style={{ textAlign: 'right' }}>{money(c.deltaEve)}</td>
                      <td style={{ textAlign: 'right' }}>{typeof c.deltaNii === 'number' ? money(c.deltaNii) : '—'}</td>
                      <td style={{ textAlign: 'right' }}>{typeof s.aggregateLoss === 'number' ? money(s.aggregateLoss) : '—'}</td>
                    </tr>
                  )
                }))}
              </tbody>
            </table>
          </div>

          <div className="card" style={{ marginBottom: 16 }}>
            <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{t('Test odlehlých hodnot (ΔEVE / Tier 1)', 'Supervisory outlier test (ΔEVE / Tier 1)')}</h2>
            {ratio !== null ? (
              <p style={{ fontSize: 13 }}>
                {t('Poměr', 'Ratio')}: <strong>{(ratio * 100).toLocaleString(locale, { maximumFractionDigits: 2 })} %</strong>{' '}
                ({t('práh', 'threshold')} {(data.outlierTest.threshold * 100).toLocaleString(locale)} %){' '}
                <StatusBadge status={data.outlierTest.breached ? 'BREACHED' : 'WITHIN'} tone={data.outlierTest.breached ? 'danger' : 'success'} label={data.outlierTest.breached ? t('Překročeno', 'Breached') : t('V limitu', 'Within threshold')} />
              </p>
            ) : (
              <p style={{ fontSize: 13 }}>{data.outlierTest.tier1Supplied ? t('Poměr nelze spočítat.', 'The ratio cannot be computed.') : t('Tier 1 nezadán — poměr se nepočítá.', 'Tier 1 not supplied — the ratio is not computed.')}</p>
            )}
            <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{data.outlierTest.note}</p>
          </div>

          {data.gaps.map(g => (
            <section key={g.currency} className="card" style={{ marginBottom: 16, overflowX: 'auto' }} aria-label={t(`Přeceňovací mezera ${g.currency}`, `Repricing gap ${g.currency}`)}>
              <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{t(`Přeceňovací mezera ${g.currency}`, `Repricing gap ${g.currency}`)}</h2>
              <RepricingGapChart rows={gapRows(g)} locale={locale} />
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                  <tr>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Pásmo', 'Bucket')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Aktiva', 'Assets')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Pasiva', 'Liabilities')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Mezera', 'Gap')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Kumulativní mezera', 'Cumulative gap')}</th>
                  </tr>
                </thead>
                <tbody>
                  {g.buckets.map(b => (
                    <tr key={b.bucket}>
                      <td>{b.bucket}</td>
                      <td style={{ textAlign: 'right' }}>{money(b.assets)}</td>
                      <td style={{ textAlign: 'right' }}>{money(b.liabilities)}</td>
                      <td style={{ textAlign: 'right' }}>{money(b.gap)}</td>
                      <td style={{ textAlign: 'right' }}>{money(b.cumulativeGap)}</td>
                    </tr>
                  ))}
                  <tr style={{ fontWeight: 600 }}>
                    <td>{t('Celkem', 'Total')}</td>
                    <td style={{ textAlign: 'right' }}>{money(g.totalAssets)}</td>
                    <td style={{ textAlign: 'right' }}>{money(g.totalLiabilities)}</td>
                    <td style={{ textAlign: 'right' }}>{money(g.totalGap)}</td>
                    <td />
                  </tr>
                </tbody>
              </table>
            </section>
          ))}

          <div className="card">
            <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{t('Předpoklady', 'Assumptions')}</h2>
            <ul style={{ fontSize: 12, paddingLeft: 16, display: 'grid', gap: 4 }}>
              <li>{t('Model chování', 'Behavioural model')}: {data.assumptions.model.id} v{data.assumptions.model.version}</li>
              <li>{t('Zdroj šoků', 'Shock source')}: {data.assumptions.shockSource}; x = {data.assumptions.shortDecayYears}</li>
              <li>
                {t('Velikosti šoků (bp, paralelní/krátký/dlouhý)', 'Shock sizes (bp, parallel/short/long)')}:{' '}
                {data.assumptions.shockSizes.length === 0 ? '—' : data.assumptions.shockSizes.map(s => `${s.currency} ${s.parallelBp}/${s.shortBp}/${s.longBp}`).join(' · ')}
              </li>
              <li>
                {t('Spodní hranice po šoku', 'Post-shock floor')}:{' '}
                {data.assumptions.postShockFloor ? `${data.assumptions.postShockFloor.atZeroBp} bp + ${data.assumptions.postShockFloor.slopeBpPerYear} bp/${t('rok', 'year')}` : t('žádná', 'none')} — {data.assumptions.postShockFloorSource}
              </li>
              <li>{t('Přecenění NMD', 'NMD repricing')}: {data.assumptions.nmdRepricing}</li>
              <li>{t('Přecenění plovoucích úvěrů', 'Floating repricing')}: {data.assumptions.floatingRepricing}</li>
              <li>EVE: {data.assumptions.eveBasis}</li>
              <li>NII ({data.assumptions.niiHorizonMonths} {t('měs.', 'months')}): {data.assumptions.niiBasis}</li>
              <li>{t('Agregace měn', 'Currency aggregation')}: {data.assumptions.currencyAggregation}</li>
              <li>{t('Sada křivek', 'Curve set')}: {data.curveSetSource} ({data.curveSetProvenance})</li>
            </ul>
          </div>
        </>
      ) : null}
    </div>
  )
}
