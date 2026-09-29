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

'use client'

import { use, useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, Droplets } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
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
        subtitle={t(`Běh ${id}`, `Run ${id}`)}
        icon={<Droplets size={20} aria-hidden="true" />}
        actions={back}
      />
      {kind ? (
        <DataUnavailable kind={kind} service="risk-engine" feature={t('likvidita (LCR/NSFR)', 'liquidity (LCR/NSFR)')} lang={language} />
      ) : data ? (
        <LiquidityBody data={data} locale={locale} />
      ) : null}
    </div>
  )
}

function LiquidityBody({ data, locale }: { data: Liquidity; locale: string }) {
  const { t } = useLanguage()
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const a = data.assumptions
  return (
    <>
      <div className="card" style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <ProvenanceBadge provenance={data.provenance} />
        <StatusBadge status="PARAMS" tone="neutral" label={`${t('Sada parametrů', 'Parameter set')} ${data.parameterSetId} v${data.parameterSetVersion}`} />
        <span style={{ fontSize: 12 }}>{a.scope}</span>
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

      {data.notes.map(n => <p key={n} role="note" style={note}>{n}</p>)}

      {data.currencies.map(c => <CurrencySection key={c.currency} c={c} locale={locale} single={data.total?.currency === c.currency} />)}

      <div className="card">
        <h2 style={h2}>{t('Předpoklady a zdroje', 'Assumptions and sources')}</h2>
        <p style={{ fontSize: 12, marginBottom: 8 }}>{a.source}</p>
        <ul style={{ fontSize: 12, paddingLeft: 16, display: 'grid', gap: 4, marginBottom: 12 }}>
          {a.classification.choices.map(choice => <li key={choice}>{choice}</li>)}
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
                <tr key={f.key}><td>{f.key}</td><td style={right}>{(f.value * 100).toLocaleString(locale)} %</td><td>{f.citation}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

function CurrencySection({ c, locale, single }: { c: CurrencyLiquidity; locale: string; single: boolean }) {
  const { t } = useLanguage()
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const lcr = ratioPercent(c.lcr.ratio, locale)
  const nsfr = ratioPercent(c.nsfr.ratio, locale)
  const caps = capEffects(c)
  const capLabel = { level2: t('strop Level 2 (40 %)', 'Level 2 cap (40%)'), level2b: t('strop Level 2B (15 %)', 'Level 2B cap (15%)'), inflow: t('strop přítoků (75 %)', 'inflow cap (75%)') }
  return (
    <section className="card" style={{ marginBottom: 16 }} aria-label={t(`Likvidita ${c.currency}`, `Liquidity ${c.currency}`)}>
      <h2 style={h2}>{c.currency}{single ? ` · ${t('celkem (jednoměnová kniha)', 'total (single-currency book)')}` : ''}</h2>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12, marginBottom: 12 }}>
        <div>
          <div style={note}>LCR = HQLA / {t('čisté odtoky', 'net outflows')}</div>
          <strong style={{ fontSize: 22 }} data-testid={`lcr-${c.currency}`}>{lcr ?? t('nedefinováno', 'undefined')}</strong>
          <div style={{ fontSize: 12 }}>
            {money(c.lcr.hqla.stock)} / ({money(c.lcr.totalOutflows)} − min({money(c.lcr.totalInflows)}, {money(c.lcr.inflowCap)})) = {money(c.lcr.hqla.stock)} / {money(c.lcr.netOutflows)}
          </div>
        </div>
        <div>
          <div style={note}>NSFR = ASF / RSF</div>
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
        <p style={note}>{t('Žádný účet není v konfiguraci zařazen jako HQLA.', 'No account is mapped as HQLA in the configuration.')}</p>
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
                  <td style={right}>{(l.haircut * 100).toLocaleString(locale)} %</td><td style={right}>{money(l.afterHaircut)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p style={{ fontSize: 12, marginBottom: 12 }}>
        L1 {money(c.lcr.hqla.level1)} · L2A {money(c.lcr.hqla.level2a)} · L2B {money(c.lcr.hqla.level2b)} · {t('úprava 15 %', '15% adj.')} {money(c.lcr.hqla.adjustmentFor15Cap)} · {t('úprava 40 %', '40% adj.')} {money(c.lcr.hqla.adjustmentFor40Cap)} · <strong>HQLA {money(c.lcr.hqla.stock)}</strong>
      </p>

      <Lines title={t('Odtoky (30 dní)', 'Outflows (30 days)')} lines={c.lcr.outflows} total={c.lcr.totalOutflows} money={money} locale={locale} />
      <Lines title={t('Přítoky (30 dní, před stropem)', 'Inflows (30 days, before the cap)')} lines={c.lcr.inflows} total={c.lcr.totalInflows} money={money} locale={locale} />
      <Lines title={t('Dostupné stabilní financování (ASF)', 'Available stable funding (ASF)')} lines={c.nsfr.asf} total={c.nsfr.totalAsf} money={money} locale={locale} />
      <Lines title={t('Požadované stabilní financování (RSF)', 'Required stable funding (RSF)')} lines={c.nsfr.rsf} total={c.nsfr.totalRsf} money={money} locale={locale} />
    </section>
  )
}

function Lines({ title, lines, total, money, locale }: { title: string; lines: LiquidityLine[]; total: number; money: (v: number) => string; locale: string }) {
  const { t } = useLanguage()
  return (
    <div style={{ overflowX: 'auto', marginBottom: 12 }}>
      <h3 style={{ ...h2, fontSize: 13 }}>{title}</h3>
      {lines.length === 0 ? <p style={note}>{t('Žádné položky.', 'No items.')}</p> : (
        <table style={table}>
          <thead><tr>
            <th scope="col" style={left}>{t('Položka', 'Item')}</th><th scope="col" style={right}>{t('Zůstatek', 'Balance')}</th>
            <th scope="col" style={right}>{t('Faktor', 'Factor')}</th><th scope="col" style={right}>{t('Vážený', 'Weighted')}</th>
            <th scope="col" style={left}>{t('Zdroj', 'Source')}</th>
          </tr></thead>
          <tbody>
            {lines.map((l, i) => (
              <tr key={`${l.label}-${i}`}>
                <td>{l.label}</td><td style={right}>{money(l.amount)}</td>
                <td style={right}>{typeof l.factor === 'number' ? `${(l.factor * 100).toLocaleString(locale)} %` : '—'}</td>
                <td style={right}>{money(l.weighted)}</td><td style={{ fontSize: 11 }}>{l.citation}</td>
              </tr>
            ))}
            <tr style={{ fontWeight: 600 }}><td>{t('Celkem', 'Total')}</td><td /><td /><td style={right}>{money(total)}</td><td /></tr>
          </tbody>
        </table>
      )}
    </div>
  )
}
