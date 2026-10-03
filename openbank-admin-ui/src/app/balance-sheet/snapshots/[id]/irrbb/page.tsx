// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// IRRBB of one balance-sheet snapshot run (risk-engine GET /snapshots/{id}/irrbb, ADR-0313 phase 1).
//
// HONESTY RULES
//   - Tier 1 is never guessed. It is taken from this same run's Pillar 1 own-funds read (risk-engine
//     GET /snapshots/{id}/capital, total in CZK) and SAYS so, with the run date and parameter set;
//     the user may override it explicitly. Without either, no outlier ratio is shown.
//   - A currency without configured shock sizes is listed as NOT CONFIGURED, never shown as zero.
//   - The assumptions the engine used (model, shock source, floor, aggregation) are shown with the
//     figures, and provenance is on the page (synthetic data labelled).
//   - Only curve sets as of the run's own date are offered: the engine refuses any other.

'use client'

import { use, useEffect, useState } from 'react'
import dynamic from 'next/dynamic'
import Link from 'next/link'
import { ArrowLeft, Activity } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { CurveSetPicker, RunSubtitle, useCurveSets, useRun } from '@/components/balance-sheet/RunContext'
import { getJson, riskUrl } from '@/components/balance-sheet/api'
import { capitalSchema, irrbbSchema, type Irrbb, type ScenarioName } from '@/components/balance-sheet/contracts'
import { formatDate, formatMoney, formatTier1Input, gapRows, outlierRatio, outlierVerdict, parseTier1 } from '@/components/balance-sheet/model'
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

/** Where the Tier 1 figure in use came from: this run's own-funds read, or the user's override. */
type Tier1Source = { kind: 'capital'; asOf: string; parameterSet: string } | { kind: 'manual' }

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
  const { run, loaded: runLoaded } = useRun(id)
  const { sets: curveSetsOrNull, kind: setsKind } = useCurveSets(run, runLoaded)
  const curveSets = curveSetsOrNull ?? []
  const [chosenSetId, setChosenSetId] = useState('')
  const curveSetId = curveSets.some(s => s.id === chosenSetId) ? chosenSetId : (curveSets[0]?.id ?? '')
  const [tier1Input, setTier1Input] = useState('')
  const [tier1, setTier1] = useState<string | null>(null)
  const [tier1Source, setTier1Source] = useState<Tier1Source | null>(null)
  const [overriding, setOverriding] = useState(false)
  const [data, setData] = useState<Irrbb | null>(null)
  const [kind, setKind] = useState<UnavailableKind | null>(null)

  // Tier 1 from this run's own Pillar 1 own-funds read (CZK at the ČNB fixing). Absent or zero
  // own funds leave the field for the user — nothing is assumed.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(id)}/capital`), capitalSchema)
      if (cancelled || !res.ok) return
      const value = res.data.total?.ownFunds?.tier1
      if (typeof value !== 'number' || !(value > 0)) return
      setTier1(prev => prev ?? String(value))
      setTier1Source(prev => prev ?? { kind: 'capital', asOf: res.data.asOf, parameterSet: `${res.data.parameterSetId} v${res.data.parameterSetVersion}` })
    })()
    return () => { cancelled = true }
  }, [id])

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
  // The currency the engine compares Tier 1 in; CZK unless the book is single-currency in another.
  const tier1Currency = data?.outlierTest.currency ?? 'CZK'
  const showInput = tier1Source?.kind !== 'capital' || overriding
  const verdict = data ? outlierVerdict(ratio, data.outlierTest.threshold, language) : null
  const reporting = data?.reportingAggregate && !data.reportingAggregate.notStated ? data.reportingAggregate : null
  const reportingLoss = reporting ? new Map(reporting.scenarios.map(r => [r.scenario, r.loss])) : null
  const worstScenario = data ? (data.worstCase.scenario ?? reporting?.worstScenario ?? null) : null
  const worstLoss = data ? (data.worstCase.loss ?? reporting?.worstLoss ?? null) : null

  return (
    <div>
      <PageHeader
        title={t('Úrokové riziko bankovní knihy (IRRBB)', 'Interest-rate risk in the banking book (IRRBB)')}
        subtitle={<RunSubtitle id={id} run={run} />}
        icon={<Activity size={20} aria-hidden="true" />}
        actions={back}
      />

      <div className="card" style={{ marginBottom: 16, display: 'flex', gap: 16, alignItems: 'start', flexWrap: 'wrap' }}>
        {curveSetsOrNull !== null && (
          <CurveSetPicker sets={curveSets} value={curveSetId} onChange={setChosenSetId} asOf={run?.asOf ?? null} />
        )}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, maxWidth: 420 }}>
          <span>{t(`Kapitál Tier 1 v ${tier1Currency === 'CZK' ? 'Kč' : tier1Currency}`, `Tier 1 capital in ${tier1Currency}`)}</span>
          {tier1Source?.kind === 'capital' && !overriding && tier1 && (
            <span data-testid="tier1-from-capital">
              <strong>{formatMoney(Number(tier1), 'CZK', language)}</strong>{' — '}
              {t(
                `převzato z výpočtu kapitálu (Pilíř 1) tohoto snímku k ${formatDate(tier1Source.asOf, 'cs')}, sada parametrů ${tier1Source.parameterSet}.`,
                `taken from this snapshot's Pillar 1 own-funds read as of ${formatDate(tier1Source.asOf, 'en')}, parameter set ${tier1Source.parameterSet}.`,
              )}{' '}
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setOverriding(true); setTier1Input(formatTier1Input(tier1, language)) }}>
                {t('Zadat jinou hodnotu', 'Override')}
              </button>
            </span>
          )}
          {showInput && (
            <form
              onSubmit={e => {
                e.preventDefault()
                const v = parseTier1(tier1Input)
                setTier1(v)
                setTier1Source(v ? { kind: 'manual' } : null)
                setOverriding(false)
              }}
              style={{ display: 'flex', gap: 8, alignItems: 'end' }}
            >
              <input
                className="input"
                inputMode="decimal"
                placeholder={t('např. 1 250 000 000', 'e.g. 1,250,000,000')}
                value={tier1Input}
                onChange={e => setTier1Input(e.target.value)}
                onBlur={() => setTier1Input(v => formatTier1Input(v, language))}
                aria-invalid={tier1Invalid}
                aria-describedby="tier1-help"
                aria-label={t('Kapitál Tier 1', 'Tier 1 capital')}
              />
              <button type="submit" className="btn btn-secondary btn-sm" disabled={tier1Invalid}>{t('Použít', 'Apply')}</button>
            </form>
          )}
          {tier1Invalid && <span role="alert" style={{ color: 'var(--danger, inherit)' }}>{t('Zadejte kladné číslo, např. 1 250 000 000.', 'Enter a positive number, e.g. 1,250,000,000.')}</span>}
          {tier1Source?.kind === 'manual' && tier1 && !overriding && (
            <span data-testid="tier1-manual">{t(`Použita ručně zadaná hodnota ${formatMoney(Number(tier1), tier1Currency, 'cs')}.`, `Using the manually entered ${formatMoney(Number(tier1), tier1Currency, 'en')}.`)}</span>
          )}
          <span id="tier1-help" style={{ color: 'var(--text-secondary)' }}>
            {t(
              'Slouží k testu odlehlé hodnoty: změna EVE nad 15 % Tier 1 znamená, že banka je odlehlou institucí (EBA GL/2022/14).',
              'Used by the supervisory outlier test: an EVE change above 15 % of Tier 1 makes the bank an outlier (EBA GL/2022/14).',
            )}
          </span>
        </div>
        {selectedSet && <ProvenanceBadge provenance={selectedSet.provenance} />}
        {data && <ProvenanceBadge provenance={data.provenance} />}
      </div>

      {setsKind ? (
        <DataUnavailable kind={setsKind} service="risk-engine" feature={t('sady výnosových křivek', 'curve sets')} lang={language} />
      ) : curveSetsOrNull === null || curveSets.length === 0 ? null : kind ? (
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
                  {reportingLoss && <th scope="col" style={{ textAlign: 'right' }}>{t('Ztráta všech měn v Kč', 'Loss, all currencies in CZK')}</th>}
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
                      <td style={{ textAlign: 'right' }}>{formatMoney(c.deltaEve, c.currency, language)}</td>
                      <td style={{ textAlign: 'right' }}>{typeof c.deltaNii === 'number' ? formatMoney(c.deltaNii, c.currency, language) : '—'}</td>
                      <td style={{ textAlign: 'right' }}>{typeof s.aggregateLoss === 'number' ? formatMoney(s.aggregateLoss, data.worstCase.currency, language) : '—'}</td>
                      {reportingLoss && <td style={{ textAlign: 'right' }}>{reportingLoss.has(s.scenario) ? formatMoney(reportingLoss.get(s.scenario) ?? 0, 'CZK', language) : '—'}</td>}
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
                {verdict && <><br /><span data-testid="sot-verdict">{verdict}</span></>}
                {worstScenario && worstLoss !== null && (
                  <><br /><span style={{ fontSize: 12 }}>{t(
                    `Nejhorší scénář: ${SCENARIO_LABEL[worstScenario][0]}, ztráta EVE ${formatMoney(worstLoss, data.outlierTest.currency, 'cs')}, Tier 1 ${formatMoney(data.outlierTest.tier1Capital ?? 0, data.outlierTest.currency, 'cs')}.`,
                    `Worst scenario: ${SCENARIO_LABEL[worstScenario][1]}, EVE loss ${formatMoney(worstLoss, data.outlierTest.currency, 'en')}, Tier 1 ${formatMoney(data.outlierTest.tier1Capital ?? 0, data.outlierTest.currency, 'en')}.`,
                  )}</span></>
                )}
              </p>
            ) : (
              <p style={{ fontSize: 13 }}>{data.outlierTest.tier1Supplied ? t('Poměr nelze spočítat.', 'The ratio cannot be computed.') : t('Tier 1 nezadán — poměr se nepočítá.', 'Tier 1 not supplied — the ratio is not computed.')}</p>
            )}
            <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{data.outlierTest.note}</p>
            {data.reportingAggregate && data.reportingAggregate.fxRates.length > 0 && (
              <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                {t('Přepočet do Kč kurzem ČNB', 'Converted to CZK at the ČNB fixing')}:{' '}
                {data.reportingAggregate.fxRates.map(r => `${r.currency} ${r.rate.toLocaleString(locale)} (${formatDate(r.fixingDate, language)})`).join(' · ')}
              </p>
            )}
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
                      <td style={{ textAlign: 'right' }}>{formatMoney(b.assets, g.currency, language)}</td>
                      <td style={{ textAlign: 'right' }}>{formatMoney(b.liabilities, g.currency, language)}</td>
                      <td style={{ textAlign: 'right' }}>{formatMoney(b.gap, g.currency, language)}</td>
                      <td style={{ textAlign: 'right' }}>{formatMoney(b.cumulativeGap, g.currency, language)}</td>
                    </tr>
                  ))}
                  <tr style={{ fontWeight: 600 }}>
                    <td>{t('Celkem', 'Total')}</td>
                    <td style={{ textAlign: 'right' }}>{formatMoney(g.totalAssets, g.currency, language)}</td>
                    <td style={{ textAlign: 'right' }}>{formatMoney(g.totalLiabilities, g.currency, language)}</td>
                    <td style={{ textAlign: 'right' }}>{formatMoney(g.totalGap, g.currency, language)}</td>
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
