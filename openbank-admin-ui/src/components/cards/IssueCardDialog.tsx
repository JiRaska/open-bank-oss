// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Issue a card from the back office — the flow the portal used to not have.
//
// The reason a decorative "Issue card" button was once removed from this page was
// that an operator-side issue form would mean pasting raw partyId/accountId UUIDs.
// That premise was wrong: account-service DOES list a party's accounts
// (`GET /api/v1/accounts?partyId=`), so every identifier the POST needs can be
// walked to instead of typed:
//
//   search a party by name → pick one of THEIR accounts → the account names its
//   product → the product code yields the party's entitlements → the account's
//   currency is the card's currency.
//
// The operator therefore chooses a person, an account, a card type and a network.
// They never see, type or paste a UUID. The rules of the walk live in
// src/lib/cards/issue.ts (pure, tested); this file is the fetching and the pixels.

'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, ArrowLeft, ArrowRight, Check, CreditCard, RefreshCw, Search, X } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import type { UnavailableKind } from '@/components/feedback/DataUnavailable'
import { CARD_NETWORKS, CARD_TYPES, type AccountRef, type Card, type CardEntitlements, type CardNetwork, type CardType, type PartyRef } from '@/lib/cards/types'
import { allowedCardTypes, allowedNetworks, issueBlockers, quotaOf, type IssueBlocker } from '@/lib/cards/entitlements'
import {
  DEFAULT_DAILY_LIMIT_MINOR, DEFAULT_MONTHLY_LIMIT_MINOR,
  ISSUE_STEPS, canAdvance, initialDraft, issueRequestBody, nextStep, prevStep,
  stepBlockers, toEmbossedName, withAccountSelected, withPartySelected,
  type IssueDraft, type IssueStep,
} from '@/lib/cards/issue'
import { formatMajor, formatMinor, minorToMajorString, parseMajorToMinor } from '@/lib/cards/money'
import { useCardOperations } from '@/lib/cards/useCardOperations'
import { trapDialogFocus } from '@/lib/a11y/trapDialogFocus'
import { CardOperationFeedback } from './CardOperationFeedback'

const SEARCH_LIMIT = 10
const ACCOUNT_LIMIT = 25

// ── small shared bits of chrome ─────────────────────────────────────────────

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: 'block' }}>
      <span style={{ display: 'block', fontSize: '11px', fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase', color: 'var(--text-tertiary)', marginBottom: '5px' }}>
        {label}
      </span>
      {children}
    </label>
  )
}

const inputStyle: React.CSSProperties = {
  width: '100%', padding: '7px 10px', borderRadius: '6px', border: '1px solid var(--border)',
  fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none',
}

function ChoiceRow({ options, value, disabledValues, onPick, ariaLabel }: {
  options: readonly string[]
  value: string
  disabledValues: readonly string[]
  onPick: (v: string) => void
  ariaLabel: string
}) {
  return (
    <div role="group" aria-label={ariaLabel} style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
      {options.map(o => {
        const off = disabledValues.includes(o)
        const on = value === o
        return (
          <button
            key={o}
            type="button"
            disabled={off}
            aria-pressed={on}
            onClick={() => onPick(o)}
            style={{
              padding: '5px 11px', borderRadius: '999px', fontSize: '11.5px', fontWeight: 600,
              cursor: off ? 'not-allowed' : 'pointer', opacity: off ? 0.45 : 1,
              background: on ? 'var(--accent-bg)' : 'var(--surface-2)',
              color: on ? 'var(--accent-text)' : 'var(--text-secondary)',
              border: `1px solid ${on ? 'var(--accent)' : 'var(--border)'}`,
            }}
          >{o}</button>
        )
      })}
    </div>
  )
}

function Row({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: '16px', padding: '6px 0', borderBottom: '1px dashed var(--border)' }}>
      <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{label}</span>
      <span style={{ fontSize: '12.5px', color: 'var(--text-primary)', fontWeight: 600, fontFamily: mono ? 'var(--font-mono)' : undefined, textAlign: 'right' }}>{value}</span>
    </div>
  )
}

// ── the dialog ──────────────────────────────────────────────────────────────

export function IssueCardDialog({ onClose, onIssued }: { onClose: () => void; onIssued: (card: Card) => void }) {
  const { t, language } = useLanguage()
  const ops = useCardOperations()
  const dialogRef = useRef<HTMLDivElement>(null)

  const [step, setStep] = useState<IssueStep>('party')
  const [draft, setDraft] = useState<IssueDraft>(initialDraft)

  // Step 1 — party search
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const [parties, setParties] = useState<PartyRef[]>([])
  const [searching, setSearching] = useState(false)
  const [searchUnavailable, setSearchUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [searchDegraded, setSearchDegraded] = useState(false)

  // Step 2 — the party's accounts
  const [accounts, setAccounts] = useState<AccountRef[]>([])
  const [accountsLoading, setAccountsLoading] = useState(false)
  const [accountsUnavailable, setAccountsUnavailable] = useState<{ kind: UnavailableKind } | null>(null)

  // Step 3 — product + entitlements resolution
  const [resolving, setResolving] = useState(false)
  const [productUnresolved, setProductUnresolved] = useState(false)
  const [entitlementsUnknown, setEntitlementsUnknown] = useState(false)
  const [resolveNonce, setResolveNonce] = useState(0)

  // Step 4 — refs update synchronously, before React can render the button disabled.
  // Keep one key for this attempt so a dropped response replays the same card rather
  // than minting a second one, and reject a second click in the same event turn.
  const idempotencyKey = useRef<string | null>(null)
  const issuingInFlight = useRef(false)

  const [dailyText, setDailyText] = useState('')
  const [monthlyText, setMonthlyText] = useState('')

  useEffect(() => {
    const id = setTimeout(() => setDebounced(query.trim()), 300)
    return () => clearTimeout(id)
  }, [query])

  // ── party search, with a fallback when /search is flag-gated off ──────────
  // party-service gates /parties/search behind the `party-search` feature flag and
  // answers 404 when it is off (ADR-0067 §6). Falling back to the plain list and
  // filtering it client-side keeps the flow usable instead of dead-ending the
  // operator on a capability that is merely switched off.
  useEffect(() => {
    if (debounced.length < 2) { setParties([]); setSearchUnavailable(null); setSearchDegraded(false); return }
    let cancelled = false
    const run = async () => {
      setSearching(true); setSearchUnavailable(null); setSearchDegraded(false)
      try {
        const res = await fetch(
          svcUrl('party-service', '/api/v1/parties/search', { q: debounced, limit: String(SEARCH_LIMIT) }),
          { signal: AbortSignal.timeout(8000), cache: 'no-store' },
        )
        if (res.ok) {
          const body = await res.json() as { data?: PartyRef[] }
          if (!cancelled) setParties(body.data ?? [])
          return
        }
        const kind = await classifyBffFailure(res)
        if (kind !== 'not_found') { if (!cancelled) setSearchUnavailable({ kind }); return }
        // Flag off → degrade to the list endpoint.
        const listRes = await fetch(
          svcUrl('party-service', '/api/v1/parties', { page: '0', size: '100' }),
          { signal: AbortSignal.timeout(8000), cache: 'no-store' },
        )
        if (!listRes.ok) { if (!cancelled) setSearchUnavailable({ kind: await classifyBffFailure(listRes) }); return }
        const listBody = await listRes.json() as { items?: PartyRef[] }
        const needle = debounced.toLowerCase()
        const hits = (listBody.items ?? []).filter(p =>
          `${p.legalName ?? ''} ${p.tradingName ?? ''} ${p.email ?? ''}`.toLowerCase().includes(needle),
        ).slice(0, SEARCH_LIMIT)
        if (!cancelled) { setParties(hits); setSearchDegraded(true) }
      } catch {
        if (!cancelled) setSearchUnavailable({ kind: 'unreachable' })
      } finally {
        if (!cancelled) setSearching(false)
      }
    }
    void run()
    return () => { cancelled = true }
  }, [debounced])

  // ── the chosen party's accounts ───────────────────────────────────────────
  const partyId = draft.party?.id
  useEffect(() => {
    if (!partyId) { setAccounts([]); return }
    let cancelled = false
    const run = async () => {
      setAccountsLoading(true); setAccountsUnavailable(null)
      try {
        const res = await fetch(
          svcUrl('account-service', '/api/v1/accounts', { partyId, limit: String(ACCOUNT_LIMIT) }),
          { signal: AbortSignal.timeout(8000), cache: 'no-store' },
        )
        if (!res.ok) { if (!cancelled) setAccountsUnavailable({ kind: await classifyBffFailure(res) }); return }
        const body = await res.json() as { data?: AccountRef[] }
        if (!cancelled) setAccounts(body.data ?? [])
      } catch {
        if (!cancelled) setAccountsUnavailable({ kind: 'unreachable' })
      } finally {
        if (!cancelled) setAccountsLoading(false)
      }
    }
    void run()
    return () => { cancelled = true }
  }, [partyId])

  // ── product code + entitlements for the chosen account ────────────────────
  // The account carries a productId (a UUID); card-issuance wants the product
  // CODE, and the entitlement document is keyed by it. Resolve, never guess: a
  // fallback code would issue the card against the wrong product's rules and fee.
  const accountId = draft.account?.id
  const productId = draft.account?.productId
  useEffect(() => {
    if (!partyId || !accountId || !productId) return
    let cancelled = false
    const run = async () => {
      setResolving(true); setProductUnresolved(false); setEntitlementsUnknown(false)
      try {
        const prodRes = await fetch(svcUrl('product-catalog', `/api/v1/products/${productId}`), {
          signal: AbortSignal.timeout(8000), cache: 'no-store',
        })
        if (!prodRes.ok) { if (!cancelled) setProductUnresolved(true); return }
        const product = await prodRes.json() as { code?: string }
        const code = product.code?.trim()
        if (!code) { if (!cancelled) setProductUnresolved(true); return }
        if (!cancelled) setDraft(d => ({ ...d, productCode: code }))

        const entRes = await fetch(
          svcUrl('card-issuance-service', `/api/v1/cards/party/${partyId}/entitlements`, { productCode: code }),
          { signal: AbortSignal.timeout(8000), cache: 'no-store' },
        )
        if (!entRes.ok) { if (!cancelled) setEntitlementsUnknown(true); return }
        const ent = await entRes.json() as CardEntitlements
        if (!cancelled) setDraft(d => ({ ...d, entitlements: ent }))
      } catch {
        if (!cancelled) setProductUnresolved(true)
      } finally {
        if (!cancelled) setResolving(false)
      }
    }
    void run()
    return () => { cancelled = true }
  }, [partyId, accountId, productId, resolveNonce])

  const currency = draft.account?.currencyCode ?? null
  const quota = quotaOf(draft.entitlements)
  const types = useMemo(() => allowedCardTypes(draft.entitlements), [draft.entitlements])
  const networks = useMemo(() => allowedNetworks(draft.entitlements, CARD_NETWORKS), [draft.entitlements])
  const blockers = issueBlockers(draft.entitlements, { cardType: draft.cardType, network: draft.network })

  // Picking an account fixes the currency, so that is the moment the limit fields
  // are seeded — in the event handler, not in an effect watching the currency.
  //
  // Always the DEFAULTS, never draft.dailyMinorUnits/monthlyMinorUnits from a
  // previous pick: those are minor units under the PREVIOUS account's currency
  // exponent, and re-formatting them under a new currency reinterprets the same
  // integer at a different scale rather than converting it — a value typed as
  // 1000.00 USD (100000 minor, 2 decimals) would render as 100000 JPY (0
  // decimals) if the operator went back and chose a JPY account instead. There
  // is no exchange rate here to convert with, so starting over is correct, not
  // just simpler.
  const pickAccount = (a: AccountRef) => {
    setDraft(d => withAccountSelected(d, a))
    setDailyText(minorToMajorString(DEFAULT_DAILY_LIMIT_MINOR, a.currencyCode))
    setMonthlyText(minorToMajorString(DEFAULT_MONTHLY_LIMIT_MINOR, a.currencyCode))
    go('configure')
  }

  const setDaily = (text: string) => {
    setDailyText(text)
    setDraft(d => ({ ...d, dailyMinorUnits: parseMajorToMinor(text, currency) }))
  }
  const setMonthly = (text: string) => {
    setMonthlyText(text)
    setDraft(d => ({ ...d, monthlyMinorUnits: parseMajorToMinor(text, currency) }))
  }

  const blockerCopy = useCallback((b: IssueBlocker): string => ({
    CARD_PRODUCT_DISABLED: t('Produkt tohoto účtu karty nevydává.', 'The account’s product does not carry cards.'),
    CARD_VIRTUAL_NOT_ALLOWED: t('Tento produkt nedovoluje virtuální ani jednorázové karty.', 'This product allows neither virtual nor single-use cards.'),
    CARD_NETWORK_NOT_ALLOWED: t('Tato karetní síť není u produktu povolena.', 'This card network is not allowed on the product.'),
    CARD_QUOTA_EXCEEDED: t('Klient už vyčerpal počet karet povolený tímto produktem.', 'The client already holds every card this product allows.'),
  }[b]), [t])

  const go = (to: IssueStep) => { ops.setFeedback(null); setStep(to) }

  const submit = async () => {
    const body = issueRequestBody(draft)
    if (!body) return
    if (issuingInFlight.current) return
    issuingInFlight.current = true
    try {
      const key = idempotencyKey.current ??= crypto.randomUUID()
      const issued = await ops.issueCard(body, key)
      if (issued?.id) onIssued(issued)
    } finally {
      issuingInFlight.current = false
    }
  }

  const stepTitle: Record<IssueStep, string> = {
    party: t('Klient', 'Client'),
    account: t('Účet', 'Account'),
    configure: t('Karta', 'Card'),
    review: t('Potvrzení', 'Review'),
  }

  const canContinue = canAdvance(step, draft) && !resolving
  const busy = ops.busy !== null

  return (
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-label={t('Vydat kartu', 'Issue a card')}
      aria-busy={busy}
      onKeyDown={e => {
        if (e.key === 'Escape' && !busy) onClose()
        trapDialogFocus(e, dialogRef.current)
      }}
      style={{
        position: 'fixed', inset: 0, zIndex: 60, background: 'rgba(15,23,42,0.45)',
        display: 'flex', alignItems: 'flex-start', justifyContent: 'center', padding: '40px 24px', overflowY: 'auto',
      }}
    >
      <div className="card" style={{ width: '100%', maxWidth: '640px', background: 'var(--surface-1)' }}>
        {/* header + stepper */}
        <div style={{ padding: '18px 22px 14px', borderBottom: '1px solid var(--border)' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '9px' }}>
              <CreditCard size={16} aria-hidden="true" style={{ color: 'var(--accent)' }} />
              <span style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)' }}>{t('Vydat kartu', 'Issue a card')}</span>
            </div>
            <button type="button" onClick={onClose} disabled={busy} aria-label={t('Zavřít', 'Close')}
              style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-tertiary)', padding: 0, lineHeight: 1 }}>
              <X size={16} aria-hidden="true" />
            </button>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginTop: '12px', flexWrap: 'wrap' }}>
            {ISSUE_STEPS.map((s, i) => {
              const active = s === step
              const done = ISSUE_STEPS.indexOf(step) > i
              return (
                <span key={s} style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
                  <span style={{
                    display: 'inline-flex', alignItems: 'center', gap: '5px',
                    padding: '3px 10px', borderRadius: '999px', fontSize: '11px', fontWeight: 700,
                    background: active ? 'var(--accent-bg)' : 'transparent',
                    color: active ? 'var(--accent-text)' : done ? 'var(--success-text)' : 'var(--text-tertiary)',
                    border: `1px solid ${active ? 'var(--accent)' : 'var(--border)'}`,
                  }}>
                    {done ? <Check size={11} /> : null}{stepTitle[s]}
                  </span>
                  {i < ISSUE_STEPS.length - 1 && <span style={{ color: 'var(--border-strong)', fontSize: '11px' }}>{'→'}</span>}
                </span>
              )
            })}
          </div>
        </div>

        <div style={{ padding: '18px 22px' }}>
          <CardOperationFeedback feedback={ops.feedback} onDismiss={() => ops.setFeedback(null)} />

          {/* ── step 1: party ─────────────────────────────────────────────── */}
          {step === 'party' && (
            <div style={{ display: 'grid', gap: '12px' }}>
              <Field label={t('Najít klienta podle jména nebo e-mailu', 'Find the client by name or e-mail')}>
                <div style={{ position: 'relative' }}>
                  <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
                  <input
                    value={query}
                    autoFocus
                    onChange={e => setQuery(e.target.value)}
                    placeholder={t('např. Novák', 'e.g. Novak')}
                    aria-label={t('Hledat klienta', 'Search for a client')}
                    style={{ ...inputStyle, paddingLeft: '30px' }}
                  />
                </div>
              </Field>

              {query.trim().length > 0 && query.trim().length < 2 && (
                <div style={{ fontSize: '11.5px', color: 'var(--text-tertiary)' }}>
                  {t('Zadejte alespoň dva znaky.', 'Type at least two characters.')}
                </div>
              )}
              {searchDegraded && (
                <div style={{ fontSize: '11.5px', color: 'var(--text-tertiary)' }}>
                  {t(
                    'Fulltextové hledání je vypnuté, filtruji tedy posledních 100 klientů.',
                    'Fulltext search is switched off, so the last 100 clients are being filtered instead.',
                  )}
                </div>
              )}

              {searching ? (
                <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: '7px' }}>
                  <RefreshCw size={13} style={{ animation: 'spin 0.8s linear infinite' }} />
                  {t('Hledám…', 'Searching…')}
                </div>
              ) : searchUnavailable ? (
                <DataUnavailable kind={searchUnavailable.kind} service={t('Party-service', 'Party-service')} feature={t('Vyhledávání klientů', 'Client search')} lang={language} dense />
              ) : debounced.length >= 2 && parties.length === 0 ? (
                <DataUnavailable kind="no_data" feature={t('Klienti', 'Clients')} lang={language} dense
                  detail={t('Žádný klient neodpovídá zadanému výrazu.', 'No client matches that term.')} />
              ) : (
                <div style={{ display: 'grid', gap: '6px' }}>
                  {parties.map(p => {
                    const picked = draft.party?.id === p.id
                    return (
                      <button
                        key={p.id}
                        type="button"
                        onClick={() => { setDraft(d => withPartySelected(d, p)); go('account') }}
                        style={{
                          textAlign: 'left', padding: '9px 12px', borderRadius: '8px', cursor: 'pointer',
                          background: picked ? 'var(--accent-bg)' : 'var(--surface-2)',
                          border: `1px solid ${picked ? 'var(--accent)' : 'var(--border)'}`,
                        }}
                      >
                        <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>
                          {p.tradingName?.trim() || p.legalName?.trim() || p.id}
                        </div>
                        <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>
                          {[p.email, p.status, p.kycStatus].filter(Boolean).join(' · ')}
                        </div>
                      </button>
                    )
                  })}
                </div>
              )}
            </div>
          )}

          {/* ── step 2: account ───────────────────────────────────────────── */}
          {step === 'account' && (
            <div style={{ display: 'grid', gap: '12px' }}>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                {t('Účty klienta', 'The client’s accounts')}
                {': '}
                <strong style={{ color: 'var(--text-primary)' }}>{draft.party?.tradingName?.trim() || draft.party?.legalName?.trim()}</strong>
              </div>
              {accountsLoading ? (
                <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: '7px' }}>
                  <RefreshCw size={13} style={{ animation: 'spin 0.8s linear infinite' }} />
                  {t('Načítám účty…', 'Loading accounts…')}
                </div>
              ) : accountsUnavailable ? (
                <DataUnavailable kind={accountsUnavailable.kind} service={t('Account-service', 'Account-service')} feature={t('Účty klienta', 'The client’s accounts')} lang={language} dense />
              ) : accounts.length === 0 ? (
                <DataUnavailable kind="no_data" feature={t('Účty klienta', 'The client’s accounts')} lang={language} dense
                  detail={t('Tento klient nemá žádný účet, na který by šlo kartu vydat.', 'This client has no account a card could be issued against.')} />
              ) : (
                <div style={{ display: 'grid', gap: '6px' }}>
                  {accounts.map(a => {
                    const picked = draft.account?.id === a.id
                    const usable = a.status === 'ACTIVE'
                    return (
                      <button
                        key={a.id}
                        type="button"
                        disabled={!usable}
                        onClick={() => pickAccount(a)}
                        title={usable ? undefined : t('Kartu lze vydat jen k aktivnímu účtu.', 'A card can only be issued against an ACTIVE account.')}
                        style={{
                          textAlign: 'left', padding: '9px 12px', borderRadius: '8px',
                          cursor: usable ? 'pointer' : 'not-allowed', opacity: usable ? 1 : 0.55,
                          background: picked ? 'var(--accent-bg)' : 'var(--surface-2)',
                          border: `1px solid ${picked ? 'var(--accent)' : 'var(--border)'}`,
                        }}
                      >
                        <div style={{ fontSize: '12.5px', fontWeight: 600, color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>{a.accountNumber}</div>
                        <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '2px' }}>
                          {[a.accountType, a.currencyCode, a.status].filter(Boolean).join(' · ')}
                          {!usable && ` — ${t('nelze použít', 'not usable')}`}
                        </div>
                      </button>
                    )
                  })}
                </div>
              )}
            </div>
          )}

          {/* ── step 3: card configuration ────────────────────────────────── */}
          {step === 'configure' && (
            <div style={{ display: 'grid', gap: '14px' }}>
              {resolving ? (
                <div style={{ fontSize: '12px', color: 'var(--text-tertiary)', display: 'flex', alignItems: 'center', gap: '7px' }}>
                  <RefreshCw size={13} style={{ animation: 'spin 0.8s linear infinite' }} />
                  {t('Zjišťuji produkt účtu a nárok klienta…', 'Resolving the account’s product and the client’s entitlement…')}
                </div>
              ) : productUnresolved ? (
                <div style={{ display: 'grid', gap: '8px' }}>
                  <div style={{ display: 'flex', gap: '8px', fontSize: '12px', color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', borderRadius: '8px', padding: '10px 12px' }}>
                    <AlertTriangle size={14} style={{ flexShrink: 0, marginTop: '1px' }} />
                    <span>{t(
                      'Produkt tohoto účtu se nepodařilo zjistit, a bez něj by karta vznikla pod cizími pravidly a poplatkem. Zkuste to prosím znovu.',
                      'The account’s product could not be resolved, and without it the card would be created under the wrong rules and fee. Please try again.',
                    )}</span>
                  </div>
                  <div>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setResolveNonce(n => n + 1)}>
                      <RefreshCw size={12} aria-hidden="true" /> {t('Zkusit znovu', 'Try again')}
                    </button>
                  </div>
                </div>
              ) : (
                <>
                  {/* entitlement context — "this party has 2 of 3 cards" */}
                  <div style={{ padding: '10px 12px', borderRadius: '8px', background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap' }}>
                      <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                        {t('Produkt', 'Product')}{': '}
                        <strong style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-mono)' }}>{draft.productCode}</strong>
                      </span>
                      <span style={{ fontSize: '12px', color: quota.exhausted ? 'var(--danger-text)' : 'var(--text-secondary)' }}>
                        {quota.known
                          ? t(`Karet: ${quota.issued} ze ${quota.max}`, `Cards: ${quota.issued} of ${quota.max}`)
                          : t('Limit počtu karet není znám', 'The card cap is unknown')}
                      </span>
                    </div>
                    {entitlementsUnknown && (
                      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '6px' }}>
                        {t(
                          'Nárok klienta se nepodařilo načíst — pravidla ověří až služba při vydání.',
                          'The client’s entitlement could not be loaded — the service will check the rules on submit.',
                        )}
                      </div>
                    )}
                    {draft.entitlements && draft.entitlements.monthlyFeePerCard > 0 && (
                      <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', marginTop: '6px' }}>
                        {t('Měsíční poplatek za kartu', 'Monthly fee per card')}{': '}
                        {formatMajor(draft.entitlements.monthlyFeePerCard, currency, language === 'cs' ? 'cs-CZ' : 'en-US')}
                      </div>
                    )}
                  </div>

                  <Field label={t('Typ karty', 'Card type')}>
                    <ChoiceRow
                      options={CARD_TYPES}
                      value={draft.cardType}
                      disabledValues={CARD_TYPES.filter(x => !types.includes(x))}
                      onPick={v => setDraft(d => ({ ...d, cardType: v as CardType }))}
                      ariaLabel={t('Typ karty', 'Card type')}
                    />
                  </Field>

                  <Field label={t('Karetní síť', 'Card network')}>
                    <ChoiceRow
                      options={CARD_NETWORKS}
                      value={draft.network}
                      disabledValues={CARD_NETWORKS.filter(x => !networks.includes(x))}
                      onPick={v => setDraft(d => ({ ...d, network: v as CardNetwork }))}
                      ariaLabel={t('Karetní síť', 'Card network')}
                    />
                  </Field>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                    <Field label={t('Jméno držitele', 'Cardholder name')}>
                      <input
                        value={draft.cardholderName}
                        onChange={e => setDraft(d => ({ ...d, cardholderName: e.target.value }))}
                        aria-label={t('Jméno držitele', 'Cardholder name')}
                        style={inputStyle}
                      />
                    </Field>
                    <Field label={t('Jméno na kartě (max. 26 znaků)', 'Embossed name (max. 26 characters)')}>
                      <input
                        value={draft.embossedName}
                        onChange={e => setDraft(d => ({ ...d, embossedName: toEmbossedName(e.target.value) }))}
                        aria-label={t('Jméno na kartě', 'Embossed name')}
                        style={{ ...inputStyle, fontFamily: 'var(--font-mono)' }}
                      />
                    </Field>
                  </div>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                    <Field label={t(`Denní limit (${currency ?? ''})`, `Daily limit (${currency ?? ''})`)}>
                      <input value={dailyText} onChange={e => setDaily(e.target.value)} inputMode="decimal"
                        aria-label={t('Denní limit', 'Daily limit')} style={inputStyle} />
                    </Field>
                    <Field label={t(`Měsíční limit (${currency ?? ''})`, `Monthly limit (${currency ?? ''})`)}>
                      <input value={monthlyText} onChange={e => setMonthly(e.target.value)} inputMode="decimal"
                        aria-label={t('Měsíční limit', 'Monthly limit')} style={inputStyle} />
                    </Field>
                  </div>

                  {/* Say WHY continue is unavailable rather than greying it out silently. */}
                  {stepBlockers('configure', draft).length > 0 && (
                    <div style={{ display: 'grid', gap: '4px' }}>
                      {blockers.map(b => (
                        <div key={b} style={{ display: 'flex', gap: '7px', fontSize: '11.5px', color: 'var(--danger-text)' }}>
                          <AlertTriangle size={13} style={{ flexShrink: 0, marginTop: '1px' }} />{blockerCopy(b)}
                        </div>
                      ))}
                      {(!draft.cardholderName.trim() || !draft.embossedName.trim()) && (
                        <div style={{ fontSize: '11.5px', color: 'var(--danger-text)' }}>
                          {t('Jméno držitele i jméno na kartě musí být vyplněné.', 'Both the cardholder name and the embossed name are required.')}
                        </div>
                      )}
                      {draft.dailyMinorUnits === null || draft.monthlyMinorUnits === null ? (
                        <div style={{ fontSize: '11.5px', color: 'var(--danger-text)' }}>
                          {t('Limity musí být kladná částka v měně účtu.', 'Both limits must be a positive amount in the account’s currency.')}
                        </div>
                      ) : draft.dailyMinorUnits > draft.monthlyMinorUnits ? (
                        <div style={{ fontSize: '11.5px', color: 'var(--danger-text)' }}>
                          {t('Denní limit nesmí být vyšší než měsíční.', 'The daily limit cannot exceed the monthly one.')}
                        </div>
                      ) : null}
                    </div>
                  )}
                </>
              )}
            </div>
          )}

          {/* ── step 4: review ────────────────────────────────────────────── */}
          {step === 'review' && (
            <div style={{ display: 'grid', gap: '10px' }}>
              <Row label={t('Klient', 'Client')} value={draft.party?.tradingName?.trim() || draft.party?.legalName?.trim()} />
              <Row label={t('Účet', 'Account')} value={draft.account?.accountNumber} mono />
              <Row label={t('Produkt', 'Product')} value={draft.productCode} mono />
              <Row label={t('Typ a síť', 'Type and network')} value={`${draft.cardType} · ${draft.network}`} />
              <Row label={t('Jméno na kartě', 'Embossed name')} value={draft.embossedName} mono />
              <Row label={t('Měna', 'Currency')} value={currency} />
              <Row label={t('Denní limit', 'Daily limit')} value={formatMinor(draft.dailyMinorUnits ?? 0, currency, language === 'cs' ? 'cs-CZ' : 'en-US')} />
              <Row label={t('Měsíční limit', 'Monthly limit')} value={formatMinor(draft.monthlyMinorUnits ?? 0, currency, language === 'cs' ? 'cs-CZ' : 'en-US')} />
              <div style={{ fontSize: '11.5px', color: 'var(--text-tertiary)', marginTop: '4px' }}>
                {t(
                  'Karta vznikne ve stavu PENDING; aktivuje se samostatným krokem. Opakované odeslání je díky idempotentnímu klíči bezpečné — nevznikne druhá karta.',
                  'The card is created PENDING; activating it is a separate step. Re-submitting is safe thanks to the idempotency key — it will not mint a second card.',
                )}
              </div>
            </div>
          )}
        </div>

        {/* footer */}
        <div style={{ padding: '14px 22px', borderTop: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', gap: '8px' }}>
          <button type="button" className="btn btn-ghost btn-sm" disabled={busy || step === 'party'} onClick={() => go(prevStep(step))}>
            <ArrowLeft size={12} aria-hidden="true" /> {t('Zpět', 'Back')}
          </button>
          {step === 'review' ? (
            <button type="button" className="btn btn-primary btn-sm" disabled={busy || !canContinue} aria-busy={busy} onClick={() => void submit()}>
              {busy
                ? <RefreshCw size={12} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite' }} />
                : <CreditCard size={12} aria-hidden="true" />}
              {t('Vydat kartu', 'Issue the card')}
            </button>
          ) : (
            <button type="button" className="btn btn-primary btn-sm" disabled={busy || !canContinue} onClick={() => go(nextStep(step))}>
              {t('Pokračovat', 'Continue')} <ArrowRight size={12} aria-hidden="true" />
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
