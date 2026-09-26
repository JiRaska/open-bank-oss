// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// New treasury deal (dealer only, ADR-0315). Creates a DRAFT — nothing posts — then offers Submit,
// which sends it to a DIFFERENT person for approval. The counterparty's limit headroom in the
// chosen currency is shown up front; exceeding it on an asset product is warned about here, but
// the control is the server's limit check at submit and approve (LIMIT_BREACHED, 422).

'use client'

import { useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { FilePlus } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatusBadge } from '@/components/ui'
import { dealActionUrl, getJson, postJson, treasuryUrl } from '@/components/treasury/api'
import {
  CNB_COUNTERPARTY_ID, counterpartyListSchema, CURRENCIES, dealSchema, PRODUCTS,
  type Counterparty, type Deal, type Product,
} from '@/components/treasury/contracts'
import {
  distinctCounterparties, eligibleCounterparties, exceedsHeadroom, headroomFor, isAssetProduct,
  productLabel, refusalText, STATE_TONE, stateLabel,
} from '@/components/treasury/model'
import { SyntheticBadge } from '@/components/treasury/SyntheticBadge'
import { bankToday, isIsoDate } from '@/components/balance-sheet/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function NewTreasuryDealPage() {
  return (
    <AuthGuard permission="treasury:deal:create">
      <NewDeal />
    </AuthGuard>
  )
}

type Notice = { tone: 'success' | 'danger'; text: string }

function NewDeal() {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  const [rows, setRows] = useState<Counterparty[] | null>(null)
  const [cpKind, setCpKind] = useState<UnavailableKind | null>(null)
  const [product, setProduct] = useState<Product>('MM_PLACEMENT')
  const [counterpartyId, setCounterpartyId] = useState('')
  const [currency, setCurrency] = useState<string>('CZK')
  const [principal, setPrincipal] = useState('')
  const [rate, setRate] = useState('')
  const [valueDate, setValueDate] = useState(bankToday())
  const [maturityDate, setMaturityDate] = useState('')
  const [rationale, setRationale] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [draft, setDraft] = useState<Deal | null>(null)

  useEffect(() => {
    void (async () => {
      const res = await getJson(treasuryUrl('/counterparties'), counterpartyListSchema)
      if (res.ok) { setRows(res.data); setCpKind(null) } else setCpKind(res.kind)
    })()
  }, [])

  const facility = product === 'CNB_DEPOSIT_FACILITY'
  const options = useMemo(() => (rows ? distinctCounterparties(eligibleCounterparties(rows, product)) : []), [rows, product])

  // Keep the form consistent with the product's rules (Deal.kt): the facility is CZK against ČNB,
  // always overnight; interbank products never face the central bank.
  useEffect(() => {
    if (facility) { setCurrency('CZK'); setCounterpartyId(CNB_COUNTERPARTY_ID); setMaturityDate('') }
    else setCounterpartyId(prev => (prev === CNB_COUNTERPARTY_ID ? '' : prev))
  }, [facility])

  const principalValue = Number(principal)
  const rateValue = Number(rate)
  const headroomRow = rows && counterpartyId ? headroomFor(rows, counterpartyId, currency) : null
  const overHeadroom = exceedsHeadroom(product, principalValue, headroomRow)
  const selected = options.find(c => c.counterpartyId === counterpartyId)

  const valid = counterpartyId !== '' && principal !== '' && Number.isFinite(principalValue) && principalValue > 0
    && rate !== '' && Number.isFinite(rateValue) && rateValue >= 0 && isIsoDate(valueDate)
    && (facility || maturityDate === '' || (isIsoDate(maturityDate) && maturityDate > valueDate))

  const create = async () => {
    if (!valid) return
    setBusy(true)
    setNotice(null)
    const body = {
      product, counterpartyId, currency, principal: principalValue, rate: rateValue, valueDate,
      ...(!facility && maturityDate ? { maturityDate } : {}),
      ...(rationale.trim() ? { rationale: rationale.trim() } : {}),
    }
    const res = await postJson(treasuryUrl('/deals'), body, dealSchema)
    setBusy(false)
    if (res.ok) {
      setDraft(res.data)
      setNotice({ tone: 'success', text: t('Koncept obchodu vytvořen. Nic se nezaúčtovalo; předložte jej ke schválení.', 'Draft deal created. Nothing has posted; submit it for approval.') })
    } else {
      setNotice({ tone: 'danger', text: refusalText(res, t('Vytvoření', 'Create'), t) })
    }
  }

  const submit = async () => {
    if (!draft) return
    setBusy(true)
    const res = await postJson(dealActionUrl(draft.dealId, 'submit'), undefined, dealSchema)
    setBusy(false)
    if (res.ok) {
      setDraft(res.data)
      setNotice({ tone: 'success', text: t('Obchod předložen. Schválit jej musí jiná osoba.', 'Deal submitted. A different person must approve it.') })
    } else {
      setNotice({ tone: 'danger', text: refusalText(res, t('Předložení', 'Submit'), t) })
    }
  }

  const field = { display: 'flex', flexDirection: 'column' as const, gap: 4, fontSize: 12 }

  return (
    <div>
      <PageHeader
        title={t('Nový obchod treasury', 'New treasury deal')}
        subtitle={t('Vytvoří koncept (DRAFT); zaúčtuje se až po schválení druhou osobou.', 'Creates a DRAFT; it posts only after a second person approves it.')}
        icon={<FilePlus size={20} aria-hidden="true" />}
      />

      {cpKind && (
        <div style={{ marginBottom: 16 }}>
          <DataUnavailable kind={cpKind} service="treasury-service" feature={t('protistrany a limity', 'counterparties and limits')} lang={language} dense />
        </div>
      )}

      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12 }}>
          <label style={field}>
            {t('Produkt', 'Product')}
            <select className="input" value={product} onChange={e => setProduct(e.target.value as Product)} aria-label={t('Produkt', 'Product')} disabled={draft !== null}>
              {PRODUCTS.map(p => <option key={p} value={p}>{productLabel(p, t)}</option>)}
            </select>
          </label>
          <label style={field}>
            {t('Protistrana', 'Counterparty')}
            <select className="input" value={counterpartyId} onChange={e => setCounterpartyId(e.target.value)} aria-label={t('Protistrana', 'Counterparty')} disabled={draft !== null || facility}>
              {!facility && <option value="">{t('— vyberte —', '— select —')}</option>}
              {options.map(c => (
                <option key={c.counterpartyId} value={c.counterpartyId}>
                  {c.synthetic ? `${c.name} (${t('simulovaná', 'synthetic')})` : c.name}
                </option>
              ))}
            </select>
          </label>
          <label style={field}>
            {t('Měna', 'Currency')}
            <select className="input" value={currency} onChange={e => setCurrency(e.target.value)} aria-label={t('Měna', 'Currency')} disabled={draft !== null || facility}>
              {CURRENCIES.filter(c => !facility || c === 'CZK').map(c => <option key={c} value={c}>{c}</option>)}
            </select>
          </label>
          <label style={field}>
            {t('Jistina', 'Principal')}
            <input className="input" type="number" min="0" step="0.01" inputMode="decimal" value={principal} onChange={e => setPrincipal(e.target.value)} aria-label={t('Jistina', 'Principal')} disabled={draft !== null} />
          </label>
          <label style={field}>
            {t('Sazba % p.a. (ACT/360)', 'Rate % p.a. (ACT/360)')}
            <input className="input" type="number" min="0" step="0.0001" inputMode="decimal" value={rate} onChange={e => setRate(e.target.value)} aria-label={t('Roční sazba v procentech', 'Annual rate in percent')} disabled={draft !== null} />
          </label>
          <label style={field}>
            {t('Datum valuty', 'Value date')}
            <input className="input" type="date" value={valueDate} onChange={e => setValueDate(e.target.value)} aria-label={t('Datum valuty', 'Value date')} disabled={draft !== null} />
          </label>
          {!facility && (
            <label style={field}>
              {t('Datum splatnosti (nepovinné)', 'Maturity date (optional)')}
              <input className="input" type="date" value={maturityDate} min={valueDate} onChange={e => setMaturityDate(e.target.value)} aria-label={t('Datum splatnosti', 'Maturity date')} disabled={draft !== null} />
              <span style={{ color: 'var(--text-tertiary)' }}>{t('Prázdné = přes noc, do dalšího pracovního dne.', 'Empty = overnight, to the next business day.')}</span>
            </label>
          )}
          {facility && (
            <p style={{ fontSize: 12, color: 'var(--text-secondary)', alignSelf: 'end' }}>
              {t('Depozitní facilita je vždy přes noc a pouze v CZK.', 'The deposit facility is always overnight and CZK only.')}
            </p>
          )}
        </div>
        <label style={{ ...field, marginTop: 12 }}>
          {t('Zdůvodnění (nepovinné)', 'Rationale (optional)')}
          <input className="input" value={rationale} onChange={e => setRationale(e.target.value)} aria-label={t('Zdůvodnění', 'Rationale')} disabled={draft !== null} />
        </label>

        {counterpartyId && (
          <div data-testid="limit-headroom" style={{ marginTop: 12, fontSize: 13 }}>
            {selected && <SyntheticBadge synthetic={selected.synthetic} />}{' '}
            {headroomRow ? (
              <>
                {t('Volný limit protistrany', 'Counterparty limit headroom')}{`: ${money(headroomRow.headroom)} ${currency}`}
                <span style={{ color: 'var(--text-tertiary)' }}>{` (${t('limit', 'limit')} ${money(headroomRow.limit)}, ${t('expozice', 'exposure')} ${money(headroomRow.exposure)})`}</span>
              </>
            ) : (
              t(`Pro měnu ${currency} není limit protistrany k dispozici.`, `No counterparty limit is available in ${currency}.`)
            )}
            {!isAssetProduct(product) && (
              <div style={{ color: 'var(--text-tertiary)' }}>{t('Přijetí prostředků nečerpá úvěrový limit protistrany.', 'Borrowing does not consume the counterparty credit limit.')}</div>
            )}
            {overHeadroom && (
              <div role="alert" style={{ color: 'var(--danger-text)', marginTop: 4 }}>
                {t('Jistina přesahuje volný limit. Server obchod při předložení či schválení odmítne (LIMIT_BREACHED).', 'The principal exceeds the headroom. The server will refuse the deal at submit or approval (LIMIT_BREACHED).')}
              </div>
            )}
          </div>
        )}

        {draft === null && (
          <button type="button" className="btn btn-primary btn-sm" style={{ marginTop: 12 }} disabled={!valid || busy} onClick={() => void create()}>
            {t('Vytvořit koncept', 'Create draft')}
          </button>
        )}
      </div>

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, borderColor: notice.tone === 'danger' ? 'var(--danger)' : 'var(--success)' }}>
          {notice.text}
        </div>
      )}

      {draft && (
        <div className="card" style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
          <StatusBadge status={draft.state} tone={STATE_TONE[draft.state]} label={stateLabel(draft.state, t)} />
          <span style={{ fontSize: 13 }}>
            {t(`Úrok ${money(draft.interest)} ${draft.currency} za ${draft.days} dní (${draft.dayCount}), splatnost ${draft.maturityDate}.`, `Interest ${money(draft.interest)} ${draft.currency} over ${draft.days} days (${draft.dayCount}), maturing ${draft.maturityDate}.`)}
          </span>
          {draft.state === 'DRAFT' && (
            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void submit()}>
              {t('Předložit ke schválení', 'Submit for approval')}
            </button>
          )}
          <Link href={`/treasury/deals/${encodeURIComponent(draft.dealId)}`} className="btn btn-secondary btn-sm">
            {t('Detail obchodu', 'Deal detail')}
          </Link>
        </div>
      )}
    </div>
  )
}
