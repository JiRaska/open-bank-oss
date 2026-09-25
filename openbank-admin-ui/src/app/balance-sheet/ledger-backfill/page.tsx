// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Lending ledger backfill — the finance department's four-eyes flow (#10746, console #10618).
//
// Replaces the curl runbook (lending docs/05-operations): dry-run -> propose (maker) -> approve or
// reject (checker, a DIFFERENT person) -> execute with explicit confirmation. Every call goes
// through the BFF with the signed-in person's own token, so lending records the logged-in humans
// as maker, checker and executor.
//
// FOUR-EYES: the page hides approve from the proposer (compared against the token's principal
// name — the same claim lending records), but that is a courtesy, not the control. The control is
// LedgerBackfillService, which answers 422 on a self-approval whatever the UI shows; the page
// renders that refusal verbatim instead of pretending it cannot happen.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSession } from 'next-auth/react'
import { BookOpen, RefreshCw } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import { backfillUrl, getJson, sendJson, type WriteResult } from '@/components/balance-sheet/api'
import {
  backfillExecutionSchema, backfillPlanSchema, backfillRequestListSchema, backfillRequestSchema,
  type BackfillExecution, type BackfillPlan, type BackfillRequest,
} from '@/components/balance-sheet/contracts'
import { backfillActions, bankToday, isIsoDate, principalNameFromToken } from '@/components/balance-sheet/model'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const HISTORY_LIMIT = 25
const STATE_TONE: Record<BackfillRequest['state'], Tone> = {
  PROPOSED: 'warning', APPROVED: 'info', REJECTED: 'danger', WITHDRAWN: 'neutral', EXECUTED: 'success',
}

export default function LedgerBackfillPage() {
  return (
    <AuthGuard permission="ledger-backfill:view">
      <LedgerBackfill />
    </AuthGuard>
  )
}

type Notice = { tone: 'success' | 'danger'; text: string }

function LedgerBackfill() {
  const { t, language } = useLanguage()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const actor = principalNameFromToken(session?.user?.accessToken)
  const perms = {
    propose: hasPermission(roles, 'ledger-backfill:propose'),
    decide: hasPermission(roles, 'ledger-backfill:decide'),
    execute: hasPermission(roles, 'ledger-backfill:execute'),
  }
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  const [cutoverDate, setCutoverDate] = useState(bankToday())
  const [disbursedBefore, setDisbursedBefore] = useState('')
  const [plan, setPlan] = useState<BackfillPlan | null>(null)
  const [planKind, setPlanKind] = useState<UnavailableKind | null>(null)
  const [requests, setRequests] = useState<BackfillRequest[] | null>(null)
  const [historyKind, setHistoryKind] = useState<UnavailableKind | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const [confirming, setConfirming] = useState<string | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  const [execution, setExecution] = useState<BackfillExecution | null>(null)

  const loadHistory = useCallback(async () => {
    const res = await getJson(backfillUrl('/requests', { limit: String(HISTORY_LIMIT) }), backfillRequestListSchema)
    if (!res.ok) { setHistoryKind(res.kind); setRequests(null); return }
    setHistoryKind(null)
    setRequests(res.data.requests)
  }, [])

  useEffect(() => { void loadHistory() }, [loadHistory])

  const refusal = (res: WriteResult<unknown>, action: string): Notice => {
    if (res.ok) return { tone: 'success', text: '' }
    if (res.kind === 'forbidden') {
      return { tone: 'danger', text: t(`${action}: nemáte oprávnění (ROLE_FINANCE nebo ROLE_ADMIN, pouze lidé).`, `${action}: not permitted (ROLE_FINANCE or ROLE_ADMIN, humans only).`) }
    }
    if (res.kind === 'refused' && res.status === 422) {
      return { tone: 'danger', text: t(`${action}: server odmítl podle pravidla čtyř očí — ${res.message ?? ''}`, `${action}: the server refused under the four-eyes rule — ${res.message ?? ''}`) }
    }
    if (res.kind === 'refused') {
      return { tone: 'danger', text: t(`${action}: lending žádost odmítl — ${res.message ?? ''}`, `${action}: lending refused the request — ${res.message ?? ''}`) }
    }
    return { tone: 'danger', text: t(`${action}: lending-service je nedostupný.`, `${action}: lending-service is unavailable.`) }
  }

  const scopeValid = isIsoDate(cutoverDate) && isIsoDate(disbursedBefore)

  const dryRun = async () => {
    if (!scopeValid) return
    setBusy(true)
    setNotice(null)
    const res = await getJson(backfillUrl('/plan', { cutoverDate, disbursedBefore }), backfillPlanSchema)
    setBusy(false)
    if (res.ok) { setPlan(res.data); setPlanKind(null) } else { setPlan(null); setPlanKind(res.kind) }
  }

  const propose = async () => {
    setBusy(true)
    const res = await sendJson(backfillUrl('/requests'), { cutoverDate, disbursedBefore }, backfillRequestSchema)
    setBusy(false)
    setNotice(res.ok
      ? { tone: 'success', text: t('Návrh zaznamenán. Schválit jej musí jiná osoba.', 'Proposal recorded. A different person must approve it.') }
      : refusal(res, t('Návrh', 'Propose')))
    void loadHistory()
  }

  const decide = async (id: string, approve: boolean) => {
    setBusy(true)
    const reason = reasons[id]?.trim() || null
    const res = await sendJson(backfillUrl(`/requests/${encodeURIComponent(id)}/decide`), { approve, reason }, backfillRequestSchema)
    setBusy(false)
    setNotice(res.ok
      ? { tone: 'success', text: approve ? t('Žádost schválena.', 'Request approved.') : t('Žádost zamítnuta.', 'Request rejected.') }
      : refusal(res, approve ? t('Schválení', 'Approve') : t('Zamítnutí', 'Reject')))
    void loadHistory()
  }

  const execute = async (id: string) => {
    setBusy(true)
    const res = await sendJson(backfillUrl(`/requests/${encodeURIComponent(id)}/execute`, { execute: 'true' }), undefined, backfillExecutionSchema)
    setBusy(false)
    setConfirming(null)
    setAcknowledged(false)
    if (res.ok) {
      setExecution(res.data)
      setNotice(res.data.execution.complete
        ? { tone: 'success', text: t('Doúčtování dokončeno — všechny úvěry zaúčtovány.', 'Backfill complete — every loan posted.') }
        : { tone: 'danger', text: t('Doúčtování neúplné: některé úvěry selhaly. Žádost zůstává schválená; po odstranění příčiny spusťte znovu.', 'Backfill incomplete: some loans failed. The request stays approved; fix the cause and run it again.') })
    } else {
      setNotice(refusal(res, t('Provedení', 'Execute')))
    }
    void loadHistory()
  }

  return (
    <div>
      <PageHeader
        title={t('Doúčtování úvěrů do hlavní knihy', 'Lending ledger backfill')}
        subtitle={t('Jednorázové zaúčtování historie úvěrů, která se nedostala do hlavní knihy — pravidlo čtyř očí (#10746).', 'One-off posting of loan GL history that never reached the ledger — four-eyes (#10746).')}
        icon={<BookOpen size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void loadHistory()} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />

      <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 12 }}>
        {actor
          ? t(`Jednáte jako ${actor}. Lending zaznamená tuto identitu jako navrhovatele, schvalovatele i provádějícího.`, `Acting as ${actor}. Lending records this identity as proposer, approver and executor.`)
          : t('Identitu se nepodařilo přečíst z relace; čtyři oči hlídá server.', 'Could not read your identity from the session; the server enforces four-eyes.')}
      </p>

      <div className="card" style={{ marginBottom: 16 }}>
        <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('1. Zkouška nanečisto', '1. Dry-run')}</h2>
        <div style={{ display: 'flex', gap: 12, alignItems: 'end', flexWrap: 'wrap' }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
            {t('Účetní den zaúčtování', 'Cut-over (accounting day)')}
            <input type="date" className="input" value={cutoverDate} min={bankToday()} onChange={e => setCutoverDate(e.target.value)} aria-label={t('Účetní den zaúčtování', 'Cut-over accounting day')} />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
            {t('Úvěry čerpané před', 'Loans disbursed before')}
            <input type="date" className="input" value={disbursedBefore} onChange={e => setDisbursedBefore(e.target.value)} aria-label={t('Úvěry čerpané před datem', 'Loans disbursed before date')} />
          </label>
          <button type="button" className="btn btn-secondary btn-sm" disabled={!scopeValid || busy} onClick={() => void dryRun()}>
            {t('Spočítat plán (nic nezapisuje)', 'Compute plan (writes nothing)')}
          </button>
        </div>

        {planKind && <div style={{ marginTop: 12 }}><DataUnavailable kind={planKind} service="lending-service" feature={t('plán doúčtování', 'backfill plan')} lang={language} dense /></div>}

        {plan && (
          <div style={{ marginTop: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, marginBottom: 12 }}>
              <StatCard label={t('Počet účetních zápisů', 'Journals')} value={plan.journalCount.toLocaleString(locale)} />
              <StatCard label={t('Úvěrů v rozsahu', 'Loans in scope')} value={plan.plan.loans.length.toLocaleString(locale)} />
              <StatCard
                label={t('Proveditelné', 'Executable')}
                value={plan.executable ? t('Ano', 'Yes') : t('Ne', 'No')}
                tone={plan.executable ? 'success' : 'danger'}
              />
            </div>
            <h3 style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>{t('Odsouhlasení (tie-out)', 'Tie-out')}</h3>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginBottom: 12 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Úvěrové pohledávky po zaúčtování', 'Loans receivable after')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Nesplacená jistina v lending', 'Lending unpaid principal')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Výsledek', 'Result')}</th>
                </tr>
              </thead>
              <tbody>
                {plan.plan.tieOut.map(row => (
                  <tr key={row.currency}>
                    <td>{row.currency}</td>
                    <td style={{ textAlign: 'right' }}>{money(row.loansReceivableAfter)}</td>
                    <td style={{ textAlign: 'right' }}>{money(row.lendingUnpaidPrincipal)}</td>
                    <td><StatusBadge status={row.ties ? 'PASS' : 'FAIL'} tone={row.ties ? 'success' : 'danger'} label={row.ties ? t('Sedí', 'Pass') : t('Nesedí', 'Fail')} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <h3 style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>{t('Pohyby na účtech hlavní knihy', 'GL totals')}</h3>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13, marginBottom: 12 }}>
              <thead>
                <tr>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Účet', 'Account')}</th>
                  <th scope="col" style={{ textAlign: 'left' }}>{t('Měna', 'Currency')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Má dáti', 'Debit')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Dal', 'Credit')}</th>
                  <th scope="col" style={{ textAlign: 'right' }}>{t('Netto', 'Net')}</th>
                </tr>
              </thead>
              <tbody>
                {plan.glTotals.map(g => (
                  <tr key={`${g.code}-${g.currency}`}>
                    <td>{g.code}</td>
                    <td>{g.currency}</td>
                    <td style={{ textAlign: 'right' }}>{money(g.debit)}</td>
                    <td style={{ textAlign: 'right' }}>{money(g.credit)}</td>
                    <td style={{ textAlign: 'right' }}>{money(g.net)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {plan.plan.loans.some(l => l.unsupportedReason) && (
              <p role="note" style={{ fontSize: 12, color: 'var(--danger-text)' }}>
                {t('Nepodporované úvěry: ', 'Unsupported loans: ')}
                {plan.plan.loans.filter(l => l.unsupportedReason).map(l => `${l.loanId} (${l.unsupportedReason})`).join('; ')}
              </p>
            )}
            {perms.propose && (
              <button type="button" className="btn btn-primary btn-sm" disabled={!plan.executable || busy} onClick={() => void propose()}>
                {t('2. Navrhnout doúčtování (maker)', '2. Propose backfill (maker)')}
              </button>
            )}
          </div>
        )}
      </div>

      {notice && (
        <div role="status" className="card" style={{ marginBottom: 16, borderColor: notice.tone === 'danger' ? 'var(--danger)' : 'var(--success)' }}>
          {notice.text}
        </div>
      )}

      {execution && (
        <div className="card" style={{ marginBottom: 16, overflowX: 'auto' }}>
          <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Výsledek provedení', 'Execution result')}</h2>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Úvěr', 'Loan')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'Status')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Zaúčtováno', 'Posted')}</th>
              </tr>
            </thead>
            <tbody>
              {execution.execution.loans.map(l => (
                <tr key={l.loanId}>
                  <td>{l.loanId}</td>
                  <td><StatusBadge status={l.status} tone={l.status === 'POSTED' ? 'success' : 'danger'} /></td>
                  <td style={{ textAlign: 'right' }}>{`${l.legsPosted} / ${l.legsTotal}`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card" style={{ overflowX: 'auto' }}>
        <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Žádosti a jejich historie', 'Requests and history')}</h2>
        {historyKind ? (
          <DataUnavailable kind={historyKind} service="lending-service" feature={t('žádosti o doúčtování', 'backfill requests')} lang={language} dense />
        ) : requests === null ? null : requests.length === 0 ? (
          <DataUnavailable kind="no_data" service="lending-service" feature={t('žádosti o doúčtování', 'backfill requests')} lang={language} dense />
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'State')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Účetní den', 'Cut-over')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Úvěry / zápisy', 'Loans / journals')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Navrhl', 'Proposed by')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Rozhodl', 'Decided by')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Provedl', 'Executed by')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Akce', 'Actions')}</th>
              </tr>
            </thead>
            <tbody>
              {requests.map(r => {
                const a = backfillActions(r, actor, perms)
                return (
                  <tr key={r.id}>
                    <td><StatusBadge status={r.state} tone={STATE_TONE[r.state]} /></td>
                    <td>{r.cutoverDate}</td>
                    <td style={{ textAlign: 'right' }}>{`${r.loanCount} / ${r.legCount}`}</td>
                    <td>
                      {r.proposedBy}
                      {r.proposedAt && <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{new Date(r.proposedAt).toLocaleString(locale)}</div>}
                    </td>
                    <td>
                      {r.decidedBy ?? '—'}
                      {r.decisionReason && <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{r.decisionReason}</div>}
                    </td>
                    <td>
                      {r.executedBy ?? '—'}
                      {r.executedAt && <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{new Date(r.executedAt).toLocaleString(locale)}</div>}
                    </td>
                    <td>
                      {r.state === 'PROPOSED' && a.decideBlockedBy === 'own-proposal' && (
                        <span style={{ fontSize: 12 }}>{t('Tento návrh je váš — schválit jej musí jiná osoba.', 'You proposed this — a different person must approve it.')}</span>
                      )}
                      {a.canDecide && (
                        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                          <input
                            className="input"
                            value={reasons[r.id] ?? ''}
                            onChange={e => setReasons(prev => ({ ...prev, [r.id]: e.target.value }))}
                            placeholder={t('Důvod', 'Reason')}
                            aria-label={t('Důvod rozhodnutí', 'Decision reason')}
                            style={{ width: 160 }}
                          />
                          <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void decide(r.id, true)}>{t('Schválit', 'Approve')}</button>
                          <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void decide(r.id, false)}>{t('Zamítnout', 'Reject')}</button>
                        </div>
                      )}
                      {a.canExecute && confirming !== r.id && (
                        <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => { setConfirming(r.id); setAcknowledged(false) }}>
                          {t('Provést…', 'Execute…')}
                        </button>
                      )}
                      {a.canExecute && confirming === r.id && (
                        <div role="group" aria-label={t('Potvrzení provedení', 'Execution confirmation')} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                          <span style={{ fontSize: 12 }}>
                            {t(
                              `Zaúčtuje ${r.legCount} zápisů za ${r.loanCount} úvěrů do účetního dne ${r.cutoverDate}. Opravu lze provést jen stornem v ledgeru.`,
                              `Posts ${r.legCount} journals for ${r.loanCount} loans into accounting day ${r.cutoverDate}. It can only be undone by reversing them in the ledger.`,
                            )}
                          </span>
                          <label style={{ fontSize: 12, display: 'flex', gap: 6, alignItems: 'center' }}>
                            <input type="checkbox" checked={acknowledged} onChange={e => setAcknowledged(e.target.checked)} />
                            {t('Rozumím a chci zaúčtovat', 'I understand and want to post')}
                          </label>
                          <div style={{ display: 'flex', gap: 6 }}>
                            <button type="button" className="btn btn-primary btn-sm" disabled={!acknowledged || busy} onClick={() => void execute(r.id)}>{t('Zaúčtovat', 'Post journals')}</button>
                            <button type="button" className="btn btn-secondary btn-sm" onClick={() => setConfirming(null)}>{t('Zrušit', 'Cancel')}</button>
                          </div>
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
  )
}
