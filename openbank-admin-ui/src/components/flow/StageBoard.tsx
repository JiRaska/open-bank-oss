// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// A lifecycle drawn as a board: one bubble per stage, sized by how much sits in it and tinted by
// how long the OLDEST item there has waited.
//
// WHY GENERIC
// The lending console grew this shape first (its origination pipeline). Campaigns need the same
// answer to the same question — where is work piling up, and what has been stuck longest — and a
// third hand-drawn variant would be a third visual language on the same console. So the geometry,
// the motion and the age semantics live here once, and each domain supplies only its own stages and
// labels. (The lending pipeline is a sibling PR; it should adopt this once both have landed —
// deliberately not refactored across an open branch.)
//
// AGE MEANS NOTHING ON A TERMINAL STAGE
// Learned the hard way on the lending board: tinting terminal stages by age painted successfully
// finished work red and told the desk it had problems it did not have. A stage marked terminal is
// never toned by age here.
//
// Motion follows the service map: SMIL particles along the connectors, off automatically under
// `prefers-reduced-motion`, and with an explicit control so anyone can stop it.

'use client'

import { FlowParticle } from '@/components/topology/FlowParticle'
import { NodeShadow } from '@/components/topology/TopologyDefs'
import { useFlowAnimation } from '@/components/topology/useFlowAnimation'

export type StageTone = 'ok' | 'warn' | 'bad' | 'done'

export type StageDef = {
  /** Machine value — also the test id and the tooltip, so the board and the state machine can
   *  never end up describing different things. */
  key: string
  label: string
  /** Terminal stages end the lifecycle; they are never toned by age. */
  terminal?: boolean
}

export type StageStat = { count: number; oldestHours: number | null }

type Props = {
  stages: StageDef[]
  stats: Map<string, StageStat>
  /** Hours after which a waiting stage is amber / red. */
  warnAfterHours?: number
  badAfterHours?: number
  selected?: string | null
  onSelect?: (key: string | null) => void
  lang?: 'cs' | 'en'
  /** Rendered under the board, e.g. the honest note about what the numbers do not include. */
  footnote?: string
  ariaLabel: string
}

const TONE_COLOR: Record<StageTone, string> = {
  ok: 'var(--success)',
  warn: 'var(--warning)',
  bad: 'var(--danger)',
  done: 'var(--text-tertiary)',
}

export function toneFor(
  stat: StageStat | undefined,
  terminal: boolean,
  warnAfter: number,
  badAfter: number,
): StageTone {
  if (terminal) return 'done'
  const h = stat?.oldestHours ?? null
  if (h === null) return 'ok'
  if (h >= badAfter) return 'bad'
  if (h >= warnAfter) return 'warn'
  return 'ok'
}

/** Split a stage name onto at most two lines at a word boundary. Truncation was tried first and
 *  produced names nobody could act on ("Připraveno k čer…"). */
export function wrapLabel(text: string, max = 15): string[] {
  if (text.length <= max) return [text]
  const lines: string[] = ['']
  for (const w of text.split(' ')) {
    const candidate = lines[lines.length - 1] ? `${lines[lines.length - 1]} ${w}` : w
    if (candidate.length <= max || lines[lines.length - 1] === '') lines[lines.length - 1] = candidate
    else lines.push(w)
  }
  return lines.slice(0, 2)
}

/** Group items by a state accessor and record the OLDEST age per state — the queue's health is set
 *  by the item that has waited longest, not by the average, which hides exactly that item. */
export function summariseBy<T>(
  items: T[],
  state: (t: T) => string,
  since: (t: T) => string | undefined,
  now = Date.now(),
): Map<string, StageStat> {
  const out = new Map<string, StageStat>()
  for (const it of items) {
    const k = state(it)
    const cur = out.get(k) ?? { count: 0, oldestHours: null }
    cur.count += 1
    const raw = since(it)
    if (raw) {
      const h = (now - new Date(raw).getTime()) / 3_600_000
      if (Number.isFinite(h)) cur.oldestHours = cur.oldestHours === null ? h : Math.max(cur.oldestHours, h)
    }
    out.set(k, cur)
  }
  return out
}

export function StageBoard({
  stages,
  stats,
  warnAfterHours = 24,
  badAfterHours = 72,
  selected,
  onSelect,
  lang = 'cs',
  footnote,
  ariaLabel,
}: Props) {
  const [flow, setFlow] = useFlowAnimation()
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)
  const max = Math.max(1, ...[...stats.values()].map(v => v.count))

  const COL = 132
  const H = 158
  const W = stages.length * COL

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 6 }}>
        <button onClick={() => setFlow(f => !f)} className="btn btn-secondary" style={{ fontSize: 11 }} aria-pressed={flow}>
          {flow ? t('Zastavit animaci', 'Pause motion') : t('Spustit animaci', 'Resume motion')}
        </button>
      </div>

      <div className="card" style={{ padding: 12, overflowX: 'auto' }}>
        {/* Capped at its natural width: with a short lifecycle (campaigns have five states) a
            stretched-to-100% viewBox letterboxes the drawing into the middle of a very wide card
            and the board reads as a lonely strip. Long lifecycles still fill the width and scroll. */}
        <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={H} role="img" aria-label={ariaLabel}
             preserveAspectRatio="xMinYMid meet" style={{ maxWidth: W, display: 'block' }}>
          <defs>
            <NodeShadow id="stage-shadow" />
            {stages.slice(0, -1).map((s, i) => (
              <path key={s.key} id={`stage-edge-${i}`} d={`M ${i * COL + 84} 46 L ${(i + 1) * COL + 42} 46`} fill="none" />
            ))}
          </defs>

          {/* One continuous rail: without it an empty stage left a gap and the lifecycle read as
              BROKEN rather than as idle at that step. */}
          <path d={`M 60 46 L ${(stages.length - 1) * COL + 60} 46`} stroke="var(--border)" strokeWidth={1.5} fill="none" />

          {stages.slice(0, -1).map((s, i) => {
            const live = (stats.get(s.key)?.count ?? 0) > 0
            return (
              <g key={`e-${s.key}`}>
                <path d={`M ${i * COL + 84} 46 L ${(i + 1) * COL + 42} 46`}
                      stroke={live ? 'var(--accent)' : 'var(--border)'} strokeWidth={live ? 2 : 1.5} fill="none" />
                {flow && live && <FlowParticle pathId={`stage-edge-${i}`} color="var(--accent)" dur={2.2} begin={i * 0.2} />}
              </g>
            )
          })}

          {stages.map((s, i) => {
            const st = stats.get(s.key)
            const count = st?.count ?? 0
            const tone = toneFor(st, !!s.terminal, warnAfterHours, badAfterHours)
            const cx = i * COL + 60
            const r = 16 + (count / max) * 13
            const isSel = selected === s.key
            const lines = wrapLabel(s.label)
            return (
              <g key={s.key} data-testid={`stage-${s.key}`} data-count={count} data-tone={tone}
                 onClick={() => onSelect?.(isSel ? null : s.key)}
                 style={{ cursor: onSelect ? 'pointer' : 'default' }}>
                <title>{`${s.label} — ${count}`}</title>
                <circle cx={cx} cy={46} r={r} filter="url(#stage-shadow)"
                        fill={count ? 'var(--surface)' : 'var(--surface-2)'}
                        stroke={count ? TONE_COLOR[tone] : 'var(--border)'}
                        strokeWidth={isSel ? 3.5 : count ? 2 : 1.2} />
                <text x={cx} y={51} textAnchor="middle" fontSize={count ? 15 : 12} fontWeight={700}
                      fill={count ? 'var(--text)' : 'var(--text-tertiary)'}>{count}</text>
                {lines.map((line, li) => (
                  <text key={li} x={cx} y={82 + li * 11} textAnchor="middle" fontSize={9.5} fill="var(--text-secondary)">
                    {line}
                  </text>
                ))}
                {st?.oldestHours != null && count > 0 && tone !== 'done' && (
                  <text x={cx} y={82 + lines.length * 11 + 3} textAnchor="middle" fontSize={9}
                        fill={TONE_COLOR[tone]} fontWeight={600}>
                    {st.oldestHours >= 24
                      ? t(`nejstarší ${Math.floor(st.oldestHours / 24)} d`, `oldest ${Math.floor(st.oldestHours / 24)}d`)
                      : t(`nejstarší ${Math.floor(st.oldestHours)} h`, `oldest ${Math.floor(st.oldestHours)}h`)}
                  </text>
                )}
              </g>
            )
          })}
        </svg>
      </div>

      <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', marginTop: 10, fontSize: 11, alignItems: 'center' }}>
        <span style={{ color: 'var(--text-tertiary)' }}>{t('Stáří nejstarší položky ve stavu:', 'Age of the oldest item in a stage:')}</span>
        {(['ok', 'warn', 'bad'] as const).map(k => (
          <span key={k} style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <span style={{ width: 9, height: 9, borderRadius: '50%', background: TONE_COLOR[k] }} />
            {k === 'ok'
              ? t(`do ${warnAfterHours} h`, `under ${warnAfterHours}h`)
              : k === 'warn'
                ? t(`${warnAfterHours}–${badAfterHours} h`, `${warnAfterHours}–${badAfterHours}h`)
                : t(`nad ${badAfterHours} h`, `over ${badAfterHours}h`)}
          </span>
        ))}
        {footnote && <span data-testid="board-footnote" style={{ marginLeft: 'auto', color: 'var(--text-tertiary)' }}>{footnote}</span>}
      </div>
    </div>
  )
}
