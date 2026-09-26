// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Liquidity survival forecast of one balance-sheet snapshot run (risk-engine
// GET /snapshots/{id}/liquidity-forecast, ADR-0313 forecasting).
//
// HONESTY RULES
//   - The survival horizon is shown only as the engine computed it: a null means "no breach within
//     the horizon", stated as such, never rendered as a day number or zero.
//   - Opening liquidity is the LCR's HQLA stock; a currency without HQLA says so instead of
//     showing a silent zero.
//   - The engine's assumptions — including what is NOT modelled — are listed with the figures, and
//     provenance is on the page (synthetic data labelled).

'use client'

import { use, useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, Droplets } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl } from '@/components/balance-sheet/api'
import { curveSetListSchema, liquidityForecastSchema, type CurveSetSummary, type LiquidityForecast } from '@/components/balance-sheet/contracts'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const HORIZONS = [30, 90, 180, 365] as const

export default function SnapshotLiquidityForecastPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return (
    <AuthGuard permission="balance-sheet:view">
      <SnapshotLiquidityForecast id={id} />
    </AuthGuard>
  )
}

function SnapshotLiquidityForecast({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const [curveSets, setCurveSets] = useState<CurveSetSummary[]>([])
  const [curveSetId, setCurveSetId] = useState('')
  const [horizon, setHorizon] = useState<number>(90)
  const [data, setData] = useState<LiquidityForecast | null>(null)
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
      const res = await getJson(
        riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(id)}/liquidity-forecast`, { curveSetId, horizonDays: String(horizon) }),
        liquidityForecastSchema,
      )
      if (cancelled) return
      if (res.ok) { setData(res.data); setKind(null) } else { setData(null); setKind(res.kind) }
    })()
    return () => { cancelled = true }
  }, [id, curveSetId, horizon])

  const back = (
    <Link href={`/balance-sheet/snapshots/${encodeURIComponent(id)}`} className="btn btn-secondary btn-sm" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <ArrowLeft size={14} aria-hidden="true" /> {t('Zpět na snímek', 'Back to snapshot')}
    </Link>
  )

  return (
    <div>
      <PageHeader
        title={t('Prognóza likvidity a horizont přežití', 'Liquidity forecast and survival horizon')}
        subtitle={t(`Běh ${id}`, `Run ${id}`)}
        icon={<Droplets size={20} aria-hidden="true" />}
        actions={back}
      />

      <div className="card" style={{ marginBottom: 16, display: 'flex', gap: 12, alignItems: 'end', flexWrap: 'wrap' }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
          {t('Sada křivek', 'Curve set')}
          <select className="input" value={curveSetId} onChange={e => setCurveSetId(e.target.value)} aria-label={t('Sada výnosových křivek', 'Yield-curve set')}>
            {curveSets.map(s => <option key={s.id} value={s.id}>{`${s.asOf} · ${s.source} · ${s.provenance}`}</option>)}
          </select>
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
          {t('Horizont (dny)', 'Horizon (days)')}
          <select className="input" value={horizon} onChange={e => setHorizon(Number(e.target.value))} aria-label={t('Horizont prognózy', 'Forecast horizon')}>
            {HORIZONS.map(h => <option key={h} value={h}>{h}</option>)}
          </select>
        </label>
        {data && <ProvenanceBadge provenance={data.provenance} />}
      </div>

      {setsKind ? (
        <DataUnavailable kind={setsKind} service="risk-engine" feature={t('sady výnosových křivek', 'curve sets')} lang={language} />
      ) : curveSets.length === 0 ? (
        <DataUnavailable kind="no_data" service="risk-engine" feature={t('sady výnosových křivek — nahrajte sadu v sekci Výnosové křivky', 'curve sets — upload one under Curve sets')} lang={language} />
      ) : kind ? (
        <DataUnavailable kind={kind} service="risk-engine" feature={t('prognóza likvidity', 'liquidity forecast')} lang={language} />
      ) : data ? (
        <>
          {data.currencies.map(c => {
            const survival = typeof c.survivalHorizonDays === 'number' ? c.survivalHorizonDays : null
            return (
              <section key={c.currency} className="card" style={{ marginBottom: 16, overflowX: 'auto' }} aria-label={t(`Prognóza ${c.currency}`, `Forecast ${c.currency}`)}>
                <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{c.currency}</h2>
                <p data-testid={`survival-${c.currency}`} style={{ fontSize: 13, marginBottom: 4 }}>
                  {t('Horizont přežití', 'Survival horizon')}:{' '}
                  {survival !== null ? (
                    <>
                      <strong>{t(`${survival}. den`, `day ${survival}`)}</strong> ({c.survivalDate}){' '}
                      <StatusBadge status="BREACH" tone="danger" label={t('Kumulativní pozice záporná', 'Cumulative position negative')} />
                    </>
                  ) : (
                    <>
                      <strong>{t(`bez výpadku do ${data.horizonDays} dnů`, `no shortfall within ${data.horizonDays} days`)}</strong>{' '}
                      <StatusBadge status="SURVIVES" tone="success" label={t('Přežije horizont', 'Survives the horizon')} />
                    </>
                  )}
                </p>
                <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
                  {t('Počáteční likvidita (HQLA jako v LCR)', 'Opening liquidity (HQLA as in the LCR)')}: {money(c.openingLiquidity)}
                  {!c.hqla && ` — ${t('měna nemá žádná HQLA', 'no HQLA held in this currency')}`}
                  {' · '}{t('Minimum kumulativní pozice', 'Minimum cumulative position')}: {money(c.minimumCumulative)}
                  {c.flowsBeyondHorizon > 0 && ` · ${t(`${c.flowsBeyondHorizon} toků za horizontem`, `${c.flowsBeyondHorizon} flows beyond the horizon`)}`}
                </p>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                  <thead>
                    <tr>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Dny', 'Days')}</th>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Do', 'To')}</th>
                      <th scope="col" style={{ textAlign: 'right' }}>{t('Smluvní přítoky', 'Contractual inflows')}</th>
                      <th scope="col" style={{ textAlign: 'right' }}>{t('Smluvní odtoky', 'Contractual outflows')}</th>
                      <th scope="col" style={{ textAlign: 'right' }}>{t('Behaviorální přítoky', 'Behavioural inflows')}</th>
                      <th scope="col" style={{ textAlign: 'right' }}>{t('Behaviorální odtoky', 'Behavioural outflows')}</th>
                      <th scope="col" style={{ textAlign: 'right' }}>{t('Netto', 'Net')}</th>
                      <th scope="col" style={{ textAlign: 'right' }}>{t('Kumulativně', 'Cumulative')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {c.ladder.map(r => {
                      const negative = r.cumulative < 0
                      return (
                        <tr key={r.fromDay} data-negative={negative ? 'true' : undefined} style={negative ? { background: 'var(--danger-bg, transparent)' } : undefined}>
                          <td>{r.fromDay === r.toDay ? r.fromDay : `${r.fromDay}–${r.toDay}`}</td>
                          <td>{r.to}</td>
                          <td style={{ textAlign: 'right' }}>{money(r.contractualInflows)}</td>
                          <td style={{ textAlign: 'right' }}>{money(r.contractualOutflows)}</td>
                          <td style={{ textAlign: 'right' }}>{money(r.behaviouralInflows)}</td>
                          <td style={{ textAlign: 'right' }}>{money(r.behaviouralOutflows)}</td>
                          <td style={{ textAlign: 'right' }}>{money(r.net)}</td>
                          <td style={{ textAlign: 'right', fontWeight: 600 }}>{money(r.cumulative)}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </section>
            )
          })}

          <div className="card">
            <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{t('Předpoklady', 'Assumptions')}</h2>
            <ul style={{ fontSize: 12, paddingLeft: 16, display: 'grid', gap: 4 }}>
              <li>{t('Model chování', 'Behavioural model')}: {data.model.id} v{data.model.version}</li>
              <li>{t('Sada parametrů likvidity', 'Liquidity parameter set')}: {data.parameterSetId} v{data.parameterSetVersion}</li>
              <li>{t(`Denní řádky do ${data.dailyDays}. dne, poté týdenní`, `Daily rows to day ${data.dailyDays}, weekly after`)}</li>
              {data.assumptions.map(a => <li key={a.key} data-assumption={a.key}>{a.statement}</li>)}
            </ul>
          </div>
        </>
      ) : null}
    </div>
  )
}
