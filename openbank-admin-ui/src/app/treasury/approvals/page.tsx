// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury approval inbox (ROLE_TREASURY_APPROVER, ADR-0315): every PENDING_APPROVAL deal, with
// approve and reject (reason required).
//
// FOUR-EYES: approve is hidden on a deal the viewer created or submitted, compared against the
// token's principal name — the claim treasury records as createdBy/submittedBy, and the same
// comparison the ledger backfill uses. That is a courtesy, not the control: the service answers
// 422 FOUR_EYES_VIOLATION on a self-approval (and LIMIT_BREACHED when the limit no longer holds)
// whatever the UI shows, and the page renders either refusal readably.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { Inbox, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader } from '@/components/ui'
import { dealActionUrl, getJson, postJson, treasuryUrl } from '@/components/treasury/api'
import { counterpartyListSchema, dealListSchema, dealSchema, type Counterparty, type Deal } from '@/components/treasury/contracts'
import { dealActions, distinctCounterparties, productLabel, refusalText } from '@/components/treasury/model'
import { SyntheticBadge } from '@/components/treasury/SyntheticBadge'
import { principalNameFromToken } from '@/components/balance-sheet/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const PAGE_SIZE = 25

export default function TreasuryApprovalsPage() {
  return (
    <AuthGuard permission="treasury:deal:approve">
      <ApprovalInbox />
    </AuthGuard>
  )
}

type Notice = { tone: 'success' | 'danger'; text: string }

function ApprovalInbox() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const actor = principalNameFromToken(session?.user?.accessToken)
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  const [deals, setDeals] = useState<Deal[] | null>(null)
  const [unavailable, setUnavailable] = useState<{ kind: UnavailableKind } | null>(null)
  const [counterparties, setCounterparties] = useState<Counterparty[]>([])
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [shown, setShown] = useState(PAGE_SIZE)

  const load = useCallback(async () => {
    const [res, cps] = await Promise.all([
      getJson(treasuryUrl('/deals', { state: 'PENDING_APPROVAL' }), dealListSchema),
      getJson(treasuryUrl('/counterparties'), counterpartyListSchema),
    ])
    if (cps.ok) setCounterparties(distinctCounterparties(cps.data))
    if (!res.ok) { setUnavailable({ kind: res.kind }); setDeals(null); return }
    setUnavailable(null)
    setDeals(res.data)
  }, [])

  useEffect(() => { void load() }, [load])

  const byId = useMemo(() => new Map(counterparties.map(c => [c.counterpartyId, c])), [counterparties])

  const decide = async (deal: Deal, approve: boolean) => {
    const reason = reasons[deal.dealId]?.trim() ?? ''
    if (!approve && !reason) return
    setBusy(true)
    setNotice(null)
    const res = await postJson(dealActionUrl(deal.dealId, approve ? 'approve' : 'reject'), approve ? undefined : { reason }, dealSchema)
    setBusy(false)
    setNotice(res.ok
      ? { tone: 'success', text: approve ? t('Obchod schválen a zaúčtován.', 'Deal approved and booked.') : t('Obchod zamítnut a vrácen dealerovi jako koncept.', 'Deal rejected and returned to the dealer as a draft.') }
      : { tone: 'danger', text: refusalText(res, approve ? t('Schválení', 'Approve') : t('Zamítnutí', 'Reject'), t) })
    void load()
  }

  return (
    <div>
      <PageHeader
        title={t('Obchody ke schválení', 'Approval inbox')}
        subtitle={t('Schválení zaúčtuje obchod do hlavní knihy. Vlastní obchody schválit nelze (pravidlo čtyř očí).', 'Approving books the deal into the ledger. You cannot approve your own deals (four-eyes).')}
        icon={<Inbox size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void load()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />

      <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 12 }}>
        {actor
          ? t(`Jednáte jako ${actor}. Treasury zaznamená tuto identitu jako schvalovatele.`, `Acting as ${actor}. Treasury records this identity as the approver.`)
          : t('Identitu se nepodařilo přečíst z relace; čtyři oči hlídá server.', 'Could not read your identity from the session; the server enforces four-eyes.')}
      </p>

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, borderColor: notice.tone === 'danger' ? 'var(--danger)' : 'var(--success)' }}>
          {notice.text}
        </div>
      )}

      <div className="card" style={{ overflowX: 'auto' }}>
        {unavailable ? (
          <DataUnavailable kind={unavailable.kind} service="treasury-service" feature={t('obchody ke schválení', 'deals awaiting approval')} lang={language} dense />
        ) : deals === null ? null : deals.length === 0 ? (
          <DataUnavailable kind="no_data" service="treasury-service" feature={t('obchody ke schválení', 'deals awaiting approval')} lang={language} dense />
        ) : (
          <>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Produkt', 'Product')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Protistrana', 'Counterparty')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Jistina', 'Principal')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Sazba % p.a.', 'Rate % p.a.')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Valuta / splatnost', 'Value / maturity')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Limit', 'Limit')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Vytvořil / předložil', 'Created / submitted by')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Rozhodnutí', 'Decision')}</th>
                </tr>
              </thead>
              <tbody>
                {deals.slice(0, shown).map(d => {
                  const a = dealActions(d, actor, roles)
                  const cp = byId.get(d.counterpartyId)
                  return (
                    <tr key={d.dealId}>
                      <td><Link href={`/treasury/deals/${encodeURIComponent(d.dealId)}`}>{productLabel(d.product, t)}</Link></td>
                      <td>{cp ? cp.name : d.counterpartyId} <SyntheticBadge synthetic={cp?.synthetic} /></td>
                      <td style={{ textAlign: 'right' }}>{`${money(d.principal)} ${d.currency}`}</td>
                      <td style={{ textAlign: 'right' }}>{d.rate.toLocaleString(locale, { maximumFractionDigits: 4 })}</td>
                      <td>{`${d.valueDate} → ${d.maturityDate}`}</td>
                      <td>
                        {d.limitCheck
                          ? (d.limitCheck.breached
                            ? <span style={{ color: 'var(--danger-text)' }}>{t('Překročen', 'Breached')}</span>
                            : `${t('volno', 'headroom')} ${money(d.limitCheck.headroomAfter)}`)
                          : '—'}
                      </td>
                      <td>{`${d.createdBy} / ${d.submittedBy ?? '—'}`}</td>
                      <td>
                        {a.ownDeal && (
                          <div style={{ fontSize: 12, marginBottom: 4 }}>{t('Váš obchod — schválit jej musí jiná osoba.', 'Your deal — a different person must approve it.')}</div>
                        )}
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                          {a.approve && (
                            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void decide(d, true)}>{t('Schválit', 'Approve')}</button>
                          )}
                          {a.reject && (
                            <>
                              <input
                                className="input"
                                value={reasons[d.dealId] ?? ''}
                                onChange={e => setReasons(prev => ({ ...prev, [d.dealId]: e.target.value }))}
                                placeholder={t('Důvod zamítnutí', 'Rejection reason')}
                                aria-label={t('Důvod zamítnutí', 'Rejection reason')}
                                style={{ width: 160 }}
                              />
                              <button type="button" className="btn btn-secondary btn-sm" disabled={busy || !(reasons[d.dealId] ?? '').trim()} onClick={() => void decide(d, false)}>{t('Zamítnout', 'Reject')}</button>
                            </>
                          )}
                        </div>
                      </td>
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
