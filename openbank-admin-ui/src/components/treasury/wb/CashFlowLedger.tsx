// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// The deal's cash-flow ledger (ADR-0344 D4): one row per flow with its leg, period, kind,
// direction, accrual window, fixing and payment date, notional, rate and where the rate came from,
// amount, state, and — when an approved override or a manual flow sits on it — the original beside
// the override. A back-office person proposes an override (date and/or amount, with a reason); it
// stays PENDING until a DIFFERENT person approves or rejects it, and the buttons for that decision
// are withheld from whoever proposed it. Used for the stored ledger and for the schedule preview.
import { useMemo, useState } from 'react'
import { StatusBadge } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import { flowKindLabel, rateSourceLabel } from './catalogue'
import { wbPost, wbUrl } from './client'
import { cashFlowOverrideSchema, type CashFlow, type CashFlowLedger, type CashFlowOverride } from './types'
import { Field, Notice, td, tdNum, th, useFmt, writeFailureText } from './ui'

const STATE_TONE: Record<string, Tone> = { PROJECTED: 'neutral', FIXED: 'info', DUE: 'warning', SETTLED: 'success', POSTED: 'success', CANCELLED: 'danger' }
const SOURCE_TONE: Record<string, Tone> = { FIXED: 'info', OBSERVED: 'success', PROJECTED: 'neutral', RATE_UNTIL_FIRST_FIXING: 'warning', MISSING: 'danger' }

export function stateLabel(s: string, t: (cs: string, en: string) => string): string {
  switch (s) {
    case 'PROJECTED': return t('Projekce', 'Projected')
    case 'FIXED': return t('Zafixováno', 'Fixed')
    case 'DUE': return t('Splatné', 'Due')
    case 'SETTLED': return t('Vypořádáno', 'Settled')
    case 'POSTED': return t('Zaúčtováno', 'Posted')
    case 'CANCELLED': return t('Zrušeno', 'Cancelled')
    default: return s
  }
}

type Props = {
  dealId: string
  ledger: CashFlowLedger
  preview?: boolean
  actor: string | null
  canPropose: boolean
  canDecide: boolean
  onChanged?: () => void
}

export function CashFlowLedgerView({ dealId, ledger, preview = false, actor, canPropose, canDecide, onChanged }: Props) {
  const { t, money, num } = useFmt()
  const [leg, setLeg] = useState('')
  const [kind, setKind] = useState('')
  const [editing, setEditing] = useState<CashFlow | null>(null)
  const [pending, setPending] = useState<Record<string, CashFlowOverride>>({})
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  const legs = useMemo(() => [...new Set(ledger.flows.map(f => f.legIndex))].sort((a, b) => a - b), [ledger.flows])
  const kinds = useMemo(() => [...new Set(ledger.flows.map(f => f.kind))], [ledger.flows])
  const rows = ledger.flows.filter(f => (leg === '' || String(f.legIndex) === leg) && (kind === '' || f.kind === kind))

  /** The undecided override attached to a flow: one proposed in this session, or one the service
   * reports on the flow without an approver. */
  const pendingFor = (f: CashFlow): { overrideId: string; by: string; reason: string } | null => {
    const local = pending[f.flowId]
    if (local && local.status === 'PENDING') return { overrideId: local.overrideId, by: local.proposedBy, reason: local.reason }
    if (f.override?.overrideId && !f.override.approvedBy && f.override.by) return { overrideId: f.override.overrideId, by: f.override.by, reason: f.override.reason ?? '' }
    return null
  }

  const decide = async (f: CashFlow, overrideId: string, action: 'approve' | 'reject') => {
    let body: unknown
    if (action === 'reject') {
      const reason = window.prompt(t('Důvod zamítnutí', 'Reason for rejection'))
      if (!reason?.trim()) return
      body = { reason: reason.trim() }
    }
    setBusy(true)
    const res = await wbPost(wbUrl(`/deals/${encodeURIComponent(dealId)}/cash-flows/${encodeURIComponent(f.flowId)}/overrides/${encodeURIComponent(overrideId)}/${action}`), body, cashFlowOverrideSchema)
    setBusy(false)
    const label = action === 'approve' ? t('Schválení přepsání', 'Approve override') : t('Zamítnutí přepsání', 'Reject override')
    if (res.ok) {
      setPending(p => { const n = { ...p }; delete n[f.flowId]; return n })
      setNotice({ tone: 'success', text: `${label}: ${t('hotovo.', 'done.')}` })
      onChanged?.()
    } else setNotice({ tone: 'danger', text: writeFailureText(res, label, t) })
  }

  return (
    <div>
      {preview && (
        <p role="note" style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }} data-testid="preview-note">
          {t('Náhled harmonogramu — nic nebylo uloženo ani zaúčtováno.', 'Schedule preview — nothing has been stored or posted.')}
        </p>
      )}
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 8, fontSize: 12 }}>
        <span>{t(`Verze harmonogramu ${ledger.scheduleVersion}`, `Schedule version ${ledger.scheduleVersion}`)}{ledger.scheduleHash ? ` · ${ledger.scheduleHash.slice(0, 10)}` : ''}</span>
        <span>{t(`Toků: ${rows.length} z ${ledger.flows.length}`, `Flows: ${rows.length} of ${ledger.flows.length}`)}</span>
        <Field label={t('Noha', 'Leg')}>
          <select className="input" value={leg} onChange={e => setLeg(e.target.value)}>
            <option value="">{t('Všechny', 'All')}</option>
            {legs.map(l => <option key={l} value={String(l)}>{t(`Noha ${l + 1}`, `Leg ${l + 1}`)}</option>)}
          </select>
        </Field>
        <Field label={t('Druh', 'Kind')}>
          <select className="input" value={kind} onChange={e => setKind(e.target.value)}>
            <option value="">{t('Všechny', 'All')}</option>
            {kinds.map(k => <option key={k} value={k}>{flowKindLabel(k, t)}</option>)}
          </select>
        </Field>
      </div>
      {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}
      {ledger.flows.length === 0 ? (
        <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
          {t('Harmonogram zatím nemá žádné toky — vzniká při předložení obchodu.', 'The schedule has no flows yet — it is written when the deal is submitted.')}
        </p>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }} data-testid="cashflow-grid">
          <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Peněžní toky obchodu', 'Cash flows of the deal')}</caption>
          <thead>
            <tr>
              {[
                t('Noha', 'Leg'), t('Období', 'Period'), t('Druh', 'Kind'), t('Směr', 'Direction'), t('Akruál od', 'Accrual from'), t('Akruál do', 'Accrual to'),
                t('Fixing', 'Fixing'), t('Platba', 'Payment'), t('Jistina', 'Notional'), t('Sazba %', 'Rate %'), t('Zdroj sazby', 'Rate source'),
                t('Částka', 'Amount'), t('Stav', 'State'), t('Přepsání', 'Override'), '',
              ].map((h, i) => <th key={i} scope="col" style={th}>{h}</th>)}
            </tr>
          </thead>
          <tbody>
            {rows.map(f => {
              const p = pendingFor(f)
              const changedDate = f.original?.paymentDate && f.original.paymentDate !== f.paymentDate
              const changedAmount = f.original && f.original.amount != null && f.original.amount !== f.amount
              return (
                <tr key={f.flowId} data-flow-row style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={td}>{f.legIndex + 1}</td>
                  <td style={td}>{f.periodIndex + 1}</td>
                  <td style={td}>{flowKindLabel(f.kind, t)}</td>
                  <td style={td}>{f.direction === 'IN' ? t('Příjem', 'In') : t('Výdaj', 'Out')}</td>
                  <td style={td}>{f.accrualStart ?? '—'}</td>
                  <td style={td}>{f.accrualEnd ?? '—'}</td>
                  <td style={td}>{f.fixingDate ?? '—'}</td>
                  <td style={td}>
                    {changedDate ? <><s aria-label={t('původní', 'original')}>{f.original?.paymentDate}</s> {f.paymentDate}</> : f.paymentDate}
                  </td>
                  <td style={tdNum}>{f.notional != null ? `${money(f.notional)} ${f.currency}` : '—'}</td>
                  <td style={tdNum}>{f.rate != null ? num(f.rate, 6) : '—'}</td>
                  <td style={td}><StatusBadge status={f.rateSource} tone={SOURCE_TONE[f.rateSource] ?? 'neutral'} label={rateSourceLabel(f.rateSource, t)} /></td>
                  <td style={tdNum}>
                    {changedAmount ? <><s aria-label={t('původní', 'original')}>{money(f.original?.amount)}</s> {money(f.amount)}</> : (f.amount != null ? money(f.amount) : '—')} {f.currency}
                  </td>
                  <td style={td}>
                    <StatusBadge status={f.state} tone={STATE_TONE[f.state] ?? 'neutral'} label={stateLabel(f.state, t)} />
                    {f.manual && <> <StatusBadge status="MANUAL" tone="accent" label={t('Ruční', 'Manual')} /></>}
                  </td>
                  <td style={td}>
                    {f.override?.approvedBy && (
                      <span title={f.override.reason ?? ''}>{t(`Schválil ${f.override.approvedBy}`, `Approved by ${f.override.approvedBy}`)}{f.override.reason ? ` · ${f.override.reason}` : ''}</span>
                    )}
                    {p && <StatusBadge status="PENDING" tone="warning" label={t(`Čeká: ${p.by}`, `Pending: ${p.by}`)} />}
                  </td>
                  <td style={{ ...td, whiteSpace: 'nowrap' }}>
                    {!preview && canPropose && !p && f.state !== 'CANCELLED' && f.state !== 'POSTED' && f.state !== 'SETTLED' && (
                      <button type="button" className="btn btn-secondary btn-sm" onClick={() => setEditing(f)}
                        aria-label={t(`Přepsat tok: noha ${f.legIndex + 1}, období ${f.periodIndex + 1}`, `Override flow: leg ${f.legIndex + 1}, period ${f.periodIndex + 1}`)}>
                        {t('Přepsat', 'Override')}
                      </button>
                    )}
                    {!preview && canDecide && p && actor !== p.by && (
                      <>
                        <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void decide(f, p.overrideId, 'approve')}>{t('Schválit', 'Approve')}</button>{' '}
                        <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void decide(f, p.overrideId, 'reject')}>{t('Zamítnout', 'Reject')}</button>
                      </>
                    )}
                    {!preview && p && actor === p.by && (
                      <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{t('Navrhli jste — rozhodne jiná osoba.', 'You proposed it — a different person decides.')}</span>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
      {editing && (
        <OverrideDialog
          dealId={dealId}
          flow={editing}
          onClose={() => setEditing(null)}
          onProposed={o => {
            setPending(p => ({ ...p, [o.flowId]: o }))
            setEditing(null)
            setNotice({ tone: 'success', text: t('Přepsání navrženo — čeká na schválení jinou osobou.', 'Override proposed — it waits for a different person to approve it.') })
            onChanged?.()
          }}
        />
      )}
    </div>
  )
}

function OverrideDialog({ dealId, flow, onClose, onProposed }: { dealId: string; flow: CashFlow; onClose: () => void; onProposed: (o: CashFlowOverride) => void }) {
  const { t } = useFmt()
  const [paymentDate, setPaymentDate] = useState('')
  const [amount, setAmount] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | null>(null)

  const submit = async () => {
    if (!paymentDate && !amount.trim()) { setFieldError(t('Zadejte nové datum platby nebo novou částku.', 'Enter a new payment date or a new amount.')); return }
    if (amount.trim() && !Number.isFinite(Number(amount))) { setFieldError(t('Částka není platné číslo.', 'The amount is not a valid number.')); return }
    if (!reason.trim()) { setFieldError(t('Důvod je povinný.', 'A reason is required.')); return }
    setFieldError(null)
    setBusy(true)
    const res = await wbPost(
      wbUrl(`/deals/${encodeURIComponent(dealId)}/cash-flows/${encodeURIComponent(flow.flowId)}/overrides`),
      { paymentDate: paymentDate || null, amount: amount.trim() ? Number(amount) : null, reason: reason.trim() },
      cashFlowOverrideSchema,
    )
    setBusy(false)
    if (res.ok) onProposed(res.data)
    else setError(writeFailureText(res, t('Návrh přepsání', 'Propose override'), t))
  }

  return (
    <div role="dialog" aria-modal="false" aria-label={t('Přepsání peněžního toku', 'Override cash flow')} className="card" style={{ marginTop: 12, borderColor: 'var(--accent, var(--border))' }}>
      <h3 style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>
        {t(`Přepsání toku: noha ${flow.legIndex + 1}, období ${flow.periodIndex + 1} (${flow.paymentDate}, ${flow.amount ?? '—'} ${flow.currency})`, `Override flow: leg ${flow.legIndex + 1}, period ${flow.periodIndex + 1} (${flow.paymentDate}, ${flow.amount ?? '—'} ${flow.currency})`)}
      </h3>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12 }}>
        <Field label={t('Nové datum platby', 'New payment date')}><input className="input" type="date" value={paymentDate} onChange={e => setPaymentDate(e.target.value)} /></Field>
        <Field label={t('Nová částka', 'New amount')} hint={flow.amount == null ? t('Tok zatím nemá částku (chybí fixing).', 'The flow has no amount yet (fixing missing).') : undefined}>
          <input className="input" inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} />
        </Field>
        <Field label={t('Důvod (povinný)', 'Reason (required)')} error={fieldError}><input className="input" value={reason} onChange={e => setReason(e.target.value)} /></Field>
      </div>
      {error && <p role="alert" style={{ color: 'var(--danger-text)', fontSize: 12, marginTop: 8 }}>{error}</p>}
      <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
        <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void submit()}>{t('Navrhnout přepsání', 'Propose override')}</button>
        <button type="button" className="btn btn-secondary btn-sm" onClick={onClose}>{t('Zavřít', 'Close')}</button>
      </div>
    </div>
  )
}
