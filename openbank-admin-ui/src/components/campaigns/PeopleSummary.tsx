// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'
import { StatusBadge, type Tone } from '@/components/ui'

/**
 * Where the people in a campaign currently are, counted by state.
 *
 * This replaces a per-person table of party UUIDs as the default view. The table was not badly
 * formatted — it answered a question a campaign person does not have. "What happened to
 * `05a02ef1`" is a lookup you perform when someone complains; "how many are still running and how
 * many stopped, and why" is what you open the screen for. Showing the lookup by default made the
 * screen read as a database dump.
 *
 * The per-person rows are still one click away, and deliberately labelled as the debugging view.
 * They are the only place an individual case can be traced, so hiding them entirely would trade one
 * unusable screen for another.
 */

export interface Enrolment {
  id: string
  partyId: string
  state: string
  currentStep: number
}

/** Colour by meaning: still running, finished normally, or stopped by a rule. */
const STATE_TONE: Record<string, Tone> = {
  ACTIVE: 'success',
  COMPLETED: 'info',
  TERMINATED_CONSENT_REVOKED: 'warning',
  TERMINATED_SUPPRESSED: 'neutral',
  TERMINATED_CAMPAIGN_CLOSED: 'neutral',
  STOPPED_MAX_SENDS: 'neutral',
}

export function PeopleSummary({
  enrolments,
  partyNames = {},
}: {
  enrolments: Enrolment[]
  /** id → display name. A missing entry falls back to the short id, never to a blank cell. */
  partyNames?: Record<string, string>
}) {
  const { t, language } = useLanguage()
  const n = (v: number) => v.toLocaleString(language === 'cs' ? 'cs-CZ' : 'en-GB')

  /** Plain language. The enum stays on the row's `data-state`, never in the visible text. */
  const stateLabel = (s: string): string =>
    ({
      ACTIVE: t('Prochází kampaní', 'Still in the journey'),
      COMPLETED: t('Dokončili cestu', 'Finished the journey'),
      TERMINATED_CONSENT_REVOKED: t('Odvolali souhlas', 'Withdrew consent'),
      TERMINATED_SUPPRESSED: t('Zastaveni pravidlem', 'Stopped by a rule'),
      TERMINATED_CAMPAIGN_CLOSED: t('Kampaň byla uzavřena', 'Campaign was closed'),
      STOPPED_MAX_SENDS: t('Dosažen limit odeslání', 'Send cap reached'),
    })[s] ?? s

  const rows = Array.isArray(enrolments) ? enrolments : []
  const counts = rows.reduce<Record<string, number>>((acc, e) => {
    acc[e.state] = (acc[e.state] ?? 0) + 1
    return acc
  }, {})
  const ordered = Object.entries(counts).sort((a, b) => b[1] - a[1])

  if (rows.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        {t('Zatím nikdo nebyl zařazen.', 'Nobody has been enrolled yet.')}
      </p>
    )
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2">
        {ordered.map(([state, count]) => (
          <div
            key={state}
            data-state={state}
            className="rounded-lg border px-4 py-3"
            style={{ minWidth: 168 }}
          >
            <div className="text-2xl font-semibold tabular-nums">{n(count)}</div>
            <div className="mt-0.5 text-xs text-muted-foreground">{stateLabel(state)}</div>
          </div>
        ))}
      </div>

      {/* Collapsed by default and named for what it is. An operator tracing one complaint needs
          this; nobody deciding whether the campaign works does. */}
      <details className="rounded-lg border">
        <summary className="cursor-pointer px-4 py-2 text-sm text-muted-foreground">
          {t('Jednotliví lidé (pro ladění)', 'Individual people (for debugging)')} — {n(rows.length)}
        </summary>
        <div className="overflow-x-auto border-t">
          <table className="data-table">
            <thead>
              <tr>
                <th>{t('Party', 'Party')}</th>
                <th>{t('Stav', 'State')}</th>
                <th>{t('Krok', 'Step')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(e => (
                <tr key={e.id} data-state={e.state}>
                  {/* Name when we have it, short id when we do not — the full id stays in `title`,
                      because the id is what goes into a support ticket. */}
                  <td className="text-xs" title={e.partyId}>
                    {partyNames[e.partyId] ?? <span className="font-mono">{e.partyId.slice(0, 8)}</span>}
                  </td>
                  <td>
                    <StatusBadge status={e.state} label={stateLabel(e.state)} tone={STATE_TONE[e.state] ?? 'neutral'} />
                  </td>
                  <td>{e.currentStep}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  )
}
