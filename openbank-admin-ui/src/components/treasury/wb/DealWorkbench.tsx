// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// What the deal page gains with ADR-0344, below the original summary: the cash-flow ledger (with
// override proposals and the four-eyes decision), the conflicts a regeneration could not carry
// over, the deal's change history, "send confirmation", and the full ticket (read-only once the
// deal has left DRAFT, editable while it is one).
import { useCallback, useEffect, useState } from 'react'
import { StatusBadge } from '@/components/ui'
import { hasPermission } from '@/lib/auth/roles'
import { CashFlowLedgerView } from './CashFlowLedger'
import { DealTicket } from './DealTicket'
import { wbGet, wbPost, wbUrl } from './client'
import {
  cashFlowConflictListSchema, cashFlowLedgerSchema, dealHistorySchema, ticketDealSchema,
  type CashFlowLedger, type DealHistory, type TicketDeal,
} from './types'
import { NotAvailable, Notice, ReadFailure, Section, td, th, useFmt, writeFailureText } from './ui'
import { z } from 'zod'
import type { WbRead } from './client'

type Conflicts = z.infer<typeof cashFlowConflictListSchema>

export function DealWorkbench({ deal, actor, roles, onChanged }: { deal: TicketDeal; actor: string | null; roles: string[]; onChanged: () => void }) {
  const { t, dateTime } = useFmt()
  const [ledger, setLedger] = useState<WbRead<CashFlowLedger> | null>(null)
  const [conflicts, setConflicts] = useState<Conflicts>([])
  const [history, setHistory] = useState<WbRead<DealHistory> | null>(null)
  const [showHistory, setShowHistory] = useState(false)
  const [ticket, setTicket] = useState<'closed' | 'open'>('closed')
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  const canPropose = hasPermission(roles, 'treasury:backoffice:write')
  const canDecide = hasPermission(roles, 'treasury:decision:approve')
  const canConfirm = hasPermission(roles, 'treasury:confirmation:send')
  const canEdit = hasPermission(roles, 'treasury:deal:create')
  // The confirmation is sent by someone other than the dealer who created the deal.
  const ownDeal = actor !== null && deal.createdBy === actor

  const loadLedger = useCallback(async () => {
    const [l, c] = await Promise.all([
      wbGet(wbUrl(`/deals/${encodeURIComponent(deal.dealId)}/cash-flows`), cashFlowLedgerSchema),
      wbGet(wbUrl(`/deals/${encodeURIComponent(deal.dealId)}/cash-flows/conflicts`), cashFlowConflictListSchema),
    ])
    setLedger(l)
    setConflicts(c.ok ? c.data : [])
  }, [deal.dealId])
  useEffect(() => { void loadLedger() }, [loadLedger])

  const openHistory = async () => {
    setShowHistory(v => !v)
    if (history) return
    setHistory(await wbGet(wbUrl(`/deals/${encodeURIComponent(deal.dealId)}/history`), dealHistorySchema))
  }

  const sendConfirmation = async () => {
    setBusy(true)
    const res = await wbPost(wbUrl(`/deals/${encodeURIComponent(deal.dealId)}/send-confirmation`), {}, ticketDealSchema)
    setBusy(false)
    if (res.ok) { setNotice({ tone: 'success', text: t('Odeslání konfirmace zaznamenáno.', 'Confirmation recorded as sent.') }); onChanged() }
    else setNotice({ tone: 'danger', text: writeFailureText(res, t('Odeslání konfirmace', 'Send confirmation'), t) })
  }

  return (
    <div data-testid="deal-workbench">
      {notice && <Notice tone={notice.tone}>{notice.text}</Notice>}

      <Section title={t('Harmonogram peněžních toků', 'Cash-flow ledger')} id="deal-cashflows">
        {conflicts.length > 0 && (
          <div role="alert" className="card" data-testid="conflicts-banner" style={{ borderColor: 'var(--warning)', marginBottom: 8, fontSize: 13 }}>
            <strong>{t(`${conflicts.length} schválených přepsání se nepodařilo přenést po změně nohy`, `${conflicts.length} approved override(s) could not be carried over after a leg change`)}</strong>
            <ul style={{ paddingLeft: 18 }}>
              {conflicts.map(c => <li key={c.conflictId}>{t(`Noha ${c.legIndex + 1}, období ${c.periodIndex + 1}: ${c.reason}`, `Leg ${c.legIndex + 1}, period ${c.periodIndex + 1}: ${c.reason}`)}</li>)}
            </ul>
          </div>
        )}
        {ledger === null && <p style={{ fontSize: 13 }}>{t('Načítám harmonogram…', 'Loading the schedule…')}</p>}
        {ledger && !ledger.ok && <ReadFailure result={ledger} what={t('Harmonogram peněžních toků', 'The cash-flow ledger')} />}
        {ledger?.ok && <CashFlowLedgerView dealId={deal.dealId} ledger={ledger.data} actor={actor} canPropose={canPropose} canDecide={canDecide} onChanged={() => void loadLedger()} />}
      </Section>

      <Section title={t('Akce nad obchodem', 'Deal workbench')} id="deal-workbench-actions">
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <button type="button" className="btn btn-secondary btn-sm" aria-expanded={ticket === 'open'} onClick={() => setTicket(v => (v === 'open' ? 'closed' : 'open'))}>
            {ticket === 'open' ? t('Skrýt tiket obchodu', 'Hide deal ticket') : deal.state === 'DRAFT' && canEdit ? t('Upravit tiket obchodu', 'Edit deal ticket') : t('Otevřít tiket obchodu', 'Open deal ticket')}
          </button>
          <button type="button" className="btn btn-secondary btn-sm" aria-expanded={showHistory} onClick={() => void openHistory()}>{t('Historie změn', 'Change history')}</button>
          {canConfirm && !ownDeal && deal.state !== 'DRAFT' && deal.state !== 'CANCELLED' && (
            <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void sendConfirmation()}>{t('Odeslat konfirmaci', 'Send confirmation')}</button>
          )}
          {canConfirm && ownDeal && deal.state !== 'DRAFT' && (
            <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{t('Konfirmaci neodesílá autor obchodu.', 'The deal’s creator does not send the confirmation.')}</span>
          )}
        </div>
        {showHistory && (
          <div style={{ marginTop: 12 }} data-testid="deal-history">
            {history === null && <p style={{ fontSize: 13 }}>{t('Načítám historii…', 'Loading history…')}</p>}
            {history && !history.ok && (history.kind === 'not_available' ? <NotAvailable what={t('Historie změn', 'Change history')} /> : <ReadFailure result={history} what={t('Historie změn', 'Change history')} />)}
            {history?.ok && (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                <caption className="sr-only" style={{ position: 'absolute', left: -9999 }}>{t('Verze obchodu', 'Deal versions')}</caption>
                <thead><tr>{[t('Verze', 'Version'), t('Čas', 'When'), t('Kdo', 'Who'), t('Co se změnilo', 'What changed')].map(h => <th key={h} scope="col" style={th}>{h}</th>)}</tr></thead>
                <tbody>
                  {history.data.versions.map(v => (
                    <tr key={v.version}>
                      <td style={td}>{v.version}{v.scheduleVersion != null && <StatusBadge status="SCHEDULE" tone="neutral" label={t(`harmonogram v${v.scheduleVersion}`, `schedule v${v.scheduleVersion}`)} />}</td>
                      <td style={td}>{dateTime(v.createdAt)}</td>
                      <td style={td}>{v.createdBy ?? '—'}</td>
                      <td style={td}>{(v.changes ?? []).join('; ') || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}
      </Section>

      {ticket === 'open' && (
        <DealTicket mode="edit" deal={deal} canCreate={canEdit} canBackOffice={false} actor={actor} onSaved={() => onChanged()} />
      )}
    </div>
  )
}
