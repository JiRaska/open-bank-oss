// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury payments (ADR-0344 D5): the payment orders the cash-flow ledger produced, by status,
// with the netting groups they were combined into. A released payment whose outcome is still open
// (RELEASED, SENT, RAIL_NOT_SUPPORTED, FAILED) is resolved by manual reconciliation — MANUALLY_SETTLED
// or RETURNED — by an approver who neither released it nor authored its deals. Release hands a payment to the rail; it is
// reserved for the payment-releaser role and withheld from the creator of the deal and from any AI
// principal. The service refuses both regardless of what is shown here.

'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSession } from 'next-auth/react'
import { Banknote } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader, StatusBadge } from '@/components/ui'
import type { Tone } from '@/components/ui/tone'
import { principalNameFromToken } from '@/components/balance-sheet/model'
import { hasPermission } from '@/lib/auth/roles'
import { wbGet, wbPost, wbUrl, type WbRead } from '@/components/treasury/wb/client'
import { canReleasePayment, canResolvePayment, nettingGroups } from '@/components/treasury/wb/payments'
import { isAiPrincipal } from '@/components/treasury/wb/principal'
import { PAYMENT_STATUSES, paymentListSchema, paymentSchema, type Payment } from '@/components/treasury/wb/types'
import { Field, NameWithId, Notice, ReadFailure, Section, td, tdNum, th, useFmt, writeFailureText } from '@/components/treasury/wb/ui'

export default function TreasuryPaymentsPage() {
  return (
    <AuthGuard permission="treasury:workbench:view">
      <Payments />
    </AuthGuard>
  )
}

const TONE: Record<string, Tone> = { PROPOSED: 'warning', RELEASED: 'info', SENT: 'info', SETTLED: 'success', FAILED: 'danger', RAIL_NOT_SUPPORTED: 'warning', MANUALLY_SETTLED: 'success', RETURNED: 'neutral', CANCELLED: 'neutral' }

function statusText(s: string, t: (cs: string, en: string) => string): string {
  switch (s) {
    case 'PROPOSED': return t('Navrženo', 'Proposed')
    case 'RELEASED': return t('Uvolněno', 'Released')
    case 'SENT': return t('Odesláno', 'Sent')
    case 'SETTLED': return t('Vypořádáno', 'Settled')
    case 'FAILED': return t('Selhalo', 'Failed')
    case 'RAIL_NOT_SUPPORTED': return t('Mimo platební systém', 'Rail not supported')
    case 'MANUALLY_SETTLED': return t('Vypořádáno ručně', 'Manually settled')
    case 'RETURNED': return t('Vráceno', 'Returned')
    case 'CANCELLED': return t('Zrušeno', 'Cancelled')
    default: return s
  }
}

function Payments() {
  const { t, money } = useFmt()
  const { data: session } = useSession()
  const roles = useMemo(() => session?.user?.roles ?? [], [session?.user?.roles])
  const actor = principalNameFromToken(session?.user?.accessToken)
  const ai = isAiPrincipal(session?.user as { roles?: string[] } | undefined)
  const holdsRelease = hasPermission(roles, 'treasury:payment:release')
  const canCancel = hasPermission(roles, 'treasury:backoffice:write') || holdsRelease
  const holdsApprove = hasPermission(roles, 'treasury:decision:approve')

  const [status, setStatus] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [result, setResult] = useState<WbRead<Payment[]> | null>(null)
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => { setResult(await wbGet(wbUrl('/payments', { status, from, to }), paymentListSchema)) }, [status, from, to])
  useEffect(() => { void load() }, [load])

  const release = async (p: Payment) => {
    setBusy(true)
    const res = await wbPost(wbUrl(`/payments/${encodeURIComponent(p.paymentId)}/release`), undefined, paymentSchema)
    setBusy(false)
    if (res.ok) { setNotice({ tone: 'success', text: t('Platba uvolněna do platebního systému.', 'Payment released to the rail.') }); void load() }
    else if (res.code === 'SSI_CHANGED') {
      // The settlement instruction changed after the payment was proposed: nothing was released.
      setNotice({ tone: 'danger', text: t('Platební instrukce protistrany se změnila po navržení platby. Nic nebylo uvolněno — platbu zrušte a navrhněte znovu.', 'The counterparty settlement instruction changed after this payment was proposed. Nothing was released — cancel it and propose again.') })
      void load()
    }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Uvolnění platby', 'Release payment'), t) })
  }
  const resolve = async (p: Payment, outcome: 'MANUALLY_SETTLED' | 'RETURNED') => {
    const reason = window.prompt(outcome === 'MANUALLY_SETTLED' ? t('Důvod ručního vypořádání', 'Reason for manual settlement') : t('Důvod vrácení', 'Reason for the return'))
    if (!reason?.trim()) return
    setBusy(true)
    const res = await wbPost(wbUrl(`/payments/${encodeURIComponent(p.paymentId)}/resolve`), { outcome, reason: reason.trim() }, paymentSchema)
    setBusy(false)
    if (res.ok) { setNotice({ tone: 'success', text: t('Platba ručně vyřešena.', 'Payment resolved manually.') }); void load() }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Ruční odsouhlasení', 'Manual reconciliation'), t) })
  }
  const cancel = async (p: Payment) => {
    const reason = window.prompt(t('Důvod zrušení', 'Reason for cancelling'))
    if (!reason?.trim()) return
    setBusy(true)
    const res = await wbPost(wbUrl(`/payments/${encodeURIComponent(p.paymentId)}/cancel`), { reason: reason.trim() }, paymentSchema)
    setBusy(false)
    if (res.ok) { setNotice({ tone: 'success', text: t('Platba zrušena.', 'Payment cancelled.') }); void load() }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Zrušení platby', 'Cancel payment'), t) })
  }

  const payments = result?.ok ? result.data : null
  const groups = payments ? nettingGroups(payments) : []
  const counts = payments ? Object.fromEntries(PAYMENT_STATUSES.map(s => [s, payments.filter(p => p.status === s).length])) : {}

  return (
    <div>
      <PageHeader title={t('Platby treasury', 'Treasury payments')} subtitle={t('Platební příkazy z peněžních toků obchodů, netting a uvolnění.', 'Payment orders from deal cash flows, netting and release.')} icon={<Banknote size={20} aria-hidden="true" />} />
      <Section title={t('Filtr', 'Filter')} id="pay-filter">
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <Field label={t('Stav', 'Status')}>
            <select className="input" value={status} onChange={e => setStatus(e.target.value)}>
              <option value="">{t('Všechny', 'All')}</option>
              {PAYMENT_STATUSES.map(s => <option key={s} value={s}>{statusText(s, t)}</option>)}
            </select>
          </Field>
          <Field label={t('Datum valuty od', 'Value date from')}><input className="input" type="date" value={from} onChange={e => setFrom(e.target.value)} /></Field>
          <Field label={t('Datum valuty do', 'Value date to')}><input className="input" type="date" value={to} onChange={e => setTo(e.target.value)} /></Field>
        </div>
        {payments && <p data-testid="status-counts" style={{ fontSize: 12, marginTop: 8 }}>{PAYMENT_STATUSES.map(s => `${statusText(s, t)}: ${counts[s]}`).join(' · ')}</p>}
      </Section>
      {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}

      <Section title={t('Platební příkazy', 'Payment orders')} id="pay-list">
        {result === null && <p style={{ fontSize: 13 }}>{t('Načítám…', 'Loading…')}</p>}
        {result && !result.ok && <ReadFailure result={result} what={t('Platební příkazy', 'Payment orders')} />}
        {payments && (payments.length === 0 ? <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('Žádné platby pro tento výběr.', 'No payments for this selection.')}</p> : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Platební příkazy', 'Payment orders')}</caption>
            <thead><tr>{[t('Protistrana', 'Counterparty'), t('Směr', 'Direction')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}<th scope="col" style={{ ...th, textAlign: 'right' }}>{t('Částka', 'Amount')}</th>{[t('Valuta', 'Value date'), t('Platební systém', 'Rail'), t('Reference systému', 'Rail reference'), t('Netting', 'Netting'), t('Stav', 'Status'), ''].map((h, i) => <th key={i} scope="col" style={th}>{h}</th>)}</tr></thead>
            <tbody>
              {payments.map(p => {
                const mayRelease = canReleasePayment(p, { actor, holdsRelease, ai })
                const mayResolve = canResolvePayment(p, { actor, holdsApprove, ai })
                return (
                  <tr key={p.paymentId} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={td}><NameWithId name={p.counterpartyName} id={p.counterpartyId} /></td>
                    <td style={td}>{p.direction === 'OUT' ? t('Odchozí', 'Outgoing') : t('Příchozí (očekávaná)', 'Incoming (expected)')}</td>
                    <td style={tdNum}>{money(p.amount)} {p.currency}</td>
                    <td style={td}>{p.valueDate}</td>
                    <td style={td}>{p.transferRail ?? p.rail ?? '—'}</td>
                    <td style={td} data-testid="rail-reference">{p.railReference ?? '—'}</td>
                    <td style={td}>{(p.nettedFlowIds?.length ?? 0) > 1 ? t(`${p.nettedFlowIds?.length} toků`, `${p.nettedFlowIds?.length} flows`) : '—'}</td>
                    <td style={td}>
                      <StatusBadge status={p.status} tone={TONE[p.status] ?? 'neutral'} label={statusText(p.status, t)} />
                      {p.statusReason && <div style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{p.statusReason}</div>}
                    </td>
                    <td style={{ ...td, whiteSpace: 'nowrap' }}>
                      {p.status === 'PROPOSED' && mayRelease && <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void release(p)}>{t('Uvolnit', 'Release')}</button>}{' '}
                      {(p.status === 'PROPOSED') && canCancel && <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void cancel(p)}>{t('Zrušit', 'Cancel')}</button>}
                      {mayResolve && <><button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void resolve(p, 'MANUALLY_SETTLED')}>{t('Vypořádáno ručně', 'Manually settled')}</button>{' '}<button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void resolve(p, 'RETURNED')}>{t('Vráceno', 'Returned')}</button></>}
                      {p.status === 'PROPOSED' && holdsRelease && !mayRelease && <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>{t('Uvolnit nesmíte: obchod jste vytvořili vy.', 'You may not release: you created the deal.')}</span>}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        ))}
      </Section>

      {groups.length > 0 && (
        <Section title={t('Netting skupiny', 'Netting groups')} id="pay-netting">
          <ul style={{ fontSize: 13 }} data-testid="netting-groups">
            {groups.map(g => <li key={g.key}>{t(`${g.counterparty} · ${g.valueDate} · ${g.currency}: ${g.count} plateb, netto ${money(g.net)}`, `${g.counterparty} · ${g.valueDate} · ${g.currency}: ${g.count} payments, net ${money(g.net)}`)}</li>)}
          </ul>
        </Section>
      )}
    </div>
  )
}
