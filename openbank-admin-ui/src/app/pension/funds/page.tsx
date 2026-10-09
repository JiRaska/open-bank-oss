// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Funds and NAV (ADR-0334, openbank-pension-fund-service): the segregated funds, and per fund the
// NAV history with the maker/checker cycle — one operator calculates a NAV, a DIFFERENT operator
// publishes or rejects it. Publishing settles the orders queued at that forward price.
//
// FOUR-EYES: publish/reject is hidden on a NAV the viewer calculated (calculatedBy vs the token's
// principal name). That is a courtesy, not the control — pension-fund-service refuses a
// self-approval whatever the UI shows, and the page renders the refusal readably.

'use client'

import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { useSession } from 'next-auth/react'
import { Landmark, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { fundUrl, getJson, PENSION_FUND, sendJson } from '@/components/pension/api'
import { fundListSchema, navListSchema, navPublicationSchema, navSchema, type Fund, type Nav } from '@/components/pension/contracts'
import { canDecideNav, refusalText, statusLabel } from '@/components/pension/model'
import { PAGE_SIZE } from '@/components/pension/PensionQueue'
import { principalNameFromToken } from '@/components/balance-sheet/model'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function PensionFundsPage() {
  return (
    <AuthGuard permission="pension:view">
      <Funds />
    </AuthGuard>
  )
}

type Notice = { tone: 'success' | 'danger'; text: string }

const DATE = /^\d{4}-\d{2}-\d{2}$/

function Funds() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const canOperate = hasPermission(roles, 'pension:operate')
  const actor = principalNameFromToken(session?.user?.accessToken)
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const num = (v: number, digits = 2) => v.toLocaleString(locale, { minimumFractionDigits: digits, maximumFractionDigits: Math.max(digits, 6) })

  const [funds, setFunds] = useState<Fund[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [navs, setNavs] = useState<Nav[] | null>(null)
  const [navKind, setNavKind] = useState<UnavailableKind | null>(null)
  const [shown, setShown] = useState(PAGE_SIZE)
  const [valuationDate, setValuationDate] = useState('')
  const [cash, setCash] = useState('0')
  const [liabilities, setLiabilities] = useState('0')
  const [formHint, setFormHint] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const loadFunds = useCallback(async () => {
    const res = await getJson(fundUrl('/funds'), fundListSchema)
    if (!res.ok) { setUnavailable({ kind: res.kind }); setFunds(null); return }
    setUnavailable(null)
    setFunds(res.data)
    setSelected(prev => prev ?? res.data[0]?.id ?? null)
  }, [])

  const loadNavs = useCallback(async (fundId: string) => {
    const res = await getJson(fundUrl(`/funds/${encodeURIComponent(fundId)}/navs`), navListSchema)
    if (!res.ok) { setNavKind(res.kind); setNavs(null); return }
    setNavKind(null)
    setNavs(res.data)
    setShown(PAGE_SIZE)
  }, [])

  useEffect(() => { void loadFunds() }, [loadFunds])
  useEffect(() => { if (selected) void loadNavs(selected) }, [selected, loadNavs])

  const calculate = async (e: FormEvent) => {
    e.preventDefault()
    if (!selected) return
    const cashValue = Number(cash)
    const liabilityValue = Number(liabilities)
    if (!DATE.test(valuationDate)) { setFormHint(t('Zadejte datum ocenění (RRRR-MM-DD).', 'Enter the valuation date (YYYY-MM-DD).')); return }
    if (!Number.isFinite(cashValue) || !Number.isFinite(liabilityValue) || liabilityValue < 0) {
      setFormHint(t('Hotovost a závazky musí být čísla, závazky nezáporné.', 'Cash and liabilities must be numbers; liabilities not negative.'))
      return
    }
    setFormHint(null)
    setBusy(true)
    setNotice(null)
    const res = await sendJson('POST', fundUrl(`/funds/${encodeURIComponent(selected)}/navs`), { valuationDate, cash: cashValue, otherLiabilities: liabilityValue }, navSchema)
    setBusy(false)
    setNotice(res.ok
      ? { tone: 'success', text: t('NAV spočten. Zveřejnit jej musí jiná osoba.', 'NAV calculated. A different person must publish it.') }
      : { tone: 'danger', text: refusalText(res, t('Výpočet NAV', 'NAV calculation'), t) })
    void loadNavs(selected)
  }

  const decide = async (nav: Nav, publish: boolean) => {
    setBusy(true)
    setNotice(null)
    const res = publish
      ? await sendJson('POST', fundUrl(`/navs/${encodeURIComponent(nav.id)}/approve`), undefined, navPublicationSchema)
      : await sendJson('POST', fundUrl(`/navs/${encodeURIComponent(nav.id)}/reject`), undefined, navSchema)
    setBusy(false)
    setNotice(res.ok
      ? { tone: 'success', text: publish ? t('NAV zveřejněn, čekající pokyny vypořádány.', 'NAV published; queued orders settled.') : t('NAV zamítnut.', 'NAV rejected.') }
      : { tone: 'danger', text: refusalText(res, publish ? t('Zveřejnění NAV', 'NAV publication') : t('Zamítnutí NAV', 'NAV rejection'), t) })
    if (selected) void loadNavs(selected)
  }

  return (
    <div>
      <PageHeader
        title={t('Fondy a NAV', 'Funds & NAV')}
        subtitle={t('Oddělený majetek fondů; NAV počítá jedna osoba a zveřejňuje druhá.', 'Segregated fund assets; one person calculates a NAV, another publishes it.')}
        icon={<Landmark size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void loadFunds()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, borderColor: notice.tone === 'danger' ? 'var(--danger)' : 'var(--success)' }}>
          {notice.text}
        </div>
      )}

      <section className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service={PENSION_FUND} feature={t('penzijní fondy', 'pension funds')} lang={language} dense />
        ) : funds === null ? null : funds.length === 0 ? (
          <DataUnavailable kind="no_data" service={PENSION_FUND} feature={t('penzijní fondy', 'pension funds')} lang={language} dense />
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Fond', 'Fund')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('ISIN', 'ISIN')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Rizikovost', 'Risk class')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Úplata p.a.', 'Fee p.a.')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
              </tr>
            </thead>
            <tbody>
              {funds.map(f => (
                <tr key={f.id} aria-selected={f.id === selected}>
                  <td>
                    <button type="button" className="btn btn-link btn-sm" onClick={() => setSelected(f.id)}>
                      {f.name}{f.mandatoryConservative ? ` · ${t('povinný konzervativní', 'mandatory conservative')}` : ''}
                    </button>
                  </td>
                  <td>{f.isin}</td>
                  <td>{f.currency}</td>
                  <td style={{ textAlign: 'right' }}>{f.riskClass}</td>
                  <td style={{ textAlign: 'right' }}>{`${num(f.managementFeeRate * 100, 2)} %`}</td>
                  <td>{statusLabel(f.status, t)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {selected && (
        <section className="card" style={{ overflowX: 'auto' }}>
          <h2 style={{ fontSize: 15, marginTop: 0 }}>{t('Historie NAV', 'NAV history')}</h2>
          {canOperate && (
            <form onSubmit={calculate} style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 12 }}>
              <label style={{ fontSize: 12 }}>{t('Datum ocenění', 'Valuation date')}
                <input className="input" type="date" value={valuationDate} onChange={e => setValuationDate(e.target.value)} aria-label={t('Datum ocenění', 'Valuation date')} />
              </label>
              <label style={{ fontSize: 12 }}>{t('Hotovost', 'Cash')}
                <input className="input" inputMode="decimal" value={cash} onChange={e => setCash(e.target.value)} aria-label={t('Hotovost', 'Cash')} style={{ width: 120 }} />
              </label>
              <label style={{ fontSize: 12 }}>{t('Ostatní závazky', 'Other liabilities')}
                <input className="input" inputMode="decimal" value={liabilities} onChange={e => setLiabilities(e.target.value)} aria-label={t('Ostatní závazky', 'Other liabilities')} style={{ width: 120 }} />
              </label>
              <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>{t('Spočítat NAV', 'Calculate NAV')}</button>
              {formHint && <div role="alert" style={{ fontSize: 12, color: 'var(--danger-text)', width: '100%' }}>{formHint}</div>}
            </form>
          )}
          {navKind ? (
            <DataUnavailable kind={navKind} service={PENSION_FUND} feature={t('historie NAV', 'NAV history')} lang={language} dense />
          ) : navs === null ? null : navs.length === 0 ? (
            <DataUnavailable kind="no_data" service={PENSION_FUND} feature={t('historie NAV', 'NAV history')} lang={language} dense />
          ) : (
            <>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                  <tr>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Datum', 'Date')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Čistá aktiva', 'Net assets')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('Jednotky v oběhu', 'Units outstanding')}</th>
                    <th scope="col" style={{ textAlign: 'right' }}>{t('NAV / jednotku', 'NAV / unit')}</th>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Spočetl / zveřejnil', 'Calculated / published by')}</th>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Rozhodnutí', 'Decision')}</th>
                  </tr>
                </thead>
                <tbody>
                  {navs.slice(0, shown).map(n => (
                    <tr key={n.id}>
                      <td>{n.valuationDate}{n.correctsNavId ? ` · ${t('oprava', 'correction')}` : ''}</td>
                      <td style={{ textAlign: 'right' }}>{num(n.netAssets)}</td>
                      <td style={{ textAlign: 'right' }}>{num(n.unitsOutstanding, 4)}</td>
                      <td style={{ textAlign: 'right' }}>{num(n.navPerUnit, 6)}</td>
                      <td>{statusLabel(n.status, t)}</td>
                      <td>{`${n.calculatedBy} / ${n.approvedBy ?? '—'}`}</td>
                      <td>
                        {n.status === 'CALCULATED' && canOperate && !canDecideNav(n, actor) && (
                          <span style={{ fontSize: 12 }}>{t('Váš výpočet — zveřejnit jej musí jiná osoba.', 'Your calculation — a different person must publish it.')}</span>
                        )}
                        {canOperate && canDecideNav(n, actor) && (
                          <div style={{ display: 'flex', gap: 6 }}>
                            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void decide(n, true)}>{t('Zveřejnit', 'Publish')}</button>
                            <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void decide(n, false)}>{t('Zamítnout', 'Reject')}</button>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {navs.length > shown && (
                <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 12 }} onClick={() => setShown(s => s + PAGE_SIZE)}>
                  {t('Načíst další', 'Load more')}
                </button>
              )}
            </>
          )}
        </section>
      )}
    </div>
  )
}
