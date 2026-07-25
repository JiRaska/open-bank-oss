// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'
import { useCallback, useMemo, useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import {
  CreditCard, Search, RefreshCw, CheckCircle2, XCircle, Clock,
  PauseCircle, PlayCircle, ShieldX, Ban, AlertTriangle, Info,
} from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { svcUrl, classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { ServiceStatusBadge } from '@/components/feedback/ServiceStatusBadge'
import {
  legalTransitions, isTerminal, type CardAction, type CardTransition,
} from '@/lib/cards/lifecycle'

interface Card {
  id: string; partyId: string; accountId: string; maskedPan: string
  cardType: string; status: string; expiryDate: string; createdAt: string
  blockedReason?: string | null
}

const STATUS_COLORS: Record<string, { bg: string; text: string; border: string }> = {
  ACTIVE:    { bg: 'var(--success-bg)',  text: 'var(--success-text)',  border: 'var(--success-border)' },
  SUSPENDED: { bg: 'var(--accent-bg)',   text: 'var(--accent-text)',   border: 'var(--accent-border)' },
  BLOCKED:   { bg: 'var(--danger-bg)',   text: 'var(--danger-text)',   border: 'var(--danger-border)' },
  EXPIRED:   { bg: 'var(--surface-3)',   text: 'var(--text-tertiary)', border: 'var(--border)' },
  CANCELLED: { bg: 'var(--surface-3)',   text: 'var(--text-tertiary)', border: 'var(--border)' },
  PENDING:   { bg: 'var(--warning-bg)',  text: 'var(--warning-text)',  border: 'var(--warning-border)' },
}

const ACTION_ICON: Record<CardAction, React.ElementType> = {
  activate: PlayCircle, resume: PlayCircle, suspend: PauseCircle, block: ShieldX, cancel: Ban,
}

// ── Mutation outcomes ───────────────────────────────────────────────────────
// `classifyBffFailure` is built for reads and lumps every 4xx that isn't 401/404
// into `error`. A lifecycle POST has three failure modes an operator must be able
// to tell apart, so they get their own kinds:
//   400 — the aggregate refused the transition. Card.kt guards each transition
//         with `require(...)`; an IllegalArgumentException is mapped to a bare 400
//         by libs' CommonExceptionMappers (NOT 409 — see the report/comment in
//         lifecycle.ts). In practice this means the card moved under us.
//   409 — CardEntitlementException: a product rule conflicts with the request.
//   403 — the operator's Keycloak roles don't cover this endpoint.
type MutationFailure = BffFailure | 'illegal_transition' | 'conflict' | 'forbidden'

async function classifyMutation(res: Response): Promise<MutationFailure> {
  if (res.status === 400 || res.status === 422) return 'illegal_transition'
  if (res.status === 409) return 'conflict'
  if (res.status === 403) return 'forbidden'
  return classifyBffFailure(res)
}

type Feedback =
  | { tone: 'ok'; text: string }
  | { tone: 'info'; text: string }
  | { tone: 'error'; text: string }

// ── Lifecycle map ───────────────────────────────────────────────────────────
// Always visible, above the table: an operator should be able to read the state
// machine off the screen instead of off Card.kt. Split by reversibility, because
// that is the distinction that actually matters when you're about to click:
// the top rail is undo-able, the bottom band is not.

function StateChip({ status, current, small }: { status: string; current?: boolean; small?: boolean }) {
  const c = STATUS_COLORS[status] ?? STATUS_COLORS.PENDING
  return (
    <span style={{
      padding: small ? '1px 7px' : '3px 10px', borderRadius: '10px',
      fontSize: small ? '10px' : '11px', fontWeight: 700, whiteSpace: 'nowrap',
      background: c.bg, color: c.text,
      border: `1px solid ${current ? 'var(--accent)' : c.border}`,
      boxShadow: current ? '0 0 0 3px var(--accent-bg)' : 'none',
    }}>{status}</span>
  )
}

function Arrow({ label, back }: { label: string; back?: boolean }) {
  return (
    <span style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'center', margin: '0 6px' }}>
      <span style={{ fontSize: '10px', color: 'var(--text-tertiary)', fontWeight: 600, whiteSpace: 'nowrap' }}>{label}</span>
      <span style={{ fontSize: '13px', color: 'var(--border-strong)', lineHeight: 1 }}>{back ? '⇄' : '→'}</span>
    </span>
  )
}

function LifecycleMap({ current }: { current?: string }) {
  const { t } = useLanguage()
  const row: React.CSSProperties = { display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '4px' }
  const caption: React.CSSProperties = {
    fontSize: '10px', fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase',
    color: 'var(--text-tertiary)', marginBottom: '8px',
  }
  return (
    <div className="card" style={{ padding: '16px 20px', marginBottom: '24px' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: '12px', marginBottom: '14px' }}>
        <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
          {t('Životní cyklus karty', 'Card lifecycle')}
        </div>
        <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
          {current
            ? t('Zvýrazněný stav patří vybrané kartě.', 'The highlighted state is the selected card’s.')
            : t('Vyberte kartu v tabulce a její stav se zvýrazní.', 'Select a card below to highlight its state.')}
        </div>
      </div>

      <div style={{ display: 'grid', gap: '14px' }}>
        <div>
          <div style={caption}>{t('Vratné přechody', 'Reversible transitions')}</div>
          <div style={row}>
            <StateChip status="PENDING" current={current === 'PENDING'} />
            <Arrow label={t('aktivovat', 'activate')} />
            <StateChip status="ACTIVE" current={current === 'ACTIVE'} />
            <Arrow label={t('pozastavit / obnovit', 'suspend / resume')} back />
            <StateChip status="SUSPENDED" current={current === 'SUSPENDED'} />
          </div>
        </div>

        <div style={{ borderTop: '1px dashed var(--border)', paddingTop: '12px' }}>
          <div style={caption}>{t('Nevratné přechody — vyžadují potvrzení a důvod', 'Irreversible transitions — confirmation and a reason required')}</div>
          <div style={{ display: 'grid', gap: '8px' }}>
            <div style={row}>
              <StateChip status="ACTIVE" small />
              <StateChip status="SUSPENDED" small />
              <Arrow label={t('blokovat', 'block')} />
              <StateChip status="BLOCKED" current={current === 'BLOCKED'} />
            </div>
            <div style={row}>
              <StateChip status="PENDING" small />
              <StateChip status="ACTIVE" small />
              <StateChip status="SUSPENDED" small />
              <StateChip status="BLOCKED" small />
              <Arrow label={t('zrušit', 'cancel')} />
              <StateChip status="CANCELLED" current={current === 'CANCELLED'} />
            </div>
          </div>
        </div>

        <div style={{ borderTop: '1px dashed var(--border)', paddingTop: '12px', display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
          <StateChip status="EXPIRED" current={current === 'EXPIRED'} />
          <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
            {t(
              'Stav existuje v modelu, ale card-issuance nemá žádnou úlohu expirace — zatím ho tedy žádná karta nedosáhne.',
              'The status exists in the model, but card-issuance runs no expiry job — no card reaches it today.',
            )}
          </span>
        </div>
      </div>
    </div>
  )
}

// ── Confirmation for the irreversible transitions ───────────────────────────

function ConfirmDialog({
  card, transition, busy, onCancel, onConfirm,
}: {
  card: Card
  transition: CardTransition
  busy: boolean
  onCancel: () => void
  onConfirm: (reason: string) => void
}) {
  const { t } = useLanguage()
  const [reason, setReason] = useState('')
  const label = transition.action === 'block'
    ? t('Blokovat kartu', 'Block card')
    : t('Zrušit kartu', 'Cancel card')

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={label}
      style={{
        position: 'fixed', inset: 0, zIndex: 60, background: 'rgba(15,23,42,0.45)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px',
      }}
    >
      <div className="card" style={{ width: '100%', maxWidth: '460px', padding: '22px 24px', background: 'var(--surface-1)' }}>
        <div style={{ display: 'flex', gap: '10px', alignItems: 'flex-start', marginBottom: '14px' }}>
          <AlertTriangle size={18} style={{ color: 'var(--danger)', flexShrink: 0, marginTop: '2px' }} />
          <div>
            <div style={{ fontSize: '15px', fontWeight: 700, color: 'var(--text-primary)' }}>{label}</div>
            <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginTop: '4px' }}>
              {t('Tuto operaci nelze vzít zpět.', 'This operation cannot be undone.')}
            </div>
          </div>
        </div>

        <div style={{
          display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap',
          padding: '10px 12px', borderRadius: '8px', background: 'var(--surface-2)', marginBottom: '14px',
        }}>
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: '12px', color: 'var(--text-primary)' }}>{card.maskedPan}</span>
          <StateChip status={card.status} small />
          <span style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>{'→'}</span>
          <StateChip status={transition.to} small />
        </div>

        <label style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '6px' }}>
          {t('Důvod (povinný, zapíše se do auditu karty)', 'Reason (required, recorded on the card’s audit trail)')}
        </label>
        <textarea
          value={reason}
          onChange={e => setReason(e.target.value)}
          rows={3}
          aria-label={t('Důvod operace', 'Reason for the operation')}
          style={{
            width: '100%', padding: '8px 10px', borderRadius: '6px', border: '1px solid var(--border)',
            fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)',
            outline: 'none', resize: 'vertical', fontFamily: 'inherit',
          }}
        />

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginTop: '16px' }}>
          <button className="btn btn-ghost btn-sm" onClick={onCancel} disabled={busy}>
            {t('Zpět', 'Back')}
          </button>
          <button
            className="btn btn-danger btn-sm"
            disabled={busy || reason.trim().length === 0}
            onClick={() => onConfirm(reason.trim())}
          >
            {busy
              ? t('Odesílám…', 'Submitting…')
              : transition.action === 'block'
                ? t('Potvrdit blokaci', 'Confirm block')
                : t('Potvrdit zrušení', 'Confirm cancellation')}
          </button>
        </div>
      </div>
    </div>
  )
}

export default function CardsPage() {
  const { t, language } = useLanguage()
  const [search, setSearch] = useState('')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [pending, setPending] = useState<{ card: Card; transition: CardTransition } | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [feedback, setFeedback] = useState<Feedback | null>(null)

  // NOTE: X-Operator-Id is NOT set here. The BFF proxy derives it from the server
  // session and refuses to forward a client-supplied one — a browser can set any
  // header, so an operator identity chosen in the browser is not evidence of
  // anything. See src/app/api/svc/[service]/[...path]/route.ts.

  // Single graceful data path (admin-ui rule #1): the hook classifies a non-OK
  // BFF response and auto-wakes a scaled-to-zero pod (KEDA, ADR-0057) instead of
  // showing a cold 503 as "not responding".
  const { data, loading, unavailable, waking, reload } = useServiceResource<Card[]>(
    svcUrl('card-issuance-service', '/api/v1/cards'),
    { select: (raw) => (Array.isArray(raw) ? (raw as Card[]) : ((raw as { cards?: Card[] }).cards ?? [])) },
  )
  const cards = useMemo(() => data ?? [], [data])

  const filtered = cards.filter(c =>
    c.maskedPan?.includes(search) || c.cardType?.toLowerCase().includes(search.toLowerCase()) ||
    c.status?.toLowerCase().includes(search.toLowerCase())
  )
  const selected = cards.find(c => c.id === selectedId) ?? null

  const failureCopy = useCallback((kind: MutationFailure): string => {
    switch (kind) {
      case 'illegal_transition':
        return t(
          'Tento přechod už z aktuálního stavu karty nevede — stav se mezitím změnil. Obnovte seznam a zkuste to znovu.',
          'That transition no longer leads anywhere from the card’s current status — it changed in the meantime. Refresh the list and try again.',
        )
      case 'conflict':
        return t(
          'Služba operaci odmítla kvůli konfliktu s pravidlem produktu (nárok na kartu).',
          'The service refused the operation as conflicting with a product rule (card entitlement).',
        )
      case 'forbidden':
        return t(
          'Vaše role nemá oprávnění pro tuto operaci s kartou — vyžaduje se operátor, správce nebo compliance.',
          'Your role is not permitted to perform this card operation — operator, admin or compliance is required.',
        )
      case 'unauthorized':
        return t(
          'Vaše přihlášení vypršelo. Přihlaste se prosím znovu a operaci zopakujte.',
          'Your session has expired. Please sign in again and repeat the operation.',
        )
      case 'not_found':
        return t(
          'Tato karta už v card-issuance neexistuje. Obnovte seznam.',
          'This card no longer exists in card-issuance. Refresh the list.',
        )
      case 'not_deployed':
        return t(
          'card-issuance není v tomto prostředí nasazená, takže operaci nelze provést.',
          'card-issuance is not deployed in this environment, so the operation cannot run.',
        )
      case 'scaled_to_zero':
        return t(
          'card-issuance je uspaná do nuly replik (KEDA) a právě se probouzí. Zkuste to prosím za okamžik znovu.',
          'card-issuance is scaled to zero (KEDA) and is waking up. Please try again in a moment.',
        )
      case 'unreachable':
        return t(
          'card-issuance je nasazená, ale na požadavek neodpověděla včas. Zkuste to prosím za chvíli znovu.',
          'card-issuance is deployed but did not answer in time. Please try again shortly.',
        )
      default:
        return t(
          'Operace se nedokončila. Zkuste to prosím znovu; podrobnosti jsou v auditním logu služby.',
          'The operation did not complete. Please try again; the details are in the service audit log.',
        )
    }
  }, [t])

  const run = useCallback(async (card: Card, transition: CardTransition, reason?: string) => {
    setBusy(`${card.id}:${transition.action}`)
    setFeedback(null)
    try {
      const res = await fetch(svcUrl('card-issuance-service', `/api/v1/cards/${card.id}/${transition.action}`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // Only block/cancel take a body (CardStatusRequest); the reversible
        // endpoints declare no entity parameter.
        body: transition.reason ? JSON.stringify({ reason }) : undefined,
        cache: 'no-store',
        signal: AbortSignal.timeout(15_000),
      })
      // ADR-0155 four-eyes: an action OPA flags as dual-control is parked, not
      // applied, and answers 202. Treating that as success would tell the
      // operator the card moved when it did not.
      if (res.status === 202) {
        setFeedback({
          tone: 'info',
          text: t(
            'Operace čeká na schválení druhým operátorem (čtyři oči).',
            'The operation is queued for a second operator’s approval (four-eyes).',
          ),
        })
        setPending(null)
        return
      }
      if (!res.ok) {
        setFeedback({ tone: 'error', text: failureCopy(await classifyMutation(res)) })
        return
      }
      setFeedback({
        tone: 'ok',
        text: t(
          `Karta ${card.maskedPan} je nyní ve stavu ${transition.to}.`,
          `Card ${card.maskedPan} is now ${transition.to}.`,
        ),
      })
      setPending(null)
      // Refresh so the row status and the summary tiles agree with the service.
      reload()
    } catch {
      setFeedback({ tone: 'error', text: failureCopy('unreachable') })
    } finally {
      setBusy(null)
    }
  }, [reload, t, failureCopy])

  const feedbackStyle = (tone: Feedback['tone']) => ({
    ok: { bg: 'var(--success-bg)', border: 'var(--success-border)', color: 'var(--success-text)' },
    info: { bg: 'var(--accent-bg)', border: 'var(--accent-border)', color: 'var(--accent-text)' },
    error: { bg: 'var(--danger-bg)', border: 'var(--danger-border)', color: 'var(--danger-text)' },
  }[tone])

  return (
    <AuthGuard>
      <div style={{ padding: '28px 32px', maxWidth: '1400px', animation: 'fadeIn 0.2s ease-out' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: '28px' }}>
          <div>
            <h1 style={{ fontSize: '24px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em', marginBottom: '4px' }}>
              {t('Vydávání karet', 'Card Issuance')}
            </h1>
            <p style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>
              {t('Vydávání a správa platebních karet — PCI DSS Level 1', 'Card issuance and management — PCI DSS Level 1')}
            </p>
          </div>
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <ServiceStatusBadge
              label="card-issuance :8118"
              loading={loading}
              waking={waking}
              unavailable={unavailable}
              copy={{
                up: t('card-issuance běží', 'card-issuance is up'),
                idle: t('card-issuance spí (scale-to-zero), probouzí se…', 'card-issuance idle (scaled to zero), waking…'),
                down: t('card-issuance neodpovídá', 'card-issuance is not responding'),
                checking: t('Zjišťuji stav služby…', 'Checking service…'),
              }}
            />
            {/* Issuance is customer-initiated (app / onboarding). The portal has no
                account picker to hang it off — account-service serves lookups, not a
                list (admin-ui rule #2) — so an operator-side issue form would mean
                pasting raw partyId/accountId UUIDs. The decorative button that used
                to sit here has been removed rather than half-wired. */}
            <button className="btn btn-ghost btn-sm" onClick={reload} disabled={loading}>
              <RefreshCw size={13} /> {t('Obnovit', 'Refresh')}
            </button>
          </div>
        </div>

        {/* KPIs */}
        <div className="grid-4" style={{ marginBottom: '24px' }}>
          {[
            { label: t('Celkem karet', 'Total cards'), value: cards.length, icon: <CreditCard size={16} />, color: 'var(--accent)' },
            { label: t('Aktivní', 'Active'), value: cards.filter(c => c.status === 'ACTIVE').length, icon: <CheckCircle2 size={16} />, color: 'var(--success)' },
            { label: t('Blokované', 'Blocked'), value: cards.filter(c => c.status === 'BLOCKED').length, icon: <XCircle size={16} />, color: 'var(--danger)' },
            { label: t('Čekající', 'Pending'), value: cards.filter(c => c.status === 'PENDING').length, icon: <Clock size={16} />, color: 'var(--warning)' },
          ].map(k => (
            <div key={k.label} className="stat-card">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
                <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: `${k.color}18`,
                  display: 'flex', alignItems: 'center', justifyContent: 'center', color: k.color }}>{k.icon}</div>
              </div>
              <div style={{ fontSize: '28px', fontWeight: 800, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>{k.value}</div>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', fontWeight: 500 }}>{k.label}</div>
            </div>
          ))}
        </div>

        <LifecycleMap current={selected?.status} />

        {feedback && (
          <div style={{
            display: 'flex', alignItems: 'flex-start', gap: '8px', marginBottom: '16px',
            padding: '10px 14px', borderRadius: '8px', fontSize: '12.5px',
            background: feedbackStyle(feedback.tone).bg,
            border: `1px solid ${feedbackStyle(feedback.tone).border}`,
            color: feedbackStyle(feedback.tone).color,
          }}>
            {feedback.tone === 'ok'
              ? <CheckCircle2 size={15} style={{ flexShrink: 0, marginTop: '1px' }} />
              : feedback.tone === 'info'
                ? <Info size={15} style={{ flexShrink: 0, marginTop: '1px' }} />
                : <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: '1px' }} />}
            <span style={{ flex: 1 }}>{feedback.text}</span>
            <button
              onClick={() => setFeedback(null)}
              aria-label={t('Zavřít zprávu', 'Dismiss message')}
              style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'inherit', lineHeight: 1, padding: 0 }}
            >{'×'}</button>
          </div>
        )}

        {/* Table */}
        <div className="card">
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '10px', alignItems: 'center' }}>
            <div style={{ position: 'relative', flex: 1 }}>
              <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-tertiary)' }} />
              <input value={search} onChange={e => setSearch(e.target.value)} placeholder={t('Hledat karty…', 'Search cards…')}
                aria-label={t('Hledat karty', 'Search cards')}
                style={{ width: '100%', paddingLeft: '30px', paddingRight: '12px', height: '32px', borderRadius: '6px',
                  border: '1px solid var(--border)', fontSize: '13px', background: 'var(--surface-2)', color: 'var(--text-primary)', outline: 'none' }} />
            </div>
          </div>
          {loading ? (
            <div style={{ padding: '48px', textAlign: 'center', color: 'var(--text-tertiary)', fontSize: '13px' }}>
              <RefreshCw size={20} style={{ animation: 'spin 0.8s linear infinite', marginBottom: '8px' }} />
              <div>{t('Načítám karty…', 'Loading cards…')}</div>
            </div>
          ) : unavailable ? (
            <DataUnavailable kind={unavailable.kind} service={t('Card-issuance-service', 'Card-issuance-service')} feature={t('Karty', 'Cards')} lang={language} />
          ) : filtered.length === 0 ? (
            <DataUnavailable kind="no_data" feature={t('Karty', 'Cards')} lang={language}
              detail={cards.length === 0
                ? t('Služba běží, zatím nebyly vydány žádné karty.', 'The service is running; no cards have been issued yet.')
                : t('Žádné výsledky pro zadaný filtr.', 'No results for the applied filter.')} />
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border)' }}>
                  {[t('PAN', 'PAN'), t('Typ', 'Type'), t('Status', 'Status'), t('Platnost', 'Expiry'),
                    t('Party ID', 'Party ID'), t('Vytvořeno', 'Created'), t('Akce', 'Actions')].map(h => (
                    <th key={h} style={{ padding: '10px 16px', textAlign: 'left', fontSize: '11px', fontWeight: 700,
                      color: 'var(--text-tertiary)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filtered.map(c => {
                  const sc = STATUS_COLORS[c.status] ?? STATUS_COLORS.PENDING
                  const isSelected = c.id === selectedId
                  const transitions = legalTransitions(c.status)
                  return (
                    <tr key={c.id}
                      onClick={() => setSelectedId(c.id)}
                      style={{
                        borderBottom: '1px solid var(--border)', cursor: 'pointer',
                        background: isSelected ? 'var(--accent-bg)' : undefined,
                        boxShadow: isSelected ? 'inset 3px 0 0 var(--accent)' : undefined,
                      }}
                      onMouseEnter={e => { if (!isSelected) e.currentTarget.style.background = 'var(--surface-2)' }}
                      onMouseLeave={e => { if (!isSelected) e.currentTarget.style.background = '' }}>
                      <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '13px', color: 'var(--text-primary)' }}>{c.maskedPan}</td>
                      <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.cardType}</td>
                      <td style={{ padding: '12px 16px' }}>
                        <span style={{ padding: '2px 8px', borderRadius: '10px', fontSize: '11px', fontWeight: 600,
                          background: sc.bg, color: sc.text, border: `1px solid ${sc.border}` }}>{c.status}</span>
                      </td>
                      <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-secondary)' }}>{c.expiryDate}</td>
                      <td style={{ padding: '12px 16px', fontFamily: 'var(--font-mono)', fontSize: '11px', color: 'var(--text-tertiary)' }}>{c.partyId?.slice(0,8)}…</td>
                      <td style={{ padding: '12px 16px', fontSize: '12px', color: 'var(--text-tertiary)' }}>{c.createdAt ? new Date(c.createdAt).toLocaleDateString('cs-CZ') : '—'}</td>
                      <td style={{ padding: '10px 16px' }} onClick={e => e.stopPropagation()}>
                        {transitions.length === 0 ? (
                          <span style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>
                            {isTerminal(c.status)
                              ? t('koncový stav', 'terminal state')
                              : t('žádná akce', 'no action')}
                          </span>
                        ) : (
                          <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                            {transitions.map(tr => {
                              const Icon = ACTION_ICON[tr.action]
                              const running = busy === `${c.id}:${tr.action}`
                              const label = {
                                activate: t('Aktivovat', 'Activate'),
                                resume: t('Obnovit', 'Resume'),
                                suspend: t('Pozastavit', 'Suspend'),
                                block: t('Blokovat', 'Block'),
                                cancel: t('Zrušit', 'Cancel'),
                              }[tr.action]
                              return (
                                <button
                                  key={tr.action}
                                  className={`btn btn-sm ${tr.irreversible ? 'btn-danger' : 'btn-ghost'}`}
                                  disabled={busy !== null}
                                  title={`${label} → ${tr.to}`}
                                  onClick={() => {
                                    setSelectedId(c.id)
                                    setFeedback(null)
                                    if (tr.irreversible) setPending({ card: c, transition: tr })
                                    else void run(c, tr)
                                  }}
                                >
                                  {running
                                    ? <RefreshCw size={12} style={{ animation: 'spin 0.8s linear infinite' }} />
                                    : <Icon size={12} />}
                                  {label}
                                </button>
                              )
                            })}
                          </div>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {pending && (
        <ConfirmDialog
          card={pending.card}
          transition={pending.transition}
          busy={busy !== null}
          onCancel={() => setPending(null)}
          onConfirm={(reason) => void run(pending.card, pending.transition, reason)}
        />
      )}
    </AuthGuard>
  )
}
