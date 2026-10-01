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
import {
  backPostRunLabel, formatAmount, formatAmounts, formatBusinessDate, formatPragueDateTime, loanOutcomeLabel,
  loanStatusLabel, localeFor, personLabel, planSentence, summarizePlan, voidStateLabel,
} from '@/components/balance-sheet/voidView'
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
  const locale = localeFor(language)
  const money = (v: number) => v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

  const [sources, setSources] = useState<BackfillRequest[] | null>(null)
  const [backPosts, setBackPosts] = useState<Record<string, BackfillRequest>>({})
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
    setBackPosts(Object.fromEntries(res.data.requests.map(r => [r.id, r])))
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
      return { tone: 'danger', text: t(`${action}: server odmítl — storno musí schválit jiná osoba než navrhovatel. ${res.message ?? ''}`, `${action}: the server refused under the four-eyes rule — ${res.message ?? ''}`) }
    }
    if (res.kind === 'refused') {
      return { tone: 'danger', text: t(`${action}: systém úvěrů žádost odmítl — ${res.message ?? ''}`, `${action}: lending refused the request — ${res.message ?? ''}`) }
    }
    return { tone: 'danger', text: t(`${action}: systém úvěrů je nedostupný.`, `${action}: lending-service is unavailable.`) }
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
        ? { tone: 'success', text: t('Storno dokončeno — úvěry jsou v hlavní knize vynulované a vedené jako stornované.', 'Void complete — the loans net to zero in the ledger and are marked as voided.') }
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
        subtitle={t('Zruší dříve provedené doúčtování: ke každému jeho zápisu zaúčtuje protizápis a dotčené úvěry označí jako stornované. Storno vyžaduje schválení druhou osobou.', 'Cancels an earlier back-post: every entry it booked gets an offsetting entry and the loans are marked as voided. A void needs approval by a second person.')}
        icon={<Undo2 size={20} aria-hidden="true" />}
        actions={
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => { void loadHistory(); void loadSources() }} aria-label={t('Obnovit', 'Refresh')}>
            <RefreshCw size={14} aria-hidden="true" />
          </button>
        }
      />

      <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 12 }}>
        {actor
          ? t(`Jste přihlášen(a) jako ${actor}. Pod tímto jménem se zaznamená každý váš návrh, schválení i provedení.`, `Signed in as ${actor}. Every proposal, approval and execution you make is recorded under this name.`)
          : t('Vaši identitu se nepodařilo zjistit; schválení druhou osobou přesto hlídá server.', 'Could not read your identity; the server still enforces approval by a second person.')}
      </p>

      <div className="card" style={{ marginBottom: 16 }}>
        <h2 style={{ fontSize: 14, fontWeight: 600, marginBottom: 8 }}>{t('1. Zkouška nanečisto — co storno udělá', '1. Dry-run — what the void will do')}</h2>
        {sources !== null && sources.length === 0 ? (
          <p style={{ fontSize: 12 }}>{t('Žádné provedené doúčtování — není co stornovat.', 'No executed back-post — nothing to void.')}</p>
        ) : (
          <div style={{ display: 'flex', gap: 12, alignItems: 'end', flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, flex: '1 1 320px', minWidth: 0 }}>
              {t('Které doúčtování stornovat', 'Which back-post to void')}
              <select className="input" value={sourceId} onChange={e => { setSourceId(e.target.value); setPlan(null) }} aria-label={t('Provedené doúčtování ke stornu', 'Executed back-post to void')}>
                {(sources ?? []).map(s => (
                  <option key={s.id} value={s.id}>{backPostRunLabel(s, t, locale)}</option>
                ))}
              </select>
              {sourceId && <Reference id={sourceId} />}
            </label>
            <button type="button" className="btn btn-secondary btn-sm" disabled={!sourceId || busy} onClick={() => void dryRun()}>
              {t('Spočítat plán (nic nezapisuje)', 'Compute plan (writes nothing)')}
            </button>
          </div>
        )}

        {planKind && <div style={{ marginTop: 12 }}><DataUnavailable kind={planKind} service="lending-service" feature={t('plán storna', 'void plan')} lang={language} dense /></div>}

        {plan && <PlanView plan={plan} locale={locale} money={money} />}
        {plan && (
          <div style={{ marginTop: 12 }}>
            {perms.propose && (
              <button type="button" className="btn btn-primary btn-sm" disabled={!plan.executable || busy} onClick={() => void propose()}>
                {t('2. Navrhnout storno ke schválení', '2. Propose void for approval')}
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
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">{t('Úvěr (reference)', 'Loan (reference)')}</th>
                <th scope="col">{t('Výsledek', 'Outcome')}</th>
              </tr>
            </thead>
            <tbody>
              {execution.execution.loans.map(l => (
                <tr key={l.loanId}>
                  <td><code style={{ fontSize: 12 }}>{l.loanId}</code></td>
                  <td><span title={l.status}><StatusBadge status={l.status} label={loanOutcomeLabel(l.status, t)} tone={l.status === 'FAILED' ? 'danger' : 'success'} /></span></td>
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
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">{t('Stav', 'Status')}</th>
                <th scope="col">{t('Co se stornuje', 'What is voided')}</th>
                <th scope="col">{t('Průběh schválení', 'Approval trail')}</th>
                <th scope="col">{t('Akce', 'Actions')}</th>
              </tr>
            </thead>
            <tbody>
              {requests.map(r => {
                const a = backfillActions(r, actor, perms)
                const source = backPosts[r.sourceRequestId]
                return (
                  <tr key={r.id} style={{ verticalAlign: 'top' }}>
                    <td><span title={r.state}><StatusBadge status={r.state} label={voidStateLabel(r.state, t)} tone={STATE_TONE[r.state]} /></span></td>
                    <td style={{ minWidth: 220 }}>
                      <div>
                        {source
                          ? backPostRunLabel(source, t, locale)
                          : t('Doúčtování (podrobnosti nejsou v posledních záznamech)', 'Back-post (details not among recent records)')}
                      </div>
                      <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                        {t(`Storno vrátí ${r.loanCount.toLocaleString(locale)} úvěrů, ${r.legCount.toLocaleString(locale)} protizápisů, účetní datum ${formatBusinessDate(r.voidDate, locale)}`,
                          `Reverses ${r.loanCount.toLocaleString(locale)} loans, ${r.legCount.toLocaleString(locale)} offsetting entries, booking date ${formatBusinessDate(r.voidDate, locale)}`)}
                      </div>
                      <Reference id={r.id} />
                    </td>
                    <td style={{ minWidth: 220 }}>
                      <TrailStep label={t('Navrhl(a)', 'Proposed by')} who={r.proposedBy} at={r.proposedAt} locale={locale} />
                      {r.decidedBy && (
                        <TrailStep label={r.state === 'REJECTED' ? t('Zamítl(a)', 'Rejected by') : t('Schválil(a)', 'Approved by')} who={r.decidedBy} at={r.decidedAt} locale={locale} note={r.decisionReason} />
                      )}
                      {r.executedBy && <TrailStep label={t('Provedl(a)', 'Executed by')} who={r.executedBy} at={r.executedAt} locale={locale} />}
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
                              `Zaúčtuje ${r.legCount} protizápisů za ${r.loanCount} úvěrů a úvěry označí jako stornované.`,
                              `Posts ${r.legCount} offsetting entries across ${r.loanCount} loans and marks the loans as voided.`,
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

/** A record id for support: full, monospace, copyable — never the primary label. */
function Reference({ id }: { id: string }) {
  const { t } = useLanguage()
  const [copied, setCopied] = useState(false)
  const copy = () => {
    void navigator.clipboard?.writeText(id).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500) }, () => undefined)
  }
  return (
    <div style={{ fontSize: 11, color: 'var(--text-tertiary)', display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginTop: 2 }}>
      <span>{t('Reference pro podporu:', 'Support reference:')}</span>
      <code style={{ fontSize: 11, userSelect: 'all', wordBreak: 'break-all' }}>{id}</code>
      <button type="button" className="btn btn-secondary btn-sm" style={{ padding: '0 6px', fontSize: 11 }} onClick={copy} aria-label={t('Zkopírovat referenci', 'Copy reference')}>
        {copied ? t('Zkopírováno', 'Copied') : t('Kopírovat', 'Copy')}
      </button>
    </div>
  )
}

function TrailStep({ label, who, at, locale, note }: { label: string; who: string; at?: string | null; locale: string; note?: string | null }) {
  const { t } = useLanguage()
  const person = personLabel(who, t)
  return (
    <div style={{ marginBottom: 6 }}>
      <div style={{ fontSize: 12 }}>
        <span style={{ color: 'var(--text-secondary)' }}>{label} </span>
        <strong style={{ fontWeight: 600, wordBreak: 'break-all' }} title={person ? `${person.kind}: ${person.text}` : undefined}>{person?.text ?? '—'}</strong>
      </div>
      {at && <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{formatPragueDateTime(at, locale)}</div>}
      {note && <div style={{ fontSize: 11, color: 'var(--text-secondary)', fontStyle: 'italic' }}>{t(`Důvod: ${note}`, `Reason: ${note}`)}</div>}
    </div>
  )
}

function PlanView({ plan, locale, money }: { plan: BackfillPlan; locale: string; money: (v: number) => string }) {
  const { t } = useLanguage()
  const s = summarizePlan(plan)
  const period = s.firstValueDate
    ? `${formatBusinessDate(s.firstValueDate, locale)} – ${formatBusinessDate(s.lastValueDate, locale)}`
    : '—'
  return (
    <div style={{ marginTop: 12 }}>
      <p role="note" style={{ fontSize: 14, fontWeight: 600, marginBottom: 12 }}>{planSentence(s, t, locale)}</p>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12, marginBottom: 12 }}>
        <StatCard label={t('Úvěrů ke stornu', 'Loans to void')} value={s.loanCount.toLocaleString(locale)} />
        <StatCard label={t('Protizápisů', 'Offsetting entries')} value={s.entryCount.toLocaleString(locale)} />
        <StatCard label={t('Celková výše', 'Total amount')} value={formatAmounts(s.totalByCurrency, locale)} />
        <StatCard label={t('Z toho vyplacená jistina', 'Of which principal paid out')} value={formatAmounts(s.disbursedByCurrency, locale)} />
        <StatCard label={t('Období zápisů (datum valuty)', 'Entry period (value date)')} value={period} />
        <StatCard label={t('Lze provést', 'Can be executed')} value={plan.executable ? t('Ano', 'Yes') : t('Ne', 'No')} tone={plan.executable ? 'success' : 'danger'} />
      </div>
      <details style={{ marginBottom: 12 }}>
        <summary style={{ cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>{t(`Dotčené úvěry (${s.loanCount})`, `Affected loans (${s.loanCount})`)}</summary>
        <div style={{ overflowX: 'auto', marginTop: 8 }}>
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">{t('Úvěr (reference)', 'Loan (reference)')}</th>
                <th scope="col">{t('Stav úvěru', 'Loan status')}</th>
                <th scope="col">{t('Vyplaceno dne', 'Paid out on')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Vyplacená jistina', 'Principal paid out')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Nesplacená jistina', 'Unpaid principal')}</th>
                <th scope="col" style={{ textAlign: 'right' }}>{t('Zápisů', 'Entries')}</th>
              </tr>
            </thead>
            <tbody>
              {s.loans.map(l => (
                <tr key={l.loanId}>
                  <td><code style={{ fontSize: 12, wordBreak: 'break-all' }}>{l.loanId}</code></td>
                  <td title={l.status}>{loanStatusLabel(l.status, t)}</td>
                  <td>{formatBusinessDate(l.disbursedOn, locale)}</td>
                  <td style={{ textAlign: 'right' }}>{l.disbursed ? formatAmount(l.disbursed, l.currency, locale) : '—'}</td>
                  <td style={{ textAlign: 'right' }}>{formatAmount(l.unpaidPrincipal, l.currency, locale)}</td>
                  <td style={{ textAlign: 'right' }}>{l.entries.toLocaleString(locale)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
      <details style={{ marginBottom: 12 }}>
        <summary style={{ cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>{t('Dopad na účty hlavní knihy', 'Effect on general-ledger accounts')}</summary>
        <div style={{ overflowX: 'auto', marginTop: 8 }}>
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">{t('Účet', 'Account')}</th>
                <th scope="col">{t('Měna', 'Currency')}</th>
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
        </div>
      </details>
    </div>
  )
}
