// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import { ShieldAlert, ShieldCheck, HelpCircle } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'
import { svcUrl, classifyBffFailure, type BffFailure } from '@/lib/services/bff'

// Issue #4265 item 2 — the operator-visible half of ADR-0220 D3.5. Reads engagement-service's
// `GET /api/v1/eligibility/adverse-states` through the ADR-0056 BFF proxy, which relays the
// operator's own Keycloak token, so what an operator may see here is decided by backend RBAC and
// the shared OPA policy (`operator-read-any`), not by this component.
//
// SOURCE, stated because the issue asks for it: `party_adverse_state` in engagement-service —
// event-materialised, authoritative for the suppression decision itself since it is the very row
// `ResolveSurfaceUseCase` reads. That is a different source from the rest of this page, which is
// the analytics silver layer, and the panel says so on screen rather than letting an operator
// assume one `asOf` covers both.
//
// ADR-0210 D3 is not weakened: flag names and a party id, no balance, no transaction row, no KYC
// content — the exact categories D3 excludes are the ones this endpoint does not carry.

/** Mirrors engagement-service's `AdverseState` enum. Not exhaustive-checked at runtime: an unknown
 *  value must still render (as its raw name) rather than vanish, since a value this UI has not
 *  heard of is the case where an operator most needs to see something. */
const LABELS: Record<string, { cs: string; en: string }> = {
  FRAUD_HOLD: { cs: 'Podezření na podvod (hold)', en: 'Fraud hold' },
  ARREARS: { cs: 'Po splatnosti', en: 'In arrears' },
  DISPUTE_OPENED: { cs: 'Otevřená reklamace', en: 'Open dispute' },
  ERASURE_REQUESTED: { cs: 'Žádost o výmaz', en: 'Erasure requested' },
}

interface AdverseStateResponse {
  partyId: string
  adverseStates: string[]
}

type State =
  | { kind: 'loading' }
  | { kind: 'ok'; states: string[] }
  | { kind: 'unknown'; why: BffFailure }

export function AdverseStatePanel({ partyId }: { partyId: string }) {
  const { t, language } = useLanguage()
  const [state, setState] = useState<State>({ kind: 'loading' })

  // No `setState({kind:'loading'})` reset here: the parent mounts this panel with `key={partyId}`,
  // so a new selection is a fresh component whose initial state is already `loading`. Resetting in
  // the effect body would be a second, cascading render of the same fact (react-hooks/
  // set-state-in-effect) — and, more to the point, would leave the previous party's badges on
  // screen for one render if the key were ever dropped.
  useEffect(() => {
    let live = true
    ;(async () => {
      try {
        const res = await fetch(svcUrl('engagement-service', '/api/v1/eligibility/adverse-states', { partyId }), {
          cache: 'no-store',
        })
        if (!res.ok) {
          const why = await classifyBffFailure(res)
          if (live) setState({ kind: 'unknown', why })
          return
        }
        const body = (await res.json()) as AdverseStateResponse
        if (live) setState({ kind: 'ok', states: body.adverseStates ?? [] })
      } catch {
        if (live) setState({ kind: 'unknown', why: 'unreachable' })
      }
    })()
    return () => {
      live = false
    }
  }, [partyId])

  const label = (s: string) => LABELS[s]?.[language === 'cs' ? 'cs' : 'en'] ?? s

  return (
    <div className="card" style={{ padding: '16px 20px', marginBottom: '20px' }}>
      <h2 className="section-title" style={{ marginBottom: '4px' }}>
        {t('Nepříznivé stavy (vyloučení z marketingu)', 'Adverse states (marketing exclusion)')}
      </h2>
      <p style={{ margin: '0 0 12px', fontSize: '11px', color: 'var(--text-secondary)' }}>
        {t(
          'Zdroj: engagement-service, tabulka party_adverse_state (ADR-0220 D3.5) — ne silver vrstva, takže „Stav k“ výše se na tento panel nevztahuje.',
          'Source: engagement-service, party_adverse_state (ADR-0220 D3.5) — not the silver layer, so the "As of" chip above does not describe this panel.',
        )}
      </p>

      {state.kind === 'loading' && (
        <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{t('Načítám…', 'Loading…')}</span>
      )}

      {/* An empty list is a MEASURED all-clear and says so. It is deliberately worded differently
          from the unknown state below: "no active flag" and "we could not ask" look identical to an
          operator if both render as an absence, and only one of them is safe to act on. */}
      {state.kind === 'ok' && state.states.length === 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px' }}>
          <ShieldCheck size={16} className="tone-text-success" />
          <span>{t('Žádné aktivní vyloučení', 'No active exclusion')}</span>
        </div>
      )}

      {state.kind === 'ok' && state.states.length > 0 && (
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
          <ShieldAlert size={16} className="tone-text-danger" />
          {/* Only states the service actually returned are rendered. No badge is drawn for a value
              that is merely declared in the enum: a permanently dark fourth badge cannot be told
              apart from "this signal is not wired", which is the reading the issue thread warns
              about for DISPUTE_OPENED. */}
          {state.states.map(s => (
            <span key={s} className="tag" style={{ fontSize: '11px' }} data-testid={`adverse-${s}`}>
              {label(s)}
            </span>
          ))}
        </div>
      )}

      {state.kind === 'unknown' && (
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', color: 'var(--text-secondary)' }}>
          <HelpCircle size={16} />
          <span>
            {t(
              `Stav nelze zjistit (engagement-service: ${state.why}). NEJDE o potvrzení, že party žádné vyloučení nemá.`,
              `State unavailable (engagement-service: ${state.why}). This is NOT a confirmation that the party has no exclusion.`,
            )}
          </span>
        </div>
      )}
    </div>
  )
}
