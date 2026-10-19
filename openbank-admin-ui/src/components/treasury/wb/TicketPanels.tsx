// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// The panels of the deal ticket (ADR-0344 catalogue): header, one panel per leg, and the
// product-specific panels. Controlled by the ticket form; every control is labelled and carries
// its own field-level error (client rule or the server's 422).
import { useId, type ReactNode } from 'react'
import {
  AMORTISATIONS, BDCS, CAP_APPLIES, CHANNELS, COLLATERALISATION, CURRENCY_OPTIONS, DAY_COUNTS, DOC_TYPES, enumLabel, FRA_SETTLEMENTS,
  FREQUENCIES, KINDS, LINK_RELATIONS, RATE_TYPES, ROLL_DIRECTIONS, STUBS, type Profile,
} from './catalogue'
import { fxCounterAmount, fxSpotDate } from '@/components/treasury/model'
import { bankToday } from '@/components/balance-sheet/model'
import { errorFor, type Errors, type LegForm, type TicketForm } from './ticket'
import { Field, gridStyle, Section, useFmt, useRefOptions } from './ui'

export type SetForm = <K extends keyof TicketForm>(key: K, value: TicketForm[K]) => void
type PanelProps = { form: TicketForm; set: SetForm; errors: Errors; disabled: boolean }

type Aria = { id?: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean }
const Sel = ({ value, onChange, options, disabled, label, empty, ...aria }: { value: string; onChange: (v: string) => void; options: readonly string[]; disabled?: boolean; label?: (v: string) => string; empty?: string } & Aria) => (
  <select className="input" value={value} disabled={disabled} onChange={e => onChange(e.target.value)} {...aria}>
    {empty !== undefined && <option value="">{empty}</option>}
    {options.map(o => <option key={o} value={o}>{label ? label(o) : o}</option>)}
  </select>
)

const Check = ({ checked, onChange, disabled, label }: { checked: boolean; onChange: (v: boolean) => void; disabled?: boolean; label: string }) => {
  const id = useId()
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, paddingTop: 18 }}>
      <input id={id} type="checkbox" checked={checked} disabled={disabled} onChange={e => onChange(e.target.checked)} />
      <label htmlFor={id}>{label}</label>
    </div>
  )
}

/** A text input that suggests active reference keys (a datalist) but never blocks free entry — a
 * deployment that does not serve the kind yet still lets the dealer type the code. */
export function RefInput({ kind, value, onChange, disabled, asOf, placeholder, ...aria }: { kind: string; value: string; onChange: (v: string) => void; disabled?: boolean; asOf?: string; placeholder?: string } & Aria) {
  const listId = useId()
  const { options } = useRefOptions(kind, asOf)
  return (
    <>
      <input className="input" list={options ? listId : undefined} value={value} disabled={disabled} placeholder={placeholder} onChange={e => onChange(e.target.value)} autoComplete="off" {...aria} />
      {options && <datalist id={listId}>{options.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}</datalist>}
    </>
  )
}

export type CounterpartyOption = { id: string; label: string }

export function HeaderPanel({ form, set, errors, disabled, profile, counterparties, facility }: PanelProps & { profile: Profile; counterparties: CounterpartyOption[]; facility: boolean }) {
  const { t } = useFmt()
  const err = (k: string) => errors[k] ?? null
  return (
    <Section title={t('Hlavička obchodu', 'Deal header')} id="ticket-header">
      <div style={gridStyle}>
        <Field label={t('Právní subjekt (zaúčtování)', 'Legal entity (booking entity)')}>
          <RefInput kind="party-group" value={form.legalEntityId} onChange={v => set('legalEntityId', v)} disabled={disabled} placeholder={t('výchozí: banka', 'default: the bank')} />
        </Field>
        <Field label={t('Protistrana', 'Counterparty')} error={err('counterpartyId')}>
          <select className="input" value={form.counterpartyId} disabled={disabled || facility} onChange={e => set('counterpartyId', e.target.value)}>
            {!facility && <option value="">{t('— vyberte —', '— select —')}</option>}
            {counterparties.map(c => <option key={c.id} value={c.id}>{c.label}</option>)}
          </select>
        </Field>
        <Field label={t('Číslo obchodu protistrany', 'Counterparty deal reference')}>
          <input className="input" value={form.counterpartyReference} disabled={disabled} onChange={e => set('counterpartyReference', e.target.value)} maxLength={64} />
        </Field>
        <Field label={t('Komunikační kanál', 'Communication channel')}>
          <Sel value={form.channel} onChange={v => set('channel', v)} options={CHANNELS} disabled={disabled} label={v => enumLabel(v, t)} />
        </Field>
        <Field label={t('Portfolio (kniha)', 'Portfolio (book)')} error={err('bookId')} hint={profile.needsBook ? undefined : t('Nepovinné u základních produktů.', 'Optional for the basic products.')}>
          <RefInput kind="book" value={form.bookId} onChange={v => set('bookId', v)} disabled={disabled} />
        </Field>
        <Field label={t('Druh obchodu', 'Deal kind')} error={err('kind')} hint={form.kind === 'HYPOTHETICAL' ? t('Jen předběžné ocenění — nelze předložit.', 'Pre-trade pricing only — cannot be submitted.') : form.kind === 'PLANNED' ? t('Vstupuje do prognózy likvidity, nezaúčtuje se.', 'Feeds the liquidity forecast; never posts.') : undefined}>
          <Sel value={form.kind} onChange={v => set('kind', v)} options={KINDS} disabled={disabled} label={v => enumLabel(v, t)} />
        </Field>
        <Field label={t('Datum a čas obchodu', 'Trade date and time')} error={err('tradeDateTime')}>
          <input className="input" type="datetime-local" value={form.tradeDateTime} disabled={disabled} onChange={e => set('tradeDateTime', e.target.value)} />
        </Field>
        <Field label={t('Datum valuty', 'Value date')} error={err('valueDate')} hint={form.product === 'FX_SPOT' ? t('Prázdné = T+2 obchodní dny; pozdější datum je forward a je odmítnut.', 'Empty = T+2 business days; a later date is a forward and is refused.') : t('Počátek (effective date) obchodu.', 'The effective (start) date of the deal.')}>
          <input className="input" type="date" value={form.valueDate} max={form.product === 'FX_SPOT' ? fxSpotDate(bankToday()) : undefined} disabled={disabled} onChange={e => set('valueDate', e.target.value)} />
        </Field>
        {!facility && form.product !== 'FX_SPOT' && (
          <Field label={t('Datum splatnosti', 'Maturity date')} error={err('maturityDate')}>
            <input className="input" type="date" value={form.maturityDate} min={form.valueDate} disabled={disabled} onChange={e => set('maturityDate', e.target.value)} />
          </Field>
        )}
        <Field label={t('Platnost od (rámcový obchod)', 'Valid from (framework deal)')} error={err('validFrom')}>
          <input className="input" type="date" value={form.validFrom} disabled={disabled} onChange={e => set('validFrom', e.target.value)} />
        </Field>
        <Field label={t('Platnost do', 'Valid to')} error={err('validTo')}>
          <input className="input" type="date" value={form.validTo} disabled={disabled} onChange={e => set('validTo', e.target.value)} />
        </Field>
        <Field label={t('Jurisdikce / rozhodné právo', 'Jurisdiction / governing law')} error={err('jurisdiction')}>
          <RefInput kind="country" value={form.jurisdiction} onChange={v => set('jurisdiction', v)} disabled={disabled} />
        </Field>
        <Field label={t('Kalendáře svátků (čárkou)', 'Holiday calendars (comma separated)')} error={err('calendars')}>
          <RefInput kind="calendar" value={form.calendars} onChange={v => set('calendars', v)} disabled={disabled} />
        </Field>
        <Field label={t('Konvence pracovních dnů', 'Business-day convention')}>
          <Sel value={form.businessDayConvention} onChange={v => set('businessDayConvention', v)} options={BDCS} disabled={disabled} />
        </Field>
        <Field label={t('Směr generování harmonogramu', 'Schedule generation direction')}>
          <Sel value={form.rollDirection} onChange={v => set('rollDirection', v)} options={ROLL_DIRECTIONS} disabled={disabled} label={v => enumLabel(v, t)} />
        </Field>
        <Check checked={form.fixedConvention} onChange={v => set('fixedConvention', v)} disabled={disabled} label={t('Pevná konvence (zmrazena při obchodu)', 'Fixed convention (frozen at trade)')} />
        <Check checked={form.notionalExchange} onChange={v => set('notionalExchange', v)} disabled={disabled} label={t('Výměna jistiny', 'Notional exchange')} />
        <Check checked={form.singleCurrency} onChange={v => set('singleCurrency', v)} disabled={disabled} label={t('Jedna měna', 'Single currency')} />
      </div>
      <div style={{ ...gridStyle, marginTop: 12 }}>
        <Field label={t('Poznámka', 'Note')}><input className="input" value={form.note} disabled={disabled} onChange={e => set('note', e.target.value)} /></Field>
        <Field label={t('Zdůvodnění', 'Rationale')}><input className="input" value={form.rationale} disabled={disabled} onChange={e => set('rationale', e.target.value)} /></Field>
      </div>
    </Section>
  )
}

/** The flat terms the five original products have always been booked with. */
export function LegacyTermsPanel({ form, set, errors, disabled }: PanelProps) {
  const { t } = useFmt()
  const err = (k: string) => errors[k] ?? null
  const fx = form.product === 'FX_SPOT'
  const facility = form.product === 'CNB_DEPOSIT_FACILITY' || form.product === 'CNB_LOMBARD'
  const lombard = form.product === 'CNB_LOMBARD'
  return (
    <Section title={t('Podmínky obchodu', 'Deal terms')} id="ticket-terms">
      <div style={gridStyle}>
        {!fx && (
          <Field label={t('Měna', 'Currency')} error={err('currency')}>
            <select className="input" value={form.currency} disabled={disabled || facility} onChange={e => set('currency', e.target.value)}>
              {['CZK', 'EUR'].filter(c => !facility || c === 'CZK').map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </Field>
        )}
        {fx && (
          <>
            <Field label={t('Kupovaná měna', 'Buy currency')} error={err('buyCurrency')}>
              <Sel value={form.buyCurrency} onChange={v => set('buyCurrency', v)} options={['CZK', 'EUR']} disabled={disabled} />
            </Field>
            <Field label={t('Prodávaná měna', 'Sell currency')}>
              <Sel value={form.sellCurrency} onChange={v => set('sellCurrency', v)} options={['CZK', 'EUR']} disabled={disabled} />
            </Field>
          </>
        )}
        <Field label={fx ? t('Cizoměnová částka', 'Foreign amount') : t('Jistina', 'Principal')} error={err('principal')}>
          <input className="input" type="number" min="0" step="0.01" inputMode="decimal" value={form.principal} disabled={disabled} onChange={e => set('principal', e.target.value)} />
        </Field>
        <Field label={fx ? t('Kurz, CZK za jednotku cizí měny', 'Rate, CZK per unit of foreign currency') : lombard ? t('Lombardní sazba ČNB (%)', 'CNB lombard rate (%)') : t('Roční sazba v procentech (ACT/360)', 'Annual rate in percent (ACT/360)')} error={err('rate')}>
          <input className="input" type="number" min={lombard ? '0.0001' : '0'} step="0.0001" inputMode="decimal" value={form.rate} disabled={disabled} onChange={e => set('rate', e.target.value)} />
        </Field>
      </div>
      {fx && <FxSpotPreview form={form} errors={errors} />}
    </Section>
  )
}

function FxSpotPreview({ form, errors }: { form: TicketForm; errors: Errors }) {
  const { t, money } = useFmt()
  const spot = fxSpotDate(bankToday())
  const counter = fxCounterAmount(Number(form.principal), Number(form.rate))
  const pairOk = form.buyCurrency !== form.sellCurrency && (form.buyCurrency === 'CZK') !== (form.sellCurrency === 'CZK')
  void errors
  return (
    <div style={{ marginTop: 12, fontSize: 13 }} data-testid="fx-preview">
      {!pairOk && <div role="alert" style={{ color: 'var(--danger-text)' }}>{t('Právě jedna z měn musí být CZK.', 'Exactly one of the two currencies must be CZK.')}</div>}
      {pairOk && counter !== null && (
        <p>{t('Náhled protihodnoty v CZK (nezaúčtováno)', 'Preview counter amount in CZK (not booked)')}{`: ${money(counter)} CZK`}</p>
      )}
      {form.valueDate !== '' && form.valueDate > spot && (
        <div role="alert" style={{ color: 'var(--danger-text)' }}>
          {t(`Datum valuty smí být nejpozději ${spot} (T+2 obchodní dny); pozdější datum je forward.`, `The value date may be at most ${spot} (T+2 business days); a later date is a forward.`)}
        </div>
      )}
    </div>
  )
}

export function LegPanel({ leg, index, set, errors, disabled, title }: { leg: LegForm; index: number; set: (patch: Partial<LegForm>) => void; errors: Errors; disabled: boolean; title: string }) {
  const { t } = useFmt()
  const e = (f: string) => errorFor(errors, `legs.${index}.${f}`)
  const floating = leg.rateType === 'FLOATING'
  const p = (key: keyof LegForm) => (v: string) => set({ [key]: v } as Partial<LegForm>)
  return (
    <Section title={title} id={`ticket-leg-${index}`}>
      <div style={gridStyle}>
        <Field label={t('Strana nohy', 'Leg side')} error={e('payReceive')}>
          <Sel value={leg.payReceive} onChange={v => set({ payReceive: v as LegForm['payReceive'] })} options={['PAY', 'RECEIVE']} disabled={disabled} label={v => enumLabel(v, t)} />
        </Field>
        <Field label={t('Měna nohy', 'Leg currency')} error={e('currency')}>
          <Sel value={leg.currency} onChange={p('currency')} options={CURRENCY_OPTIONS} disabled={disabled} />
        </Field>
        <Field label={t('Jistina nohy', 'Leg notional')} error={e('notional')}>
          <input className="input" inputMode="decimal" value={leg.notional} disabled={disabled} onChange={ev => set({ notional: ev.target.value })} />
        </Field>
        <Field label={t('Amortizace', 'Amortisation')}>
          <Sel value={leg.amortisation} onChange={p('amortisation')} options={AMORTISATIONS} disabled={disabled} label={v => enumLabel(v, t)} />
        </Field>
        <Field label={t('Začátek amortizace', 'Amortisation start')} error={e('amortisationStart')}>
          <input className="input" type="date" value={leg.amortisationStart} disabled={disabled} onChange={ev => set({ amortisationStart: ev.target.value })} />
        </Field>
        {leg.amortisation === 'CUSTOM' && (
          <Field label={t('Splátkový kalendář (RRRR-MM-DD;částka)', 'Repayment schedule (YYYY-MM-DD;amount)')} error={e('amortisationLines')}>
            <textarea className="input" rows={3} value={leg.amortisationLines} disabled={disabled} onChange={ev => set({ amortisationLines: ev.target.value })} />
          </Field>
        )}
        <Field label={t('Začátek akruálu', 'Accrual start')} error={e('accrualStart')}>
          <input className="input" type="date" value={leg.accrualStart} disabled={disabled} onChange={ev => set({ accrualStart: ev.target.value })} />
        </Field>
        <Field label={t('Konec akruálu', 'Accrual end')} error={e('accrualEnd')}>
          <input className="input" type="date" value={leg.accrualEnd} disabled={disabled} onChange={ev => set({ accrualEnd: ev.target.value })} />
        </Field>
        <Field label={t('Frekvence akruálu', 'Accrual frequency')}>
          <Sel value={leg.accrualFrequency} onChange={p('accrualFrequency')} options={FREQUENCIES} disabled={disabled} label={v => enumLabel(v, t)} />
        </Field>
        <Field label={t('Frekvence plateb', 'Payment frequency')}>
          <Sel value={leg.paymentFrequency} onChange={p('paymentFrequency')} options={FREQUENCIES} disabled={disabled} label={v => enumLabel(v, t)} />
        </Field>
        <Field label={t('Zkrácené / prodloužené období', 'Stub')}>
          <Sel value={leg.stub} onChange={p('stub')} options={STUBS} disabled={disabled} label={v => enumLabel(v, t)} />
        </Field>
        <Field label={t('Konvence dní (akruál)', 'Day count (accrual)')}>
          <Sel value={leg.dayCount} onChange={p('dayCount')} options={DAY_COUNTS} disabled={disabled} />
        </Field>
        <Field label={t('Odklad platby (pracovní dny)', 'Payment lag (business days)')} error={e('paymentLagBusinessDays')}>
          <input className="input" inputMode="numeric" value={leg.paymentLagBusinessDays} disabled={disabled} onChange={ev => set({ paymentLagBusinessDays: ev.target.value })} />
        </Field>
        <Field label={t('Platební kalendáře (čárkou)', 'Payment calendars (comma separated)')} error={e('paymentCalendars')}>
          <RefInput kind="calendar" value={leg.paymentCalendars} onChange={p('paymentCalendars')} disabled={disabled} />
        </Field>
        <Check checked={leg.adjustPaymentDate} onChange={v => set({ adjustPaymentDate: v })} disabled={disabled} label={t('Upravit datum platby', 'Adjust payment date')} />
        <Check checked={leg.adjustAccrualPeriod} onChange={v => set({ adjustAccrualPeriod: v })} disabled={disabled} label={t('Upravit akruální období', 'Adjust accrual period')} />
        <Check checked={leg.initialNotionalExchange} onChange={v => set({ initialNotionalExchange: v })} disabled={disabled} label={t('Počáteční výměna jistiny', 'Initial notional exchange')} />
        <Check checked={leg.finalNotionalExchange} onChange={v => set({ finalNotionalExchange: v })} disabled={disabled} label={t('Závěrečná výměna jistiny', 'Final notional exchange')} />
        <Field label={t('Typ sazby', 'Rate type')}>
          <Sel value={leg.rateType} onChange={v => set({ rateType: v as LegForm['rateType'] })} options={RATE_TYPES} disabled={disabled} label={v => enumLabel(v, t)} />
        </Field>
        {leg.rateType === 'FIXED' && (
          <Field label={t('Pevná sazba % p.a.', 'Fixed rate % p.a.')} error={e('rate')}>
            <input className="input" inputMode="decimal" value={leg.rate} disabled={disabled} onChange={ev => set({ rate: ev.target.value })} />
          </Field>
        )}
        {floating && (
          <>
            <Field label={t('Referenční index', 'Reference index')} error={e('index')}>
              <RefInput kind="index" value={leg.index} onChange={p('index')} disabled={disabled} />
            </Field>
            <Field label={t('Marže % p.a.', 'Spread % p.a.')} error={e('spread')}>
              <input className="input" inputMode="decimal" value={leg.spread} disabled={disabled} onChange={ev => set({ spread: ev.target.value })} />
            </Field>
            <Field label={t('Frekvence refixace', 'Refixing frequency')}>
              <Sel value={leg.resetFrequency} onChange={p('resetFrequency')} options={FREQUENCIES} disabled={disabled} label={v => enumLabel(v, t)} />
            </Field>
            <Field label={t('Předstih fixingu (pracovní dny)', 'Fixing lead (business days)')} error={e('fixingLagBusinessDays')}>
              <input className="input" inputMode="numeric" value={leg.fixingLagBusinessDays} disabled={disabled} onChange={ev => set({ fixingLagBusinessDays: ev.target.value })} />
            </Field>
            <Field label={t('Celková sazba do prvního fixingu %', 'Total rate until first fixing %')} error={e('rateUntilFirstFixing')}>
              <input className="input" inputMode="decimal" value={leg.rateUntilFirstFixing} disabled={disabled} onChange={ev => set({ rateUntilFirstFixing: ev.target.value })} />
            </Field>
            <Field label={t('Strop (cap) %', 'Cap %')} error={e('cap')}>
              <input className="input" inputMode="decimal" value={leg.cap} disabled={disabled} onChange={ev => set({ cap: ev.target.value })} />
            </Field>
            <Field label={t('Spodní hranice (floor) %', 'Floor %')} error={e('floor')}>
              <input className="input" inputMode="decimal" value={leg.floor} disabled={disabled} onChange={ev => set({ floor: ev.target.value })} />
            </Field>
            <Field label={t('Cap/floor se vztahuje na', 'Cap/floor applies to')}>
              <Sel value={leg.capFloorAppliesTo} onChange={p('capFloorAppliesTo')} options={CAP_APPLIES} disabled={disabled} label={v => enumLabel(v, t)} />
            </Field>
          </>
        )}
      </div>
    </Section>
  )
}

export function SecuritiesPanel({ form, set, errors, disabled }: PanelProps) {
  const { t } = useFmt()
  const err = (k: string) => errors[k] ?? null
  return (
    <Section title={t('Cenný papír', 'Security')} id="ticket-securities">
      <div style={gridStyle}>
        <Field label={t('Cenný papír (ISIN)', 'Security (ISIN)')} error={err('securityIsin')}>
          <RefInput kind="security" value={form.securityIsin} onChange={v => set('securityIsin', v)} disabled={disabled} />
        </Field>
        <Field label={t('Nominál', 'Nominal')} error={err('nominal')}>
          <input className="input" inputMode="decimal" value={form.nominal} disabled={disabled} onChange={e => set('nominal', e.target.value)} />
        </Field>
        <Field label={t('Zadat jako', 'Quote as')}>
          <Sel value={form.priceMode} onChange={v => set('priceMode', v as TicketForm['priceMode'])} options={['CLEAN_PRICE', 'YIELD']} disabled={disabled}
            label={v => (v === 'YIELD' ? t('Výnos', 'Yield') : t('Čistá cena', 'Clean price'))} />
        </Field>
        <Field label={form.priceMode === 'YIELD' ? t('Výnos % p.a.', 'Yield % p.a.') : t('Čistá cena %', 'Clean price %')} error={err('priceValue')}>
          <input className="input" inputMode="decimal" value={form.priceValue} disabled={disabled} onChange={e => set('priceValue', e.target.value)} />
        </Field>
        <Field label={t('Naběhlý úrok', 'Accrued interest')}>
          <input className="input" inputMode="decimal" value={form.accruedInterest} disabled={disabled} onChange={e => set('accruedInterest', e.target.value)} />
        </Field>
        <Field label={t('Místo vypořádání', 'Settlement place')}>
          <RefInput kind="settlement-place" value={form.settlementPlace} onChange={v => set('settlementPlace', v)} disabled={disabled} />
        </Field>
        <Field label={t('Účet cenných papírů (úschova)', 'Custody (securities) account')}>
          <RefInput kind="account" value={form.custodyAccountId} onChange={v => set('custodyAccountId', v)} disabled={disabled} />
        </Field>
      </div>
      <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>
        {t('Kupóny a splatnost se odvodí z kmenových dat cenného papíru; harmonogram si zobrazíte tlačítkem „Vygenerovat harmonogram“.', 'Coupons and maturity are derived from the security master; use “Generate schedule” to see them.')}
      </p>
    </Section>
  )
}

export function FxPanel({ form, set, errors, disabled }: PanelProps) {
  const { t } = useFmt()
  const err = (k: string) => errors[k] ?? null
  const swap = form.product === 'FX_SWAP'
  return (
    <Section title={swap ? t('FX swap', 'FX swap') : t('FX forward', 'FX forward')} id="ticket-fx">
      <div style={gridStyle}>
        <Field label={t('Kupovaná měna', 'Buy currency')} error={err('buyCurrency')}><Sel value={form.buyCurrency} onChange={v => set('buyCurrency', v)} options={CURRENCY_OPTIONS} disabled={disabled} /></Field>
        <Field label={t('Kupovaná částka', 'Buy amount')} error={err('fxBuyAmount')}><input className="input" inputMode="decimal" value={form.fxBuyAmount} disabled={disabled} onChange={e => set('fxBuyAmount', e.target.value)} /></Field>
        <Field label={t('Prodávaná měna', 'Sell currency')}><Sel value={form.sellCurrency} onChange={v => set('sellCurrency', v)} options={CURRENCY_OPTIONS} disabled={disabled} /></Field>
        <Field label={t('Prodávaná částka', 'Sell amount')} error={err('fxSellAmount')}><input className="input" inputMode="decimal" value={form.fxSellAmount} disabled={disabled} onChange={e => set('fxSellAmount', e.target.value)} /></Field>
        <Field label={t('Kurz', 'Rate')} error={err('fxRate')}><input className="input" inputMode="decimal" value={form.fxRate} disabled={disabled} onChange={e => set('fxRate', e.target.value)} /></Field>
        <Field label={t('Forwardové body', 'Forward points')} error={err('forwardPoints')}><input className="input" inputMode="decimal" value={form.forwardPoints} disabled={disabled} onChange={e => set('forwardPoints', e.target.value)} /></Field>
        {swap && (
          <>
            <Field label={t('Datum blízké nohy', 'Near-leg date')} error={err('nearDate')}><input className="input" type="date" value={form.nearDate} disabled={disabled} onChange={e => set('nearDate', e.target.value)} /></Field>
            <Field label={t('Kurz blízké nohy', 'Near-leg rate')} error={err('nearRate')}><input className="input" inputMode="decimal" value={form.nearRate} disabled={disabled} onChange={e => set('nearRate', e.target.value)} /></Field>
          </>
        )}
        <Field label={swap ? t('Datum vzdálené nohy', 'Far-leg date') : t('Datum vypořádání forwardu', 'Forward settlement date')} error={err('farDate')}>
          <input className="input" type="date" value={form.farDate} disabled={disabled} onChange={e => set('farDate', e.target.value)} />
        </Field>
        {swap && <Field label={t('Kurz vzdálené nohy', 'Far-leg rate')} error={err('farRate')}><input className="input" inputMode="decimal" value={form.farRate} disabled={disabled} onChange={e => set('farRate', e.target.value)} /></Field>}
      </div>
    </Section>
  )
}

export function FraPanel({ form, set, errors, disabled }: PanelProps) {
  const { t } = useFmt()
  return (
    <Section title={t('FRA', 'FRA')} id="ticket-fra">
      <div style={gridStyle}>
        <Field label={t('Období kontraktu (např. 3x6)', 'Contract period (e.g. 3x6)')} error={errors.fraPeriod ?? null}>
          <input className="input" value={form.fraPeriod} disabled={disabled} onChange={e => set('fraPeriod', e.target.value)} placeholder="3x6" />
        </Field>
        <Field label={t('Konvence vypořádání', 'Settlement convention')}>
          <Sel value={form.fraSettlement} onChange={v => set('fraSettlement', v)} options={FRA_SETTLEMENTS} disabled={disabled} label={v => enumLabel(v, t)} />
        </Field>
      </div>
      <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>
        {t('Referenční index a datum fixingu zadejte na noze níže.', 'Enter the reference index and fixing lead on the leg below.')}
      </p>
    </Section>
  )
}

export function DepositPanel({ form, set, errors, disabled }: PanelProps) {
  const { t } = useFmt()
  return (
    <Section title={form.product === 'LOAN' ? t('Úvěr', 'Loan') : t('Hotovost / vklad', 'Cash / deposit')} id="ticket-deposit">
      <div style={gridStyle}>
        <Field label={t('Účet jistiny', 'Principal account')}><RefInput kind="account" value={form.principalAccountId} onChange={v => set('principalAccountId', v)} disabled={disabled} /></Field>
        <Field label={t('Účet úroků', 'Interest account')}><RefInput kind="account" value={form.interestAccountId} onChange={v => set('interestAccountId', v)} disabled={disabled} /></Field>
        <label style={{ fontSize: 12, alignSelf: 'end' }}>
          <input type="checkbox" checked={form.earlyWithdrawalAllowed} disabled={disabled} onChange={e => set('earlyWithdrawalAllowed', e.target.checked)} /> {t('Předčasný výběr povolen', 'Early withdrawal allowed')}
        </label>
        <Field label={t('Výpovědní lhůta (dny)', 'Notice (days)')} error={errors.earlyWithdrawalNoticeDays ?? null}><input className="input" inputMode="numeric" value={form.earlyWithdrawalNoticeDays} disabled={disabled || !form.earlyWithdrawalAllowed} onChange={e => set('earlyWithdrawalNoticeDays', e.target.value)} /></Field>
        <Field label={t('Sankce (bp)', 'Penalty (bp)')} error={errors.earlyWithdrawalPenaltyBp ?? null}><input className="input" inputMode="numeric" value={form.earlyWithdrawalPenaltyBp} disabled={disabled || !form.earlyWithdrawalAllowed} onChange={e => set('earlyWithdrawalPenaltyBp', e.target.value)} /></Field>
        <Field label={t('Poznámka k předčasnému výběru', 'Early-withdrawal note')}><input className="input" value={form.earlyWithdrawalNote} disabled={disabled} onChange={e => set('earlyWithdrawalNote', e.target.value)} /></Field>
      </div>
    </Section>
  )
}

// ── tab bodies ──────────────────────────────────────────────────────────────────────────────────
export function AccountsTab({ form, set, disabled }: PanelProps) {
  const { t } = useFmt()
  return (
    <div style={gridStyle}>
      <Field label={t('Peněžní účet vypořádání', 'Cash settlement account')}><RefInput kind="account" value={form.cashAccountId} onChange={v => set('cashAccountId', v)} disabled={disabled} /></Field>
      <Field label={t('Účet cenných papírů', 'Securities account')}><RefInput kind="account" value={form.securitiesAccountId} onChange={v => set('securitiesAccountId', v)} disabled={disabled} /></Field>
    </div>
  )
}

function RowsHeader({ title, onAdd, addLabel, disabled }: { title: string; onAdd: () => void; addLabel: string; disabled: boolean }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
      <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{title}</span>
      <button type="button" className="btn btn-secondary btn-sm" disabled={disabled} onClick={onAdd}>{addLabel}</button>
    </div>
  )
}

export function DocumentsTab({ form, set, errors, disabled }: PanelProps) {
  const { t } = useFmt()
  const update = (i: number, patch: Partial<TicketForm['documents'][number]>) => set('documents', form.documents.map((d, k) => (k === i ? { ...d, ...patch } : d)))
  return (
    <div>
      <RowsHeader title={t('Konfirmace, term sheety a podepsané skeny (ID z úložiště dokumentů).', 'Confirmations, term sheets and signed scans (ids from the document store).')} onAdd={() => set('documents', [...form.documents, { documentId: '', type: 'CONFIRMATION' }])} addLabel={t('Přidat dokument', 'Add document')} disabled={disabled} />
      {form.documents.map((d, i) => (
        <div key={i} style={{ ...gridStyle, marginBottom: 8, alignItems: 'end' }}>
          <Field label={t(`Typ dokumentu ${i + 1}`, `Document ${i + 1} type`)}><Sel value={d.type} onChange={v => update(i, { type: v })} options={DOC_TYPES} disabled={disabled} label={v => enumLabel(v, t)} /></Field>
          <Field label={t(`ID dokumentu ${i + 1}`, `Document ${i + 1} id`)} error={errors[`documents.${i}.documentId`] ?? null}><input className="input" value={d.documentId} disabled={disabled} onChange={e => update(i, { documentId: e.target.value })} /></Field>
          <button type="button" className="btn btn-secondary btn-sm" disabled={disabled} onClick={() => set('documents', form.documents.filter((_, k) => k !== i))}>{t('Odebrat', 'Remove')}</button>
        </div>
      ))}
      {form.documents.length === 0 && <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Žádné dokumenty.', 'No documents.')}</p>}
    </div>
  )
}

export function CollateralTab({ form, set, errors, disabled, relevant }: PanelProps & { relevant: boolean }) {
  const { t } = useFmt()
  const err = (k: string) => errors[k] ?? null
  if (!relevant) return <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Zajištění se eviduje u repo a reverzního repa.', 'Collateral is recorded on repo and reverse repo.')}</p>
  return (
    <div style={gridStyle}>
      <Field label={t('Zajišťovací cenný papír (ISIN)', 'Collateral security (ISIN)')} error={err('collIsin')}><RefInput kind="security" value={form.collIsin} onChange={v => set('collIsin', v)} disabled={disabled} /></Field>
      <Field label={t('Nominál zajištění', 'Collateral nominal')} error={err('collNominal')}><input className="input" inputMode="decimal" value={form.collNominal} disabled={disabled} onChange={e => set('collNominal', e.target.value)} /></Field>
      <Field label={t('Špinavá cena (dirty) %', 'Dirty price %')} error={err('collDirtyPrice')}><input className="input" inputMode="decimal" value={form.collDirtyPrice} disabled={disabled} onChange={e => set('collDirtyPrice', e.target.value)} /></Field>
      <Field label={t('Haircut %', 'Haircut %')} error={err('collHaircut')}><input className="input" inputMode="decimal" value={form.collHaircut} disabled={disabled} onChange={e => set('collHaircut', e.target.value)} /></Field>
      <Field label={t('Práh margin callu', 'Margin-call threshold')} error={err('collMarginCall')}><input className="input" inputMode="decimal" value={form.collMarginCall} disabled={disabled} onChange={e => set('collMarginCall', e.target.value)} /></Field>
      <Check checked={form.collSubstitution} onChange={v => set('collSubstitution', v)} disabled={disabled} label={t('Právo na výměnu zajištění', 'Substitution rights')} />
    </div>
  )
}

export function LinkedDealsTab({ form, set, errors, disabled }: PanelProps) {
  const { t } = useFmt()
  const update = (i: number, patch: Partial<TicketForm['linkedDeals'][number]>) => set('linkedDeals', form.linkedDeals.map((d, k) => (k === i ? { ...d, ...patch } : d)))
  return (
    <div>
      <Field label={t('Původní obchod (dodatek / prodloužení)', 'Original deal (amendment / roll-over)')} error={errors.originalDealId ?? null}>
        <input className="input" value={form.originalDealId} disabled={disabled} onChange={e => set('originalDealId', e.target.value)} />
      </Field>
      <RowsHeader title={t('Zajištění, prodloužení a netting sety.', 'Hedge relationships, roll-overs and netting sets.')} onAdd={() => set('linkedDeals', [...form.linkedDeals, { dealId: '', relation: 'HEDGE' }])} addLabel={t('Přidat vazbu', 'Add link')} disabled={disabled} />
      {form.linkedDeals.map((d, i) => (
        <div key={i} style={{ ...gridStyle, marginBottom: 8, alignItems: 'end' }}>
          <Field label={t(`Vztah ${i + 1}`, `Relation ${i + 1}`)}><Sel value={d.relation} onChange={v => update(i, { relation: v })} options={LINK_RELATIONS} disabled={disabled} label={v => enumLabel(v, t)} /></Field>
          <Field label={t(`Propojený obchod ${i + 1} (ID)`, `Linked deal ${i + 1} (id)`)} error={errors[`linkedDeals.${i}.dealId`] ?? null}><input className="input" value={d.dealId} disabled={disabled} onChange={e => update(i, { dealId: e.target.value })} /></Field>
          <button type="button" className="btn btn-secondary btn-sm" disabled={disabled} onClick={() => set('linkedDeals', form.linkedDeals.filter((_, k) => k !== i))}>{t('Odebrat', 'Remove')}</button>
        </div>
      ))}
    </div>
  )
}

export function FeesTab({ form, set, errors, disabled }: PanelProps) {
  const { t } = useFmt()
  const update = (i: number, patch: Partial<TicketForm['fees'][number]>) => set('fees', form.fees.map((d, k) => (k === i ? { ...d, ...patch } : d)))
  return (
    <div>
      <RowsHeader title={t('Poplatky obchodu s datem platby.', 'Deal fees with their payment date.')} onAdd={() => set('fees', [...form.fees, { type: '', amount: '', currency: 'CZK', paymentDate: '' }])} addLabel={t('Přidat poplatek', 'Add fee')} disabled={disabled} />
      {form.fees.map((f, i) => (
        <div key={i} style={{ ...gridStyle, marginBottom: 8, alignItems: 'end' }}>
          <Field label={t(`Typ poplatku ${i + 1}`, `Fee ${i + 1} type`)} error={errors[`fees.${i}.type`] ?? null}><input className="input" value={f.type} disabled={disabled} onChange={e => update(i, { type: e.target.value })} /></Field>
          <Field label={t(`Částka poplatku ${i + 1}`, `Fee ${i + 1} amount`)} error={errors[`fees.${i}.amount`] ?? null}><input className="input" inputMode="decimal" value={f.amount} disabled={disabled} onChange={e => update(i, { amount: e.target.value })} /></Field>
          <Field label={t(`Měna poplatku ${i + 1}`, `Fee ${i + 1} currency`)}><Sel value={f.currency} onChange={v => update(i, { currency: v })} options={CURRENCY_OPTIONS} disabled={disabled} /></Field>
          <Field label={t(`Datum platby poplatku ${i + 1}`, `Fee ${i + 1} payment date`)} error={errors[`fees.${i}.paymentDate`] ?? null}><input className="input" type="date" value={f.paymentDate} disabled={disabled} onChange={e => update(i, { paymentDate: e.target.value })} /></Field>
          <button type="button" className="btn btn-secondary btn-sm" disabled={disabled} onClick={() => set('fees', form.fees.filter((_, k) => k !== i))}>{t('Odebrat', 'Remove')}</button>
        </div>
      ))}
    </div>
  )
}

export function CentralBankTab({ form, set, errors, disabled }: PanelProps) {
  const { t } = useFmt()
  return (
    <div style={gridStyle}>
      <Field label={t('Kódy ČNB / klasifikace (čárkou)', 'Central-bank / classification codes (comma separated)')}>
        <RefInput kind="regulatory-code" value={form.centralBankCodes} onChange={v => set('centralBankCodes', v)} disabled={disabled} />
      </Field>
      <Field label="UTI" error={errors.uti ?? null}><input className="input" value={form.uti} disabled={disabled} onChange={e => set('uti', e.target.value)} /></Field>
      <Field label={t('LEI protistrany', 'Counterparty LEI')} error={errors.counterpartyLei ?? null}><input className="input" value={form.counterpartyLei} disabled={disabled} onChange={e => set('counterpartyLei', e.target.value)} /></Field>
      <Field label={t('Povinnost clearingu', 'Clearing obligation')}>
        <Sel value={form.clearingObligation} onChange={v => set('clearingObligation', v as TicketForm['clearingObligation'])} options={['true', 'false']} disabled={disabled} empty={t('— neuvedeno —', '— not stated —')} label={v => (v === 'true' ? t('Ano', 'Yes') : t('Ne', 'No'))} />
      </Field>
      <Field label={t('Kolateralizace', 'Collateralisation')}>
        <Sel value={form.collateralisation} onChange={v => set('collateralisation', v)} options={COLLATERALISATION} disabled={disabled} empty={t('— neuvedeno —', '— not stated —')} label={v => enumLabel(v, t)} />
      </Field>
    </div>
  )
}

export function LiquidityTab({ form, set, disabled }: PanelProps) {
  const { t } = useFmt()
  return (
    <div style={gridStyle}>
      <Field label={t('Kategorie LCR', 'LCR category')}><RefInput kind="regulatory-code" value={form.lcrCategory} onChange={v => set('lcrCategory', v)} disabled={disabled} /></Field>
      <Field label={t('Kategorie NSFR', 'NSFR category')}><RefInput kind="regulatory-code" value={form.nsfrCategory} onChange={v => set('nsfrCategory', v)} disabled={disabled} /></Field>
    </div>
  )
}

export type { ReactNode }
