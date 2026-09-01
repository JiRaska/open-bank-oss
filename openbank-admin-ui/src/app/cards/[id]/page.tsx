// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One card, everything the service knows about it, and everything an operator may
// do to it.
//
// This is a ROUTE (`/cards/<uuid>`), not a slide-over panel, and that is a
// deliberate call: a card in a back office is what a conversation is about — a
// fraud call, a complaint, a four-eyes approval — so the view has to be linkable
// into a ticket, survive a reload, and support browser back. A panel is faster to
// open and impossible to send to a colleague.
//
// PCI: the console never renders a full PAN. card-issuance does expose
// `GET /{id}/secure-details` (the synthetic PAN + CVV of a virtual card), and it is
// deliberately NOT wired here — a back-office screen that can show a PAN turns
// every operator session into a PAN oracle and drags the whole console into CDE
// scope. The masked PAN is the identifier operators work with; the note on screen
// says so, so its absence reads as a control rather than as a missing feature.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import {
  ArrowLeft, CreditCard, Info, RefreshCw, ShieldCheck, Clock, User, Landmark,
} from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { hasPermission } from '@/lib/auth/roles'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { classifyBffFailure, svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { CardStatusChip } from '@/components/cards/CardStatusChip'
import { CardLifecycleMap } from '@/components/cards/CardLifecycleMap'
import { CardTransitionButtons } from '@/components/cards/CardTransitionButtons'
import { ConfirmTransitionDialog } from '@/components/cards/ConfirmTransitionDialog'
import { CardOperationFeedback } from '@/components/cards/CardOperationFeedback'
import { PageHeader } from '@/components/ui/PageHeader'
import { CardLimitsPanel } from '@/components/cards/CardLimitsPanel'
import { CardControlsPanel } from '@/components/cards/CardControlsPanel'
import { useCardOperations } from '@/lib/cards/useCardOperations'
import { quotaOf } from '@/lib/cards/entitlements'
import type { CardTransition } from '@/lib/cards/lifecycle'
import type { AccountRef, Card, CardEntitlements, PartyRef } from '@/lib/cards/types'

function Row({ label, value, mono, muted }: { label: string; value: React.ReactNode; mono?: boolean; muted?: boolean }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: '14px', padding: '7px 0', borderBottom: '1px dashed var(--border)' }}>
      <span style={{ fontSize: '11.5px', color: 'var(--text-tertiary)', whiteSpace: 'nowrap' }}>{label}</span>
      <span style={{
        fontSize: '12.5px', textAlign: 'right', wordBreak: 'break-word',
        color: muted ? 'var(--text-tertiary)' : 'var(--text-primary)',
        fontWeight: muted ? 400 : 600,
        fontFamily: mono ? 'var(--font-mono)' : undefined,
      }}>{value}</span>
    </div>
  )
}

function Panel({ icon, title, children, span }: { icon: React.ReactNode; title: string; children: React.ReactNode; span?: boolean }) {
  return (
    <div className="card" style={{ padding: '16px 20px', gridColumn: span ? '1 / -1' : undefined }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
        {icon}
        <span style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>{title}</span>
      </div>
      {children}
    </div>
  )
}

export default function CardDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-US'
  const canManage = hasPermission(session?.user?.roles ?? [], 'cards:manage')
  const canBlock = hasPermission(session?.user?.roles ?? [], 'cards:block')

  const { data: card, loading, unavailable, waking, reload } = useServiceResource<Card>(
    id ? svcUrl('card-issuance-service', `/api/v1/cards/${id}`) : null,
  )
  const showingRetainedSnapshot = unavailable !== null && card !== null

  const ops = useCardOperations(reload)
  const [pending, setPending] = useState<CardTransition | null>(null)

  // Context the card only carries as UUIDs. Each is best-effort: the card view must
  // still render when party-service or account-service is asleep, so a failed lookup
  // degrades to the identifier the card itself holds rather than to an error.
  const [party, setParty] = useState<PartyRef | null>(null)
  const [account, setAccount] = useState<AccountRef | null>(null)
  const [entitlements, setEntitlements] = useState<CardEntitlements | null>(null)
  const [siblings, setSiblings] = useState<Card[] | null>(null)

  const partyId = card?.partyId
  const accountId = card?.accountId
  const productCode = card?.productCode

  const fetchJson = useCallback(async <T,>(url: string): Promise<T | null> => {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(8000), cache: 'no-store' })
      if (!res.ok) { await classifyBffFailure(res); return null }
      return (await res.json()) as T
    } catch {
      return null
    }
  }, [])

  useEffect(() => {
    if (!partyId) return
    let cancelled = false
    void (async () => {
      const p = await fetchJson<PartyRef>(svcUrl('party-service', `/api/v1/parties/${partyId}`))
      if (!cancelled) setParty(p)
      const cards = await fetchJson<Card[]>(svcUrl('card-issuance-service', `/api/v1/cards/party/${partyId}`))
      if (!cancelled) setSiblings(Array.isArray(cards) ? cards : null)
    })()
    return () => { cancelled = true }
  }, [partyId, fetchJson])

  useEffect(() => {
    if (!accountId) return
    let cancelled = false
    void (async () => {
      const a = await fetchJson<AccountRef>(svcUrl('account-service', `/api/v1/accounts/${accountId}`))
      if (!cancelled) setAccount(a)
    })()
    return () => { cancelled = true }
  }, [accountId, fetchJson])

  useEffect(() => {
    if (!partyId || !productCode) return
    let cancelled = false
    void (async () => {
      const e = await fetchJson<CardEntitlements>(
        svcUrl('card-issuance-service', `/api/v1/cards/party/${partyId}/entitlements`, { productCode }),
      )
      if (!cancelled) setEntitlements(e)
    })()
    return () => { cancelled = true }
  }, [partyId, productCode, fetchJson])

  const when = useCallback((iso: string | null | undefined): string => {
    if (!iso) return '—'
    const d = new Date(iso)
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString(locale)
  }, [locale])

  const quota = quotaOf(entitlements)
  const otherCards = useMemo(
    () => (siblings ?? []).filter(c => c.id !== card?.id),
    [siblings, card?.id],
  )

  const onSelectTransition = (tr: CardTransition) => {
    ops.setFeedback(null)
    if (tr.irreversible) setPending(tr)
    else if (card) void ops.runTransition(card, tr)
  }

  return (
    <AuthGuard permission="cards:view">
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <div style={{ marginBottom: '18px' }}>
          <Link href="/cards" className="btn btn-ghost btn-sm" style={{ textDecoration: 'none' }}>
            <ArrowLeft size={12} /> {t('Zpět na karty', 'Back to cards')}
          </Link>
        </div>

        {loading && !card ? (
          <div role="status" aria-live="polite" style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
            <RefreshCw size={20} aria-hidden="true" style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} />
            <div>{waking
              ? t('Služba se probouzí…', 'The service is waking up…')
              : t('Načítám kartu…', 'Loading the card…')}</div>
          </div>
        ) : !card ? (
          <DataUnavailable
            kind={unavailable?.kind ?? 'not_found'}
            service={t('Card-issuance-service', 'Card-issuance-service')}
            feature={t('Detail karty', 'Card detail')}
            lang={language}
          />
        ) : (
          <>
            {showingRetainedSnapshot && <div role="status" aria-live="polite" style={{ marginBottom: 18 }}>
              <DataUnavailable
                kind={unavailable.kind}
                service={t('Card-issuance-service', 'Card-issuance-service')}
                feature={t('Aktualizace detailu karty', 'Card detail refresh')}
                lang={language}
                dense
              />
              <p style={{ margin: '6px 0 0', color: 'var(--text-tertiary)', fontSize: 11 }}>
                {t(
                  'Zobrazen je poslední úspěšný snapshot. Stav karty, limity i ovládací prvky se od té doby mohly změnit.',
                  'Showing the last successful snapshot. Card status, limits, and controls may have changed since then.',
                )}
              </p>
            </div>}

            {loading && <p role="status" aria-live="polite" style={{ margin: '0 0 12px', color: 'var(--text-tertiary)', fontSize: 11 }}>
              {t('Aktualizuji kartu; poslední snapshot zůstává dostupný.', 'Refreshing the card; the last snapshot remains available.')}
            </p>}

            <PageHeader
              icon={<CreditCard size={20} aria-hidden="true" />}
              title={card.maskedPan}
              subtitle={[card.cardType, card.productCode, card.currency].filter(Boolean).join(' · ')}
              actions={<div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
                <CardStatusChip status={card.status} current />
                {(canManage || canBlock) && <CardTransitionButtons card={card} busy={ops.busy} canManage={canManage} canBlock={canBlock} onSelect={onSelectTransition} />}
                <button type="button" className="btn btn-ghost btn-sm" onClick={reload} disabled={loading || ops.busy !== null} aria-busy={loading} aria-label={t('Obnovit kartu', 'Refresh card')}>
                  <RefreshCw size={12} aria-hidden="true" className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
                </button>
              </div>}
            />

            <CardOperationFeedback feedback={ops.feedback} onDismiss={() => ops.setFeedback(null)} />

            {/* ── PCI boundary, stated once, visibly ─────────────────────── */}
            <div style={{
              display: 'flex', gap: '9px', alignItems: 'flex-start', marginBottom: '18px',
              padding: '9px 13px', borderRadius: '8px', fontSize: '12px',
              background: 'var(--surface-2)', border: '1px solid var(--border)', color: 'var(--text-secondary)',
            }}>
              <ShieldCheck size={14} style={{ color: 'var(--success)', flexShrink: 0, marginTop: '1px' }} />
              <span>{t(
                'Zpětná kancelář zobrazuje jen maskovaný PAN. Úplné číslo karty ani CVV zde záměrně nejsou dostupné (PCI DSS) — vidí je pouze držitel ve svém mobilním bankovnictví.',
                'The back office shows the masked PAN only. The full card number and CVV are deliberately not available here (PCI DSS) — only the cardholder sees them, in the mobile app.',
              )}</span>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '16px', alignItems: 'start' }}>
              {/* ── the card itself ──────────────────────────────────────── */}
              <Panel icon={<CreditCard size={15} style={{ color: 'var(--accent)' }} />} title={t('Karta', 'Card')}>
                <Row label={t('Maskovaný PAN', 'Masked PAN')} value={card.maskedPan} mono />
                <Row label={t('Typ', 'Type')} value={card.cardType} />
                <Row label={t('Produkt', 'Product')} value={card.productCode} mono />
                <Row label={t('Měna', 'Currency')} value={card.currency} />
                <Row label={t('Platnost do', 'Expires')} value={card.expiryDate || '—'} mono />
                <Row label={t('Držitel', 'Cardholder')} value={card.cardholderName || '—'} />
                <Row label={t('Jméno na kartě', 'Embossed name')} value={card.embossedName || '—'} mono />
                <Row label={t('Doručovací adresa', 'Delivery address')} value={card.deliveryAddress || '—'} muted={!card.deliveryAddress} />
                <Row label={t('ID karty', 'Card ID')} value={card.id} mono muted />
              </Panel>

              {/* ── holder + account ─────────────────────────────────────── */}
              <Panel icon={<User size={15} style={{ color: 'var(--accent)' }} />} title={t('Klient a účet', 'Client and account')}>
                <Row
                  label={t('Klient', 'Client')}
                  value={party
                    ? <Link href={`/parties/${card.partyId}`} style={{ color: 'var(--accent)', textDecoration: 'none' }}>
                        {party.tradingName?.trim() || party.legalName?.trim() || card.partyId}
                      </Link>
                    : <Link href={`/parties/${card.partyId}`} style={{ color: 'var(--accent)', textDecoration: 'none', fontFamily: 'var(--font-mono)', fontSize: '11.5px' }}>{card.partyId}</Link>}
                />
                {party?.email && <Row label={t('E-mail', 'E-mail')} value={party.email} muted />}
                {party?.kycStatus && <Row label={t('Stav KYC', 'KYC status')} value={party.kycStatus} />}
                <Row
                  label={t('Účet', 'Account')}
                  value={account
                    ? <Link href={`/accounts/${card.accountId}`} style={{ color: 'var(--accent)', textDecoration: 'none', fontFamily: 'var(--font-mono)' }}>
                        {account.accountNumber}
                      </Link>
                    : <Link href={`/accounts/${card.accountId}`} style={{ color: 'var(--accent)', textDecoration: 'none', fontFamily: 'var(--font-mono)', fontSize: '11.5px' }}>{card.accountId}</Link>}
                />
                {account && <Row label={t('Typ účtu', 'Account type')} value={`${account.accountType} · ${account.currencyCode} · ${account.status}`} />}

                <div style={{ marginTop: '12px', fontSize: '11.5px', color: 'var(--text-secondary)' }}>
                  {quota.known
                    ? t(
                      `Klient drží ${quota.issued} z ${quota.max} karet povolených produktem ${card.productCode}.`,
                      `The client holds ${quota.issued} of the ${quota.max} cards product ${card.productCode} allows.`,
                    )
                    : t(
                      'Nárok na karty u tohoto produktu se nepodařilo zjistit — limit počtu karet tedy není znám.',
                      'The card entitlement for this product could not be read, so the cap is unknown.',
                    )}
                </div>

                {otherCards.length > 0 && (
                  <div style={{ marginTop: '10px' }}>
                    <div style={{ fontSize: '11px', fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase', color: 'var(--text-tertiary)', marginBottom: '6px' }}>
                      {t('Další karty klienta', 'The client’s other cards')}
                    </div>
                    <div style={{ display: 'grid', gap: '5px' }}>
                      {otherCards.map(c => (
                        <Link key={c.id} href={`/cards/${c.id}`} style={{
                          display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '10px',
                          padding: '6px 9px', borderRadius: '6px', background: 'var(--surface-2)',
                          border: '1px solid var(--border)', textDecoration: 'none',
                        }}>
                          <span style={{ fontFamily: 'var(--font-mono)', fontSize: '11.5px', color: 'var(--text-primary)' }}>{c.maskedPan}</span>
                          <span style={{ display: 'flex', gap: '6px', alignItems: 'center' }}>
                            <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{c.cardType}</span>
                            <CardStatusChip status={c.status} small />
                          </span>
                        </Link>
                      ))}
                    </div>
                  </div>
                )}
              </Panel>

              {/* ── operator parity: limits + channels ───────────────────── */}
              {/* The `key` carries the server's own values: when the service hands
                  back a changed card the editor remounts on the new truth instead
                  of keeping a stale draft alive. */}
              {canManage && (
                <>
                  <CardLimitsPanel
                    key={`limits-${card.dailyLimitMinorUnits}-${card.monthlyLimitMinorUnits}-${card.status}`}
                    card={card}
                    busy={ops.busy}
                    onSave={(daily, monthly) => ops.saveLimits(card, daily, monthly)}
                  />
                  <CardControlsPanel
                    key={`controls-${card.contactlessEnabled}-${card.onlineEnabled}-${card.atmEnabled}-${card.abroadEnabled}-${card.status}`}
                    card={card}
                    busy={ops.busy}
                    onSave={controls => ops.saveControls(card, controls)}
                  />
                </>
              )}

              {/* ── audit trail ─────────────────────────────────────────── */}
              <Panel icon={<Clock size={15} style={{ color: 'var(--accent)' }} />} title={t('Časová osa', 'Timeline')}>
                <Row label={t('Vytvořeno', 'Created')} value={when(card.createdAt)} />
                <Row label={t('Aktivováno', 'Activated')} value={when(card.activatedAt)} muted={!card.activatedAt} />
                <Row label={t('Blokováno', 'Blocked')} value={when(card.blockedAt)} muted={!card.blockedAt} />
                {card.blockedReason && (
                  <Row label={t('Důvod', 'Reason')} value={card.blockedReason} />
                )}
                <Row label={t('Naposledy změněno', 'Last changed')} value={when(card.updatedAt)} />
                <div style={{ marginTop: '10px', display: 'flex', gap: '7px', fontSize: '11px', color: 'var(--text-tertiary)' }}>
                  <Info size={12} style={{ flexShrink: 0, marginTop: '1px' }} />
                  <span>{t(
                    'Kdo změnu provedl, nese auditní stopa služby: každou operaci portál podepisuje identitou operátora ze session (X-Operator-Id), kterou card-issuance zapisuje do události CardStatusChanged.',
                    'Who made a change lives on the service’s audit trail: the portal signs every operation with the operator identity from the session (X-Operator-Id), which card-issuance stamps onto the CardStatusChanged event.',
                  )}</span>
                </div>
              </Panel>

              {/* ── the state machine, with this card ringed ─────────────── */}
              <div style={{ gridColumn: '1 / -1' }}>
                <CardLifecycleMap current={card.status} compact />
              </div>
            </div>

            {/* entitlement detail, kept out of the way but reachable */}
            {entitlements && (
              <div style={{ marginTop: '16px' }}>
                <Panel icon={<Landmark size={15} style={{ color: 'var(--accent)' }} />} title={t('Nárok na karty', 'Card entitlement')} span>
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '0 20px' }}>
                    <Row label={t('Produkt', 'Product')} value={entitlements.productCode} mono />
                    <Row label={t('Vydáno / maximum', 'Issued / cap')} value={quota.known ? `${quota.issued} / ${quota.max}` : t('neznámo', 'unknown')} />
                    <Row label={t('Virtuální karty', 'Virtual cards')} value={entitlements.virtualCardAllowed ? t('povoleny', 'allowed') : t('nepovoleny', 'not allowed')} />
                    <Row label={t('Jednorázové karty', 'Single-use cards')} value={entitlements.singleUseAllowed ? t('povoleny', 'allowed') : t('nepovoleny', 'not allowed')} />
                    <Row label={t('Měsíční poplatek za kartu', 'Monthly fee per card')} value={String(entitlements.monthlyFeePerCard)} />
                    <Row label={t('Zdroj', 'Source')} value={entitlements.source} muted />
                  </div>
                </Panel>
              </div>
            )}
          </>
        )}
      </div>

      {pending && card && (
        <ConfirmTransitionDialog
          card={card}
          transition={pending}
          busy={ops.busy !== null}
          onCancel={() => setPending(null)}
          onConfirm={reason => void ops.runTransition(card, pending, reason).then(ok => { if (ok) setPending(null) })}
        />
      )}
    </AuthGuard>
  )
}
