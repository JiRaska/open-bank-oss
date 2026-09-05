// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0212 D4 — the four-eyes activation console for jurisdictional credit compliance packs.
//
// Why this screen exists: activating a pack was reachable only by hand-driving
// POST /compliance-packs/proposals and .../decide with two separately-minted operator tokens.
// A control that needs a shell is a control nobody exercises — and
// `openbank.lending.compliance.enforce-pack` must stay false until a pack is active, because with
// enforcement on and no active pack every origination is REFUSED. The gate and the only way
// through it belong in the same place.
//
// Maker != checker is NOT enforced here. The service raises MakerCheckerViolation (422) when one
// principal tries both halves, and this page renders that refusal verbatim. A client-side copy of
// the rule would only hide the real control behind a weaker one.

'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSession } from 'next-auth/react'
import * as Dialog from '@radix-ui/react-dialog'
import { CheckCircle2, Clock, RefreshCw, ShieldCheck, ScrollText, AlertTriangle } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { PageHeader } from '@/components/ui/PageHeader'
import { AuthGuard, Can } from '@/components/auth/AuthGuard'

/** Mirrors lending-service `PackActivationView`. `listActive()` synthesises id = all-zero UUID for
 *  every row (it projects the in-memory registry, not a workflow row) — never key a list on it. */
type PackActivationView = {
  id: string
  jurisdiction: string
  productType: string
  packVersion: number
  effectiveFrom: string
  contentHash: string
  state: string
  proposedBy: string
  decidedBy: string | null
  decidedAt: string | null
  proposedAt: string | null
  decisionReason: string | null
  pack: Record<string, unknown>
}

/** A read that was REFUSED must never render as "nothing pending" — same reasoning as /approvals
 *  (ADR-0227 D2): an approvals screen wrongly saying "nothing to do" is its worst failure. */
type SourceState = 'ok' | 'forbidden' | 'unavailable'

function stateFor(status: number): SourceState {
  return status === 401 || status === 403 ? 'forbidden' : 'unavailable'
}

const PACKS_BASE = '/api/v1/lending/compliance-packs'
const cell = { padding: '10px 14px' } as const
const th = { padding: '10px 14px', fontSize: 11, color: 'var(--text-tertiary)' } as const

export default function CompliancePacksPage() {
  const { t } = useLanguage()
  const { data: session } = useSession()
  const actor = session?.user?.email || session?.user?.name || null

  const [active, setActive] = useState<PackActivationView[]>([])
  const [pending, setPending] = useState<PackActivationView[]>([])
  const [sources, setSources] = useState<Record<string, SourceState>>({})
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const [packJson, setPackJson] = useState('')
  const [detail, setDetail] = useState<PackActivationView | null>(null)
  const [review, setReview] = useState<{ proposal: PackActivationView; approve: boolean } | null>(null)
  const reviewCancelRef = useRef<HTMLButtonElement>(null)
  const reviewConfirmRef = useRef<HTMLButtonElement>(null)
  const detailTriggerRef = useRef<HTMLElement | null>(null)

  const openDetail = (pack: PackActivationView, event: React.MouseEvent<HTMLElement>) => {
    detailTriggerRef.current = event.currentTarget
    setDetail(pack)
  }

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [activeRes, pendingRes] = await Promise.all([
        fetch(svcUrl('lending-service', `${PACKS_BASE}/active`), { cache: 'no-store' }),
        fetch(svcUrl('lending-service', `${PACKS_BASE}/proposals/pending`), { cache: 'no-store' }),
      ])
      const next: Record<string, SourceState> = {}
      if (activeRes.ok) {
        const rows = await activeRes.json()
        setActive(Array.isArray(rows) ? rows : [])
        next.active = 'ok'
      } else {
        setActive([])
        next.active = stateFor(activeRes.status)
      }
      if (pendingRes.ok) {
        const rows = await pendingRes.json()
        setPending(Array.isArray(rows) ? rows : [])
        next.pending = 'ok'
      } else {
        setPending([])
        next.pending = stateFor(pendingRes.status)
      }
      setSources(next)
      setError(null)
    } catch {
      setActive([])
      setPending([])
      setSources({ active: 'unavailable', pending: 'unavailable' })
      setError(t('lending-service je nedostupný.', 'lending-service is unreachable.'))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => { void load() }, [load])

  /** Surface the service's own message. A 422 here is the maker-checker refusal — the single most
   *  important thing this screen can say. Never flatten it to a generic "failed". */
  const failWith = async (res: Response, fallback: string) => {
    const body = await res.json().catch(() => ({} as { error?: string }))
    setError(body?.error || `${fallback} (HTTP ${res.status})`)
  }

  const propose = async () => {
    let parsed: unknown
    try {
      parsed = JSON.parse(packJson)
    } catch {
      setError(t('Pack není platný JSON.', 'Pack is not valid JSON.'))
      return
    }
    setBusyId('propose')
    setNotice(null)
    try {
      const res = await fetch(svcUrl('lending-service', `${PACKS_BASE}/proposals`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(parsed),
      })
      if (!res.ok) {
        await failWith(res, t('Návrh se nezdařil', 'Proposal failed'))
        return
      }
      setError(null)
      setPackJson('')
      setNotice(t(
        'Návrh zapsán. Aktivuje ho AŽ jiný compliance principál — ne vy.',
        'Proposal recorded. It activates only once a DIFFERENT compliance principal decides it — not you.',
      ))
      await load()
    } catch {
      setError(t('lending-service je nedostupný.', 'lending-service is unreachable.'))
    } finally {
      setBusyId(null)
    }
  }

  const decide = async (id: string, approve: boolean) => {
    setBusyId(id)
    setNotice(null)
    try {
      const res = await fetch(svcUrl('lending-service', `${PACKS_BASE}/proposals/${id}/decide`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ approve, reason: reasons[id] || null }),
      })
      if (!res.ok) {
        await failWith(res, t('Rozhodnutí se nezdařilo', 'Decision failed'))
        return
      }
      setError(null)
      setNotice(approve
        ? t('Pack aktivován. Guard ho používá okamžitě, bez restartu služby.',
            'Pack activated. The origination guard uses it immediately — no service restart.')
        : t('Návrh zamítnut.', 'Proposal rejected.'))
      setReview(null)
      await load()
    } catch {
      setError(t('lending-service je nedostupný.', 'lending-service is unreachable.'))
    } finally {
      setBusyId(null)
    }
  }

  const degraded = useMemo(
    () => Object.entries(sources).filter(([, v]) => v !== 'ok').map(([k]) => k),
    [sources],
  )

  return (
    <AuthGuard permission="lending:compliance:view">
    <div>
      <PageHeader
        breadcrumb={<div className="breadcrumb">
            <span>OpenBank</span><span className="breadcrumb-sep">/</span>
            <span>{t('Úvěry', 'Lending')}</span><span className="breadcrumb-sep">/</span>
            <span className="breadcrumb-current">{t('Compliance packy', 'Compliance packs')}</span>
          </div>}
        icon={<ShieldCheck aria-hidden="true" size={18} style={{ color: 'var(--accent)' }} />}
        title={t('Aktivace compliance packů', 'Compliance pack activation')}
        subtitle={t(
          'Jurisdikční úvěrové compliance packy (ADR-0212 D4). Maker navrhne, JINÝ compliance principál rozhodne. Aktivovaný pack platí okamžitě — bez release služby.',
          'Jurisdictional credit compliance packs (ADR-0212 D4). A maker proposes, a DIFFERENT compliance principal decides. An activated pack takes effect immediately — no service release.',
        )}
        actions={<button type="button" onClick={() => void load()} disabled={loading} className="btn btn-secondary" style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
          <RefreshCw aria-hidden="true" size={14} className={loading ? 'animate-spin' : ''} /> {t('Obnovit', 'Refresh')}
        </button>}
      />
      {actor && (
        <p className="page-subtitle" data-testid="acting-as" style={{ marginTop: -20, marginBottom: 20 }}>
          {t('Jednáte jako', 'Acting as')}: <strong>{actor}</strong>
        </p>
      )}

      {degraded.length > 0 && (
        <div className="card" data-testid="degraded" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--warning)', fontSize: 13, display: 'flex', alignItems: 'center', gap: 6 }}>
          <AlertTriangle aria-hidden="true" size={14} />
          {t(
            `Nepřečteno (403 / nedostupné): ${degraded.join(', ')} — prázdný seznam NEZNAMENÁ, že nic nečeká.`,
            `Not read (403 / unavailable): ${degraded.join(', ')} — an empty list does NOT mean nothing is pending.`,
          )}
        </div>
      )}
      {error && (
        <div className="card" data-testid="error" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--danger)', color: 'var(--danger)', fontSize: 13 }}>
          {error}
        </div>
      )}
      {notice && (
        <div className="card" data-testid="notice" style={{ padding: 12, marginBottom: 16, borderLeft: '3px solid var(--success)', fontSize: 13 }}>
          {notice}
        </div>
      )}

      <div className="section-title" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <CheckCircle2 aria-hidden="true" size={15} /> {t('Aktivní packy', 'Active packs')} ({active.length})
      </div>
      <div className="card" style={{ padding: 0, overflow: 'hidden', marginBottom: 24 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'var(--surface-2)', textAlign: 'left' }}>
              <th style={th}>{t('Jurisdikce', 'Jurisdiction')}</th>
              <th style={th}>{t('Produkt', 'Product')}</th>
              <th style={th}>{t('Verze', 'Version')}</th>
              <th style={th}>{t('Účinnost od', 'Effective from')}</th>
              <th style={th}>{t('Otisk obsahu', 'Content hash')}</th>
            </tr>
          </thead>
          <tbody>
            {active.map(p => (
              <tr key={`${p.jurisdiction}-${p.productType}-${p.packVersion}`} style={{ borderTop: '1px solid var(--border)' }}>
                <td style={cell}>{p.jurisdiction}</td>
                <td style={cell}>{p.productType}</td>
                <td style={cell}>v{p.packVersion}</td>
                <td style={cell}>{p.effectiveFrom}</td>
                <td style={{ ...cell, fontSize: 11 }}>
                  <span className="mono">{p.contentHash.slice(0, 16)}…</span>{' '}
                  <button type="button" className="btn btn-secondary btn-sm" onClick={event => openDetail(p, event)}>
                    {t('Zobrazit detail', 'View details')}
                  </button>
                </td>
              </tr>
            ))}
            {active.length === 0 && (
              <tr><td colSpan={5} data-testid="no-active" style={{ padding: 20, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>
                {sources.active === 'ok'
                  ? t(
                      'Žádný aktivní pack. Dokud je tu prázdno, nesmí se zapnout LENDING_ENFORCE_PACK — s vynucením a bez packu je každá žádost ODMÍTNUTA.',
                      'No active pack. While this is empty, LENDING_ENFORCE_PACK must not be turned on — with enforcement on and no pack, every application is REFUSED.',
                    )
                  : t('Nenačteno.', 'Not loaded.')}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="section-title" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <Clock aria-hidden="true" size={15} /> {t('Čeká na druhý pár očí', 'Awaiting a checker')} ({pending.length})
      </div>
      <div className="card" style={{ padding: 0, overflow: 'hidden', marginBottom: 24 }}>
        {pending.map(p => (
          <div
            key={p.id}
            data-testid={`proposal-${p.id}`}
            style={{ display: 'flex', gap: 12, alignItems: 'center', justifyContent: 'space-between', padding: '12px 14px', borderTop: '1px solid var(--border)', flexWrap: 'wrap' }}
          >
            <div style={{ fontSize: 13 }}>
              <div>
                {p.jurisdiction} / {p.productType} · <strong>v{p.packVersion}</strong> ·{' '}
                {t('účinnost od', 'effective from')} {p.effectiveFrom}
              </div>
              <div style={{ color: 'var(--text-tertiary)', fontSize: 12 }}>
                {t('Navrhl', 'Proposed by')}: {p.proposedBy} · <span className="mono">{p.contentHash.slice(0, 16)}…</span>
              </div>
              <button type="button" className="btn btn-secondary btn-sm" onClick={event => openDetail(p, event)} style={{ marginTop: 6 }}>
                {t('Zobrazit detail', 'View details')}
              </button>
            </div>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <Can permission="lending:compliance:decide" fallback={<span style={{ color: 'var(--text-tertiary)', fontSize: 12 }}>{t('Rozhodnutí je pouze pro compliance principály.', 'Decisions are limited to compliance principals.')}</span>}>
              <input
                className="input"
                type="text"
                aria-label={t('Důvod rozhodnutí', 'Decision reason')}
                placeholder={t('Důvod (volitelný)', 'Reason (optional)')}
                value={reasons[p.id] ?? ''}
                onChange={e => setReasons(r => ({ ...r, [p.id]: e.target.value }))}
                style={{ fontSize: 12 }}
              />
              <button type="button" className="btn btn-primary" disabled={busyId === p.id} onClick={() => { setError(null); setReview({ proposal: p, approve: true }) }} style={{ fontSize: 12 }}>
                {t('Schválit', 'Approve')}
              </button>
              <button type="button" className="btn btn-secondary" disabled={busyId === p.id} onClick={() => { setError(null); setReview({ proposal: p, approve: false }) }} style={{ fontSize: 12 }}>
                {t('Zamítnout', 'Reject')}
              </button>
              </Can>
            </div>
          </div>
        ))}
        {pending.length === 0 && (
          <div data-testid="no-pending" style={{ padding: 20, textAlign: 'center', color: 'var(--text-tertiary)', fontSize: 13 }}>
            {sources.pending === 'ok' ? t('Nic nečeká.', 'Nothing pending.') : t('Nenačteno.', 'Not loaded.')}
          </div>
        )}
      </div>

      {review && (
        <div
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="pack-decision-review-title"
          aria-describedby="pack-decision-review-impact"
          onKeyDown={event => {
            if (event.key === 'Escape' && busyId !== review.proposal.id) {
              setReview(null)
              setError(null)
            }
            if (event.key === 'Tab') {
              const first = reviewCancelRef.current
              const last = reviewConfirmRef.current
              if (event.shiftKey && document.activeElement === first) {
                event.preventDefault()
                last?.focus()
              } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault()
                first?.focus()
              }
            }
          }}
          style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.68)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20 }}
        >
          <div className="card" style={{ width: 'min(620px, 100%)', maxHeight: '90vh', overflowY: 'auto', padding: 22 }}>
            <h2 id="pack-decision-review-title" style={{ margin: 0, fontSize: 17 }}>
              {review.approve ? t('Zkontrolovat aktivaci packu', 'Review pack activation') : t('Zkontrolovat zamítnutí packu', 'Review pack rejection')}
            </h2>
            <p id="pack-decision-review-impact" style={{ color: 'var(--text-secondary)', fontSize: 13, lineHeight: 1.55 }}>
              {review.approve
                ? t('Po potvrzení začne tento pack okamžitě řídit nové úvěrové žádosti. Není potřeba restart ani release služby.', 'Once confirmed, this pack immediately governs new lending applications. No service restart or release is required.')
                : t('Po potvrzení bude návrh definitivně zamítnut a nebude ovlivňovat nové úvěrové žádosti.', 'Once confirmed, the proposal is rejected and will not affect new lending applications.')}
            </p>
            <dl style={{ display: 'grid', gridTemplateColumns: '150px minmax(0, 1fr)', gap: '8px 12px', padding: 14, borderRadius: 8, background: 'var(--surface-2)', fontSize: 12 }}>
              <dt>{t('Jurisdikce', 'Jurisdiction')}</dt><dd>{review.proposal.jurisdiction}</dd>
              <dt>{t('Produkt', 'Product')}</dt><dd>{review.proposal.productType}</dd>
              <dt>{t('Verze', 'Version')}</dt><dd>v{review.proposal.packVersion}</dd>
              <dt>{t('Účinnost od', 'Effective from')}</dt><dd>{review.proposal.effectiveFrom}</dd>
              <dt>{t('Navrhl', 'Proposed by')}</dt><dd>{review.proposal.proposedBy}</dd>
              <dt>{t('Důvod rozhodnutí', 'Decision reason')}</dt><dd>{reasons[review.proposal.id]?.trim() || t('Neuveden', 'Not provided')}</dd>
              <dt>{t('Otisk obsahu', 'Content hash')}</dt><dd className="mono" style={{ overflowWrap: 'anywhere' }}>{review.proposal.contentHash}</dd>
              <dt>{t('ID návrhu', 'Proposal ID')}</dt><dd className="mono" style={{ overflowWrap: 'anywhere' }}>{review.proposal.id}</dd>
            </dl>
            <p style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
              {t('Four-eyes kontrolu vynucuje lending-service: checker musí být jiný principál než maker.', 'The lending service enforces four-eyes: the checker must be a different principal from the maker.')}
            </p>
            {error && <div data-testid="decision-review-error" style={{ padding: 10, borderLeft: '3px solid var(--danger)', color: 'var(--danger)', fontSize: 12 }}>{error}</div>}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
              <button ref={reviewCancelRef} autoFocus type="button" className="btn btn-secondary" disabled={busyId === review.proposal.id} onClick={() => { setReview(null); setError(null) }}>
                {t('Zpět', 'Back')}
              </button>
              <button ref={reviewConfirmRef} type="button" className={review.approve ? 'btn btn-primary' : 'btn btn-secondary'} aria-busy={busyId === review.proposal.id} disabled={busyId === review.proposal.id} onClick={() => void decide(review.proposal.id, review.approve)}>
                {busyId === review.proposal.id ? t('Odesílám…', 'Submitting…') : review.approve ? t('Potvrdit aktivaci', 'Confirm activation') : t('Potvrdit zamítnutí', 'Confirm rejection')}
              </button>
            </div>
          </div>
        </div>
      )}

      <Dialog.Root open={detail !== null} onOpenChange={open => { if (!open) setDetail(null) }}>
      {detail && <Dialog.Portal>
        <Dialog.Overlay style={{ position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(0,0,0,.45)' }} />
        <Dialog.Content
          className="card"
          aria-modal="true"
          onCloseAutoFocus={event => {
            event.preventDefault()
            if (detailTriggerRef.current?.isConnected) detailTriggerRef.current.focus()
          }}
          style={{ position: 'fixed', zIndex: 1001, top: '50%', left: '50%', transform: 'translate(-50%, -50%)', width: 'calc(100% - 48px)', maxWidth: 820, maxHeight: '88vh', overflow: 'auto', padding: 20 }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}><div><Dialog.Title style={{ margin: 0 }}>{detail.jurisdiction} / {detail.productType} · v{detail.packVersion}</Dialog.Title><Dialog.Description className="mono" style={{ fontSize: 11, color: 'var(--text-tertiary)', marginTop: 4 }}>{detail.contentHash}</Dialog.Description></div><Dialog.Close asChild><button type="button" className="btn btn-secondary">{t('Zavřít', 'Close')}</button></Dialog.Close></div>
          <dl style={{ display: 'grid', gridTemplateColumns: '180px 1fr', gap: '8px 12px', fontSize: 12, margin: '18px 0' }}>
            <dt>{t('Navrhl', 'Proposed by')}</dt><dd>{detail.proposedBy || '—'} · {detail.proposedAt || '—'}</dd>
            <dt>{t('Rozhodl', 'Decided by')}</dt><dd>{detail.decidedBy || '—'} · {detail.decidedAt || '—'}</dd>
            <dt>{t('Důvod rozhodnutí', 'Decision reason')}</dt><dd>{detail.decisionReason || '—'}</dd>
          </dl>
          <h3 className="section-title">{t('Přesný obsah packu', 'Exact pack content')}</h3>
          <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', padding: 14, borderRadius: 8, background: 'var(--surface-2)', fontSize: 11 }}>{JSON.stringify(detail.pack, null, 2)}</pre>
        </Dialog.Content>
      </Dialog.Portal>}
      </Dialog.Root>

      <Can permission="lending:compliance:propose">
      <div className="section-title" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <ScrollText aria-hidden="true" size={15} /> {t('Navrhnout pack (maker)', 'Propose a pack (maker)')}
      </div>
      <div className="card" style={{ padding: 14 }}>
        <p style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 0 }}>
          {t(
            'Vložte JSON packu — referenční CZ pack je openbank-lending-service/src/main/resources/compliance-packs/cz-consumer-credit-v1.json. Váš návrh nemá žádný efekt, dokud ho neschválí někdo jiný.',
            'Paste the pack JSON — the CZ reference pack is openbank-lending-service/src/main/resources/compliance-packs/cz-consumer-credit-v1.json. Your proposal has no effect until someone else approves it.',
          )}
        </p>
        <textarea
          aria-label={t('JSON packu', 'Pack JSON')}
          rows={10}
          value={packJson}
          onChange={e => setPackJson(e.target.value)}
          style={{ width: '100%', fontFamily: 'monospace', fontSize: 12, padding: 8, borderRadius: 6, border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text)' }}
        />
        <button
          type="button"
          className="btn btn-primary"
          style={{ marginTop: 8, fontSize: 12 }}
          disabled={busyId === 'propose' || packJson.trim().length === 0}
          onClick={() => void propose()}
        >
          {t('Navrhnout', 'Propose')}
        </button>
      </div>
      </Can>
    </div>
    </AuthGuard>
  )
}
