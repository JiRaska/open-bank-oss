// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// IRRBB at a glance on the snapshot detail page (ADR-0313 phase 1): ΔEVE / ΔNII per supervisory
// scenario, the outlier test against the declared limit, and every gap the engine reports. The
// curve set is the engine's default — the newest one as of the run's own date — so the panel
// never prices a run on another day's curves. Full detail lives on the IRRBB page.

'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { StatusBadge } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl } from '@/components/balance-sheet/api'
import { irrbbSchema, type Irrbb } from '@/components/balance-sheet/contracts'
import { SCENARIO_NAMES, dataGapText, irrbbRows, outlierStatus, treasuryRows, type OutlierStatus } from '@/components/balance-sheet/irrbbSummary'
import { formatMoney } from '@/lib/lending/money'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const STATUS_TONE: Record<OutlierStatus, Tone> = { OK: 'success', EARLY_WARNING: 'warning', BREACH: 'danger', NOT_EVALUABLE: 'neutral' }

export function IrrbbSummaryPanel({ runId }: { runId: string }) {
  const { t, language } = useLanguage()
  const lang = language === 'cs' ? 'cs' : 'en'
  const locale = lang === 'cs' ? 'cs-CZ' : 'en-GB'
  const [data, setData] = useState<Irrbb | null>(null)
  const [kind, setKind] = useState<UnavailableKind | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(runId)}/irrbb`), irrbbSchema)
      if (cancelled) return
      if (res.ok) { setData(res.data); setKind(null) } else { setData(null); setKind(res.kind) }
    })()
    return () => { cancelled = true }
  }, [runId])

  if (kind) {
    return (
      <DataUnavailable
        kind={kind}
        service="risk-engine"
        feature={t('úrokové riziko — pro datum snímku chybí sada výnosových křivek', 'interest-rate risk — no curve set as of the snapshot date')}
        lang={language}
        dense
      />
    )
  }
  if (!data) return null

  const pct = (v: number | null) => v === null ? '—' : `${(v * 100).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} %`
  const status = outlierStatus(data)
  const o = data.outlierTest
  const statusLabel: Record<OutlierStatus, string> = {
    OK: t('Pod prahem testu odlehlých hodnot', 'Below the outlier threshold'),
    EARLY_WARNING: t('Včasné varování', 'Early warning'),
    BREACH: t('Odlehlá banka (překročeno)', 'Outlier (breached)'),
    NOT_EVALUABLE: t('Test nelze vyhodnotit', 'Test not evaluable'),
  }
  const tier1From = o.tier1Source === 'own-funds'
    ? t('z kapitálu snímku', 'from the snapshot’s own funds')
    : o.tier1Source === 'caller' ? t('zadáno ručně', 'supplied manually') : null
  const rows = irrbbRows(data)
  const gaps = data.dataGaps ?? []
  const treasury = treasuryRows(data)

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
        <StatusBadge status={status} tone={STATUS_TONE[status]} label={statusLabel[status]} />
        <span style={{ fontSize: 12 }}>
          {o.ratio !== null && o.ratio !== undefined
            ? t(
              `Nejhorší ztráta EVE / Tier 1: ${pct(o.ratio)} (limit ${pct(o.threshold)}${o.earlyWarning ? `, varování ${pct(o.earlyWarning)}` : ''}).`,
              `Worst EVE loss / Tier 1: ${pct(o.ratio)} (limit ${pct(o.threshold)}${o.earlyWarning ? `, early warning ${pct(o.earlyWarning)}` : ''}).`,
            )
            : t(`Poměr k Tier 1 se nepočítá: ${o.tier1Gap ?? o.note}`, `No Tier 1 ratio: ${o.tier1Gap ?? o.note}`)}
        </span>
        {o.tier1Capital !== null && o.tier1Capital !== undefined && o.currency && (
          <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
            {t(`Tier 1 ${formatMoney(o.tier1Capital, o.currency, 'cs')}`, `Tier 1 ${formatMoney(o.tier1Capital, o.currency, 'en')}`)}
            {tier1From ? ` · ${tier1From}` : ''}
          </span>
        )}
        <ProvenanceBadge provenance={data.curveSetProvenance} />
      </div>
      <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
        {t(
          `Křivky k ${data.asOf} (${data.curveSetSource}). ΔEVE = hodnota po šoku − výchozí, záporná je ztráta; ΔNII za ${data.assumptions.niiHorizonMonths} měsíců při konstantní rozvaze, jen paralelní scénáře.`,
          `Curves as of ${data.asOf} (${data.curveSetSource}). ΔEVE = shocked − base value, negative is a loss; ΔNII over ${data.assumptions.niiHorizonMonths} months, constant balance sheet, parallel scenarios only.`,
        )}
      </p>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }} aria-label={t('Scénáře úrokových šoků', 'Interest-rate shock scenarios')}>
        <thead>
          <tr>
            <th scope="col" style={{ textAlign: 'left' }}>{t('Scénář', 'Scenario')}</th>
            <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
            <th scope="col" style={{ textAlign: 'right' }}>ΔEVE</th>
            <th scope="col" style={{ textAlign: 'right' }}>{t('ΔEVE / Tier 1', 'ΔEVE / Tier 1')}</th>
            <th scope="col" style={{ textAlign: 'right' }}>ΔNII</th>
            <th scope="col" style={{ textAlign: 'right' }}>{t('ΔNII / Tier 1', 'ΔNII / Tier 1')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={`${r.scenario}-${r.currency}`} data-worst={r.worst ? 'true' : undefined} style={r.worst ? { fontWeight: 600 } : undefined}>
              <td>{t(SCENARIO_NAMES[r.scenario][0], SCENARIO_NAMES[r.scenario][1])}</td>
              <td>{r.currency}</td>
              <td style={{ textAlign: 'right' }}>{formatMoney(r.deltaEve, r.currency, lang)}</td>
              <td style={{ textAlign: 'right' }}>{pct(r.eveToTier1)}</td>
              <td style={{ textAlign: 'right' }}>{r.deltaNii === null ? '—' : formatMoney(r.deltaNii, r.currency, lang)}</td>
              <td style={{ textAlign: 'right' }}>{pct(r.niiToTier1)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {treasury.length > 0 && (
        <section style={{ marginTop: 12 }} aria-label={t('Podíl treasury obchodů', 'Treasury deals contribution')}>
          <h3 style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>
            {t('Z toho obchody peněžního trhu (již zahrnuto výše)', 'Of which money-market deals (already included above)')}
          </h3>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }} aria-label={t('Podíl treasury obchodů', 'Treasury deals contribution')}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Obchody', 'Deals')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Umístění (aktiva)', 'Placements (assets)')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Výpůjčky (pasiva)', 'Borrowings (liabilities)')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>ΔEVE</th>
              </tr>
            </thead>
            <tbody>
              {treasury.map(r => (
                <tr key={r.currency} data-treasury={r.currency}>
                  <td>{r.currency}</td>
                  <td style={{ textAlign: 'right' }}>{r.deals}</td>
                  <td style={{ textAlign: 'right' }}>{formatMoney(r.placements, r.currency, lang)}</td>
                  <td style={{ textAlign: 'right' }}>{formatMoney(r.borrowings, r.currency, lang)}</td>
                  <td style={{ textAlign: 'right' }}>
                    {r.deltaEve === null || r.scenario === null
                      ? '—'
                      : `${formatMoney(r.deltaEve, r.currency, lang)} (${t(SCENARIO_NAMES[r.scenario][0], SCENARIO_NAMES[r.scenario][1])})`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
      {(gaps.length > 0 || data.shockNotConfigured.length > 0 || data.unpriced.length > 0) && (
        <section style={{ marginTop: 12 }} aria-label={t('Předpoklady a mezery v datech', 'Assumptions and data gaps')}>
          <h3 style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>{t('Předpoklady a mezery v datech', 'Assumptions and data gaps')}</h3>
          <ul style={{ fontSize: 12, paddingLeft: 18 }}>
            {gaps.map((g, i) => <li key={`${g.code}-${g.curveIndex ?? ''}-${i}`} data-gap={g.code}>{dataGapText(g, lang)}</li>)}
            {data.shockNotConfigured.length > 0 && (
              <li>{t(`Pro tyto měny nejsou nastaveny velikosti šoků: ${data.shockNotConfigured.join(', ')}`, `No shock sizes configured for: ${data.shockNotConfigured.join(', ')}`)}</li>
            )}
            {data.unpriced.length > 0 && (
              <li>{t(`Bez diskontní křivky (neoceněno): ${data.unpriced.join(', ')}`, `No discounting curve (unpriced): ${data.unpriced.join(', ')}`)}</li>
            )}
          </ul>
        </section>
      )}
      <Link href={`/balance-sheet/snapshots/${encodeURIComponent(data.runId)}/irrbb`} className="btn btn-secondary btn-sm" style={{ marginTop: 8 }}>
        {t('Podrobnosti úrokového rizika', 'Interest-rate risk detail')}
      </Link>
    </div>
  )
}
