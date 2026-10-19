// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury deal overview (openbank-treasury-service, ADR-0315 / ADR-0344; console #10618). Every deal
// the desk has drafted with the catalogue's filters (instrument, state, portfolio, counterparty,
// currency, hide matured / authorised, free text, date ranges, kind), the record count the service
// reports, export of all / the selection / the regulatory layout, saved personal filters, and the
// position close of day (plus a special close). Reads go through the BFF with the signed-in
// person's own token.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { FilePlus, Landmark, RefreshCw } from 'lucide-react'
import { z } from 'zod'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { ExplorerGuide } from '@/components/brand/ExplorerGuide'
import overview from '@/components/brand/DomainOverview.module.css'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { getJson, treasuryUrl } from '@/components/treasury/api'
import { counterpartyListSchema, DEAL_STATES, type Counterparty, type DealState } from '@/components/treasury/contracts'
import { distinctCounterparties, STATE_TONE, stateLabel } from '@/components/treasury/model'
import { SyntheticBadge } from '@/components/treasury/SyntheticBadge'
import { ALL_PRODUCTS, enumLabel, KINDS, productName } from '@/components/treasury/wb/catalogue'
import { wbGet, wbGetText, wbPost, wbSend, wbUrl } from '@/components/treasury/wb/client'
import { positionCloseSchema, savedFilterListSchema, savedFilterSchema, ticketDealListSchema, type TicketDeal } from '@/components/treasury/wb/types'
import { downloadText, Field, Notice, NotAvailable, ReadFailure, Section, useFmt, writeFailureText, th, td, tdNum } from '@/components/treasury/wb/ui'
import { dealFilterQuery, filtersFromSaved, NO_DEAL_FILTERS, type DealFilters } from '@/components/treasury/wb/model'
import { bankToday } from '@/components/balance-sheet/model'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { WbRead } from '@/components/treasury/wb/client'

const PAGE_SIZE = 25

export default function TreasuryDealsPage() {
  return (
    <AuthGuard permission="treasury:view">
      <DealBlotter />
    </AuthGuard>
  )
}

function DealBlotter() {
  const { t, language, locale, money, num } = useFmt()
  const { language: lang } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const canCreate = hasPermission(roles, 'treasury:deal:create')
  const canApprove = hasPermission(roles, 'treasury:deal:approve')
  const canClose = hasPermission(roles, 'treasury:backoffice:write')

  const [filters, setFilters] = useState<DealFilters>(NO_DEAL_FILTERS)
  const [result, setResult] = useState<WbRead<TicketDeal[]> | null>(null)
  const [counterparties, setCounterparties] = useState<Counterparty[]>([])
  const [shown, setShown] = useState(PAGE_SIZE)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [saved, setSaved] = useState<WbRead<z.infer<typeof savedFilterListSchema>> | null>(null)
  const [filterName, setFilterName] = useState('')
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [closeDate, setCloseDate] = useState(bankToday())
  const [closeBook, setCloseBook] = useState('')
  const [scenario, setScenario] = useState('')

  const set = <K extends keyof DealFilters>(k: K, v: DealFilters[K]) => setFilters(f => ({ ...f, [k]: v }))

  const load = useCallback(async () => {
    const [res, cps] = await Promise.all([
      wbGet(wbUrl('/deals', dealFilterQuery(filters)), ticketDealListSchema),
      getJson(treasuryUrl('/counterparties'), counterpartyListSchema),
    ])
    if (cps.ok) setCounterparties(distinctCounterparties(cps.data))
    setResult(res)
    setShown(PAGE_SIZE)
  }, [filters])
  useEffect(() => { void load() }, [load])
  useEffect(() => { void (async () => setSaved(await wbGet(wbUrl('/views/deal-filters'), savedFilterListSchema)))() }, [])

  const byId = useMemo(() => new Map(counterparties.map(c => [c.counterpartyId, c])), [counterparties])
  const deals = result?.ok ? result.data : null
  const total = result?.ok ? (result.total ?? result.data.length) : null

  const toggle = (id: string) => setSelected(s => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })

  const exportDeals = async (scope: 'all' | 'selection', layout: 'STANDARD' | 'REGULATORY') => {
    if (scope === 'selection' && selected.size === 0) return
    const res = await wbGetText(wbUrl('/deals/export', { format: 'csv', scope, layout, ids: scope === 'selection' ? [...selected].join(',') : undefined, ...(scope === 'all' ? dealFilterQuery(filters) : {}) }))
    if (res.ok) { downloadText(`treasury-deals-${layout.toLowerCase()}.csv`, res.text); setNotice({ tone: 'success', text: t('Export stažen.', 'Export downloaded.') }) }
    else setNotice({ tone: 'danger', text: res.kind === 'not_available' ? t('Export zatím toto nasazení nenabízí.', 'This deployment does not offer export yet.') : res.kind === 'forbidden' ? t('Export: nemáte oprávnění.', 'Export: you are not permitted.') : t('Export se nepodařil.', 'The export failed.') })
  }

  const saveFilter = async () => {
    if (!filterName.trim()) return
    const res = await wbPost(wbUrl('/views/deal-filters'), { name: filterName.trim(), filter: dealFilterQuery(filters) }, savedFilterSchema)
    if (res.ok) {
      setFilterName('')
      setSaved(await wbGet(wbUrl('/views/deal-filters'), savedFilterListSchema))
      setNotice({ tone: 'success', text: t('Filtr uložen.', 'Filter saved.') })
    } else setNotice({ tone: 'danger', text: writeFailureText(res, t('Uložení filtru', 'Save filter'), t) })
  }
  const applyFilter = (raw: Record<string, unknown>) => setFilters(filtersFromSaved(raw))
  const deleteFilter = async (name: string) => {
    const res = await wbSend('DELETE', wbUrl(`/views/deal-filters/${encodeURIComponent(name)}`), undefined, z.unknown())
    if (!res.ok) setNotice({ tone: 'danger', text: writeFailureText(res, t('Smazání filtru', 'Delete filter'), t) })
    setSaved(await wbGet(wbUrl('/views/deal-filters'), savedFilterListSchema))
  }

  const close = async (special: boolean) => {
    const res = await wbPost(wbUrl(special ? '/positions/close-of-day/special' : '/positions/close-of-day'), { date: closeDate, bookId: closeBook.trim() || null, scenario: special ? scenario.trim() || null : null }, positionCloseSchema)
    const label = special ? t('Zvláštní uzávěrka', 'Special close') : t('Uzávěrka pozic', 'Position close')
    if (res.ok) setNotice({ tone: 'success', text: t(`${label}: zaznamenáno${res.data.snapshotId ? ` (snímek ${res.data.snapshotId.slice(0, 8)})` : ''}.`, `${label}: recorded${res.data.snapshotId ? ` (snapshot ${res.data.snapshotId.slice(0, 8)})` : ''}.`) })
    else setNotice({ tone: 'danger', text: writeFailureText(res, label, t) })
  }

  return (
    <div>
      <PageHeader
        title={t('Obchody treasury', 'Treasury deals')}
        subtitle={t('Obchody peněžního trhu, cenných papírů, FX a derivátů — pravidlo čtyř očí (ADR-0315, ADR-0344).', 'Money-market, securities, FX and derivative deals — four-eyes booking (ADR-0315, ADR-0344).')}
        icon={<Landmark size={20} aria-hidden="true" />}
        actions={
          <div style={{ display: 'flex', gap: 8 }}>
            {canCreate && (
              <Link href="/treasury/deals/new" className="btn btn-primary btn-sm">
                <FilePlus size={14} aria-hidden="true" /> {t('Nový obchod', 'New deal')}
              </Link>
            )}
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
              <RefreshCw size={14} aria-hidden="true" />
            </button>
          </div>
        }
      />

      <ExplorerGuide compact title={t('Nejdřív obchod, potom druhý pár očí', 'First the deal, then a second pair of eyes')}>
        {t(
          'Připravte obchod, zkontrolujte protistranu a limit a teprve pak jej předejte ke schválení. Vlastní obchod si neschválíte — účetní zápis vzniká až po nezávislém rozhodnutí.',
          'Prepare the deal, check the counterparty and limit, then send it for approval. You cannot approve your own deal — booking follows an independent decision.',
        )}
      </ExplorerGuide>

      <div className={overview.overview} aria-label={t('Přehled treasury', 'Treasury overview')}>
        <div className={overview.tile}>
          <span className={overview.label}>{t('Obchody v aktuálním výběru', 'Deals in current selection')}</span>
          <span className={overview.value}>{deals === null ? '—' : (total ?? deals.length).toLocaleString(locale)}</span>
          <span className={overview.detail}>{t('Počet hlásí služba pro aktuální filtr.', 'The service reports the count for the current filter.')}</span>
        </div>
        {canApprove ? (
          <Link className={overview.tile} href="/treasury/approvals">
            <span className={overview.label}>{t('Čtyři oči', 'Four eyes')}</span>
            <span className={overview.value}>{t('Ke schválení →', 'Approval inbox →')}</span>
            <span className={overview.detail}>{t('Rozhodnutí dělá jiná oprávněná osoba.', 'A different authorised person makes the decision.')}</span>
          </Link>
        ) : (
          <div className={overview.tile}>
            <span className={overview.label}>{t('Čtyři oči', 'Four eyes')}</span>
            <span className={overview.value}>{t('Nezávislé schválení', 'Independent approval')}</span>
            <span className={overview.detail}>{t('Po předložení rozhodne oprávněný schvalovatel.', 'After submission, an authorised approver decides.')}</span>
          </div>
        )}
        <Link className={overview.tile} href="/treasury/limits">
          <span className={overview.label}>{t('Před obchodem', 'Before booking')}</span>
          <span className={overview.value}>{t('Čerpání limitů →', 'Limit utilisation →')}</span>
          <span className={overview.detail}>{t('Podívejte se na dostupnou kapacitu protistran.', 'See available counterparty capacity.')}</span>
        </Link>
      </div>

      {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}

      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12, alignItems: 'end' }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
            {t('Stav', 'State')}
            <select className="input" value={filters.state} onChange={e => set('state', e.target.value as DealState | '')} aria-label={t('Filtr podle stavu', 'Filter by state')}>
              <option value="">{t('Všechny stavy', 'All states')}</option>
              {DEAL_STATES.map(s => <option key={s} value={s}>{stateLabel(s, t)}</option>)}
            </select>
          </label>
          <Field label={t('Nástroj', 'Instrument')}>
            <select className="input" value={filters.instrument} onChange={e => set('instrument', e.target.value)}>
              <option value="">{t('Všechny', 'All')}</option>
              {ALL_PRODUCTS.map(p => <option key={p} value={p}>{productName(p, t)}</option>)}
            </select>
          </Field>
          <Field label={t('Portfolio (kniha)', 'Portfolio (book)')}><input className="input" value={filters.bookId} onChange={e => set('bookId', e.target.value)} /></Field>
          <Field label={t('Protistrana', 'Counterparty')}>
            <select className="input" value={filters.counterpartyId} onChange={e => set('counterpartyId', e.target.value)}>
              <option value="">{t('Všechny', 'All')}</option>
              {counterparties.map(c => <option key={c.counterpartyId} value={c.counterpartyId}>{c.name}</option>)}
            </select>
          </Field>
          <Field label={t('Měna', 'Currency')}><input className="input" value={filters.currency} maxLength={3} onChange={e => set('currency', e.target.value.toUpperCase())} /></Field>
          <Field label={t('Druh obchodu', 'Deal kind')}>
            <select className="input" value={filters.kind} onChange={e => set('kind', e.target.value)}>
              <option value="">{t('Všechny', 'All')}</option>
              {KINDS.map(k => <option key={k} value={k}>{enumLabel(k, t)}</option>)}
            </select>
          </Field>
          <Field label={t('Hledat text', 'Free text')}><input className="input" value={filters.text} maxLength={100} onChange={e => set('text', e.target.value)} /></Field>
          <Field label={t('Obchod od', 'Trade date from')}><input className="input" type="date" value={filters.tradeDateFrom} onChange={e => set('tradeDateFrom', e.target.value)} /></Field>
          <Field label={t('Obchod do', 'Trade date to')}><input className="input" type="date" value={filters.tradeDateTo} onChange={e => set('tradeDateTo', e.target.value)} /></Field>
          <Field label={t('Splatnost od', 'Maturity from')}><input className="input" type="date" value={filters.maturityFrom} onChange={e => set('maturityFrom', e.target.value)} /></Field>
          <Field label={t('Splatnost do', 'Maturity to')}><input className="input" type="date" value={filters.maturityTo} onChange={e => set('maturityTo', e.target.value)} /></Field>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
            <label><input type="checkbox" checked={filters.hideMatured} onChange={e => set('hideMatured', e.target.checked)} /> {t('Skrýt splatné', 'Hide matured')}</label>
            <label><input type="checkbox" checked={filters.hideAuthorised} onChange={e => set('hideAuthorised', e.target.checked)} /> {t('Skrýt autorizované', 'Hide authorised')}</label>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end', marginTop: 12 }}>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => setFilters(NO_DEAL_FILTERS)}>{t('Zrušit filtry', 'Clear filters')}</button>
          <Field label={t('Název osobního filtru', 'Personal filter name')}><input className="input" value={filterName} onChange={e => setFilterName(e.target.value)} /></Field>
          <button type="button" className="btn btn-secondary btn-sm" disabled={!filterName.trim()} onClick={() => void saveFilter()}>{t('Uložit filtr', 'Save filter')}</button>
          {saved?.ok && saved.data.map(f => (
            <span key={f.name} style={{ display: 'inline-flex', gap: 2 }}>
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => applyFilter(f.filter)}>{f.name}</button>
              <button type="button" className="btn btn-ghost btn-sm" aria-label={t(`Smazat filtr ${f.name}`, `Delete filter ${f.name}`)} onClick={() => void deleteFilter(f.name)}>×</button>
            </span>
          ))}
          {saved && !saved.ok && saved.kind === 'not_available' && <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Osobní filtry toto nasazení zatím nenabízí.', 'Personal filters are not available on this deployment yet.')}</span>}
        </div>
      </div>

      <Section title={t('Export a uzávěrka', 'Export and close of day')} id="deal-export">
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void exportDeals('all', 'STANDARD')}>{t('Exportovat vše', 'Export all')}</button>
          <button type="button" className="btn btn-secondary btn-sm" disabled={selected.size === 0} onClick={() => void exportDeals('selection', 'STANDARD')}>{t(`Exportovat výběr (${selected.size})`, `Export selection (${selected.size})`)}</button>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void exportDeals('all', 'REGULATORY')}>{t('Export v regulatorním formátu', 'Export in regulatory layout')}</button>
          {canClose && (
            <>
              <Field label={t('Datum uzávěrky', 'Close date')}><input className="input" type="date" value={closeDate} onChange={e => setCloseDate(e.target.value)} /></Field>
              <Field label={t('Kniha uzávěrky (nepovinné)', 'Close book (optional)')}><input className="input" value={closeBook} onChange={e => setCloseBook(e.target.value)} /></Field>
              <button type="button" className="btn btn-primary btn-sm" onClick={() => void close(false)}>{t('Uzávěrka pozic', 'Position close of day')}</button>
              <Field label={t('Scénář zvláštní uzávěrky', 'Special-close scenario')}><input className="input" value={scenario} onChange={e => setScenario(e.target.value)} /></Field>
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => void close(true)}>{t('Zvláštní uzávěrka', 'Special close')}</button>
            </>
          )}
        </div>
      </Section>

      <div className="card" style={{ overflowX: 'auto' }}>
        <p style={{ fontSize: 12, marginBottom: 8 }} data-testid="record-count" aria-live="polite">
          {total === null ? '' : t(`Záznamů: ${total.toLocaleString(locale)}`, `Records: ${total.toLocaleString(locale)}`)}
          {deals !== null && total !== null && deals.length < total ? t(` (načteno ${deals.length})`, ` (loaded ${deals.length})`) : ''}
        </p>
        {result && !result.ok ? (
          result.kind === 'not_available' ? <NotAvailable what={t('Přehled obchodů', 'The deal overview')} /> :
            result.kind === 'forbidden' ? <ReadFailure result={result} what={t('obchody treasury', 'treasury deals')} /> :
              <DataUnavailable kind={result.kind === 'unauthorized' ? 'unauthorized' : result.kind} service="treasury-service" feature={t('obchody treasury', 'treasury deals')} lang={lang} dense />
        ) : deals === null ? null : deals.length === 0 ? (
          <DataUnavailable kind="no_data" service="treasury-service" feature={t('obchody treasury', 'treasury deals')} lang={language} dense />
        ) : (
          <>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
              <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Obchody treasury', 'Treasury deals')}</caption>
              <thead>
                <tr>
                  <th scope="col" style={th}><span className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Výběr', 'Select')}</span></th>
                  {[
                    t('Obchod', 'Deal'), t('Nástroj', 'Instrument'), t('Stav', 'State'), t('Původní obchod', 'Original deal'), t('Portfolio', 'Portfolio'),
                    t('Druh', 'Kind'), t('Protistrana', 'Counterparty'), t('Měna', 'Ccy'),
                  ].map(h => <th key={h} scope="col" style={th}>{h}</th>)}
                  <th scope="col" style={{ ...th, textAlign: 'right' }}>{t('Jistina', 'Principal')}</th>
                  <th scope="col" style={{ ...th, textAlign: 'right' }}>{t('Sazba % p.a.', 'Rate % p.a.')}</th>
                  {[t('Obchod / valuta / splatnost', 'Trade / value / maturity'), t('FX', 'FX'), t('Poslední změna', 'Last change'), t('Vytvořil', 'Created by')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {deals.slice(0, shown).map(d => {
                  const cp = byId.get(d.counterpartyId)
                  const cpName = d.counterpartyName ?? cp?.name ?? d.counterpartyId
                  return (
                    <tr key={d.dealId} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={td}><input type="checkbox" checked={selected.has(d.dealId)} onChange={() => toggle(d.dealId)} aria-label={t(`Vybrat obchod ${productName(d.product, t)} ${cpName}`, `Select deal ${productName(d.product, t)} ${cpName}`)} /></td>
                      <td style={td}><Link href={`/treasury/deals/${encodeURIComponent(d.dealId)}`}>{productName(d.product, t)}</Link></td>
                      <td style={td}>{d.product}</td>
                      <td style={td}><StatusBadge status={d.state} tone={STATE_TONE[d.state]} label={stateLabel(d.state, t)} /></td>
                      <td style={td}>{d.originalDealId ? <Link href={`/treasury/deals/${encodeURIComponent(d.originalDealId)}`}>{t('původní', 'original')}</Link> : '—'}</td>
                      <td style={td}>{d.bookId ?? '—'}</td>
                      <td style={td}>{d.kind ? enumLabel(d.kind, t) : '—'}</td>
                      <td style={td}>{cpName} <SyntheticBadge synthetic={cp?.synthetic} /></td>
                      <td style={td}>{d.currency}</td>
                      <td style={tdNum}>{money(d.notional ?? d.principal)}</td>
                      <td style={tdNum}>{num(d.rate)}</td>
                      <td style={td}>{`${d.tradeDate} · ${d.valueDate} → ${d.maturityDate}`}</td>
                      <td style={td}>
                        {d.fx ? (
                          <>
                            {`${d.fx.side} ${d.currency}/CZK`}
                            <span style={{ color: 'var(--text-tertiary)' }}>{` · ${money(d.fx.side === 'BUY' ? d.fx.sellAmount : d.fx.buyAmount)} CZK`}</span>
                          </>
                        ) : '—'}
                      </td>
                      <td style={td}>{d.lastChangedAt ? `${new Date(d.lastChangedAt).toLocaleString(locale)}${d.lastChangedBy ? ` · ${d.lastChangedBy}` : ''}` : '—'}</td>
                      <td style={td}>{d.createdBy}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {deals.length > shown && (
              <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 12 }} onClick={() => setShown(s => s + PAGE_SIZE)}>
                {t('Načíst další', 'Load more')}
              </button>
            )}
          </>
        )}
      </div>
    </div>
  )
}
