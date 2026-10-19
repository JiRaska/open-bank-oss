// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// The deal ticket (ADR-0344 catalogue): product picker for all 16 products, the header, the leg
// panels, the product-specific panels and the tabs, with a "Generate schedule" action that previews
// the cash-flow ledger before anything is submitted. Client rules mirror the server's; a server
// 422 (REFERENCE_NOT_EFFECTIVE, PRODUCT_LIMIT_BREACHED, FOUR_EYES_VIOLATION, …) lands on the field
// it concerns. Creates a DRAFT (nothing posts); submit sends it to a DIFFERENT person.
import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { StatusBadge, Tabs, type TabItem } from '@/components/ui'
import { dealActionUrl, treasuryUrl } from '@/components/treasury/api'
import { counterpartyListSchema, type Counterparty } from '@/components/treasury/contracts'
import { distinctCounterparties, eligibleCounterparties, exceedsHeadroom, headroomFor, isAssetProduct, nextBusinessDay } from '@/components/treasury/model'
import { SyntheticBadge } from '@/components/treasury/SyntheticBadge'
import { bankToday } from '@/components/balance-sheet/model'
import { ALL_PRODUCTS, productName, profileOf, type AnyProduct } from './catalogue'
import { CashFlowLedgerView } from './CashFlowLedger'
import { wbGet, wbPost, wbUrl, riskUrl, type WbWrite } from './client'
import {
  AccountsTab, CentralBankTab, CollateralTab, DepositPanel, DocumentsTab, FeesTab, FraPanel, FxPanel, HeaderPanel, LegacyTermsPanel,
  LegPanel, LinkedDealsTab, LiquidityTab, SecuritiesPanel, type SetForm,
} from './TicketPanels'
import {
  buildRequest, emptyLeg, emptyTicket, legsFor, refusalErrors, ticketFromDeal, validateTicket, type Errors, type TicketForm,
} from './ticket'
import { cashFlowLedgerSchema, paymentListSchema, ticketDealSchema, type CashFlowLedger, type TicketDeal } from './types'
import { Field, Notice, Section, useFmt, writeFailureText, NotAvailable } from './ui'
import { z } from 'zod'

type TabId = 'timeline' | 'accounts' | 'documents' | 'payments' | 'collateral' | 'linked' | 'credit' | 'fees' | 'codes' | 'liquidity'

type Props = {
  mode: 'create' | 'edit'
  /** The stored DRAFT in edit mode. */
  deal?: TicketDeal
  canCreate: boolean
  canBackOffice: boolean
  actor: string | null
  onSaved?: (deal: TicketDeal) => void
}

const pricingSchema = z.object({
  pv: z.union([z.number(), z.string()]).transform(Number), pv01: z.union([z.number(), z.string()]).transform(Number).nullish(),
  currency: z.string().nullish(), provenance: z.string().nullish(), accrued: z.union([z.number(), z.string()]).transform(Number).nullish(),
  dv01Buckets: z.array(z.object({ bucket: z.string(), dv01: z.union([z.number(), z.string()]).transform(Number) })).default([]),
})

export function DealTicket({ mode, deal, canCreate, canBackOffice, actor, onSaved }: Props) {
  const { t, money, num } = useFmt()
  const today = useMemo(() => bankToday(), [])
  const [form, setForm] = useState<TicketForm>(() => (deal ? ticketFromDeal(deal) : emptyTicket('MM_PLACEMENT', today)))
  const [errors, setErrors] = useState<Errors>({})
  const [saved, setSaved] = useState<TicketDeal | null>(deal ?? null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger' | 'warning'; text: string } | null>(null)
  const [preview, setPreview] = useState<CashFlowLedger | null>(null)
  const [previewState, setPreviewState] = useState<'idle' | 'busy' | 'not_available'>('idle')
  const [priced, setPriced] = useState<z.infer<typeof pricingSchema> | null>(null)
  const [tab, setTab] = useState<TabId>('accounts')
  const [counterparties, setCounterparties] = useState<Counterparty[] | null>(null)

  const profile = profileOf(form.product)
  const facility = form.product === 'CNB_DEPOSIT_FACILITY' || form.product === 'CNB_LOMBARD'
  // A stored deal may only be edited while it is still a DRAFT, by a dealer.
  const locked = saved !== null && mode === 'create'
  const disabled = busy || locked || (mode === 'edit' && saved?.state !== 'DRAFT') || !canCreate

  useEffect(() => {
    void (async () => {
      const res = await wbGet(treasuryUrl('/counterparties'), counterpartyListSchema)
      if (res.ok) setCounterparties(distinctCounterparties(res.data))
    })()
  }, [])

  const set: SetForm = useCallback((key, value) => {
    setForm(f => ({ ...f, [key]: value }))
    setErrors(e => { if (!(key in e)) return e; const n = { ...e }; delete n[key as string]; return n })
  }, [])

  const changeProduct = (product: AnyProduct) => {
    setForm(f => {
      const base = emptyTicket(product, today)
      return { ...base, counterpartyId: product === 'CNB_DEPOSIT_FACILITY' || product === 'CNB_LOMBARD' ? 'CNB' : '', kind: f.kind, tradeDateTime: f.tradeDateTime, note: f.note, rationale: f.rationale, bookId: f.bookId, channel: f.channel }
    })
    setErrors({})
    setPreview(null)
    setPriced(null)
  }

  const setLeg = (i: number, patch: Partial<TicketForm['legs'][number]>) => {
    setForm(f => ({ ...f, legs: f.legs.map((l, k) => (k === i ? { ...l, ...patch } : l)) }))
    setErrors(e => {
      const n = { ...e }
      for (const key of Object.keys(patch)) delete n[`legs.${i}.${key}`]
      return n
    })
  }

  const eligible = useMemo(() => {
    if (!counterparties) return []
    const list = profile.legacy ? eligibleCounterparties(counterparties, form.product as never) : counterparties
    return list.map(c => ({ id: c.counterpartyId, label: c.synthetic ? `${c.name} (${t('simulovaná', 'synthetic')})` : c.name }))
  }, [counterparties, form.product, profile.legacy, t])

  const selectedCp = counterparties?.find(c => c.counterpartyId === form.counterpartyId)
  const headCurrency = form.product === 'FX_SPOT' ? 'CZK' : (profile.legacy ? form.currency : form.legs[0]?.currency ?? form.currency)
  const headroomRow = counterparties && form.counterpartyId ? headroomFor(counterparties, form.counterpartyId, headCurrency) : null
  const headAmount = profile.legacy && form.product !== 'FX_SPOT' ? Number(form.principal) : Number(form.legs[0]?.notional ?? form.principal)
  const overHeadroom = profile.legacy && form.product !== 'FX_SPOT' && exceedsHeadroom(form.product as never, headAmount, headroomRow)

  const clientErrors = () => validateTicket(form, t)

  const save = async (): Promise<TicketDeal | null> => {
    const e = clientErrors()
    setErrors(e)
    if (Object.keys(e).length) { setNotice({ tone: 'danger', text: t('Opravte zvýrazněná pole.', 'Correct the highlighted fields.') }); return null }
    setBusy(true)
    setNotice(null)
    const body = buildRequest(form)
    const res: WbWrite<TicketDeal> = saved && mode === 'edit'
      ? await (await import('./client')).wbSend('PUT', treasuryUrl(`/deals/${encodeURIComponent(saved.dealId)}`), body, ticketDealSchema)
      : await wbPost(treasuryUrl('/deals'), body, ticketDealSchema)
    setBusy(false)
    if (res.ok) {
      setSaved(res.data)
      setNotice({ tone: 'success', text: t('Koncept obchodu uložen. Nic se nezaúčtovalo; předložte jej ke schválení.', 'Draft deal saved. Nothing has posted; submit it for approval.') })
      onSaved?.(res.data)
      return res.data
    }
    if (res.kind === 'refused') {
      const fe = refusalErrors(res, t)
      if (Object.keys(fe).length) setErrors(fe)
    }
    setNotice({ tone: 'danger', text: writeFailureText(res, saved && mode === 'edit' ? t('Uložení změn', 'Save changes') : t('Vytvoření', 'Create'), t) })
    return null
  }

  const saveAndClose = async () => {
    const d = await save()
    if (d) window.location.href = '/treasury/deals'
  }

  const submit = async () => {
    if (!saved) return
    if (form.kind === 'HYPOTHETICAL') return
    setBusy(true)
    const res = await wbPost(dealActionUrl(saved.dealId, 'submit'), undefined, ticketDealSchema)
    setBusy(false)
    if (res.ok) {
      setSaved(res.data)
      setErrors({})
      setNotice({ tone: 'success', text: t('Obchod předložen. Schválit jej musí jiná osoba.', 'Deal submitted. A different person must approve it.') })
    } else {
      setErrors(refusalErrors(res, t))
      setNotice({ tone: 'danger', text: writeFailureText(res, t('Předložení', 'Submit'), t) })
    }
  }

  const cancelDraft = async () => {
    if (!saved) return
    setBusy(true)
    const res = await wbPost(dealActionUrl(saved.dealId, 'cancel'), undefined, ticketDealSchema)
    setBusy(false)
    if (res.ok) { setSaved(res.data); setNotice({ tone: 'success', text: t('Obchod zrušen.', 'Deal cancelled.') }) }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Zrušení', 'Cancel'), t) })
  }

  const sendConfirmation = async () => {
    if (!saved) return
    setBusy(true)
    const res = await wbPost(wbUrl(`/deals/${encodeURIComponent(saved.dealId)}/send-confirmation`), {}, ticketDealSchema)
    setBusy(false)
    if (res.ok) { setSaved(res.data); setNotice({ tone: 'success', text: t('Odeslání konfirmace zaznamenáno.', 'Confirmation recorded as sent.') }) }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Odeslání konfirmace', 'Send confirmation'), t) })
  }

  /** "Generate schedule": drafts the deal if needed, then asks the service for the ledger it would write. */
  const generate = async () => {
    let target = saved
    if (!target) {
      target = await save()
      if (!target) return
    }
    setPreviewState('busy')
    const res = await wbPost(wbUrl(`/deals/${encodeURIComponent(target.dealId)}/schedule-preview`), undefined, cashFlowLedgerSchema)
    if (res.ok) { setPreview(res.data); setPreviewState('idle'); return }
    setPreviewState(res.kind === 'not_available' ? 'not_available' : 'idle')
    if (res.kind === 'refused') {
      setErrors(refusalErrors(res, t))
      setNotice({ tone: 'danger', text: writeFailureText(res, t('Náhled harmonogramu', 'Schedule preview'), t) })
    } else if (res.kind !== 'not_available') setNotice({ tone: 'danger', text: writeFailureText(res, t('Náhled harmonogramu', 'Schedule preview'), t) })
  }

  /** Pre-trade pricing of the legs (risk-engine, stateless). */
  const price = async () => {
    const e = clientErrors()
    const legErrors = Object.fromEntries(Object.entries(e).filter(([k]) => k.startsWith('legs.') || k.startsWith('fx') || k === 'buyCurrency' || k === 'farDate' || k === 'nearDate'))
    if (Object.keys(legErrors).length) { setErrors(e); setNotice({ tone: 'danger', text: t('Pro ocenění opravte nohy obchodu.', 'Correct the legs before pricing.') }); return }
    setBusy(true)
    const res = await wbPost(riskUrl('/pricing'), { asOf: today, currency: 'CZK', calendars: form.calendars.split(',').map(x => x.trim()).filter(Boolean), businessDayConvention: form.businessDayConvention, legs: legsFor(form) }, pricingSchema)
    setBusy(false)
    if (res.ok) { setPriced(res.data); setNotice(null) }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Ocenění', 'Pricing'), t) })
  }

  const canSubmit = profile.legacy || !form.kind || form.kind !== 'HYPOTHETICAL'
  const showLegs = profile.legs > 0
  const hasPricing = (profile.legs > 0 || profile.panels.includes('fx')) && !profile.legacy

  const tabs: TabItem<TabId>[] = [
    { id: 'timeline', label: t('Stav a průběh', 'State & timeline') },
    { id: 'accounts', label: t('Účty vypořádání', 'Cash & securities accounts') },
    { id: 'documents', label: t('Dokumenty', 'Documents') },
    { id: 'payments', label: t('Platební příkazy', 'Payment orders') },
    { id: 'collateral', label: t('Zajištění', 'Collateral') },
    { id: 'linked', label: t('Propojené obchody', 'Linked deals') },
    { id: 'credit', label: t('Kontrola expozice', 'Credit-exposure check') },
    { id: 'fees', label: t('Poplatky', 'Fees') },
    { id: 'codes', label: t('Kódy ČNB', 'Central-bank codes') },
    { id: 'liquidity', label: t('Parametry LCR/NSFR', 'LCR/NSFR parameters') },
  ]
  const panelProps = { form, set, errors, disabled }

  return (
    <div>
      {!canCreate && (
        <p role="note" style={{ fontSize: 12, marginBottom: 8, color: 'var(--text-secondary)' }}>{t('Obchod smí zadávat a měnit jen dealer.', 'Only a dealer may enter or change a deal.')}</p>
      )}
      <Section title={t('Typ obchodu', 'Deal type')} id="ticket-product">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
          <Field label={t('Produkt', 'Product')} error={errors.product ?? null}>
            <select className="input" value={form.product} disabled={disabled || mode === 'edit'} onChange={e => changeProduct(e.target.value as AnyProduct)}>
              {ALL_PRODUCTS.map(p => <option key={p} value={p}>{productName(p, t)}</option>)}
            </select>
          </Field>
        </div>
      </Section>

      <HeaderPanel {...panelProps} profile={profile} counterparties={eligible} facility={facility} />

      {counterparties === null && null}
      {form.counterpartyId && (
        <div data-testid="limit-headroom" style={{ margin: '0 0 16px', fontSize: 13 }}>
          {selectedCp && <SyntheticBadge synthetic={selectedCp.synthetic} />}{' '}
          {headroomRow ? (
            <>
              {t('Volný limit protistrany', 'Counterparty limit headroom')}{`: ${money(headroomRow.headroom)} ${headCurrency}`}
              <span style={{ color: 'var(--text-tertiary)' }}>{` (${t('limit', 'limit')} ${money(headroomRow.limit)}, ${t('expozice', 'exposure')} ${money(headroomRow.exposure)})`}</span>
            </>
          ) : t(`Pro měnu ${headCurrency} není limit protistrany k dispozici.`, `No counterparty limit is available in ${headCurrency}.`)}
          {!isAssetProduct(form.product as never) && profile.legacy && <div style={{ color: 'var(--text-tertiary)' }}>{t('Přijetí prostředků nečerpá úvěrový limit protistrany.', 'Borrowing does not consume the counterparty credit limit.')}</div>}
          {overHeadroom && <div role="alert" style={{ color: 'var(--danger-text)', marginTop: 4 }}>{t('Jistina přesahuje volný limit. Server obchod při předložení či schválení odmítne (LIMIT_BREACHED).', 'The principal exceeds the headroom. The server will refuse the deal at submit or approval (LIMIT_BREACHED).')}</div>}
        </div>
      )}

      {profile.legacy && <LegacyTermsPanel {...panelProps} />}
      {facility && (
        <p role="note" style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 16 }} data-testid="facility-maturity-preview">
          {form.product === 'CNB_LOMBARD'
            ? t('Lombardní úvěr ČNB je vždy přes noc a pouze v CZK.', 'The ČNB lombard facility is always overnight and CZK only.')
            : t('Depozitní facilita je vždy přes noc a pouze v CZK.', 'The deposit facility is always overnight and CZK only.')}
          {nextBusinessDay(form.valueDate) && <> {t(`Splatnost (další pracovní den): ${nextBusinessDay(form.valueDate)}.`, `Maturity (next business day): ${nextBusinessDay(form.valueDate)}.`)}</>}
        </p>
      )}
      {errors.principal && !profile.legacy && <p role="alert" style={{ color: 'var(--danger-text)', fontSize: 12 }}>{errors.principal}</p>}
      {errors.currency && !profile.legacy && <p role="alert" style={{ color: 'var(--danger-text)', fontSize: 12 }}>{errors.currency}</p>}
      {errors.maturityDate && !profile.legacy && null}

      {profile.panels.includes('deposit') && <DepositPanel {...panelProps} />}
      {profile.panels.includes('securities') && <SecuritiesPanel {...panelProps} />}
      {profile.panels.includes('fx') && <FxPanel {...panelProps} />}
      {profile.panels.includes('fra') && <FraPanel {...panelProps} />}
      {profile.panels.includes('collateral') && (
        <p role="note" style={{ fontSize: 12, marginBottom: 16 }}>
          {t('Repo: zajištění, haircut a práva na výměnu zadejte na záložce ', 'Repo: enter the collateral, haircut and substitution rights on the ')}
          <button type="button" className="btn btn-ghost" onClick={() => setTab('collateral')}>{t('Zajištění', 'Collateral')}</button>.
        </p>
      )}

      {showLegs && form.legs.map((leg, i) => (
        <LegPanel key={i} leg={leg} index={i} errors={errors} disabled={disabled} set={patch => setLeg(i, patch)}
          title={form.legs.length > 1 ? t(`Noha ${i + 1}: ${leg.payReceive === 'PAY' ? 'platím' : 'přijímám'}`, `Leg ${i + 1}: ${leg.payReceive === 'PAY' ? 'pay' : 'receive'}`) : t('Noha obchodu', 'Deal leg')} />
      ))}
      {showLegs && form.legs.length < 2 && form.product === 'IRS' && (
        <button type="button" className="btn btn-secondary btn-sm" style={{ marginBottom: 16 }} disabled={disabled} onClick={() => set('legs', [...form.legs, emptyLeg('RECEIVE')])}>{t('Přidat nohu', 'Add leg')}</button>
      )}

      <Section title={t('Harmonogram peněžních toků', 'Cash-flow schedule')} id="ticket-schedule"
        actions={
          <div style={{ display: 'flex', gap: 8 }}>
            {hasPricing && (
              <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void price()}>{t('Předběžně ocenit', 'Price pre-trade')}</button>
            )}
            <button type="button" className="btn btn-secondary btn-sm" disabled={busy || !canCreate} onClick={() => void generate()}>{t('Vygenerovat harmonogram', 'Generate schedule')}</button>
          </div>
        }>
        {previewState === 'busy' && <p style={{ fontSize: 13 }}>{t('Generuji harmonogram…', 'Generating the schedule…')}</p>}
        {previewState === 'not_available' && <NotAvailable what={t('Náhled harmonogramu', 'Schedule preview')} />}
        {preview && saved && <CashFlowLedgerView dealId={saved.dealId} ledger={preview} preview actor={actor} canPropose={false} canDecide={false} />}
        {!preview && previewState === 'idle' && <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Harmonogram se zobrazí po stisknutí „Vygenerovat harmonogram“. Uloží se jen koncept obchodu.', 'The schedule appears after “Generate schedule”. Only the draft deal is saved.')}</p>}
        {priced && (
          <div data-testid="pricing-result" style={{ fontSize: 13, marginTop: 8 }}>
            <strong>{t('Předběžné ocenění', 'Pre-trade pricing')}</strong>{' '}
            {priced.provenance && <StatusBadge status={priced.provenance.toUpperCase()} tone={priced.provenance === 'regulatory' ? 'success' : 'warning'} label={priced.provenance} />}
            <div>{`PV ${money(priced.pv)} ${priced.currency ?? ''} · PV01 ${money(priced.pv01 ?? null)}`}</div>
            {priced.dv01Buckets.length > 0 && <div style={{ color: 'var(--text-secondary)' }}>{priced.dv01Buckets.map(b => `${b.bucket}: ${num(b.dv01, 2)}`).join(' · ')}</div>}
          </div>
        )}
      </Section>

      <Section title={t('Další údaje', 'Further details')} id="ticket-tabs">
        <Tabs items={tabs} value={tab} onChange={setTab} label={t('Záložky obchodu', 'Deal tabs')} idPrefix="ticket-tab" />
        <div role="tabpanel" id={`ticket-tab-panel-${tab}`} aria-labelledby={`ticket-tab-${tab}`} style={{ marginTop: 12 }}>
          {tab === 'timeline' && <TimelineTab deal={saved} />}
          {tab === 'accounts' && <AccountsTab {...panelProps} />}
          {tab === 'documents' && <DocumentsTab {...panelProps} />}
          {tab === 'payments' && <PaymentOrdersTab deal={saved} />}
          {tab === 'collateral' && <CollateralTab {...panelProps} relevant={profile.panels.includes('collateral')} />}
          {tab === 'linked' && <LinkedDealsTab {...panelProps} />}
          {tab === 'credit' && <CreditTab deal={saved} />}
          {tab === 'fees' && <FeesTab {...panelProps} />}
          {tab === 'codes' && <CentralBankTab {...panelProps} />}
          {tab === 'liquidity' && <LiquidityTab {...panelProps} />}
        </div>
      </Section>

      {errors._form && <Notice tone="danger">{errors._form}</Notice>}
      {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}

      <div className="card" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }} data-testid="ticket-actions">
        {saved && <StatusBadge status={saved.state} tone="neutral" label={saved.state} />}
        {(!saved || mode === 'edit') && canCreate && (
          <>
            <button type="button" className="btn btn-primary btn-sm" disabled={busy || (saved !== null && saved.state !== 'DRAFT') || (profile.legacy && mode === 'create' && Object.keys(validateTicket(form, t)).length > 0)} onClick={() => void save()}>
              {mode === 'edit' ? t('Uložit změny', 'Save changes') : t('Vytvořit koncept', 'Create draft')}
            </button>
            <button type="button" className="btn btn-secondary btn-sm" disabled={busy || (saved !== null && saved.state !== 'DRAFT')} onClick={() => void saveAndClose()}>{t('Uložit a zavřít', 'Save & close')}</button>
          </>
        )}
        {saved?.state === 'DRAFT' && canCreate && canSubmit && (
          <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void submit()}>{t('Předložit ke schválení', 'Submit for approval')}</button>
        )}
        {saved?.state === 'DRAFT' && form.kind === 'HYPOTHETICAL' && (
          <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Hypotetický obchod se nepředkládá — slouží jen k ocenění.', 'A hypothetical deal is never submitted — it is for pricing only.')}</span>
        )}
        {saved?.state === 'DRAFT' && canCreate && (
          <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void cancelDraft()}>{t('Zrušit koncept', 'Cancel draft')}</button>
        )}
        {saved && canBackOffice && saved.state !== 'DRAFT' && saved.state !== 'CANCELLED' && (
          <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void sendConfirmation()}>{t('Odeslat konfirmaci', 'Send confirmation')}</button>
        )}
        {saved && mode === 'create' && (
          <Link href={`/treasury/deals/${encodeURIComponent(saved.dealId)}`} className="btn btn-secondary btn-sm">{t('Detail obchodu', 'Deal detail')}</Link>
        )}
      </div>
    </div>
  )
}

function TimelineTab({ deal }: { deal: TicketDeal | null }) {
  const { t, dateTime } = useFmt()
  if (!deal) return <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Obchod začne ve stavu Koncept; průběh se zobrazí po uložení.', 'The deal starts as a Draft; the timeline appears once it is saved.')}</p>
  return (
    <ol style={{ fontSize: 13, paddingLeft: 18 }}>
      {deal.history.map((h, i) => (
        <li key={i}>{`${dateTime(h.at)} · ${h.from ?? '—'} → ${h.to} · ${h.actor}${h.note ? ` · ${h.note}` : ''}`}</li>
      ))}
    </ol>
  )
}

function PaymentOrdersTab({ deal }: { deal: TicketDeal | null }) {
  const { t, money } = useFmt()
  const [rows, setRows] = useState<z.infer<typeof paymentListSchema> | null>(null)
  const [state, setState] = useState<'loading' | 'ok' | 'not_available' | 'error'>('loading')
  useEffect(() => {
    if (!deal) return
    let cancelled = false
    void (async () => {
      const res = await wbGet(wbUrl('/payments', { dealId: deal.dealId }), paymentListSchema)
      if (cancelled) return
      if (res.ok) { setRows(res.data.filter(p => !p.dealIds || p.dealIds.includes(deal.dealId))); setState('ok') }
      else setState(res.kind === 'not_available' ? 'not_available' : 'error')
    })()
    return () => { cancelled = true }
  }, [deal])
  if (!deal) return <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Platební příkazy vznikají z uloženého obchodu.', 'Payment orders are generated from a saved deal.')}</p>
  if (state === 'not_available') return <NotAvailable what={t('Platební příkazy', 'Payment orders')} />
  if (state === 'error') return <p role="alert" style={{ fontSize: 12 }}>{t('Platební příkazy se nepodařilo načíst.', 'Payment orders could not be loaded.')}</p>
  if (!rows) return null
  if (rows.length === 0) return <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Obchod zatím nemá žádné platební příkazy.', 'The deal has no payment orders yet.')}</p>
  return (
    <ul style={{ fontSize: 13 }}>
      {rows.map(p => <li key={p.paymentId}>{`${p.valueDate} · ${p.direction} · ${money(p.amount)} ${p.currency} · ${p.status}`}</li>)}
    </ul>
  )
}

function CreditTab({ deal }: { deal: TicketDeal | null }) {
  const { t, money } = useFmt()
  const c = deal?.creditExposureCheck ?? null
  if (!deal) return <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Kontrola expozice se provede při předložení a schválení.', 'The exposure check runs at submit and at approval.')}</p>
  const limit = c?.counterparty ?? deal.limitCheck
  if (!limit && !c) return <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Kontrola expozice zatím neproběhla.', 'The exposure check has not run yet.')}</p>
  const row = (label: string, breached: boolean | null | undefined, detail: string) => (
    <tr><th scope="row" style={{ textAlign: 'left', padding: '4px 8px' }}>{label}</th>
      <td style={{ padding: '4px 8px' }}><StatusBadge status={breached ? 'BREACHED' : 'OK'} tone={breached ? 'danger' : 'success'} label={breached ? t('Překročen', 'Breached') : t('V limitu', 'Within limit')} /></td>
      <td style={{ padding: '4px 8px' }}>{detail}</td></tr>
  )
  return (
    <table style={{ fontSize: 13, borderCollapse: 'collapse' }}>
      <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Kontrola expozice', 'Credit-exposure check')}</caption>
      <tbody>
        {limit && row(t('Protistrana', 'Counterparty'), limit.breached, `${t('limit', 'limit')} ${money(limit.limit ?? null)} · ${t('po obchodu', 'after')} ${money(limit.exposureAfter ?? null)} · ${t('volné', 'headroom')} ${money(limit.headroomAfter ?? null)} ${limit.currency ?? ''}`)}
        {c?.product && row(t('Produkt', 'Product'), c.product.breached, (c.product.breaches ?? []).map(b => b.rule).join(', ') || '—')}
        {c?.country && row(t('Země', 'Country'), c.country.breached, `${t('limit', 'limit')} ${money(c.country.limit ?? null)} · ${t('po obchodu', 'after')} ${money(c.country.exposureAfter ?? null)}`)}
        {(c?.override ?? deal.limitOverride) && (
          <tr><th scope="row" style={{ textAlign: 'left', padding: '4px 8px' }}>{t('Přepsání', 'Override')}</th><td colSpan={2} style={{ padding: '4px 8px' }}>{`${(c?.override ?? deal.limitOverride)?.by} · ${(c?.override ?? deal.limitOverride)?.reason}`}</td></tr>
        )}
      </tbody>
    </table>
  )
}
