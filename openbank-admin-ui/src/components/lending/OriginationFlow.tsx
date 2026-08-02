// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The ADR-0211 origination lifecycle, drawn for a business reader.
//
// The state list and the edges are NOT written here — they are baked from the Kotlin that runs the
// machine (`scripts/generate-origination-graph.mjs` ← OriginationState.kt +
// OriginationTransitionPolicy.kt). A diagram carrying its own copy of the states would drift from
// the machine while still rendering perfectly convincingly, which is the failure this repo keeps
// paying for. Only the human LABELS live here, and `originationFlow.test.tsx` fails if a state in
// the generated graph has no label — so adding a state in Kotlin surfaces as a red test, not as a
// node silently labelled with a raw enum name.

'use client'

import type { CSSProperties } from 'react'
import graph from '../../../origination-graph.json'

export type OriginationGraph = {
  states: string[]
  terminal: string[]
  edges: Record<string, string[]>
}

export const ORIGINATION_GRAPH = graph as OriginationGraph

/** Business-readable names. Deliberately not "SUBMITTED" — this screen is read by people who
 *  approve loans, not by people who read enums. The raw state stays available as a tooltip. */
export const STATE_LABELS: Record<string, { cs: string; en: string }> = {
  DRAFT: { cs: 'Rozpracováno', en: 'Draft' },
  SUBMITTED: { cs: 'Podáno', en: 'Submitted' },
  KYC_PENDING: { cs: 'Čeká na KYC', en: 'Awaiting KYC' },
  DOCS_REQUIRED: { cs: 'Doložit dokumenty', en: 'Documents required' },
  ASSESSMENT: { cs: 'Posouzení bonity', en: 'Credit assessment' },
  DECISION_PENDING: { cs: 'Čeká na rozhodnutí', en: 'Decision pending' },
  FOUR_EYES: { cs: 'Druhý pár očí', en: 'Four-eyes review' },
  OFFERED: { cs: 'Nabídka vystavena', en: 'Offer issued' },
  AWAITING_SIGNATURE: { cs: 'Čeká na podpis', en: 'Awaiting signature' },
  SIGNED: { cs: 'Podepsáno', en: 'Signed' },
  REFLECTION_PERIOD: { cs: 'Lhůta na rozmyšlenou', en: 'Reflection period' },
  READY_TO_DISBURSE: { cs: 'Připraveno k čerpání', en: 'Ready to disburse' },
  DISBURSED: { cs: 'Vyčerpáno', en: 'Disbursed' },
  WITHDRAWN: { cs: 'Vzato zpět klientem', en: 'Withdrawn by client' },
  DECLINED: { cs: 'Zamítnuto', en: 'Declined' },
  EXPIRED: { cs: 'Propadlo', en: 'Expired' },
}

/**
 * The happy path, DERIVED from the graph rather than listed: follow the FIRST allowed target from
 * DRAFT until a terminal state. The policy writes each state's targets with the forward one first
 * (`DRAFT to setOf(SUBMITTED, WITHDRAWN)`), so this reconstructs the spine without a second
 * hand-kept ordering. `originationFlow.test.tsx` asserts the spine covers every non-terminal
 * state — a new state added off this chain fails the suite instead of vanishing from the diagram.
 */
export function happyPath(g: OriginationGraph = ORIGINATION_GRAPH): string[] {
  const path: string[] = []
  let cur: string | undefined = g.states[0]
  const seen = new Set<string>()
  while (cur && !seen.has(cur)) {
    seen.add(cur)
    path.push(cur)
    if (g.terminal.includes(cur)) break
    cur = g.edges[cur]?.[0]
  }
  return path
}

/** Terminal states that are not the happy ending — the ways an application can stop early. */
export function exitStates(g: OriginationGraph = ORIGINATION_GRAPH): string[] {
  const spine = new Set(happyPath(g))
  return g.terminal.filter(s => !spine.has(s))
}

export type StepFact = {
  state: string
  at?: string | null
  actor?: string | null
  actorKind?: string | null
  reason?: string | null
}

type Props = {
  /** The application's current state, from the service. */
  current: string
  /** What actually happened, oldest first — reconstructed from the ADR-0214 evidence stream. */
  history?: StepFact[]
  lang?: 'cs' | 'en'
}

type NodeTone = 'done' | 'current' | 'future' | 'stopped'

/** Dot colours carry the state. They differ by fill and by glyph, never by shade alone — a reader
 *  scanning the rail should see "done / here / not yet" without comparing greys. */
const DOT: Record<NodeTone, CSSProperties> = {
  done: { background: 'var(--success)', borderColor: 'var(--success)', color: '#fff' },
  current: {
    background: 'var(--accent)',
    borderColor: 'var(--accent)',
    color: '#fff',
    boxShadow: '0 0 0 4px color-mix(in srgb, var(--accent) 26%, transparent)',
  },
  future: { background: 'var(--surface)', borderColor: 'var(--border)', color: 'var(--text-tertiary)' },
  stopped: {
    background: 'var(--danger)',
    borderColor: 'var(--danger)',
    color: '#fff',
    boxShadow: '0 0 0 4px color-mix(in srgb, var(--danger) 24%, transparent)',
  },
}

const MARK: Record<NodeTone, string> = { done: '✓', current: '●', future: '', stopped: '✕' }

export function OriginationFlow({ current, history = [], lang = 'cs' }: Props) {
  const g = ORIGINATION_GRAPH
  const spine = happyPath(g)
  const exits = exitStates(g)
  const visited = new Map(history.map(h => [h.state, h]))
  const currentIndex = spine.indexOf(current)
  const stoppedEarly = exits.includes(current)

  const label = (s: string) => {
    const l = STATE_LABELS[s]
    return l ? (lang === 'cs' ? l.cs : l.en) : s
  }

  const toneFor = (s: string): NodeTone => {
    if (s === current) return stoppedEarly ? 'stopped' : 'current'
    if (visited.has(s)) return 'done'
    // Once an application has stopped, nothing downstream is "coming" — showing the rest as
    // pending would tell an operator to wait for something that will never happen.
    if (stoppedEarly) return 'future'
    const i = spine.indexOf(s)
    return i >= 0 && currentIndex >= 0 && i < currentIndex ? 'done' : 'future'
  }

  /** One rail row: dot + connector on the left, the facts on the right. Vertical because the graph
   *  is 13 steps long — laid out horizontally it needs ~2000px, so on any real screen the operator
   *  would be asked to scroll sideways to see "the whole flow", which is the one thing they asked
   *  to see at once. Top-to-bottom also leaves room for the time and the actor on every step, so
   *  the rail IS the audit trail rather than a picture sitting above a second copy of it. */
  const row = (s: string, step: number | null, isLast: boolean) => {
    const tone = toneFor(s)
    const fact = visited.get(s)
    return (
      <div key={s} data-testid={`node-${s}`} data-tone={tone} title={s} style={{ display: 'flex', gap: 12 }}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: '0 0 auto' }}>
          <div
            style={{
              ...DOT[tone],
              width: 22,
              height: 22,
              borderRadius: '50%',
              border: '1.5px solid',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 11,
              fontWeight: 700,
            }}
          >
            {MARK[tone] || (step ?? '')}
          </div>
          {!isLast && (
            <div
              aria-hidden="true"
              style={{
                width: 2,
                flex: 1,
                minHeight: 18,
                background: tone === 'done'
                  ? 'var(--success)'
                  // --border alone is near-invisible at 2px, which made the not-yet-started tail
                  // read as detached from the flow rather than as its continuation.
                  : 'color-mix(in srgb, var(--text-tertiary) 38%, transparent)',
              }}
            />
          )}
        </div>
        <div style={{ paddingBottom: isLast ? 0 : 14, minWidth: 0 }}>
          <div style={{ fontSize: 13, fontWeight: 600, color: tone === 'future' ? 'var(--text-tertiary)' : 'var(--text)' }}>
            {label(s)}
          </div>
          {(fact?.at || fact?.actor) && (
            <div style={{ fontSize: 11, color: 'var(--text-tertiary)', display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              {fact?.at && <span>{new Date(fact.at).toLocaleString()}</span>}
              {fact?.actor && <span title={fact.actorKind ?? undefined}>{fact.actor}</span>}
              {fact?.actorKind && <span className="pill" style={{ fontSize: 10 }}>{fact.actorKind}</span>}
            </div>
          )}
          {fact?.reason && (
            <div style={{ fontSize: 11, color: 'var(--text-tertiary)', fontStyle: 'italic' }}>{fact.reason}</div>
          )}
        </div>
      </div>
    )
  }

  return (
    <div data-testid="origination-flow">
      <div>{spine.map((s, i) => row(s, i + 1, i === spine.length - 1))}</div>

      <div style={{ fontSize: 11, color: 'var(--text-tertiary)', margin: '14px 0 8px' }}>
        {lang === 'cs' ? 'Předčasná ukončení' : 'Early exits'}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
        {exits.map(s => row(s, null, true))}
      </div>
    </div>
  )
}
