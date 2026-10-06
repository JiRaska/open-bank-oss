// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One treasury deal (ADR-0315): every field, the ACT/360 interest, the limit check recorded at
// submit/approve, the lifecycle timeline with who acted (and as what kind of actor), and the
// ledger journals the booking posted. Action buttons follow role and state (dealActions); the
// approve button is withheld from the deal's own creator/submitter, and a server refusal —
// FOUR_EYES_VIOLATION, LIMIT_BREACHED, PRODUCT_LIMIT_BREACHED (ADR-0315 D4, re-checked at approval),
// INVALID_STATE — is rendered readably, with the server's reason, if it happens anyway.

'use client'

import { use, useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { ArrowLeft, Landmark } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { HumanReference, LoadMoreControl, PageHeader, StatCard, StatusBadge } from '@/components/ui'
import { dealActionUrl, getJson, postJson, treasuryUrl, type DealAction } from '@/components/treasury/api'
import { counterpartyListSchema, dealSchema, type Counterparty, type Deal } from '@/components/treasury/contracts'
import { dealActions, distinctCounterparties, productLabel, refusalText, STATE_TONE, stateLabel } from '@/components/treasury/model'
import { SyntheticBadge } from '@/components/treasury/SyntheticBadge'
import { principalNameFromToken } from '@/components/balance-sheet/model'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { parseLedgerJournalEntry, type LedgerJournalEntry } from '@/lib/ledger/ledgerJournalContract'
import { formatMoneyExact } from '@/lib/format/money'
import { actorDisplay, actorTypeLabel, daysText, limitSnapshotText, postingRow, type PostingAccount } from '@/components/treasury/presentation'

const POSTINGS_PAGE = 25

/** One ledger journal's lines; undefined when the ledger is not reachable — the row then shows
 *  the event and the time only, never an error (graceful-state rule). */
async function loadLedgerEntry(journalId: string): Promise<LedgerJournalEntry | undefined> {
  try {
    const res = await fetch(svcUrl('ledger-service', `/api/v1/journals/${encodeURIComponent(journalId)}`), { cache: 'no-store' })
    if (!res.ok) return undefined
    return parseLedgerJournalEntry(await res.json())
  } catch {
    return undefined
  }
}

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
  const [ledger, setLedger] = useState<Record<string, LedgerJournalEntry | null>>({})
  const [postingsShown, setPostingsShown] = useState(POSTINGS_PAGE)

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

  // Ledger detail for the visible postings only (bounded, rule #2); null marks "tried, unavailable".
  const visibleJournalIds = useMemo(
    () => (deal?.journals ?? []).slice(0, postingsShown).map(j => j.journalId),
    [deal?.journals, postingsShown],
  )
  useEffect(() => {
    const missing = visibleJournalIds.filter(jid => !(jid in ledger))
    if (missing.length === 0) return
    let cancelled = false
    void Promise.all(missing.map(async jid => [jid, (await loadLedgerEntry(jid)) ?? null] as const)).then(pairs => {
      if (!cancelled) setLedger(prev => ({ ...prev, ...Object.fromEntries(pairs) }))
    })
    return () => { cancelled = true }
  }, [visibleJournalIds, ledger])

  const labels: Record<DealAction, string> = {
    submit: t('Předložit', 'Submit'), approve: t('Schválit', 'Approve'), reject: t('Zamítnout', 'Reject'),
    cancel: t('Zrušit obchod', 'Cancel deal'), confirm: t('Potvrdit protistranou', 'Record confirmation'), settle: t('Vypořádat', 'Settle'), mature: t('Ukončit ke splatnosti', 'Mature'),
    reverse: t('Stornovat', 'Reverse'), 'override-limit': t('Překročit limit', 'Override limit'),
  }

  const act = async (action: DealAction) => {
    const needsReason = action === 'reject' || action === 'reverse' || action === 'override-limit'
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
  const plain: DealAction[] = (['submit', 'approve', 'confirm', 'settle', 'mature', 'cancel'] as const).filter(k => a[k])
  // override-limit needs a mandatory reason exactly like reject/reverse, so it shares the same
  // reason input and disabled-until-filled behaviour rather than a separate dialog.
  const withReason: DealAction[] = [
    ...(['reject', 'reverse'] as const).filter(k => a[k]),
    ...(a.overrideLimit ? (['override-limit'] as const) : []),
  ]

  return (
    <div>
      <PageHeader
        title={`${productLabel(deal.product, t)} · ${deal.currency} ${money(deal.principal)}`}
        subtitle={deal.dealId}
        icon={<Landmark size={20} aria-hidden="true" />}
        actions={back}
      />

      {deal.product === 'CNB_LOMBARD' && (
        <p role="note" style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 16 }}>
          {t('Zástava není v systému evidována.', 'The collateral pledge is not modelled in this system.')}
        </p>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, marginBottom: 16 }}>
        <StatCard label={t('Stav', 'State')} value={stateLabel(deal.state, t)} tone={STATE_TONE[deal.state] === 'danger' ? 'danger' : undefined} />
        <StatCard label={t('Úrok', 'Interest')} value={`${money(deal.interest)} ${deal.currency}`} />
        <StatCard label={t('Konvence dní', 'Day count')} value={`${deal.dayCount} · ${daysText(deal.days, t)}`} />
        <StatCard label={t('Sazba % p.a.', 'Rate % p.a.')} value={deal.rate.toLocaleString(locale, { maximumFractionDigits: 4 })} />
      </div>

      <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <tbody>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Protistrana', 'Counterparty')}</th><td>{counterparty ? `${counterparty.name} (${deal.counterpartyId})` : deal.counterpartyId} <SyntheticBadge synthetic={counterparty?.synthetic} /></td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Datum obchodu', 'Trade date')}</th><td>{deal.tradeDate}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Datum valuty', 'Value date')}</th><td>{deal.valueDate}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Datum splatnosti', 'Maturity date')}</th><td>{deal.maturityDate}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Vytvořil', 'Created by')}</th><td>{deal.createdByType === 'HUMAN' ? deal.createdBy : `${actorDisplay(deal.createdBy, deal.createdByType, t)} (${actorTypeLabel(deal.createdByType, deal.createdBy, t)})`}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Předložil', 'Submitted by')}</th><td>{deal.submittedBy ?? '—'}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Schválil', 'Approved by')}</th><td>{deal.approvedBy ?? '—'}</td></tr>
            <tr><th scope="row" style={{ textAlign: 'left' }}>{t('Zdůvodnění', 'Rationale')}</th><td>{deal.rationale ?? '—'}</td></tr>
          </tbody>
        </table>
      </div>

      {deal.fx && (
        <div className="card" style={{ marginBottom: 16 }} data-testid="fx-terms">
          <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Podmínky FX spotu', 'FX spot terms')}</h2>
          <div style={{ fontSize: 13, display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
            <StatusBadge status={deal.fx.side} tone={deal.fx.side === 'BUY' ? 'info' : 'warning'} label={deal.fx.side} />
            <span>{`${deal.fx.buyCurrency}/${deal.fx.sellCurrency}`}</span>
            <span>{`${t('Kupuje', 'Buys')} ${money(deal.fx.buyAmount)} ${deal.fx.buyCurrency}`}</span>
            <span>{`${t('Prodává', 'Sells')} ${money(deal.fx.sellAmount)} ${deal.fx.sellCurrency}`}</span>
            <span>{`${t('Kurz obchodu', 'Deal rate')} ${deal.fx.dealRate.toLocaleString(locale, { maximumFractionDigits: 4 })}`}</span>
            {deal.fx.midRate !== null && (
              <span>{`${t('Střední kurz fx-service', 'fx-service mid')} ${deal.fx.midRate.toLocaleString(locale, { maximumFractionDigits: 4 })}`}</span>
            )}
          </div>
          {deal.fx.rateFlag && (
            <div role="alert" style={{ color: 'var(--danger-text)', marginTop: 8 }}>
              {t(`Kurz označen: ${deal.fx.rateFlag}`, `Rate flagged: ${deal.fx.rateFlag}`)}
            </div>
          )}
        </div>
      )}

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
        {deal.limitOverride && (
          <p style={{ fontSize: 13, marginTop: 8 }}>
            {t(
              `Limit překročen senior schvalovatelem ${deal.limitOverride.by} — pokrývá expozici do ${money(deal.limitOverride.coversExposureUpTo)}. Důvod: ${deal.limitOverride.reason}`,
              `Limit overridden by senior approver ${deal.limitOverride.by} — covers exposure up to ${money(deal.limitOverride.coversExposureUpTo)}. Reason: ${deal.limitOverride.reason}`,
            )}
          </p>
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
                <input className="input" value={reason} onChange={e => setReason(e.target.value)} placeholder={t('Důvod (povinný)', 'Reason (required)')} aria-label={t('Důvod zamítnutí, storna nebo překročení limitu', 'Reason for rejection, reversal or limit override')} style={{ width: 220 }} />
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
              <th scope="col" style={{ textAlign: 'left' }}>{t('Poznámka', 'Note')}</th>
            </tr>
          </thead>
          <tbody>
            {deal.history.map((h, i) => (
              <tr key={`${h.at}-${i}`}>
                <td>{new Date(h.at).toLocaleString(locale)}</td>
                <td>{`${h.from ? stateLabel(h.from, t) : '—'} → ${stateLabel(h.to, t)}`}</td>
                <td>
                  <div>{actorDisplay(h.actor, h.actorType, t)}</div>
                  {h.actorType !== 'SYSTEM' && (
                    <div style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{actorTypeLabel(h.actorType, h.actor, t)}</div>
                  )}
                </td>
                <td>{h.limitSnapshot ? limitSnapshotText(h.limitSnapshot, t, locale) : (h.note ?? '—')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card" style={{ overflowX: 'auto' }}>
        <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Zaúčtování v hlavní knize', 'Ledger postings')}</h2>
        {deal.journals.length === 0 ? (
          <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Zatím nic nezaúčtováno — zápisy vznikají při vypořádání a dalších krocích.', 'Nothing posted yet — journals are posted at settlement and later steps.')}</p>
        ) : (
          <>
            <table id="treasury-postings" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Událost', 'Event')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Částka', 'Amount')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Má dáti', 'Debit')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Dal', 'Credit')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Zaúčtováno', 'Posted')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Zápis', 'Journal')}</th>
                </tr>
              </thead>
              <tbody>
                {deal.journals.slice(0, postingsShown).map(j => {
                  const entry = ledger[j.journalId]
                  const row = postingRow(j, entry ?? undefined, t, locale)
                  const accounts = (list: PostingAccount[]) => list.length === 0
                    ? <span style={{ color: 'var(--text-secondary)' }}>{entry === null ? t('detail nedostupný', 'detail unavailable') : '…'}</span>
                    : list.map((acc, i) => (
                      <div key={i}>
                        {acc.name}{acc.code && <span className="mono" style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{` ${acc.code}`}</span>}
                        {list.length > 1 && <span style={{ color: 'var(--text-secondary)' }}>{` · ${formatMoneyExact(acc.amount, acc.currency, locale)}`}</span>}
                      </div>
                    ))
                  return (
                    <tr key={row.key}>
                      <td style={{ fontWeight: 500 }}>{row.label}</td>
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>{row.totals.length ? row.totals.map(x => formatMoneyExact(x.amount, x.currency, locale)).join(' + ') : '—'}</td>
                      <td>{accounts(row.debits)}</td>
                      <td>{accounts(row.credits)}</td>
                      <td style={{ whiteSpace: 'nowrap' }}>{new Date(row.postedAt).toLocaleString(locale)}</td>
                      <td>
                        <HumanReference
                          label={row.entryNumber !== null ? t(`č. ${row.entryNumber}`, `No. ${row.entryNumber}`) : t('Zápis', 'Journal')}
                          reference={row.journalId}
                          copyLabel={t('Kopírovat ID zápisu', 'Copy journal ID')}
                        />
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {deal.journals.length > POSTINGS_PAGE && (
              <LoadMoreControl
                loaded={Math.min(postingsShown, deal.journals.length)}
                total={deal.journals.length}
                progressLabel={t(`Zobrazeno ${Math.min(postingsShown, deal.journals.length)} z ${deal.journals.length}`, `Showing ${Math.min(postingsShown, deal.journals.length)} of ${deal.journals.length}`)}
                buttonLabel={t('Načíst další', 'Load more')}
                buttonAriaLabel={t('Načíst další zaúčtování', 'Load more postings')}
                controls="treasury-postings"
                onLoadMore={() => setPostingsShown(n => n + POSTINGS_PAGE)}
              />
            )}
            <details style={{ marginTop: 12, fontSize: 12 }}>
              <summary style={{ cursor: 'pointer', color: 'var(--text-secondary)' }}>{t('Technické detaily', 'Technical details')}</summary>
              <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: 8 }}>
                <thead>
                  <tr>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('Idempotenční klíč', 'Idempotency key')}</th>
                    <th scope="col" style={{ textAlign: 'left' }}>{t('ID zápisu', 'Journal ID')}</th>
                  </tr>
                </thead>
                <tbody>
                  {deal.journals.slice(0, postingsShown).map(j => (
                    <tr key={j.idempotencyKey}><td><code>{j.idempotencyKey}</code></td><td><code>{j.journalId}</code></td></tr>
                  ))}
                </tbody>
              </table>
            </details>
          </>
        )}
      </div>
    </div>
  )
}
