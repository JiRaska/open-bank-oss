// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One balance-sheet snapshot run (risk-engine, ADR-0314; admin console #10618).
//
// HONESTY RULES
//   - An UNTIED run shows its mismatches and NOTHING derived from it: risk-engine withholds its
//     positions (409, ADR-0314 D3) and so does this page.
//   - Provenance is on every figure (ADR-0313 D13): synthetic data is labelled as such, and a curve
//     set's provenance is shown next to the run's, because a production run priced on a synthetic
//     curve is still a synthetic number.
//   - A currency without a discounting curve is listed as UNPRICED, and positions the engine could
//     not expand are counted with the engine's own reason — neither is ever drawn as a zero.

'use client'

import { use, useCallback, useEffect, useMemo, useState } from 'react'
import dynamic from 'next/dynamic'
import Link from 'next/link'
import { ArrowLeft, Scale } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl } from '@/components/balance-sheet/api'
import {
  cashFlowsSchema, curveSetListSchema, instrumentsSchema, snapshotRunSchema,
  type CashFlows, type CurveSetSummary, type Instrument, type SnapshotRun,
} from '@/components/balance-sheet/contracts'
import { ladderRows } from '@/components/balance-sheet/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

// Recharts loads only once a TIED_OUT run has flows to draw; the placeholder reserves the height.
const MaturityLadder = dynamic(
  () => import('@/components/balance-sheet/charts').then(module => module.MaturityLadder),
  { ssr: false, loading: () => <div style={{ height: 280 }} aria-hidden="true" /> },
)

const INSTRUMENT_ROWS = 50

export default function SnapshotDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return (
    <AuthGuard permission="balance-sheet:view">
      <SnapshotDetail id={id} />
    </AuthGuard>
  )
}

function SnapshotDetail({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const [run, setRun] = useState<SnapshotRun | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [instruments, setInstruments] = useState<Instrument[] | null>(null)
  const [instrumentsKind, setInstrumentsKind] = useState<UnavailableKind | null>(null)
  const [curveSets, setCurveSets] = useState<CurveSetSummary[]>([])
  const [curveSetId, setCurveSetId] = useState('')
  const [flows, setFlows] = useState<CashFlows | null>(null)
  const [flowsKind, setFlowsKind] = useState<UnavailableKind | null>(null)
  const [showAll, setShowAll] = useState(false)

  const load = useCallback(async () => {
    const res = await getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(id)}`), snapshotRunSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); return }
    setUnavailable(null)
    setRun(res.data)
    if (res.data.status !== 'TIED_OUT') return
    const [inst, sets] = await Promise.all([
      getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(id)}/instruments`), instrumentsSchema),
      getJson(riskUrl('/api/v1/risk/curve-sets', { limit: '25' }), curveSetListSchema),
    ])
    if (inst.ok) { setInstruments(inst.data.instruments); setInstrumentsKind(null) } else setInstrumentsKind(inst.kind)
    if (sets.ok) {
      setCurveSets(sets.data.curveSets)
      if (sets.data.curveSets.length > 0) setCurveSetId(prev => prev || sets.data.curveSets[0].id)
    }
  }, [id])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    if (!curveSetId || run?.status !== 'TIED_OUT') return
    let cancelled = false
    void (async () => {
      const res = await getJson(
        riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(id)}/cash-flows`, { curveSetId }),
        cashFlowsSchema,
      )
      if (cancelled) return
      if (res.ok) { setFlows(res.data); setFlowsKind(null) } else { setFlows(null); setFlowsKind(res.kind) }
    })()
    return () => { cancelled = true }
  }, [id, curveSetId, run?.status])

  const byKind = useMemo(() => {
    const counts = new Map<string, number>()
    for (const i of instruments ?? []) counts.set(i.kind, (counts.get(i.kind) ?? 0) + 1)
    return [...counts.entries()].sort()
  }, [instruments])

  const back = (
    <Link href="/balance-sheet/snapshots" className="btn btn-secondary btn-sm" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <ArrowLeft size={14} aria-hidden="true" /> {t('Zpět na snímky', 'Back to snapshots')}
    </Link>
  )

  if (unavailable) {
    return (
      <div>
        <PageHeader title={t('Snímek rozvahy', 'Balance-sheet snapshot')} icon={<Scale size={20} aria-hidden="true" />} actions={back} />
        <DataUnavailable kind={unavailable.kind} service="risk-engine" feature={t('snímek rozvahy', 'balance-sheet snapshot')} lang={language} />
      </div>
    )
  }
  if (!run) return null

  const tied = run.status === 'TIED_OUT'
  const selectedSet = curveSets.find(s => s.id === curveSetId)
  const visibleInstruments = showAll ? instruments ?? [] : (instruments ?? []).slice(0, INSTRUMENT_ROWS)

  return (
    <div>
      <PageHeader
        title={t(`Snímek rozvahy k ${run.asOf}`, `Balance-sheet snapshot as of ${run.asOf}`)}
        subtitle={t(`Běh ${run.id} · hash vstupů ${run.inputHash.slice(0, 12)}…`, `Run ${run.id} · input hash ${run.inputHash.slice(0, 12)}…`)}
        icon={<Scale size={20} aria-hidden="true" />}
        actions={back}
      />

      <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        <StatusBadge status={run.status} tone={tied ? 'success' : 'danger'} label={tied ? t('Odsouhlaseno s hlavní knihou', 'Tied out to the ledger') : t('Neodsouhlaseno s hlavní knihou', 'Did not tie out to the ledger')} />
        <ProvenanceBadge provenance={run.provenance} />
        {tied && (
          <Link href={`/balance-sheet/snapshots/${encodeURIComponent(run.id)}/irrbb`} className="btn btn-secondary btn-sm">
            {t('Úrokové riziko (IRRBB)', 'Interest-rate risk (IRRBB)')}
          </Link>
        )}
        {tied && (
          <Link href={`/balance-sheet/snapshots/${encodeURIComponent(run.id)}/liquidity`} className="btn btn-secondary btn-sm">
            {t('Likvidita (LCR/NSFR)', 'Liquidity (LCR/NSFR)')}
          </Link>
        )}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 16 }}>
        <StatCard label={t('Pozice', 'Positions')} value={run.positionCount.toLocaleString(locale)} />
        <StatCard label={t('Rozdíly', 'Mismatches')} value={run.mismatchCount.toLocaleString(locale)} tone={run.mismatchCount > 0 ? 'danger' : undefined} />
        <StatCard label={t('Zaznamenáno', 'Recorded')} value={new Date(run.recordedAt).toLocaleString(locale)} />
      </div>

      {!tied && (
        <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
          <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{t('Rozdíly proti hlavní knize', 'Mismatches against the ledger')}</h2>
          <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
            {t('Neodsouhlasený běh je uložen a označen, ale jeho pozice ani peněžní toky se nezobrazují (ADR-0314 D3).', 'An untied run is stored and flagged, but neither its positions nor its cash flows are shown (ADR-0314 D3).')}
          </p>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Účet hlavní knihy', 'GL account')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Hlavní kniha (netto)', 'Ledger net')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Pozice (netto)', 'Positions net')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Rozdíl', 'Difference')}</th>
              </tr>
            </thead>
            <tbody>
              {run.mismatches.map(m => (
                <tr key={`${m.glAccountCode ?? '—'}-${m.currency}`}>
                  <td>{m.glAccountCode ?? '—'}</td>
                  <td>{m.currency}</td>
                  <td style={{ textAlign: 'right' }}>{money(m.ledgerNet)}</td>
                  <td style={{ textAlign: 'right' }}>{money(m.positionsNet)}</td>
                  <td style={{ textAlign: 'right', fontWeight: 600 }}>{money(m.difference)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {tied && (
        <>
          <div className="card" style={{ marginBottom: 16 }}>
            <div style={{ display: 'flex', gap: 12, alignItems: 'end', flexWrap: 'wrap', marginBottom: 8 }}>
              <h2 style={{ fontSize: 14, fontWeight: 600, marginRight: 'auto' }}>{t('Splatnostní žebříček peněžních toků', 'Cash-flow maturity ladder')}</h2>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
                {t('Sada křivek', 'Curve set')}
                <select className="input" value={curveSetId} onChange={e => setCurveSetId(e.target.value)} aria-label={t('Sada výnosových křivek', 'Yield-curve set')}>
                  {curveSets.map(s => (
                    <option key={s.id} value={s.id}>{`${s.asOf} · ${s.source} · ${s.provenance}`}</option>
                  ))}
                </select>
              </label>
              {selectedSet && <ProvenanceBadge provenance={selectedSet.provenance} />}
            </div>
            {curveSets.length === 0 ? (
              <DataUnavailable kind="no_data" service="risk-engine" feature={t('sady výnosových křivek — nahrajte sadu v sekci Výnosové křivky', 'curve sets — upload one under Curve sets')} lang={language} dense />
            ) : flowsKind ? (
              <DataUnavailable kind={flowsKind} service="risk-engine" feature={t('peněžní toky', 'cash flows')} lang={language} dense />
            ) : flows ? (
              <CashFlowPanel flows={flows} locale={locale} money={money} />
            ) : null}
          </div>

          <div className="card" style={{ overflowX: 'auto' }}>
            <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{t('Nástroje (úvěry a vklady)', 'Instruments (loans and deposits)')}</h2>
            {instrumentsKind ? (
              <DataUnavailable kind={instrumentsKind} service="risk-engine" feature={t('nástroje', 'instruments')} lang={language} dense />
            ) : instruments && instruments.length === 0 ? (
              <DataUnavailable kind="no_data" service="risk-engine" feature={t('nástroje', 'instruments')} lang={language} dense />
            ) : instruments ? (
              <>
                <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
                  {byKind.map(([kind, n]) => `${kind}: ${n.toLocaleString(locale)}`).join(' · ')}
                </p>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                  <thead>
                    <tr>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Nástroj', 'Instrument')}</th>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Druh', 'Kind')}</th>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Účet', 'GL')}</th>
                      <th scope="col" style={{ textAlign: 'right' }}>{t('Zůstatek', 'Outstanding')}</th>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Splatnost', 'Maturity')}</th>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('Sazba', 'Rate')}</th>
                      <th scope="col" style={{ textAlign: 'left' }}>{t('IFRS 9', 'IFRS 9')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visibleInstruments.map(i => (
                      <tr key={i.id}>
                        <td style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: 12 }}>{i.id}</td>
                        <td>{i.kind}</td>
                        <td>{i.glAccountCode ?? '—'}</td>
                        <td style={{ textAlign: 'right' }}>{money(i.outstanding)}</td>
                        <td>{i.currency}</td>
                        <td>{i.maturityDate ?? '—'}</td>
                        <td>
                          {i.rateTerms
                            ? i.rateTerms.rateType === 'FLOATING'
                              ? `${i.rateTerms.index ?? '—'} + ${i.rateTerms.spread ?? '—'}`
                              : i.rateTerms.currentAnnualRate !== null ? `${(i.rateTerms.currentAnnualRate * 100).toLocaleString(locale, { maximumFractionDigits: 3 })} %` : '—'
                            : '—'}
                        </td>
                        <td>{i.ifrs9Stage ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!showAll && instruments.length > INSTRUMENT_ROWS && (
                  <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 8 }} onClick={() => setShowAll(true)}>
                    {t(`Zobrazit všech ${instruments.length}`, `Show all ${instruments.length}`)}
                  </button>
                )}
              </>
            ) : null}
          </div>
        </>
      )}
    </div>
  )
}

function CashFlowPanel({ flows, locale, money }: { flows: CashFlows; locale: string; money: (v: number) => string }) {
  const { t } = useLanguage()
  return (
    <div>
      <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
        {t(
          `Model chování ${flows.model.id} v${flows.model.version}. Rozpadlé pozice: ${flows.expandedPositions}. Znaménko z pohledu banky: přítok +, odtok −.`,
          `Behavioural model ${flows.model.id} v${flows.model.version}. Expanded positions: ${flows.expandedPositions}. Bank-signed: inflow +, outflow −.`,
        )}
      </p>
      {flows.notExpanded > 0 && (
        <div role="note" className="card" style={{ marginBottom: 8, borderColor: 'var(--warning, var(--border))' }}>
          <StatusBadge status="NOT_EXPANDED" tone="warning" label={t(`Nerozpadnuto: ${flows.notExpanded}`, `Not expanded: ${flows.notExpanded}`)} />{' '}
          <span style={{ fontSize: 12 }}>{flows.notExpandedReason}</span>
        </div>
      )}
      {flows.unpriced.length > 0 && (
        <div role="note" className="card" style={{ marginBottom: 8, borderColor: 'var(--warning, var(--border))' }}>
          <StatusBadge status="UNPRICED" tone="warning" label={t('Neoceněno', 'Unpriced')} />{' '}
          <span style={{ fontSize: 12 }}>
            {t(
              `Pro tyto měny sada neobsahuje diskontní křivku, současná hodnota se nepočítá: ${flows.unpriced.join(', ')}`,
              `The curve set has no discounting curve for these currencies, so no present value is computed: ${flows.unpriced.join(', ')}`,
            )}
          </span>
        </div>
      )}
      {flows.currencies.length === 0 ? (
        <p style={{ fontSize: 12 }}>{t('Běh neobsahuje žádné rozpadlé toky.', 'The run has no expanded flows.')}</p>
      ) : flows.currencies.map(c => (
        <section key={c.currency} style={{ marginTop: 12 }} aria-label={t(`Peněžní toky ${c.currency}`, `Cash flows ${c.currency}`)}>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', marginBottom: 4 }}>
            <h3 style={{ fontSize: 13, fontWeight: 600 }}>{c.currency}</h3>
            <span style={{ fontSize: 12 }}>{t(`Pozic: ${c.positions}`, `Positions: ${c.positions}`)}</span>
            <span style={{ fontSize: 12 }}>{t(`Součet toků: ${money(c.total)}`, `Total flows: ${money(c.total)}`)}</span>
            {c.priced && c.presentValue !== null
              ? <span style={{ fontSize: 12 }}>{t(`Současná hodnota (${c.discountIndex ?? '—'}): ${money(c.presentValue)}`, `Present value (${c.discountIndex ?? '—'}): ${money(c.presentValue)}`)}</span>
              : <StatusBadge status="UNPRICED" tone="warning" label={t('Současná hodnota: neoceněno', 'Present value: unpriced')} />}
          </div>
          <MaturityLadder rows={ladderRows(c)} locale={locale} />
        </section>
      ))}
    </div>
  )
}
