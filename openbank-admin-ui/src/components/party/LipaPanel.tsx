// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

// The Lípa half of Customer 360 (ADR-0282 D8). It shows a party's Lístek balance, what they have
// earned this year against the annual cap, and when the oldest lot expires.
//
// D8's claim is reciprocal transparency: what the operator sees here is what the customer sees in
// their own app, from the same source. So this panel deliberately carries no operator-only field —
// the moment it gained one, the claim on the console's Principles tab would stop being true, and
// nothing else in the system would notice.
//
// Source: loyalty-service's own ledger through the admin-ui BFF, NOT the analytics silver layer.
// That is a different source from the rest of this page, and the panel says so rather than letting
// one "as of" chip appear to cover both.

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowRight, HelpCircle, Leaf } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import type { LoyaltyPartyResponse } from '@/app/api/loyalty/party/[partyId]/route'

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; data: LoyaltyPartyResponse }
  | { kind: 'unknown'; why: string }

export function LipaPanel({ partyId }: { partyId: string }) {
  const { t, language } = useLanguage()
  const [state, setState] = useState<State>({ kind: 'loading' })
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'

  // No loading reset in the effect: the parent mounts this with key={partyId}, so a new selection
  // is a fresh component whose initial state is already `loading`.
  useEffect(() => {
    let live = true
    ;(async () => {
      try {
        const res = await fetch(`/api/loyalty/party/${partyId}`, { cache: 'no-store' })
        if (!res.ok) {
          // The BFF route already classifies upstream trouble into a typed state, so a non-ok
          // response here is the admin-ui route itself failing. It is reported as a kind, never as
          // a raw status: `graceful-states.guard.test.ts` bans an "HTTP ${status}" string in a
          // page precisely because an operator reads it as the app being broken.
          if (live) setState({ kind: 'unknown', why: res.status === 403 ? 'unauthorized' : 'unreachable' })
          return
        }
        const body = (await res.json()) as LoyaltyPartyResponse
        if (!live) return
        setState(body.state === 'ok' ? { kind: 'ok', data: body } : { kind: 'unknown', why: body.state })
      } catch {
        if (live) setState({ kind: 'unknown', why: 'unreachable' })
      }
    })()
    return () => { live = false }
  }, [partyId])

  return (
    <div className="card" style={{ padding: '16px 20px', marginBottom: '20px' }}>
      <h2 className="section-title" style={{ marginBottom: '4px', display: 'flex', alignItems: 'center', gap: 8 }}>
        <Leaf size={16} />
        {t('Lípa — věrnostní zůstatek', 'Lípa — loyalty balance')}
      </h2>
      <p style={{ margin: '0 0 12px', fontSize: '11px', color: 'var(--text-secondary)' }}>
        {t(
          'Zdroj: loyalty-service, vlastní evidence Lístků (ADR-0282) — ne silver vrstva, takže „Stav k“ výše se na tento panel nevztahuje. Je to totéž, co vidí klient ve své aplikaci.',
          'Source: loyalty-service, its own Lístek ledger (ADR-0282) — not the silver layer, so the "As of" chip above does not describe this panel. It is the same view the customer has in their app.',
        )}
      </p>

      {state.kind === 'loading' && (
        <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{t('Načítám…', 'Loading…')}</span>
      )}

      {state.kind === 'ok' && (
        <>
          <div style={{ display: 'flex', gap: '24px', flexWrap: 'wrap', alignItems: 'baseline' }}>
            <div>
              <div style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>{t('Zůstatek', 'Balance')}</div>
              <div style={{ fontSize: '20px', fontWeight: 600 }} data-testid="lipa-balance">
                {state.data.balance.toLocaleString(locale)}
              </div>
            </div>
            <div>
              <div style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>{t('Získáno letos', 'Earned this year')}</div>
              <div style={{ fontSize: '20px', fontWeight: 600 }}>{state.data.earnedThisYear.toLocaleString(locale)}</div>
            </div>
            <div>
              <div style={{ fontSize: '11px', color: 'var(--text-secondary)' }}>{t('Nejbližší expirace', 'Next expiry')}</div>
              <div style={{ fontSize: '20px', fontWeight: 600 }}>
                {state.data.nextExpiry
                  ? new Date(state.data.nextExpiry).toLocaleDateString(locale)
                  : t('žádná', 'none')}
              </div>
            </div>
          </div>
          {/* A zero balance is a MEASURED zero and is worded so it cannot be read as the unknown
              state below. "Nothing earned yet" and "we could not ask" look identical if both
              render as an absence, and only one of them is safe to act on. */}
          {state.data.balance === 0 && state.data.history.length === 0 && (
            <p style={{ margin: '10px 0 0', fontSize: '12px', color: 'var(--text-secondary)' }}>
              {t('Klient zatím nezískal žádné Lístky.', 'This customer has not earned any Lístky yet.')}
            </p>
          )}
          <Link
            href="/loyalty"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 4, marginTop: 12, fontSize: '12px', fontWeight: 600 }}
          >
            {t('Otevřít Lípu', 'Open Lípa')}<ArrowRight size={12} />
          </Link>
        </>
      )}

      {state.kind === 'unknown' && (
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: 'var(--text-secondary)' }}>
          <HelpCircle size={16} />
          <span>
            {t(
              `Zůstatek nelze zjistit (loyalty-service: ${state.why}). NEJDE o potvrzení, že klient žádné Lístky nemá.`,
              `Balance unavailable (loyalty-service: ${state.why}). This is NOT a confirmation that the customer has no Lístky.`,
            )}
          </span>
        </div>
      )}
    </div>
  )
}
