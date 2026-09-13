// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// One place where the console WRITES to card-issuance.
//
// The Cards list, the card detail view and the issue flow all mutate the same
// aggregate through the same five-ish endpoints, and each one needs the identical
// three behaviours: a per-action busy key, an ADR-0155 four-eyes 202 that must NOT
// be reported as success, and a classified failure turned into calm bilingual copy.
// Duplicating that per screen is how the three drift apart, so it lives here.
//
// X-Operator-Id is deliberately NOT set on any of these requests: the BFF derives
// it from the server session and refuses a client-supplied one (a browser can set
// any header, so an operator identity chosen in the browser is not evidence of
// anything). See src/app/api/svc/[service]/[...path]/route.ts.

'use client'

import { useCallback, useState } from 'react'
import { useSingleFlight, wasSkipped } from '@/lib/mutations/singleFlight'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl } from '@/lib/services/bff'
import { classifyMutation, type MutationFailure } from './mutations'
import type { CardTransition } from './lifecycle'
import type { Card, CardControls } from './types'
import type { IssueCardRequestBody } from './issue'

export type Feedback =
  | { tone: 'ok'; text: string }
  | { tone: 'info'; text: string }
  | { tone: 'error'; text: string }

const CARD_SVC = 'card-issuance-service'
const WRITE_TIMEOUT_MS = 15_000

export interface CardOperations {
  /** `${cardId}:${action}` while that action is in flight, else null. */
  busy: string | null
  feedback: Feedback | null
  setFeedback: (f: Feedback | null) => void
  failureCopy: (kind: MutationFailure) => string
  runTransition: (card: Card, transition: CardTransition, reason?: string) => Promise<boolean>
  saveLimits: (card: Card, dailyMinorUnits: number, monthlyMinorUnits: number) => Promise<boolean>
  saveControls: (card: Card, controls: CardControls) => Promise<boolean>
  issueCard: (body: IssueCardRequestBody, idempotencyKey: string) => Promise<Card | null>
}

export function useCardOperations(onChanged?: () => void): CardOperations {
  const { t } = useLanguage()
  const [busy, setBusy] = useState<string | null>(null)
  const flight = useSingleFlight()
  const [feedback, setFeedback] = useState<Feedback | null>(null)

  const failureCopy = useCallback((kind: MutationFailure): string => {
    switch (kind) {
      case 'illegal_transition':
        return t(
          'Služba operaci odmítla — stav karty se mezitím změnil. Obnovte data a zkuste to znovu.',
          'The service refused the operation — the card changed in the meantime. Refresh and try again.',
        )
      case 'conflict:CARD_QUOTA_EXCEEDED':
        return t(
          'Klient už vyčerpal počet karet, které tento produkt dovoluje.',
          'The client already holds every card this product allows.',
        )
      case 'conflict:CARD_PRODUCT_DISABLED':
        return t(
          'Produkt tohoto účtu karty nevydává.',
          'The account’s product does not carry cards.',
        )
      case 'conflict:CARD_NETWORK_NOT_ALLOWED':
        return t(
          'Zvolená karetní síť není u tohoto produktu povolena.',
          'The chosen card network is not allowed on this product.',
        )
      case 'conflict:CARD_VIRTUAL_NOT_ALLOWED':
        return t(
          'Tento produkt nedovoluje virtuální ani jednorázové karty.',
          'This product allows neither virtual nor single-use cards.',
        )
      case 'conflict':
        return t(
          'Služba operaci odmítla kvůli konfliktu s pravidlem produktu (nárok na kartu).',
          'The service refused the operation as conflicting with a product rule (card entitlement).',
        )
      case 'forbidden':
        return t(
          'Vaše role nemá oprávnění pro tuto operaci s kartou — vyžaduje se operátor, správce nebo compliance.',
          'Your role is not permitted to perform this card operation — operator, admin or compliance is required.',
        )
      case 'unauthorized':
        return t(
          'Vaše přihlášení vypršelo. Přihlaste se prosím znovu a operaci zopakujte.',
          'Your session has expired. Please sign in again and repeat the operation.',
        )
      case 'not_found':
        return t(
          'Tato karta už v card-issuance neexistuje. Obnovte seznam.',
          'This card no longer exists in card-issuance. Refresh the list.',
        )
      case 'not_deployed':
        return t(
          'card-issuance není v tomto prostředí nasazená, takže operaci nelze provést.',
          'card-issuance is not deployed in this environment, so the operation cannot run.',
        )
      case 'scaled_to_zero':
        return t(
          'card-issuance je uspaná do nuly replik (KEDA) a právě se probouzí. Zkuste to prosím za okamžik znovu.',
          'card-issuance is scaled to zero (KEDA) and is waking up. Please try again in a moment.',
        )
      case 'unreachable':
        return t(
          'card-issuance je nasazená, ale na požadavek neodpověděla včas. Zkuste to prosím za chvíli znovu.',
          'card-issuance is deployed but did not answer in time. Please try again shortly.',
        )
      default:
        return t(
          'Operace se nedokončila. Zkuste to prosím znovu; podrobnosti jsou v auditním logu služby.',
          'The operation did not complete. Please try again; the details are in the service audit log.',
        )
    }
  }, [t])

  const fourEyesCopy = useCallback(() => t(
    'Operace čeká na schválení druhým operátorem (čtyři oči).',
    'The operation is queued for a second operator’s approval (four-eyes).',
  ), [t])

  /**
   * Send one mutation. Resolves to the parsed body on success, `'parked'` when
   * OPA held it for a second pair of eyes (ADR-0155, HTTP 202 — reporting that as
   * success would tell the operator the card moved when it did not), or null on
   * any failure, with the feedback banner already set.
   */
  const send = useCallback(async (
    busyKey: string,
    path: string,
    init: RequestInit,
    okText: string,
  ): Promise<unknown | 'parked' | null> => {
    // `setBusy` only disables the control on the NEXT render, so two activations in
    // the same event-loop turn both used to reach `fetch` — two lifecycle
    // transitions, two limit writes. The lock is claimed synchronously here, at the
    // single choke point every card mutation already goes through.
    const outcome = await flight.run(busyKey, async () => {
    setBusy(busyKey)
    setFeedback(null)
    try {
      const res = await fetch(svcUrl(CARD_SVC, path), {
        cache: 'no-store',
        signal: AbortSignal.timeout(WRITE_TIMEOUT_MS),
        ...init,
        headers: { 'Content-Type': 'application/json', ...(init.headers ?? {}) },
      })
      if (res.status === 202) {
        setFeedback({ tone: 'info', text: fourEyesCopy() })
        return 'parked'
      }
      if (!res.ok) {
        setFeedback({ tone: 'error', text: failureCopy(await classifyMutation(res)) })
        return null
      }
      const body = await res.json().catch(() => null)
      setFeedback({ tone: 'ok', text: okText })
      onChanged?.()
      return body ?? {}
    } catch {
      setFeedback({ tone: 'error', text: failureCopy('unreachable') })
      return null
    } finally {
      setBusy(null)
    }
    })
    // A rejected re-entry is not a failure: the first attempt is still running and
    // owns the feedback banner. Report it as "nothing happened", never as an error.
    return wasSkipped(outcome) ? null : outcome
  }, [flight, failureCopy, fourEyesCopy, onChanged])

  const runTransition = useCallback(async (card: Card, transition: CardTransition, reason?: string) => {
    const out = await send(
      `${card.id}:${transition.action}`,
      `/api/v1/cards/${card.id}/${transition.action}`,
      {
        method: 'POST',
        // Only block/cancel take a body (CardStatusRequest); the reversible
        // endpoints declare no entity parameter.
        body: transition.reason ? JSON.stringify({ reason }) : undefined,
      },
      t(
        `Karta ${card.maskedPan} je nyní ve stavu ${transition.to}.`,
        `Card ${card.maskedPan} is now ${transition.to}.`,
      ),
    )
    return out !== null && out !== 'parked'
  }, [send, t])

  const saveLimits = useCallback(async (card: Card, dailyMinorUnits: number, monthlyMinorUnits: number) => {
    const out = await send(
      `${card.id}:limits`,
      `/api/v1/cards/${card.id}/limits`,
      { method: 'PUT', body: JSON.stringify({ dailyMinorUnits, monthlyMinorUnits }) },
      t('Limity karty byly uloženy.', 'The card’s limits have been saved.'),
    )
    return out !== null && out !== 'parked'
  }, [send, t])

  const saveControls = useCallback(async (card: Card, controls: CardControls) => {
    const out = await send(
      `${card.id}:controls`,
      `/api/v1/cards/${card.id}/controls`,
      { method: 'PUT', body: JSON.stringify(controls) },
      t('Nastavení kanálů bylo uloženo.', 'The channel controls have been saved.'),
    )
    return out !== null && out !== 'parked'
  }, [send, t])

  const issueCard = useCallback(async (body: IssueCardRequestBody, idempotencyKey: string) => {
    const out = await send(
      'issue',
      '/api/v1/cards',
      {
        method: 'POST',
        // The key is generated once per attempt and REUSED on retry, so a retry
        // after a dropped response replays the same card instead of minting a
        // second one (CardResource requires the header and rejects a blank one).
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(body),
      },
      t('Karta byla vydána.', 'The card has been issued.'),
    )
    if (out === null || out === 'parked') return null
    return out as Card
  }, [send, t])

  return { busy, feedback, setFeedback, failureCopy, runTransition, saveLimits, saveControls, issueCard }
}
