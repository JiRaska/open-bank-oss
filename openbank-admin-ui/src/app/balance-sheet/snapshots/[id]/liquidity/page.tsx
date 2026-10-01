// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// LCR and NSFR of one balance-sheet snapshot run (risk-engine GET /snapshots/{id}/liquidity,
// ADR-0313 phase 1).
//
// HONESTY RULES
//   - An undefined ratio (no net outflows / no required stable funding) is shown as undefined,
//     never as 0 % or ∞.
//   - GL balances the engine could not classify are shown as a WARNING with their amounts: they
//     are in neither ratio, and the reader must see that before reading the ratio.
//   - Every factor is shown with its BCBS paragraph, and the parameter set id/version, the scope
//     statement (BCBS standard factors, EU deviations not applied) and the classification choices
//     are on the page with the figures. Provenance is on the page (synthetic data labelled).
//   - An EMPTY HQLA stock is a configuration/data gap, not a liquidity fact: LCR is then shown as
//     "cannot be computed" with what is missing and where it is configured, never as a bare 0 %.
//   - Lines are read by regulatory CATEGORY (one row per factor), with the per-loan items behind
//     an expander and named by product/maturity — the instrument id is only a copyable reference.

'use client'

import { use, useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, Droplets } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { ItemLabel } from '@/components/balance-sheet/ItemLabel'
import { CategoryTable } from '@/components/balance-sheet/CategoryTable'
import { loanLabel, useSnapshotInstruments } from '@/components/balance-sheet/instrumentLabels'
import type { Instrument } from '@/components/balance-sheet/contracts'
import { formatMoney, formatPercent, groupLiquidityLines } from '@/lib/risk/aggregate'
import { withoutReviewMarkers, categoryCountText, liquidityCategoryLabel, plainCitation } from '@/lib/risk/labels'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl } from '@/components/balance-sheet/api'
import { liquiditySchema, type CurrencyLiquidity, type Liquidity, type LiquidityLine } from '@/components/balance-sheet/contracts'
import { capEffects, ratioPercent } from '@/components/balance-sheet/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function SnapshotLiquidityPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return (
    <AuthGuard permission="balance-sheet:view">
      <SnapshotLiquidity id={id} />
    </AuthGuard>
  )
}

const h2 = { fontSize: 14, fontWeight: 600, marginBottom: 4 } as const
const note = { fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 } as const
const table = { width: '100%', borderCollapse: 'collapse', fontSize: 13 } as const
const right = { textAlign: 'right' } as const
const left = { textAlign: 'left' } as const

function SnapshotLiquidity({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [data, setData] = useState<Liquidity | null>(null)
  const [kind, setKind] = useState<UnavailableKind | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(id)}/liquidity`), liquiditySchema)
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
        title={t('Likvidita: LCR a NSFR', 'Liquidity: LCR and NSFR')}
        subtitle={data ? t(`Snímek rozvahy k ${new Date(data.asOf).toLocaleDateString(locale)}`, `Balance-sheet snapshot as of ${new Date(data.asOf).toLocaleDateString(locale)}`) : t('Snímek rozvahy', 'Balance-sheet snapshot')}
        icon={<Droplets size={20} aria-hidden="true" />}
        actions={back}
      />
      {kind ? (
        <DataUnavailable kind={kind} service="risk-engine" feature={t('likvidita (LCR/NSFR)', 'liquidity (LCR/NSFR)')} lang={language} />
      ) : data ? (
        <LiquidityBody data={data} locale={locale} runId={id} />
      ) : null}
    </div>
  )
}

function LiquidityBody({ data, locale, runId }: { data: Liquidity; locale: string; runId: string }) {
  const { t, language } = useLanguage()
  const lang = language === 'cs' ? 'cs' : 'en'
  const hasLoans = data.currencies.some(c => [...c.lcr.inflows, ...c.lcr.outflows, ...c.nsfr.asf, ...c.nsfr.rsf].some(l => !!l.instrumentId))
  const instruments = useSnapshotInstruments(runId, hasLoans)
  const hqlaAccounts = data.assumptions.classification.glAccounts.filter(m => m.glClass.startsWith('hqla-'))
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const a = data.assumptions
  return (
    <>
      <div className="card" style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <ProvenanceBadge provenance={data.provenance} />
        <StatusBadge status="PARAMS" tone="neutral" label={`${t('Sada parametrů', 'Parameter set')} ${data.parameterSetId} v${data.parameterSetVersion}`} /> {/* raw-id-ok: parameter-set name, not an entity id */}
        <span style={{ fontSize: 12 }}>{t('LCR a NSFR podle standardních faktorů; podrobnosti v části „Jak se to počítá“.', 'LCR and NSFR on standard factors; details under "How it is computed".')}</span>
      </div>

      {data.unclassified.length > 0 && (
        <div role="alert" className="card" style={{ marginBottom: 16, borderColor: 'var(--warning, var(--border))', overflowX: 'auto' }}>
          <StatusBadge status="UNCLASSIFIED" tone="warning" label={t('Nezařazené zůstatky', 'Unclassified balances')} />{' '}
          <span style={{ fontSize: 12 }}>
            {t(
              'Tyto zůstatky hlavní knihy nemají v konfiguraci třídu likvidity a nejsou započteny v žádném ukazateli.',
              'These GL balances have no liquidity class in the configuration and are counted in neither ratio.',
            )}
          </span>
          <table style={{ ...table, marginTop: 8 }}>
            <thead>
              <tr>
                <th scope="col" style={left}>{t('Účet', 'Account')}</th>
                <th scope="col" style={left}>{t('Typ', 'Type')}</th>
                <th scope="col" style={left}>{t('Měna', 'Currency')}</th>
                <th scope="col" style={right}>{t('Zůstatek (MD − D)', 'Balance (debit − credit)')}</th>
              </tr>
            </thead>
            <tbody>
              {data.unclassified.map(u => (
                <tr key={`${u.glAccountCode}-${u.currency}`} data-unclassified="true">
                  <td>{u.glAccountCode ?? '—'}</td>
                  <td>{u.glAccountType ?? '—'}</td>
                  <td>{u.currency}</td>
                  <td style={right}>{money(u.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data.currencies.map(c => (
        <CurrencySection key={c.currency} c={c} locale={locale} single={data.total?.currency === c.currency} instruments={instruments} hqlaAccounts={hqlaAccounts} />
      ))}

      <details className="card">
        <summary style={{ ...h2, cursor: 'pointer' }}>{t('Jak se to počítá — předpoklady a zdroje', 'How it is computed — assumptions and sources')}</summary>
        <p style={{ fontSize: 12, margin: '8px 0' }}>{withoutReviewMarkers(a.scope)}</p>
        {data.notes.map(n => <p key={n} role="note" style={note}>{withoutReviewMarkers(n)}</p>)}
        <p style={{ fontSize: 12, marginBottom: 8 }}>{a.source}</p>
        <ul style={{ fontSize: 12, paddingLeft: 16, display: 'grid', gap: 4, marginBottom: 12 }}>
          {a.classification.choices.map(choice => <li key={choice}>{withoutReviewMarkers(choice)}</li>)}
          <li>HQLA: {a.hqlaCapMethod}</li>
          <li>{t('Přítoky z úvěrů', 'Loan inflows')}: {a.loanInflows}</li>
          <li>{t('RSF úvěrů', 'Loan RSF')}: {a.loanRsf}</li>
          <li>{t('Není v datech', 'Not in the data')}: {a.notInData}</li>
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
          <table style={table} aria-label={t('Faktory', 'Factors')}>
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

type HqlaAccount = { key: string; glClass: string; description: string }

function CurrencySection({ c, locale, single, instruments, hqlaAccounts }: {
  c: CurrencyLiquidity; locale: string; single: boolean; instruments: Map<string, Instrument> | null; hqlaAccounts: HqlaAccount[]
}) {
  const { t } = useLanguage()
  const money = (v: number) => formatMoney(v, locale, c.currency)
  const lcr = ratioPercent(c.lcr.ratio, locale)
  const nsfr = ratioPercent(c.nsfr.ratio, locale)
  const caps = capEffects(c)
  const capLabel = { level2: t('strop Level 2 (40 %)', 'Level 2 cap (40%)'), level2b: t('strop Level 2B (15 %)', 'Level 2B cap (15%)'), inflow: t('strop přítoků (75 %)', 'inflow cap (75%)') }
  // An empty HQLA stock makes LCR 0 % by arithmetic, which reads as "the bank has no liquidity".
  // What it means is that nothing in the snapshot is counted as HQLA — a configuration or data gap.
  const hqlaMissing = c.lcr.hqla.stock === 0 && c.lcr.netOutflows > 0
  return (
    <section className="card" style={{ marginBottom: 16 }} aria-label={t(`Likvidita ${c.currency}`, `Liquidity ${c.currency}`)}>
      <h2 style={h2}>{c.currency}{single ? ` · ${t('celkem (jednoměnová kniha)', 'total (single-currency book)')}` : ''}</h2>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 12, marginBottom: 12 }}>
        <div>
          <div style={note}>LCR = HQLA / {t('čisté odtoky (30 dní)', 'net outflows (30 days)')}</div>
          {hqlaMissing ? (
            <div role="alert" data-testid={`lcr-${c.currency}`} data-lcr-state="hqla-missing">
              <StatusBadge status="NO_HQLA" tone="warning" label={t('LCR nelze spočítat', 'LCR cannot be computed')} />
              <p style={{ fontSize: 13, marginTop: 6 }}>
                {t(
                  'Ve snímku není žádná zásoba likvidních aktiv (HQLA), takže poměr by vyšel 0 %. To není údaj o likviditě banky, ale chybějící vstup.',
                  'The snapshot holds no stock of high-quality liquid assets (HQLA), so the ratio would come out as 0 %. That is a missing input, not a statement about the bank\'s liquidity.',
                )}
              </p>
              <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                {hqlaAccounts.length > 0
                  ? t(
                      `Jako HQLA jsou nastaveny účty ${hqlaAccounts.map(a => a.key).join(', ')}; ve snímku mají nulový zůstatek. Jakmile na nich bude zůstatek (např. vklad u ČNB), LCR se spočítá.`,
                      `Accounts ${hqlaAccounts.map(a => a.key).join(', ')} are configured as HQLA; they have a zero balance in the snapshot. Once they carry a balance (e.g. a deposit at the CNB), LCR is computed.`,
                    )
                  : t('Žádný účet není nastaven jako HQLA.', 'No account is configured as HQLA.')}{' '}
                {t('Nastavení: risk-engine, ', 'Configured in: risk-engine, ')}<code>openbank.risk.liquidity.classification.gl-accounts</code>.
              </p>
            </div>
          ) : (
            <>
              <strong style={{ fontSize: 22 }} data-testid={`lcr-${c.currency}`}>{lcr ?? t('nedefinováno', 'undefined')}</strong>
              <div style={{ fontSize: 12 }}>{money(c.lcr.hqla.stock)} / {money(c.lcr.netOutflows)}</div>
            </>
          )}
          <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
            {t('Čisté odtoky', 'Net outflows')} = {money(c.lcr.totalOutflows)} − min({money(c.lcr.totalInflows)}, {money(c.lcr.inflowCap)}) = {money(c.lcr.netOutflows)}
          </div>
        </div>
        <div>
          <div style={note}>NSFR = {t('dostupné / požadované stabilní financování', 'available / required stable funding')}</div>
          <strong style={{ fontSize: 22 }} data-testid={`nsfr-${c.currency}`}>{nsfr ?? t('nedefinováno', 'undefined')}</strong>
          <div style={{ fontSize: 12 }}>{money(c.nsfr.totalAsf)} / {money(c.nsfr.totalRsf)}</div>
        </div>
      </div>
      {caps.length > 0 && (
        <p role="note" style={{ fontSize: 12, marginBottom: 8 }}>
          <StatusBadge status="CAP" tone="warning" label={t('Uplatněný strop', 'Cap applied')} />{' '}
          {caps.map(cap => `${capLabel[cap.cap]}: −${money(cap.amount)}`).join(' · ')}
        </p>
      )}

      <h3 style={{ ...h2, fontSize: 13 }}>{t('Zásoba HQLA', 'Stock of HQLA')}</h3>
      {c.lcr.hqla.lines.length === 0 ? (
        <p style={note}>{t('Žádný účet zařazený jako HQLA nemá ve snímku zůstatek.', 'No account mapped as HQLA has a balance in the snapshot.')}</p>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table style={table}>
            <thead><tr>
              <th scope="col" style={left}>{t('Úroveň', 'Level')}</th><th scope="col" style={left}>{t('Účet', 'Account')}</th>
              <th scope="col" style={right}>{t('Tržní hodnota', 'Market value')}</th><th scope="col" style={right}>{t('Srážka', 'Haircut')}</th>
              <th scope="col" style={right}>{t('Po srážce', 'After haircut')}</th>
            </tr></thead>
            <tbody>
              {c.lcr.hqla.lines.map(l => (
                <tr key={`${l.level}-${l.glAccountCode}`}>
                  <td>{l.level}</td><td>{l.glAccountCode}</td><td style={right}>{money(l.marketValue)}</td>
                  <td style={right}>{formatPercent(l.haircut, locale)}</td><td style={right}>{money(l.afterHaircut)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p style={{ fontSize: 12, marginBottom: 12 }}>
        L1 {money(c.lcr.hqla.level1)} · L2A {money(c.lcr.hqla.level2a)} · L2B {money(c.lcr.hqla.level2b)} · {t('úprava 15 %', '15% adj.')} {money(c.lcr.hqla.adjustmentFor15Cap)} · {t('úprava 40 %', '40% adj.')} {money(c.lcr.hqla.adjustmentFor40Cap)} · <strong>HQLA {money(c.lcr.hqla.stock)}</strong>
      </p>

      <Lines title={t('Odtoky (30 dní)', 'Outflows (30 days)')} lines={c.lcr.outflows} total={c.lcr.totalOutflows} currency={c.currency} locale={locale} instruments={instruments} />
      <Lines title={t('Přítoky (30 dní, před stropem)', 'Inflows (30 days, before the cap)')} lines={c.lcr.inflows} total={c.lcr.totalInflows} currency={c.currency} locale={locale} instruments={instruments} />
      <Lines title={t('Dostupné stabilní financování (ASF)', 'Available stable funding (ASF)')} lines={c.nsfr.asf} total={c.nsfr.totalAsf} currency={c.currency} locale={locale} instruments={instruments} />
      <Lines title={t('Požadované stabilní financování (RSF)', 'Required stable funding (RSF)')} lines={c.nsfr.rsf} total={c.nsfr.totalRsf} currency={c.currency} locale={locale} instruments={instruments} />
    </section>
  )
}

function Lines({ title, lines, total, currency, locale, instruments }: {
  title: string; lines: LiquidityLine[]; total: number; currency: string; locale: string; instruments: Map<string, Instrument> | null
}) {
  const { t, language } = useLanguage()
  const lang = language === 'cs' ? 'cs' : 'en'
  const money = (v: number) => formatMoney(v, locale, currency)
  const groups = groupLiquidityLines(lines)
  return (
    <div style={{ overflowX: 'auto', marginBottom: 12 }}>
      <h3 style={{ ...h2, fontSize: 13 }}>{title}</h3>
      {groups.length === 0 ? <p style={note}>{t('Žádné položky.', 'No items.')}</p> : (
        <CategoryTable
          caption={title}
          columns={[
            { key: 'amount', header: t('Zůstatek', 'Balance'), numeric: true },
            { key: 'factor', header: t('Faktor', 'Factor'), numeric: true },
            { key: 'weighted', header: t('Vážená částka', 'Weighted amount'), numeric: true },
          ]}
          rows={groups.map(g => ({
            key: g.key,
            label: liquidityCategoryLabel(g.factorKey, lang),
            countText: categoryCountText(g, lang),
            values: [money(g.amount), typeof g.factor === 'number' ? formatPercent(g.factor, locale) : '—', money(g.weighted)],
            basis: plainCitation(g.citation, lang),
            items: g.items.length > 1 || g.unit === 'loans'
              ? () => g.items.map((l, i) => {
                  const named = l.instrumentId
                    ? loanLabel(instruments?.get(l.instrumentId), l.glAccountCode, lang, locale)
                    : { label: l.glAccountCode ? t(`Účet ${l.glAccountCode}`, `Account ${l.glAccountCode}`) : liquidityCategoryLabel(l.factorKey, lang) }
                  return {
                    key: `${l.instrumentId ?? l.glAccountCode ?? ''}-${i}`,
                    label: <ItemLabel label={named.label} sublabel={named.sublabel} reference={l.instrumentId} />,
                    values: [money(l.amount), typeof l.factor === 'number' ? formatPercent(l.factor, locale) : '—', money(l.weighted)],
                  }
                })
              : undefined,
          }))}
          totals={[null, null, money(total)]}
        />
      )}
    </div>
  )
}
