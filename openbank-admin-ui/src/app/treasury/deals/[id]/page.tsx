// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One treasury deal (ADR-0315): every field, the ACT/360 interest, the limit check recorded at
// submit/approve, the lifecycle timeline with who acted (and as what kind of actor), and the
// ledger journals the booking posted. Action buttons follow role and state (dealActions); the
// approve button is withheld from the deal's own creator/submitter, and a server refusal —
// FOUR_EYES_VIOLATION, LIMIT_BREACHED, INVALID_STATE — is rendered readably if it happens anyway.

'use client'

import { use, useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { ArrowLeft, Landmark } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import { dealActionUrl, getJson, postJson, treasuryUrl, type DealAction } from '@/components/treasury/api'
import { counterpartyListSchema, dealSchema, type Counterparty, type Deal } from '@/components/treasury/contracts'
import { dealActions, distinctCounterparties, productLabel, refusalText, STATE_TONE, stateLabel } from '@/components/treasury/model'
import { SyntheticBadge } from '@/components/treasury/SyntheticBadge'
import { principalNameFromToken } from '@/components/balance-sheet/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export default function TreasuryDealDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params)
  return (
    <AuthGuard permission="treasury:view">
      <DealDetail id={id} />
    </AuthGuard>
  )
}

type Notice = { tone: 'success' | 'danger'; text: string }

function DealDetail({ id }: { id: string }) {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const actor = principalNameFromToken(session?.user?.accessToken)
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  const [deal, setDeal] = useState<Deal | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [counterparty, setCounterparty] = useState<Counterparty | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const load = useCallback(async () => {
    const [res, cps] = await Promise.all([
      getJson(treasuryUrl(`/deals/${encodeURIComponent(id)}`), dealSchema),
      getJson(treasuryUrl('/counterparties'), counterpartyListSchema),
    ])
    if (!res.ok) { setUnavailable({ kind: res.kind }); return }
    setUnavailable(null)
    setDeal(res.data)
    if (cps.ok) setCounterparty(distinctCounterparties(cps.data).find(c => c.counterpartyId === res.data.counterpartyId) ?? null)
  }, [id])

  useEffect(() => { void load() }, [load])

  const labels: Record<DealAction, string> = {
    submit: t('Předložit', 'Submit'), approve: t('Schválit', 'Approve'), reject: t('Zamítnout', 'Reject'),
    cancel: t('Zrušit obchod', 'Cancel deal'), settle: t('Vypořádat', 'Settle'), mature: t('Ukončit ke splatnosti', 'Mature'),
    reverse: t('Stornovat', 'Reverse'),
  }

  const act = async (action: DealAction) => {
    const needsReason = action === 'reject' || action === 'reverse'
    if (needsReason && !reason.trim()) return
    setBusy(true)
    setNotice(null)
    const res = await postJson(dealActionUrl(id, action), needsReason ? { reason: reason.trim() } : undefined, dealSchema)
    setBusy(false)
    if (res.ok) {
      setDeal(res.data)
      setReason('')
      setNotice({ tone: 'success', text: t(`${labels[action]}: hotovo — obchod je nyní ve stavu ${stateLabel(res.data.state, t)}.`, `${labels[action]}: done — the deal is now ${stateLabel(res.data.state, t)}.`) })
      void load()
    } else {
      setNotice({ tone: 'danger', text: refusalText(res, labels[action], t) })
    }
  }

  const back = (
    <Link href="/treasury/deals" className="btn btn-secondary btn-sm">
      <ArrowLeft size={14} aria-hidden="true" /> {t('Zpět na obchody', 'Back to deals')}
    </Link>
  )

  if (unavailable) {
    return (
      <div>
        <PageHeader title={t('Obchod treasury', 'Treasury deal')} icon={<Landmark size={20} aria-hidden="true" />} actions={back} />
        <DataUnavailable kind={unavailable.kind} service="treasury-service" feature={t('obchod treasury', 'treasury deal')} lang={language} />
      </div>
    )
  }
  if (!deal) return null

  const a = dealActions(deal, actor, roles)
  const plain: DealAction[] = (['submit', 'approve', 'settle', 'mature', 'cancel'] as const).filter(k => a[k])
  const withReason: DealAction[] = (['reject', 'reverse'] as const).filter(k => a[k])

  return (
    <div>
      <PageHeader
        title={`${productLabel(deal.product, t)} · ${deal.currency} ${money(deal.principal)}`}
        subtitle={deal.dealId}
        icon={<Landmark size={20} aria-hidden="true" />}
        actions={back}
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, marginBottom: 16 }}>
        <StatCard label={t('Stav', 'State')} value={stateLabel(deal.state, t)} tone={STATE_TONE[deal.state] === 'danger' ? 'danger' : undefined} />
        <StatCard label={t('Úrok', 'Interest')} value={`${money(deal.interest)} ${deal.currency}`} />
        <StatCard label={t('Konvence dní', 'Day count')} value={`${deal.dayCount} · ${deal.days} ${t('dní', 'days')}`} />
        <StatCard label={t('Sazba % p.a.', 'Rate % p.a.')} value={deal.rate.toLocaleString(locale, { maximumFractionDigits: 4 })} />
      </div>

      <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <tbody>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Protistrana', 'Counterparty')}</th><td>{counterparty ? `${counterparty.name} (${deal.counterpartyId})` : deal.counterpartyId} <SyntheticBadge synthetic={counterparty?.synthetic} /></td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Datum obchodu', 'Trade date')}</th><td>{deal.tradeDate}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Datum valuty', 'Value date')}</th><td>{deal.valueDate}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Datum splatnosti', 'Maturity date')}</th><td>{deal.maturityDate}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Vytvořil', 'Created by')}</th><td>{`${deal.createdBy} (${deal.createdByType})`}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Předložil', 'Submitted by')}</th><td>{deal.submittedBy ?? '—'}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Schválil', 'Approved by')}</th><td>{deal.approvedBy ?? '—'}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Zdůvodnění', 'Rationale')}</th><td>{deal.rationale ?? '—'}</td></tr>
          </tbody>
        </table>
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Kontrola limitu', 'Limit check')}</h2>
        {deal.limitCheck ? (
          <div style={{ fontSize: 13, display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
            <StatusBadge status={deal.limitCheck.breached ? 'BREACHED' : 'OK'} tone={deal.limitCheck.breached ? 'danger' : 'success'} label={deal.limitCheck.breached ? t('Překročen', 'Breached') : t('V limitu', 'Within limit')} />
            <span>{`${t('Limit', 'Limit')} ${money(deal.limitCheck.limit)} ${deal.limitCheck.currency}`}</span>
            <span>{`${t('Expozice před', 'Exposure before')} ${money(deal.limitCheck.exposureBefore)}`}</span>
            <span>{`${t('Expozice po', 'Exposure after')} ${money(deal.limitCheck.exposureAfter)}`}</span>
            <span>{`${t('Volný limit po', 'Headroom after')} ${money(deal.limitCheck.headroomAfter)}`}</span>
          </div>
        ) : (
          <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Limit se kontroluje při předložení a schválení.', 'The limit is checked at submit and at approval.')}</p>
        )}
      </div>

      {(plain.length > 0 || withReason.length > 0 || a.ownDeal) && (
        <div className="card" style={{ marginBottom: 16 }}>
          <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Akce', 'Actions')}</h2>
          {a.ownDeal && (
            <p style={{ fontSize: 12, marginBottom: 8 }}>{t('Tento obchod jste vytvořili nebo předložili — schválit jej musí jiná osoba.', 'You created or submitted this deal — a different person must approve it.')}</p>
          )}
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            {plain.map(k => (
              <button key={k} type="button" className={k === 'cancel' ? 'btn btn-secondary btn-sm' : 'btn btn-primary btn-sm'} disabled={busy} onClick={() => void act(k)}>{labels[k]}</button>
            ))}
            {withReason.length > 0 && (
              <>
                <input className="input" value={reason} onChange={e => setReason(e.target.value)} placeholder={t('Důvod (povinný)', 'Reason (required)')} aria-label={t('Důvod zamítnutí nebo storna', 'Reason for rejection or reversal')} style={{ width: 220 }} />
                {withReason.map(k => (
                  <button key={k} type="button" className="btn btn-secondary btn-sm" disabled={busy || !reason.trim()} onClick={() => void act(k)}>{labels[k]}</button>
                ))}
              </>
            )}
          </div>
        </div>
      )}

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, borderColor: notice.tone === 'danger' ? 'var(--danger)' : 'var(--success)' }}>
          {notice.text}
        </div>
      )}

      <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
        <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Průběh obchodu', 'Lifecycle timeline')}</h2>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr>
              <th scope="col" style={{ textAlign: 'left' }}>{t('Čas', 'When')}</th>
              <th scope="col" style={{ textAlign: 'left' }}>{t('Přechod', 'Transition')}</th>
              <th scope="col" style={{ textAlign: 'left' }}>{t('Kdo', 'Actor')}</th>
              <th scope="col" style={{ textAlign: 'left' }}>{t('Typ aktéra', 'Actor type')}</th>
              <th scope="col" style={{ textAlign: 'left' }}>{t('Poznámka', 'Note')}</th>
            </tr>
          </thead>
          <tbody>
            {deal.history.map((h, i) => (
              <tr key={`${h.at}-${i}`}>
                <td>{new Date(h.at).toLocaleString(locale)}</td>
                <td>{`${h.from ? stateLabel(h.from, t) : '—'} → ${stateLabel(h.to, t)}`}</td>
                <td>{h.actor}</td>
                <td>{h.actorType}</td>
                <td>{h.note ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card" style={{ overflowX: 'auto' }}>
        <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Účetní zápisy v hlavní knize', 'Ledger journals')}</h2>
        {deal.journals.length === 0 ? (
          <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Zatím nic nezaúčtováno — zápisy vznikají při schválení a dalších krocích.', 'Nothing posted yet — journals are posted at approval and later steps.')}</p>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Událost', 'Event')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Idempotenční klíč', 'Idempotency key')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('ID zápisu', 'Journal ID')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Zaúčtováno', 'Posted')}</th>
              </tr>
            </thead>
            <tbody>
              {deal.journals.map(j => (
                <tr key={j.idempotencyKey}>
                  <td>{j.event}</td>
                  <td><code>{j.idempotencyKey}</code></td>
                  <td><code>{j.journalId}</code></td>
                  <td>{new Date(j.postedAt).toLocaleString(locale)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
