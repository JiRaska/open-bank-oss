// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useMemo, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { Scale, RefreshCw, FileUp } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui/PageHeader'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { hasPermission } from '@/lib/auth/roles'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import {
  daysUntil,
  formatMinorUnits,
  type DisputeListResponse,
  type DisputeStatus,
  type DisputeView,
} from '@/lib/cards/lifecycleTypes'

/**
 * Card Center — the chargeback desk (ADR-0283 phase 3, issue #8811).
 *
 * ## Two vocabularies, both shown
 *
 * Every row carries the bank's status AND the network's own status string. They are deliberately
 * not merged: the scheme's vocabulary differs per network and moves with their release cycles, and
 * card-processing carries it verbatim rather than mapping it. An operator who sees only one of them
 * cannot tell an unrecognised scheme state from a state the bank decided.
 *
 * ## The deadline is the number that costs money
 *
 * `respondByDate` is the network's own. Days remaining is computed here and shown NEGATIVE when the
 * window has closed, never clamped: a missed deadline and an urgent one must not render the same,
 * because they call for different work.
 *
 * ## What this desk is NOT
 *
 * The customer's complaint. That belongs to `openbank-dispute-service` (ADR-0117) — who complained,
 * what evidence was gathered, what remediation the investigation supports. This screen is the case
 * the NETWORK holds. The two are not wired together yet, and the gap is tracked as its own issue:
 * a dispute-service case can be resolved as CHARGEBACK today without any chargeback being filed.
 */
export default function CardDisputesPage() {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const { data: session } = useSession()
  const canAct = hasPermission(session?.user?.roles ?? [], 'cards:issue')

  const [cardIdInput, setCardIdInput] = useState('')
  const [cardId, setCardId] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const { data, loading, unavailable, reload } = useServiceResource<DisputeListResponse>(
    cardId ? svcUrl('card-processing-service', `/api/v1/card-disputes/card/${cardId}`) : null,
  )
  const disputes = useMemo(() => data?.disputes ?? [], [data])
  const now = new Date()

  async function post(path: string, body?: unknown) {
    setBusy(path)
    setActionError(null)
    try {
      const res = await fetch(svcUrl('card-processing-service', path), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      if (!res.ok) {
        const parsed = (await res.json().catch(() => null)) as { reason?: string; message?: string } | null
        // The reason, not the status code: "the case is closed and accepts no further evidence" and
        // "the dispute binding could not answer" are both 409 and need different next steps.
        setActionError(parsed?.message ?? parsed?.reason ?? t('Akce se nezdařila.', 'The action did not go through.'))
        return
      }
      reload()
    } catch {
      setActionError(t('Službu se nepodařilo oslovit.', 'The service could not be reached.'))
    } finally {
      setBusy(null)
    }
  }

  return (
    <AuthGuard>
      <div className="space-y-6">
        <PageHeader
          title={t('Karetní spory', 'Card disputes')}
          subtitle={t(
            'Případy, které banka vede u karetní sítě — se lhůtou sítě a jejím vlastním stavem',
            'The cases this bank holds with the card network — with the network’s deadline and its own status',
          )}
          icon={<Scale className="h-6 w-6 text-slate-500" />}
          breadcrumb={
            <Link href="/cards" className="text-xs text-slate-500 hover:text-slate-700">
              {t('Karty', 'Cards')}
            </Link>
          }
        />

        <div className="rounded border border-slate-200 bg-slate-50 p-3 text-sm text-slate-700">
          {t(
            'Toto je případ u sítě, nikoli stížnost zákazníka. Zákaznický případ vede dispute-service (ADR-0117); obojí se týká stejných peněz a ani jedno není kopií druhého.',
            'This is the case held with the network, not the customer’s complaint. The customer case lives in dispute-service (ADR-0117); both concern the same money and neither is a copy of the other.',
          )}
        </div>

        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={e => {
            e.preventDefault()
            setActionError(null)
            setCardId(cardIdInput.trim() || null)
          }}
        >
          <label className="flex flex-col gap-1 text-xs text-slate-600">
            {t('ID karty', 'Card id')}
            <input
              value={cardIdInput}
              onChange={e => setCardIdInput(e.target.value)}
              className="w-96 rounded border border-slate-300 px-2 py-1 text-sm"
              placeholder="00000000-0000-0000-0000-000000000000"
            />
          </label>
          <button type="submit" className="rounded bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-700">
            {t('Načíst', 'Load')}
          </button>
        </form>

        {actionError && (
          <div className="rounded border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">{actionError}</div>
        )}

        {cardId && unavailable && (
          <DataUnavailable
            kind={unavailable.kind}
            service="Card-processing"
            feature={t('Karetní spory', 'Card disputes')}
          />
        )}

        {cardId && !unavailable && data && disputes.length === 0 && (
          <p className="text-sm text-slate-600">
            {t('Pro tuto kartu není u sítě veden žádný případ.', 'No case is held with the network for this card.')}
          </p>
        )}

        {cardId && loading && !data && <p className="text-sm text-slate-500">{t('Načítám…', 'Loading…')}</p>}

        {disputes.length > 0 && (
          <div className="overflow-x-auto rounded border border-slate-200">
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr>
                  <th className="px-4 py-3 text-left font-medium text-slate-700">{t('Případ u sítě', 'Network case')}</th>
                  <th className="px-4 py-3 text-left font-medium text-slate-700">{t('Částka', 'Amount')}</th>
                  <th className="px-4 py-3 text-left font-medium text-slate-700">{t('Stav banky', 'Bank status')}</th>
                  <th className="px-4 py-3 text-left font-medium text-slate-700">{t('Stav sítě', 'Scheme status')}</th>
                  <th className="px-4 py-3 text-left font-medium text-slate-700">{t('Lhůta', 'Deadline')}</th>
                  <th className="px-4 py-3 text-left font-medium text-slate-700">{t('Akce', 'Actions')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 bg-white">
                {disputes.map(dispute => (
                  <tr key={dispute.id}>
                    <td className="px-4 py-3">
                      <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-800">
                        {dispute.networkCaseId}
                      </code>
                      <div className="mt-1 text-xs text-slate-500">
                        {t('důvod', 'reason')} {dispute.reasonCode} · {dispute.scheme}
                      </div>
                    </td>
                    <td className="px-4 py-3 text-slate-800">
                      {formatMinorUnits(dispute.amountMinorUnits, dispute.currencyCode, locale)}
                    </td>
                    <td className="px-4 py-3">
                      <span className={`rounded border px-1.5 py-0.5 text-xs ${statusTone(dispute.status)}`}>
                        {dispute.status}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-xs text-slate-600">{dispute.schemeStatus}</td>
                    <td className="px-4 py-3">
                      <Deadline dispute={dispute} now={now} />
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-1">
                        <button
                          type="button"
                          disabled={busy !== null}
                          onClick={() => post(`/api/v1/card-disputes/${dispute.id}/refresh`)}
                          className="inline-flex items-center gap-1 rounded border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                        >
                          <RefreshCw className="h-3.5 w-3.5" />
                          {t('Zjistit u sítě', 'Ask the network')}
                        </button>
                        {canAct && dispute.status === 'OPEN' && (
                          <EvidenceButton
                            disabled={busy !== null}
                            onSubmit={ref => post(`/api/v1/card-disputes/${dispute.id}/evidence`, {
                              documentReference: ref,
                              note: null,
                            })}
                          />
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </AuthGuard>
  )
}

/**
 * Days remaining, negative when the window has closed.
 *
 * The expired case is rendered differently rather than being clamped to zero: "the deadline passed"
 * and "the deadline is today" are the two states an operator must never confuse.
 */
function Deadline({ dispute, now }: { dispute: DisputeView; now: Date }) {
  const { t } = useLanguage()
  const days = daysUntil(dispute.respondByDate, now)
  if (days === null) {
    return <span className="text-xs text-slate-500">{t('síť lhůtu neuvedla', 'the network gave none')}</span>
  }
  if (days < 0) {
    return (
      <span className="rounded border border-rose-200 bg-rose-50 px-1.5 py-0.5 text-xs text-rose-700">
        {t(`prošlo před ${Math.abs(days)} dny`, `${Math.abs(days)} days past`)}
      </span>
    )
  }
  const tone = days <= 5
    ? 'border-amber-200 bg-amber-50 text-amber-700'
    : 'border-slate-200 bg-slate-50 text-slate-600'
  return (
    <span className={`rounded border px-1.5 py-0.5 text-xs ${tone}`}>
      {t(`zbývá ${days} dní`, `${days} days left`)}
    </span>
  )
}

function EvidenceButton({ disabled, onSubmit }: { disabled: boolean; onSubmit: (reference: string) => void }) {
  const { t } = useLanguage()
  const [reference, setReference] = useState('')
  const [open, setOpen] = useState(false)

  if (!open) {
    return (
      <button
        type="button"
        disabled={disabled}
        onClick={() => setOpen(true)}
        className="inline-flex items-center gap-1 rounded border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-50"
      >
        <FileUp className="h-3.5 w-3.5" />
        {t('Doložit důkaz', 'File evidence')}
      </button>
    )
  }
  return (
    <span className="flex items-center gap-1">
      <input
        value={reference}
        onChange={e => setReference(e.target.value)}
        // A REFERENCE, never the document: the evidence itself never travels through this screen or
        // the topic behind it, only a handle to it.
        placeholder={t('reference dokumentu', 'document reference')}
        className="w-44 rounded border border-slate-300 px-2 py-1 text-xs"
      />
      <button
        type="button"
        disabled={disabled || reference.trim() === ''}
        onClick={() => {
          onSubmit(reference.trim())
          setOpen(false)
          setReference('')
        }}
        className="rounded bg-slate-900 px-2 py-1 text-xs text-white disabled:opacity-50"
      >
        {t('Odeslat', 'Submit')}
      </button>
    </span>
  )
}

function statusTone(status: DisputeStatus): string {
  if (status === 'WON') return 'border-emerald-200 bg-emerald-50 text-emerald-700'
  if (status === 'LOST') return 'border-rose-200 bg-rose-50 text-rose-700'
  if (status === 'EVIDENCE_SUBMITTED') return 'border-sky-200 bg-sky-50 text-sky-700'
  if (status === 'WITHDRAWN') return 'border-slate-200 bg-slate-100 text-slate-600'
  return 'border-amber-200 bg-amber-50 text-amber-700'
}
