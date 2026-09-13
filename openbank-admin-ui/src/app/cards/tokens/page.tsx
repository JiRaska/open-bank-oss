// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useMemo, useState } from 'react'
import Link from 'next/link'
import { useSession } from 'next-auth/react'
import { Smartphone, RefreshCw, PauseCircle, PlayCircle, Trash2 } from 'lucide-react'
import { AuthGuard } from '@/components/auth/AuthGuard'
import { PageHeader } from '@/components/ui/PageHeader'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { hasPermission } from '@/lib/auth/roles'
import { svcUrl } from '@/lib/services/bff'
import { useServiceResource } from '@/lib/services/useServiceResource'
import type { NetworkTokenStatus, TokenListResponse } from '@/lib/cards/lifecycleTypes'

/**
 * Card Center — network tokens for one card (ADR-0283 phase 3, issue #8811).
 *
 * ## The one thing this screen exists to say
 *
 * Where the answer came from. The service returns `source: NETWORK | LOCAL_MIRROR` on every read,
 * and this page renders the difference loudly, because the two look identical in the rows: a
 * mirror row can say ACTIVE about a token the network suspended an hour ago. A screen that dropped
 * `source` would present a stale state as current and an operator would have no way to tell.
 *
 * ## Why a card id has to be typed in
 *
 * There is no "all tokens" route, deliberately: the token vault belongs to the network and this
 * bank holds only a mirror, so a fleet-wide list would be a list of what we happen to have recorded
 * rather than of what exists. The card is the unit the network answers about.
 */
export default function CardTokensPage() {
  const { t } = useLanguage()
  const { data: session } = useSession()
  const canManage = hasPermission(session?.user?.roles ?? [], 'cards:issue')

  const [cardIdInput, setCardIdInput] = useState('')
  const [cardId, setCardId] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const { data, loading, unavailable, reload } = useServiceResource<TokenListResponse>(
    cardId ? svcUrl('card-processing-service', `/api/v1/card-tokens/card/${cardId}`) : null,
  )

  const tokens = useMemo(() => data?.tokens ?? [], [data])
  const degraded = data?.source === 'LOCAL_MIRROR'

  async function changeStatus(tokenReference: string, status: NetworkTokenStatus) {
    setBusy(tokenReference)
    setActionError(null)
    try {
      const res = await fetch(
        svcUrl('card-processing-service', `/api/v1/card-tokens/${encodeURIComponent(tokenReference)}/status`),
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ status }),
        },
      )
      if (!res.ok) {
        // The service answers 409 with a machine-readable reason. Showing the reason rather than
        // the status code is what lets an operator tell "this token is deleted, which is final"
        // from "the network could not be reached", which need different next steps.
        const body = (await res.json().catch(() => null)) as { reason?: string; message?: string } | null
        setActionError(body?.message ?? body?.reason ?? t('Změna se nezdařila.', 'The change did not go through.'))
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
          title={t('Síťové tokeny', 'Network tokens')}
          subtitle={t(
            'Co má karta zřízeno v peněženkách a u obchodníků, a odkud ta odpověď pochází',
            'What a card has provisioned in wallets and at merchants, and where that answer came from',
          )}
          icon={<Smartphone className="h-6 w-6 text-slate-500" />}
          breadcrumb={
            <Link href="/cards" className="text-xs text-slate-500 hover:text-slate-700">
              {t('Karty', 'Cards')}
            </Link>
          }
        />

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
          <button
            type="submit"
            className="rounded bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-700"
          >
            {t('Načíst', 'Load')}
          </button>
          {cardId && (
            <button
              type="button"
              onClick={reload}
              className="inline-flex items-center gap-1 rounded border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              {t('Obnovit', 'Refresh')}
            </button>
          )}
        </form>

        {actionError && (
          <div className="rounded border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">{actionError}</div>
        )}

        {!cardId && (
          <p className="text-sm text-slate-600">
            {t(
              'Zadejte ID karty. Trezor tokenů patří síti — banka drží jen svůj záznam, takže seznam „všech tokenů“ by byl seznamem toho, co jsme si zapsali, ne toho, co existuje.',
              'Enter a card id. The token vault belongs to the network and this bank holds only its own record, so a list of "all tokens" would be a list of what we recorded, not of what exists.',
            )}
          </p>
        )}

        {cardId && unavailable && (
          <DataUnavailable
            kind={unavailable.kind}
            service="Card-processing"
            feature={t('Síťové tokeny', 'Network tokens')}
          />
        )}

        {cardId && !unavailable && data && (
          <>
            {degraded ? (
              <div className="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
                <strong>{t('Síť neodpověděla.', 'The network did not answer.')}</strong>{' '}
                {t(
                  'Níže je poslední záznam banky, který může být zastaralý — token mohl být mezitím pozastaven nebo smazán. Nejde o aktuální stav u sítě.',
                  'Below is the bank’s last record, which may be stale — a token may have been suspended or deleted since. This is not the network’s current state.',
                )}
                {data.degradedReason && (
                  <div className="mt-1 text-xs text-amber-800">{data.degradedReason}</div>
                )}
              </div>
            ) : (
              <div className="rounded border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">
                {t(
                  'Odpověděla síť — stav níže je ten, který síť hlásí právě teď.',
                  'The network answered — the state below is what it reports right now.',
                )}
              </div>
            )}

            {tokens.length === 0 ? (
              <p className="text-sm text-slate-600">
                {t('Tato karta nemá zřízený žádný token.', 'This card has no provisioned token.')}
              </p>
            ) : (
              <div className="overflow-x-auto rounded border border-slate-200">
                <table className="min-w-full divide-y divide-slate-200 text-sm">
                  <thead className="bg-slate-50">
                    <tr>
                      <th className="px-4 py-3 text-left font-medium text-slate-700">
                        {t('Žadatel', 'Requestor')}
                      </th>
                      <th className="px-4 py-3 text-left font-medium text-slate-700">
                        {t('Reference tokenu', 'Token reference')}
                      </th>
                      <th className="px-4 py-3 text-left font-medium text-slate-700">{t('Stav', 'Status')}</th>
                      <th className="px-4 py-3 text-left font-medium text-slate-700">{t('Síť', 'Scheme')}</th>
                      <th className="px-4 py-3 text-left font-medium text-slate-700">
                        {t('Akce', 'Actions')}
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100 bg-white">
                    {tokens.map(token => (
                      <tr key={token.tokenReference}>
                        <td className="px-4 py-3">
                          <div className="font-medium text-slate-900">{token.requestorLabel}</div>
                          <div className="text-xs text-slate-500">{token.requestorId}</div>
                        </td>
                        <td className="px-4 py-3">
                          <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-800">
                            {token.tokenReference}
                          </code>
                          <div className="mt-1 text-xs text-slate-500">•••• {token.last4}</div>
                        </td>
                        <td className="px-4 py-3">
                          <span className={`rounded border px-1.5 py-0.5 text-xs ${statusTone(token.status)}`}>
                            {token.status}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-xs text-slate-600">{token.scheme}</td>
                        <td className="px-4 py-3">
                          {canManage && token.status !== 'DELETED' ? (
                            <div className="flex flex-wrap gap-1">
                              {token.status === 'ACTIVE' ? (
                                <ActionButton
                                  disabled={busy === token.tokenReference}
                                  onClick={() => changeStatus(token.tokenReference, 'SUSPENDED')}
                                  icon={<PauseCircle className="h-3.5 w-3.5" />}
                                  label={t('Pozastavit', 'Suspend')}
                                />
                              ) : (
                                <ActionButton
                                  disabled={busy === token.tokenReference}
                                  onClick={() => changeStatus(token.tokenReference, 'ACTIVE')}
                                  icon={<PlayCircle className="h-3.5 w-3.5" />}
                                  label={t('Obnovit token', 'Resume')}
                                />
                              )}
                              <ActionButton
                                disabled={busy === token.tokenReference}
                                onClick={() => changeStatus(token.tokenReference, 'DELETED')}
                                icon={<Trash2 className="h-3.5 w-3.5" />}
                                label={t('Smazat', 'Delete')}
                                tone="danger"
                              />
                            </div>
                          ) : (
                            <span className="text-xs text-slate-500">
                              {token.status === 'DELETED'
                                ? t('smazaný token je konečný stav', 'a deleted token is terminal')
                                : t('bez oprávnění', 'no permission')}
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}

        {cardId && loading && !data && (
          <p className="text-sm text-slate-500">{t('Načítám…', 'Loading…')}</p>
        )}
      </div>
    </AuthGuard>
  )
}

function statusTone(status: NetworkTokenStatus): string {
  if (status === 'ACTIVE') return 'border-emerald-200 bg-emerald-50 text-emerald-700'
  if (status === 'SUSPENDED') return 'border-amber-200 bg-amber-50 text-amber-700'
  return 'border-slate-200 bg-slate-100 text-slate-600'
}

function ActionButton({
  onClick,
  icon,
  label,
  disabled,
  tone,
}: {
  onClick: () => void
  icon: React.ReactNode
  label: string
  disabled?: boolean
  tone?: 'danger'
}) {
  const base = tone === 'danger'
    ? 'border-rose-200 text-rose-700 hover:bg-rose-50'
    : 'border-slate-300 text-slate-700 hover:bg-slate-50'
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`inline-flex items-center gap-1 rounded border px-2 py-1 text-xs disabled:opacity-50 ${base}`}
    >
      {icon}
      {label}
    </button>
  )
}
