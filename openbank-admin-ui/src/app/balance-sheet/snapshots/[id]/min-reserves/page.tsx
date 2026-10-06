// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Povinné minimální rezervy (ČNB minimum reserve requirement) of one balance-sheet snapshot run
// (risk-engine GET /snapshots/{id}/min-reserves, ADR-0313/ADR-0315).
//
// HONESTY RULES
//   - Holdings, surplus and shortfall are null (never a stand-in zero) while no GL account is
//     mapped as the ČNB current account; `holdingsNotStated` is then shown prominently instead of
//     any figure.
//   - GL balances the engine could not classify are shown as a WARNING with their amounts: they
//     are excluded from every reserve base and counted nowhere.
//   - Excluded balances (liabilities to banks / ČNB, out of scope by rule) are listed separately
//     from unclassified ones — excluded is a deliberate scope decision, unclassified is a gap.
//   - The parameter set id/version, rate and remuneration rate are on the page with the figures.
//     Provenance is on the page (synthetic data labelled).
//   - Maintenance period (ADR-0315 D8): the period containing the run's as-of date, evaluated on
//     that date. A SAMPLE / UNVERIFIED calendar is badged as such next to the figures; average,
//     requirement and proposal each show their not-stated reason instead of a figure.

'use client'

import { use, useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowLeft, Landmark } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { HumanReference, PageHeader, StatusBadge } from '@/components/ui'
import { ProvenanceBadge } from '@/components/balance-sheet/ProvenanceBadge'
import { getJson, riskUrl } from '@/components/balance-sheet/api'
import {
  maintenanceCalendarSchema, minReservePeriodSchema, minReservesSchema,
  type MinReservePeriod, type MinReserves, type ReserveBase, type ReserveLine,
} from '@/components/balance-sheet/contracts'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function SnapshotMinReservesPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return (
    <AuthGuard permission="balance-sheet:view">
      <SnapshotMinReserves id={id} />
    </AuthGuard>
  )
}

const h2 = { fontSize: 14, fontWeight: 600, marginBottom: 4 } as const
const note = { fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 } as const
const table = { width: '100%', borderCollapse: 'collapse', fontSize: 13 } as const
const right = { textAlign: 'right' } as const
const left = { textAlign: 'left' } as const

function SnapshotMinReserves({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const [data, setData] = useState<MinReserves | null>(null)
  const [kind, setKind] = useState<UnavailableKind | null>(null)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const res = await getJson(riskUrl(`/api/v1/risk/snapshots/${encodeURIComponent(id)}/min-reserves`), minReservesSchema)
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
        title={t('Povinné minimální rezervy', 'Minimum reserve requirement')}
        subtitle={data ? t(`Stav k ${data.asOf}`, `As of ${data.asOf}`) : t('Výpočet pro snímek rozvahy', 'Calculation for a balance-sheet snapshot')}
        icon={<Landmark size={20} aria-hidden="true" />}
        actions={back}
      />
      <div style={{ marginBottom: 16 }}>
        <HumanReference label={t('Běh snímku', 'Snapshot run')} reference={id} copyLabel={t('Kopírovat ID běhu', 'Copy run ID')} />
      </div>
      {kind ? (
        <DataUnavailable kind={kind} service="risk-engine" feature={t('povinné minimální rezervy', 'minimum reserve requirement')} lang={language} />
      ) : data ? (
        <MinReservesBody data={data} locale={locale} />
      ) : null}
    </div>
  )
}

function MinReservesBody({ data, locale }: { data: MinReserves; locale: string }) {
  const { t } = useLanguage()
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const a = data.assumptions
  return (
    <>
      <div className="card" style={{ marginBottom: 16, display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <ProvenanceBadge provenance={data.provenance} />
        <StatusBadge status="PARAMS" tone="neutral" label={`${t('Sada parametrů', 'Parameter set')} ${data.parameterSetId} v${data.parameterSetVersion}`} /> {/* raw-id-ok: named parameter set, not a run identifier */}
        <span style={{ fontSize: 12 }}>{t('Sazba', 'Rate')} {(a.rate * 100).toLocaleString(locale)} % · {t('sazba úročení', 'remuneration rate')} {(a.remunerationRate * 100).toLocaleString(locale)} %</span>
      </div>

      <div className="card" style={{ marginBottom: 16 }} data-testid="holdings-summary">
        <h2 style={h2}>{t('Zůstatek na účtu u ČNB, požadavek a přebytek', 'ČNB account holdings, requirement and surplus')} ({data.holdingCurrency})</h2>
        {data.holdingsNotStated ? (
          <div role="alert" className="card" style={{ borderColor: 'var(--warning, var(--border))' }} data-testid="holdings-not-stated">
            <StatusBadge status="NOT_STATED" tone="warning" label={t('Zůstatek neuveden', 'Holdings not stated')} />{' '}
            <span style={{ fontSize: 12 }}>{data.holdingsNotStated}</span>
          </div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
            <Stat label={t('Zůstatek u ČNB', 'ČNB account holdings')} value={data.totalHoldings === null ? null : money(data.totalHoldings)} testId="total-holdings" />
            <Stat label={t('Požadavek', 'Requirement')} value={data.requirement === null ? null : money(data.requirement)} testId="requirement" locale={locale} />
            <Stat label={t('Přebytek / nedostatek', 'Surplus / shortfall')} value={data.surplus === null ? null : money(data.surplus)} testId="surplus" />
          </div>
        )}
      </div>

      {data.excluded.length > 0 && (
        <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
          <h2 style={h2}>{t('Vyloučené zůstatky', 'Excluded balances')}</h2>
          <p style={note}>{t('Mimo rozsah povinných minimálních rezerv (banky, ČNB).', 'Out of the minimum reserve scope (banks, ČNB).')}</p>
          <table style={table}>
            <thead>
              <tr>
                <th scope="col" style={left}>{t('Účet', 'Account')}</th>
                <th scope="col" style={left}>{t('Třída', 'Class')}</th>
                <th scope="col" style={right}>{t('Zůstatek', 'Balance')}</th>
              </tr>
            </thead>
            <tbody>
              {data.excluded.map((e, i) => (
                <tr key={`${e.glAccountCode}-${i}`} data-excluded="true">
                  <td>{e.glAccountCode ?? '—'}</td>
                  <td>{e.reserveClass}</td>
                  <td style={right}>{money(e.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data.unclassified.length > 0 && (
        <div role="alert" className="card" style={{ marginBottom: 16, borderColor: 'var(--warning, var(--border))', overflowX: 'auto' }}>
          <StatusBadge status="UNCLASSIFIED" tone="warning" label={t('Nezařazené zůstatky', 'Unclassified balances')} />{' '}
          <span style={{ fontSize: 12 }}>
            {t(
              'Tyto zůstatky hlavní knihy nemají v konfiguraci třídu rezerv a nejsou započteny nikde.',
              'These GL balances have no reserve class in the configuration and are counted nowhere.',
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

      <MaintenancePeriodSection asOf={data.asOf} locale={locale} />

      {data.currencies.map(c => <ReserveBaseSection key={c.currency} c={c} locale={locale} />)}

      {data.holdings && data.holdings.length > 0 && (
        <ReserveLines title={t('Zůstatek na účtu u ČNB', 'ČNB current-account holdings')} lines={data.holdings} total={data.totalHoldings} money={money} />
      )}

      <div className="card">
        <h2 style={h2}>{t('Předpoklady a zdroje', 'Assumptions and sources')}</h2>
        <p style={{ fontSize: 12, marginBottom: 8 }}>{a.source}</p>
        <div style={{ overflowX: 'auto' }}>
          <table style={table} aria-label={t('Mapování účtů', 'Account mapping')}>
            <thead><tr><th scope="col" style={left}>{t('Účet / typ', 'Account / type')}</th><th scope="col" style={left}>{t('Třída', 'Class')}</th></tr></thead>
            <tbody>
              {[...a.glAccounts, ...a.glAccountTypes].map(m => (
                <tr key={m.key}><td>{m.key}</td><td>{m.description}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

function Stat({ label, value, testId, locale }: { label: string; value: string | null; testId: string; locale?: string }) {
  const { t } = useLanguage()
  void locale
  return (
    <div>
      <div style={note}>{label}</div>
      <strong style={{ fontSize: 22 }} data-testid={testId}>{value ?? t('neuvedeno', 'not stated')}</strong>
    </div>
  )
}

function ReserveBaseSection({ c, locale }: { c: ReserveBase; locale: string }) {
  const { t } = useLanguage()
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return (
    <section className="card" style={{ marginBottom: 16 }} aria-label={t(`Rezervní základna ${c.currency}`, `Reserve base ${c.currency}`)}>
      <h2 style={h2}>{c.currency}</h2>
      {c.base === null || c.requirement === null ? (
        <div role="alert" className="card" style={{ marginBottom: 12, borderColor: 'var(--warning, var(--border))' }} data-testid={`requirement-not-stated-${c.currency}`}>
          <StatusBadge status="NOT_STATED" tone="warning" label={t('Požadavek neuveden', 'Requirement not stated')} />{' '}
          <span style={{ fontSize: 12 }}>{c.requirementNotStated}</span>
        </div>
      ) : (
        <p style={{ fontSize: 12, marginBottom: 12 }}>
          {t('Základna', 'Base')} {money(c.base)} × {(c.rate * 100).toLocaleString(locale)} % = <strong data-testid={`requirement-${c.currency}`}>{money(c.requirement)}</strong>
        </p>
      )}
      <ReserveLines title={t('Rezervní základna', 'Reserve base')} lines={c.lines} total={c.base} money={money} />
    </section>
  )
}

function ReserveLines({ title, lines, total, money }: { title: string; lines: ReserveLine[]; total: number | null; money: (v: number) => string }) {
  const { t } = useLanguage()
  return (
    <div style={{ overflowX: 'auto', marginBottom: 12 }}>
      <h3 style={{ ...h2, fontSize: 13 }}>{title}</h3>
      {lines.length === 0 ? <p style={note}>{t('Žádné položky.', 'No items.')}</p> : (
        <table style={table}>
          <thead><tr>
            <th scope="col" style={left}>{t('Položka', 'Item')}</th><th scope="col" style={left}>{t('Účet', 'Account')}</th>
            <th scope="col" style={left}>{t('Třída', 'Class')}</th><th scope="col" style={right}>{t('Zůstatek', 'Balance')}</th>
          </tr></thead>
          <tbody>
            {lines.map((l, i) => (
              <tr key={`${l.label}-${i}`}>
                <td>{l.label}</td><td>{l.glAccountCode ?? '—'}</td><td>{l.reserveClass}</td><td style={right}>{money(l.amount)}</td>
              </tr>
            ))}
            <tr style={{ fontWeight: 600 }}><td>{t('Celkem', 'Total')}</td><td /><td /><td style={right}>{total === null ? t('neuvedeno', 'not stated') : money(total)}</td></tr>
          </tbody>
        </table>
      )}
    </div>
  )
}

type PeriodState =
  | { state: 'loading' }
  | { state: 'none' }
  | { state: 'unavailable' }
  | { state: 'ok'; data: MinReservePeriod }

/** The maintenance period containing [asOf], evaluated on [asOf] (ADR-0315 D8). */
function MaintenancePeriodSection({ asOf, locale }: { asOf: string; locale: string }) {
  const { t } = useLanguage()
  const [p, setP] = useState<PeriodState>({ state: 'loading' })
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const cal = await getJson(riskUrl('/api/v1/risk/min-reserves/periods', { asOf }), maintenanceCalendarSchema)
      if (cancelled) return
      if (!cal.ok) { setP({ state: 'unavailable' }); return }
      const period = cal.data.periods[0]
      if (!period) { setP({ state: 'none' }); return }
      const res = await getJson(
        riskUrl(`/api/v1/risk/min-reserves/periods/${encodeURIComponent(period.id)}`, { asOf }),
        minReservePeriodSchema,
      )
      if (cancelled) return
      setP(res.ok ? { state: 'ok', data: res.data } : { state: 'unavailable' })
    })()
    return () => { cancelled = true }
  }, [asOf])

  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return (
    <section className="card" style={{ marginBottom: 16 }} data-testid="maintenance-period" aria-label={t('Udržovací období', 'Maintenance period')}>
      <h2 style={h2}>{t('Průměr za udržovací období', 'Maintenance-period average')}</h2>
      {p.state === 'loading' ? null : p.state === 'none' ? (
        <p style={note} data-testid="period-none">
          {t(`Žádné udržovací období v kalendáři neobsahuje ${asOf}.`, `No maintenance period in the configured calendar contains ${asOf}.`)}
        </p>
      ) : p.state === 'unavailable' ? (
        <p style={note} data-testid="period-unavailable">
          {t('Údaje za udržovací období nejsou k dispozici.', 'Maintenance-period figures are unavailable.')}
        </p>
      ) : (
        <PeriodBody d={p.data} money={money} locale={locale} />
      )}
    </section>
  )
}

function NotStated({ label, reason, testId }: { label: string; reason: string | null; testId: string }) {
  const { t } = useLanguage()
  return (
    <div>
      <div style={note}>{label}</div>
      <StatusBadge status="NOT_STATED" tone="warning" label={t('neuvedeno', 'not stated')} />{' '}
      <span style={{ fontSize: 12 }} data-testid={testId}>{reason}</span>
    </div>
  )
}

function PeriodBody({ d, money, locale }: { d: MinReservePeriod; money: (v: number) => string; locale: string }) {
  const { t } = useLanguage()
  const pct = (v: number) => (v * 100).toLocaleString(locale, { maximumFractionDigits: 1 })
  return (
    <>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', marginBottom: 8 }}>
        {d.calendarStatus === 'sample-unverified' ? (
          <StatusBadge status="SAMPLE" tone="warning" label={t('Vzorový, neověřený kalendář', 'Sample, unverified calendar')} />
        ) : (
          <StatusBadge status="VERIFIED" tone="neutral" label={t('Ověřený kalendář', 'Verified calendar')} />
        )}
        <span style={{ fontSize: 12 }} data-testid="period-range">
          {d.period.id}: {d.period.start} – {d.period.end} · {t('základna k', 'base as of')} {d.period.baseReferenceDate} · {t('stav k', 'as of')} {d.evaluationDate}
        </span>
      </div>
      {d.notes.map(n => <p key={n} role="note" style={note}>{n}</p>)}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, marginBottom: 12 }}>
        {d.requirement === null
          ? <NotStated label={t('Požadavek', 'Requirement')} reason={d.requirementNotStated} testId="period-requirement-not-stated" />
          : <Stat label={`${t('Požadavek', 'Requirement')} (${d.holdingCurrency})`} value={money(d.requirement)} testId="period-requirement" />}
        {d.averageHoldings === null
          ? <NotStated label={t('Průběžný průměr', 'Running average')} reason={d.averageNotStated} testId="period-average-not-stated" />
          : <Stat label={t('Průběžný průměr', 'Running average')} value={money(d.averageHoldings)} testId="period-average" />}
        <Stat
          label={t('Pokrytí (dny se snímkem / uplynulé dny)', 'Coverage (days with a snapshot / days elapsed)')}
          value={d.coverage === null ? null : `${d.daysWithData} / ${d.daysElapsed} (${pct(d.coverage)} %)`}
          testId="period-coverage"
        />
        {d.dailyHoldingProposal === null
          ? <NotStated label={t('Návrh denního zůstatku', 'Daily holding proposal')} reason={d.proposalNotStated} testId="period-proposal-not-stated" />
          : <Stat label={`${t('Návrh denního zůstatku', 'Daily holding proposal')} (${d.daysRemaining} ${t('dní', 'days')})`} value={money(d.dailyHoldingProposal)} testId="period-proposal" />}
      </div>
      {d.requirementMet !== null && (
        <p style={{ fontSize: 12 }} data-testid="period-verdict">
          {d.requirementMet ? t('Požadavek za období splněn.', 'Requirement met over the period.') : t('Požadavek za období nesplněn.', 'Requirement not met over the period.')}
        </p>
      )}
      {d.missingDays.length > 0 && (
        <p style={note} data-testid="period-missing-days">
          {t('Dny bez snímku', 'Days without a snapshot')}: {d.missingDays.join(', ')}
        </p>
      )}
      {d.days.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table style={table} aria-label={t('Denní zůstatky', 'Daily holdings')}>
            <thead><tr><th scope="col" style={left}>{t('Den', 'Day')}</th><th scope="col" style={right}>{t('Zůstatek u ČNB', 'ČNB holding')}</th></tr></thead>
            <tbody>
              {d.days.map(day => (
                <tr key={day.date} data-period-day="true">
                  <td>{day.date}</td>
                  <td style={right}>{day.holdings === null ? t('neuvedeno', 'not stated') : money(day.holdings)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
