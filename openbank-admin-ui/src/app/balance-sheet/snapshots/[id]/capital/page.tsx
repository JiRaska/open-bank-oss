// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pillar 1 credit-risk capital of one balance-sheet snapshot run, standardised approach
// (risk-engine GET /snapshots/{id}/capital, ADR-0313 phase 2).
//
// HONESTY RULES
//   - Capital ratios are shown only when the engine computed them; otherwise the page says
//     "not computable" and the engine's reason. A ratio is never derived or guessed here.
//   - The ratios divide by CREDIT-RISK RWA only, so the page states they are an upper bound.
//   - GL balances the engine could not classify are shown as a WARNING with their amounts: they
//     are in no exposure class, so RWA is understated by them.
//   - Every risk weight is shown with its BCBS d424 paragraph; the parameter set id/version, the
//     scope statement and every classification choice are on the page with the figures.
//   - Exposures are read by exposure class and IFRS 9 stage (one row each, with counts), the
//     per-loan items behind an expander and named by product/maturity; a loan id is only a
//     copyable reference. Class/stage names and the "not computable" reason are localised from
//     the engine's codes; the engine's English long form stays under "How it is computed".
//   - A citation the engine marks UNVERIFIED shows its article only; the marker is an internal
//     review flag, never user-facing text.

'use client'

import { use, useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, Landmark } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { HumanReference, PageHeader, StatusBadge } from '@/components/ui'
import { CategoryTable } from '@/components/balance-sheet/CategoryTable'
import { loanLabel, useSnapshotInstruments } from '@/components/balance-sheet/instrumentLabels'
import type { Instrument } from '@/components/balance-sheet/contracts'
import { formatMoney, formatPercent, groupExposures } from '@/lib/risk/aggregate'
import { withoutReviewMarkers, categoryCountText, exposureClassLabel, ifrs9StageLabel, plainCitation, ratiosNotComputableText } from '@/lib/risk/labels'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl } from '@/components/balance-sheet/api'
import { capitalSchema, type Capital, type CurrencyCapital } from '@/components/balance-sheet/contracts'
import { ratioPercent } from '@/components/balance-sheet/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function SnapshotCapitalPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return (
    <AuthGuard permission="balance-sheet:view">
      <SnapshotCapital id={id} />
    </AuthGuard>
  )
}

const h2 = { fontSize: 14, fontWeight: 600, marginBottom: 4 } as const
const note = { fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 } as const
const table = { width: '100%', borderCollapse: 'collapse', fontSize: 13 } as const
const right = { textAlign: 'right' } as const
const left = { textAlign: 'left' } as const

function SnapshotCapital({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [data, setData] = useState<Capital | null>(null)
  const [kind, setKind] = useState<UnavailableKind | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(id)}/capital`), capitalSchema)
      if (cancelled) return
      if (res.ok) { setData(res.data); setKind(null) } else { setData(null); setKind(res.kind) }
    })()
    return () => { cancelled = true }
  }, [id])

  const back = (
    <Link href={`/balance-sheet/snapshots/${encodeURIComponent(id)}`} className="btn btn-secondary btn-sm" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <ArrowLeft size={14} aria-hidden="true" /> {t('Zpět na snímek', 'Back to snapshot')}
    </Link>
  )

  return (
    <div>
      <PageHeader
        title={t('Kapitál: úvěrové riziko (standardizovaný přístup)', 'Capital: credit risk (standardised approach)')}
        subtitle={data ? t(`Snímek rozvahy k ${new Date(data.asOf).toLocaleDateString(locale)}`, `Balance-sheet snapshot as of ${new Date(data.asOf).toLocaleDateString(locale)}`) : t('Snímek rozvahy', 'Balance-sheet snapshot')}
        icon={<Landmark size={20} aria-hidden="true" />}
        actions={back}
      />
      {kind ? (
        <DataUnavailable kind={kind} service="risk-engine" feature={t('kapitálový požadavek', 'capital requirement')} lang={language} />
      ) : data ? (
        <CapitalBody data={data} locale={locale} runId={id} />
      ) : null}
    </div>
  )
}

function CapitalBody({ data, locale, runId }: { data: Capital; locale: string; runId: string }) {
  const { t, language } = useLanguage()
  const lang = language === 'cs' ? 'cs' : 'en'
  const instruments = useSnapshotInstruments(runId, data.currencies.some(c => c.lines.some(l => !!l.instrumentId)))
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const a = data.assumptions
  const ratioRows = data.ratios
    ? [
        { key: 'cet1', label: 'CET1', r: data.ratios.cet1 },
        { key: 'tier1', label: 'Tier 1', r: data.ratios.tier1 },
        { key: 'total', label: t('Celkový kapitál', 'Total capital'), r: data.ratios.total },
      ]
    : []
  return (
    <>
      <div className="card" style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <ProvenanceBadge provenance={data.provenance} />
        <StatusBadge status="PARAMS" tone="neutral" label={`${t('Sada parametrů', 'Parameter set')} ${data.parameterSetId} v${data.parameterSetVersion}`} /> {/* raw-id-ok: parameter-set name, not an entity id */}
        <span style={{ fontSize: 12 }}>{t('Pilíř 1, úvěrové riziko, standardizovaný přístup.', 'Pillar 1 credit risk, standardised approach.')}</span>
      </div>

      {data.unclassified.length > 0 && (
        <div role="alert" className="card" style={{ marginBottom: 16, borderColor: 'var(--warning, var(--border))', overflowX: 'auto' }}>
          <StatusBadge status="UNCLASSIFIED" tone="warning" label={t('Nezařazené zůstatky', 'Unclassified balances')} />{' '}
          <span style={{ fontSize: 12 }}>
            {t(
              'Tyto zůstatky nemají v konfiguraci třídu expozice a nejsou v RWA započteny: RWA je o ně podhodnoceno.',
              'These balances have no exposure class in the configuration and are not in RWA: RWA is understated by them.',
            )}
          </span>
          <table style={{ ...table, marginTop: 8 }}>
            <thead>
              <tr>
                <th scope="col" style={left}>{t('Účet', 'Account')}</th>
                <th scope="col" style={left}>{t('Typ', 'Type')}</th>
                <th scope="col" style={left}>{t('Měna', 'Currency')}</th>
                <th scope="col" style={right}>{t('Zůstatek (MD − D)', 'Balance (debit − credit)')}</th>
                <th scope="col" style={left}>{t('Důvod', 'Reason')}</th>
              </tr>
            </thead>
            <tbody>
              {data.unclassified.map((u, i) => (
                <tr key={`${u.glAccountCode}-${u.currency}-${i}`} data-unclassified="true">
                  <td>{u.glAccountCode ?? '—'}</td>
                  <td>{u.glAccountType ?? '—'}</td>
                  <td>{u.currency}</td>
                  <td style={right}>{money(u.amount)}</td>
                  <td style={{ fontSize: 11 }}>{u.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <section className="card" style={{ marginBottom: 16 }} aria-label={t('Požadavek a poměry', 'Requirement and ratios')}>
        <h2 style={h2}>{t('Kapitálový požadavek a poměry', 'Capital requirement and ratios')}</h2>
        {data.total ? (
          <p style={{ fontSize: 13, marginBottom: 8 }}>
            RWA {data.total.currency}: <strong data-testid="total-rwa">{money(data.total.totalRwa)}</strong>
            {typeof data.ownFundsRequirement === 'number' && (
              <> · {t('Požadavek na kapitál (8 % RWA)', 'Own-funds requirement (8% of RWA)')}: <strong data-testid="requirement">{money(data.ownFundsRequirement)}</strong></>
            )}
          </p>
        ) : (
          <p style={note}>{t('Vícerměnová kniha: celkové RWA se neuvádí.', 'Multi-currency book: no total RWA is reported.')}</p>
        )}
        {data.ratios ? (
          <div style={{ overflowX: 'auto' }}>
            <table style={table} aria-label={t('Kapitálové poměry', 'Capital ratios')}>
              <thead><tr>
                <th scope="col" style={left}>{t('Poměr', 'Ratio')}</th><th scope="col" style={right}>{t('Hodnota', 'Value')}</th>
                <th scope="col" style={right}>{t('Minimum', 'Minimum')}</th><th scope="col" style={left}>{t('Zdroj', 'Source')}</th>
              </tr></thead>
              <tbody>
                {ratioRows.map(({ key, label, r }) => (
                  <tr key={key}>
                    <td>{label}</td>
                    <td style={right} data-testid={`ratio-${key}`}>{ratioPercent(r.ratio, locale)}</td>
                    <td style={right}>
                      {ratioPercent(r.minimum, locale)}{' '}
                      <StatusBadge status={r.meetsMinimum ? 'OK' : 'BREACH'} tone={r.meetsMinimum ? 'success' : 'danger'} label={r.meetsMinimum ? t('splněno', 'met') : t('nesplněno', 'not met')} />
                    </td>
                    <td style={{ fontSize: 11 }} title={r.citation}>{plainCitation(r.citation, lang).article}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p role="note" data-testid="ratios-not-computable" style={{ fontSize: 13 }}>
            <StatusBadge status="N/A" tone="warning" label={t('Poměry nelze spočítat', 'Ratios not computable')} />{' '}
            {ratiosNotComputableText(data.ratiosNotComputableCode, lang) ?? data.ratiosNotComputable}
          </p>
        )}
        <p role="note" style={{ ...note, marginTop: 8 }}>
          {t(
            'Poměry jsou horní odhad: rizikově vážená aktiva zahrnují jen úvěrové riziko, bez tržního a operačního rizika. Více měn se sčítá v CZK kurzem ČNB k datu snímku.',
            'The ratios are an upper bound: risk-weighted assets cover credit risk only, without market and operational risk. Several currencies are added up in CZK at the CNB fixing for the snapshot date.',
          )}
        </p>
      </section>

      {data.currencies.map(c => <CurrencySection key={c.currency} c={c} locale={locale} single={data.total?.currency === c.currency} instruments={instruments} />)}

      <details className="card">
        <summary style={{ ...h2, cursor: 'pointer' }}>{t('Jak se to počítá — předpoklady a zdroje', 'How it is computed — assumptions and sources')}</summary>
        <p style={{ fontSize: 12, margin: '8px 0' }}>{withoutReviewMarkers(a.scope)}</p>
        {[a.creditRiskOnly, ...data.notes.filter(n => n !== a.creditRiskOnly)].map(n => <p key={n} role="note" style={note}>{withoutReviewMarkers(n)}</p>)}
        <p style={{ fontSize: 12, marginBottom: 8 }}>{a.source}</p>
        <ul style={{ fontSize: 12, paddingLeft: 16, display: 'grid', gap: 4, marginBottom: 12 }}>
          {a.classification.choices.map(choice => <li key={choice}>{withoutReviewMarkers(choice)}</li>)}
          <li>EAD: {a.exposureValue}</li>
          <li>{t('Zajištění (CRM)', 'Credit-risk mitigation')}: {a.creditRiskMitigation}</li>
          <li>{t('Podrozvahové položky', 'Off-balance sheet')}: {a.offBalanceSheet}</li>
          <li>{t('Expozice v selhání', 'Defaulted exposures')}: {a.defaulted}</li>
          <li>{t('Kapitál', 'Own funds')}: {a.ownFunds}</li>
          <li>{t('Agregace měn', 'Currency aggregation')}: {a.currencyAggregation}</li>
        </ul>
        <div style={{ overflowX: 'auto' }}>
          <table style={table} aria-label={t('Mapování účtů', 'Account mapping')}>
            <thead><tr><th scope="col" style={left}>{t('Účet / typ', 'Account / type')}</th><th scope="col" style={left}>{t('Třída', 'Class')}</th></tr></thead>
            <tbody>
              {[...a.classification.glAccounts, ...a.classification.glAccountTypes].map(m => (
                <tr key={m.key}><td>{m.key}</td><td>{m.description}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
        <div style={{ overflowX: 'auto', marginTop: 12 }}>
          <table style={table} aria-label={t('Rizikové váhy a minima', 'Risk weights and minima')}>
            <thead><tr><th scope="col" style={left}>{t('Faktor', 'Factor')}</th><th scope="col" style={right}>{t('Hodnota', 'Value')}</th><th scope="col" style={left}>{t('Zdroj', 'Source')}</th></tr></thead>
            <tbody>
              {a.factors.map(f => (
                <tr key={f.key}><td>{f.key}</td><td style={right}>{formatPercent(f.value, locale)}</td><td title={f.citation}>{plainCitation(f.citation, lang).article}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </>
  )
}

function CurrencySection({ c, locale, single, instruments }: { c: CurrencyCapital; locale: string; single: boolean; instruments: Map<string, Instrument> | null }) {
  const { t, language } = useLanguage()
  const lang = language === 'cs' ? 'cs' : 'en'
  const money = (v: number) => formatMoney(v, locale, c.currency)
  const groups = groupExposures(c.lines)
  return (
    <section className="card" style={{ marginBottom: 16 }} aria-label={t(`Úvěrové riziko ${c.currency}`, `Credit risk ${c.currency}`)}>
      <h2 style={h2}>{c.currency}{single ? ` · ${t('celkem (jednoměnová kniha)', 'total (single-currency book)')}` : ''}</h2>

      <h3 style={{ ...h2, fontSize: 13 }}>{t('RWA podle třídy expozice', 'RWA by exposure class')}</h3>
      <div style={{ overflowX: 'auto', marginBottom: 12 }}>
        <table style={table} aria-label={t(`RWA podle třídy ${c.currency}`, `RWA by class ${c.currency}`)}>
          <thead><tr>
            <th scope="col" style={left}>{t('Třída expozice', 'Exposure class')}</th>
            <th scope="col" style={right}>{t('Expozice (EAD)', 'Exposure (EAD)')}</th>
            <th scope="col" style={right}>RWA</th>
            <th scope="col" style={left}>{t('Regulatorní základ', 'Regulatory basis')}</th>
          </tr></thead>
          <tbody>
            {c.classes.map(k => (
              <tr key={k.exposureClass} data-class={k.exposureClass}>
                <td>{exposureClassLabel(k.exposureClass, lang)}</td>
                <td style={{ ...right, whiteSpace: 'nowrap' }}>{money(k.ead)}</td>
                <td style={{ ...right, whiteSpace: 'nowrap' }}>{money(k.rwa)}</td>
                <td style={{ fontSize: 11, color: 'var(--text-secondary)', paddingLeft: 12 }} title={k.citations.join('; ')}>
                  {[...new Set(k.citations.map(x => plainCitation(x, lang).article))].join('; ')}
                </td>
              </tr>
            ))}
            <tr style={{ fontWeight: 600 }}><td>{t('Celkem', 'Total')}</td><td style={{ ...right, whiteSpace: 'nowrap' }}>{money(c.totalEad)}</td><td style={{ ...right, whiteSpace: 'nowrap' }}>{money(c.totalRwa)}</td><td /></tr>
          </tbody>
        </table>
      </div>

      <h3 style={{ ...h2, fontSize: 13 }}>{t('Expozice', 'Exposures')}</h3>
      <div style={{ overflowX: 'auto', marginBottom: 12 }}>
        <CategoryTable
          caption={t(`Expozice ${c.currency}`, `Exposures ${c.currency}`)}
          columns={[
            { key: 'ead', header: t('Expozice (EAD)', 'Exposure (EAD)'), numeric: true },
            { key: 'rw', header: t('Riziková váha', 'Risk weight'), numeric: true },
            { key: 'rwa', header: 'RWA', numeric: true },
          ]}
          rows={groups.map(g => ({
            key: g.key,
            label: g.ifrs9Stage || g.unit === 'loans'
              ? `${exposureClassLabel(g.exposureClass, lang)} · ${ifrs9StageLabel(g.ifrs9Stage, lang)}`
              : exposureClassLabel(g.exposureClass, lang),
            countText: categoryCountText(g, lang),
            values: [money(g.ead), formatPercent(g.riskWeight, locale), money(g.rwa)],
            basis: plainCitation(g.citation, lang),
            items: g.items.length > 1 || g.unit === 'loans'
              ? () => g.items.map((l, i) => {
                  const named = l.instrumentId
                    ? loanLabel(instruments?.get(l.instrumentId), l.glAccountCode, lang, locale)
                    : { label: l.glAccountCode ? t(`Účet ${l.glAccountCode}`, `Account ${l.glAccountCode}`) : exposureClassLabel(l.exposureClass, lang) }
                  return {
                    key: `${l.instrumentId ?? l.glAccountCode ?? ''}-${i}`,
                    label: <HumanReference label={named.label} sublabel={named.sublabel} reference={l.instrumentId} />,
                    values: [money(l.ead), formatPercent(l.riskWeight, locale), money(l.rwa)],
                  }
                })
              : undefined,
          }))}
          totals={[money(c.totalEad), null, money(c.totalRwa)]}
        />
      </div>

      <h3 style={{ ...h2, fontSize: 13 }}>{t('Kapitál z účtů 6000–6060', 'Own funds from accounts 6000–6060')}</h3>
      {c.ownFunds ? (
        <p style={{ fontSize: 12 }} data-currency={c.currency}>
          CET1 {money(c.ownFunds.cet1BeforeDeductions)} {t('před odpočty', 'before deductions')}, {t('odpočty', 'deductions')} {money(c.ownFunds.cet1Deductions)} → <strong>CET1 {money(c.ownFunds.cet1)}</strong> · AT1 {money(c.ownFunds.at1)} · <strong>Tier 1 {money(c.ownFunds.tier1)}</strong> · Tier 2 {money(c.ownFunds.tier2)} · <strong>{t('Celkem', 'Total')} {money(c.ownFunds.total)}</strong>
        </p>
      ) : (
        <p style={note}>{t('V této měně není žádný kapitálový účet.', 'No own-funds account in this currency.')}</p>
      )}
    </section>
  )
}
