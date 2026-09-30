// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Void of back-posted loans — the finance department's four-eyes flow for #10969 (console #11487).
//
// The backfill (#10746) posted the GL history of synthetic loans that were never paid out. The void
// offsets every leg of an EXECUTED backfill with a mirror journal and moves each fully offset loan to
// UNWOUND. It used to be reachable only by curl with two people's tokens; this page is the same
// dry-run -> propose (maker) -> approve or reject (checker, a DIFFERENT person) -> execute flow as
// the backfill page, through the BFF with the signed-in person's own token.
//
// FOUR-EYES: hiding approve from the proposer is a courtesy. The control is LedgerBackfillVoidService
// (Proposal refuses checker == maker with 422), and the page renders that refusal verbatim.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSession } from 'next-auth/react'
import { RefreshCw, Undo2 } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { DataUnavailable, type UnavailableKind } from '@/components/feedback/DataUnavailable'
import { PageHeader, StatCard, StatusBadge } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import { backfillUrl, getJson, sendJson, voidUrl, type WriteResult } from '@/components/balance-sheet/api'
import {
  backfillPlanSchema, backfillRequestListSchema, voidExecutionSchema, voidRequestListSchema, voidRequestSchema,
  type BackfillPlan, type BackfillRequest, type VoidExecution, type VoidRequest,
} from '@/components/balance-sheet/contracts'
import { backfillActions, principalNameFromToken } from '@/components/balance-sheet/model'
import { hasPermission } from '@/lib/auth/roles'
import { useLanguage } from '@/lib/i18n/LanguageContext'

const HISTORY_LIMIT = 25
const STATE_TONE: Record<VoidRequest['state'], Tone> = {
  PROPOSED: 'warning', APPROVED: 'info', REJECTED: 'danger', WITHDRAWN: 'neutral', EXECUTED: 'success',
}

/** One key per user action: a retried click replays on the server instead of acting twice. */
const newKey = () => (typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`)

export default function LedgerBackfillVoidPage() {
  return (
    <AuthGuard permission="ledger-backfill:view">
      <LedgerBackfillVoid />
    </AuthGuard>
  )
}

type Notice = { tone: 'success' | 'danger'; text: string }

function LedgerBackfillVoid() {
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

  const [sources, setSources] = useState<BackfillRequest[] | null>(null)
  const [sourceId, setSourceId] = useState('')
  const [plan, setPlan] = useState<BackfillPlan | null>(null)
  const [planKind, setPlanKind] = useState<UnavailableKind | null>(null)
  const [requests, setRequests] = useState<VoidRequest[] | null>(null)
  const [historyKind, setHistoryKind] = useState<UnavailableKind | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const [confirming, setConfirming] = useState<string | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  const [execution, setExecution] = useState<VoidExecution | null>(null)

  const loadHistory = useCallback(async () => {
    const res = await getJson(voidUrl('', { limit: String(HISTORY_LIMIT) }), voidRequestListSchema)
    if (!res.ok) { setHistoryKind(res.kind); setRequests(null); return }
    setHistoryKind(null)
    setRequests(res.data.requests)
  }, [])

  const loadSources = useCallback(async () => {
    const res = await getJson(backfillUrl('/requests', { limit: String(HISTORY_LIMIT) }), backfillRequestListSchema)
    if (!res.ok) { setSources(null); return }
    const executed = res.data.requests.filter(r => r.state === 'EXECUTED')
    setSources(executed)
    setSourceId(prev => prev || executed[0]?.id || '')
  }, [])

  useEffect(() => { void loadHistory(); void loadSources() }, [loadHistory, loadSources])

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

  const dryRun = async () => {
    if (!sourceId) return
    setBusy(true)
    setNotice(null)
    const res = await getJson(voidUrl('/plan', { sourceRequestId: sourceId }), backfillPlanSchema)
    setBusy(false)
    if (res.ok) { setPlan(res.data); setPlanKind(null) } else { setPlan(null); setPlanKind(res.kind) }
  }

  const propose = async () => {
    setBusy(true)
    const res = await sendJson(voidUrl(''), { sourceRequestId: sourceId }, voidRequestSchema, newKey())
    setBusy(false)
    setNotice(res.ok
      ? { tone: 'success', text: t('Návrh storna zaznamenán. Schválit jej musí jiná osoba.', 'Void proposal recorded. A different person must approve it.') }
      : refusal(res, t('Návrh', 'Propose')))
    void loadHistory()
  }

  const decide = async (id: string, approve: boolean) => {
    setBusy(true)
    const reason = reasons[id]?.trim() || null
    const res = await sendJson(voidUrl(`/${encodeURIComponent(id)}/decide`), { approve, reason }, voidRequestSchema, newKey())
    setBusy(false)
    setNotice(res.ok
      ? { tone: 'success', text: approve ? t('Storno schváleno.', 'Void approved.') : t('Storno zamítnuto.', 'Void rejected.') }
      : refusal(res, approve ? t('Schválení', 'Approve') : t('Zamítnutí', 'Reject')))
    void loadHistory()
  }

  const execute = async (id: string) => {
    setBusy(true)
    const res = await sendJson(voidUrl(`/${encodeURIComponent(id)}/execute`, { execute: 'true' }), undefined, voidExecutionSchema, newKey())
    setBusy(false)
    setConfirming(null)
    setAcknowledged(false)
    if (res.ok) {
      setExecution(res.data)
      setNotice(res.data.execution.complete
        ? { tone: 'success', text: t('Storno dokončeno — úvěry jsou v hlavní knize vynulované a ve stavu UNWOUND.', 'Void complete — the loans net to zero in the ledger and read UNWOUND.') }
        : { tone: 'danger', text: t('Storno neúplné: některé úvěry selhaly. Žádost zůstává schválená; po odstranění příčiny spusťte znovu.', 'Void incomplete: some loans failed. The request stays approved; fix the cause and run it again.') })
    } else {
      setNotice(refusal(res, t('Provedení', 'Execute')))
    }
    void loadHistory()
  }

  return (
    <div>
      <PageHeader
        title={t('Storno doúčtovaných úvěrů', 'Void back-posted loans')}
        subtitle={t('Vyrovná každý zápis provedeného doúčtování zrcadlovým zápisem a úvěry převede do stavu UNWOUND — pravidlo čtyř očí (#10969).', 'Offsets every journal of an executed backfill with a mirror and moves the loans to UNWOUND — four-eyes (#10969).')}
        icon={<Undo2 size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => { void loadHistory(); void loadSources() }} aria-label={t('Obnovit', 'Refresh')}>
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
        {sources !== null && sources.length === 0 ? (
          <p style={{ fontSize: 12 }}>{t('Žádné provedené doúčtování — není co stornovat.', 'No executed backfill — nothing to void.')}</p>
        ) : (
          <div style={{ display: 'flex', gap: 12, alignItems: 'end', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12 }}>
              {t('Provedené doúčtování', 'Executed backfill')}
              <select className="input" value={sourceId} onChange={e => { setSourceId(e.target.value); setPlan(null) }} aria-label={t('Provedené doúčtování ke stornu', 'Executed backfill to void')}>
                {(sources ?? []).map(s => (
                  <option key={s.id} value={s.id}>
                    {`${s.id.slice(0, 8)} · ${s.loanCount} ${t('úvěrů', 'loans')} · ${s.executedAt ? new Date(s.executedAt).toLocaleString(locale) : ''}`}
                  </option>
                ))}
              </select>
            </label>
            <button type="button" className="btn btn-secondary btn-sm" disabled={!sourceId || busy} onClick={() => void dryRun()}>
              {t('Spočítat plán (nic nezapisuje)', 'Compute plan (writes nothing)')}
            </button>
          </div>
        )}

        {planKind && <div style={{ marginTop: 12 }}><DataUnavailable kind={planKind} service="lending-service" feature={t('plán storna', 'void plan')} lang={language} dense /></div>}

        {plan && (
          <div style={{ marginTop: 12 }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, marginBottom: 12 }}>
              <StatCard label={t('Zápisů k vyrovnání', 'Journals to offset')} value={plan.journalCount.toLocaleString(locale)} />
              <StatCard label={t('Úvěrů v rozsahu', 'Loans in scope')} value={plan.plan.loans.length.toLocaleString(locale)} />
              <StatCard label={t('Proveditelné', 'Executable')} value={plan.executable ? t('Ano', 'Yes') : t('Ne', 'No')} tone={plan.executable ? 'success' : 'danger'} />
            </div>
            <h3 style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>{t('Vyrovnávané pohyby (zrcadla je obrátí)', 'Movements being offset (the mirrors reverse them)')}</h3>
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
            {perms.propose && (
              <button type="button" className="btn btn-primary btn-sm" disabled={!plan.executable || busy} onClick={() => void propose()}>
                {t('2. Navrhnout storno (maker)', '2. Propose void (maker)')}
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
              </tr>
            </thead>
            <tbody>
              {execution.execution.loans.map(l => (
                <tr key={l.loanId}>
                  <td>{l.loanId}</td>
                  <td><StatusBadge status={l.status} tone={l.status === 'FAILED' ? 'danger' : 'success'} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card" style={{ overflowX: 'auto' }}>
        <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('Žádosti o storno a jejich historie', 'Void requests and history')}</h2>
        {historyKind ? (
          <DataUnavailable kind={historyKind} service="lending-service" feature={t('žádosti o storno', 'void requests')} lang={language} dense />
        ) : requests === null ? null : requests.length === 0 ? (
          <DataUnavailable kind="no_data" service="lending-service" feature={t('žádosti o storno', 'void requests')} lang={language} dense />
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Stav', 'State')}</th>
                <th scope="col" style={{ textAlign: 'left' }}>{t('Doúčtování', 'Backfill')}</th>
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
                    <td>{r.sourceRequestId.slice(0, 8)}</td>
                    <td style={{ textAlign: 'right' }}>{`${r.loanCount} / ${r.legCount}`}</td>
                    <td>{r.proposedBy}</td>
                    <td>
                      {r.decidedBy ?? '—'}
                      {r.decisionReason && <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{r.decisionReason}</div>}
                    </td>
                    <td>{r.executedBy ?? '—'}</td>
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
                              `Zaúčtuje zrcadlové zápisy k ${r.legCount} zápisům za ${r.loanCount} úvěrů a převede je do stavu UNWOUND.`,
                              `Posts mirror journals for ${r.legCount} journals across ${r.loanCount} loans and moves them to UNWOUND.`,
                            )}
                          </span>
                          <label style={{ fontSize: 12, display: 'flex', gap: 6, alignItems: 'center' }}>
                            <input type="checkbox" checked={acknowledged} onChange={e => setAcknowledged(e.target.checked)} />
                            {t('Rozumím a chci stornovat', 'I understand and want to void')}
                          </label>
                          <div style={{ display: 'flex', gap: 6 }}>
                            <button type="button" className="btn btn-primary btn-sm" disabled={!acknowledged || busy} onClick={() => void execute(r.id)}>{t('Stornovat', 'Void loans')}</button>
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
