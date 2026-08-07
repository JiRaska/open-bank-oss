// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The origination pipeline as a credit desk reads it: where the book is, where it is STUCK, and
// what is aging. Same animated engine as the service map (FlowParticle / NodeShadow / reduced-motion
// respected) — that page is the house benchmark for "a diagram that teaches", and the lending
// console had none of it.
//
// WHAT THE DESIGN IS FOR
// A flat table answers "which applications exist". An underwriter's first question is never that —
// it is "what is waiting on me, and how long has it waited". So the primary object here is a STAGE,
// sized by how much sits in it and tinted by how long the oldest item has been there. The
// application rows stay, one level down.
//
// THE CAP IS PART OF THE PICTURE, NOT A FOOTNOTE
// `/applications/recent` is a capped list (server clamps limit to 1..100). Counts here are therefore
// "of the newest N", never the book. Rendering them as totals would be worse than useless to a
// credit officer: "12 waiting" when 300 wait is a staffing decision made on a wrong number. The cap
// is shown next to the count and the component refuses to imply otherwise.

'use client'

import { useMemo } from 'react'
import { FlowParticle } from '@/components/topology/FlowParticle'
import { NodeShadow } from '@/components/topology/TopologyDefs'
import { useFlowAnimation } from '@/components/topology/useFlowAnimation'
import { ORIGINATION_GRAPH, STATE_LABELS, happyPath, exitStates } from './OriginationFlow'

export type PipelineItem = {
  id: string
  status: string
  createdAt?: string
  requestedAmount?: { amount: number; currency: string }
}

type Props = {
  items: PipelineItem[]
  /** The server-side cap the list was fetched under — shown, never hidden. */
  cap: number
  /**
   * Whole-book totals from `/applications/summary` (#3294). When present the board stops deriving
   * counts from the capped page and stops warning about the cap, because there is no longer a cap
   * to warn about. Absent = the aggregate is not in the deployed build, and the old behaviour —
   * derive and say so — is the honest one.
   */
  summary?: { status: string; count: number; oldestCreatedAt?: string | null }[] | null
  lang?: 'cs' | 'en'
  onSelectStage?: (state: string | null) => void
  selected?: string | null
}

/** Hours the oldest item in a stage has waited → a tone. Thresholds are DELIBERATELY coarse: an
 *  underwriter needs "fine / slipping / stuck", not a gradient nobody can act on.
 *
 *  TERMINAL STATES ARE NEVER TONED BY AGE. A loan that was disbursed four days ago is not a
 *  four-day-old problem — it is a finished one, and painting it red tells a credit officer there
 *  are nine problems where there are none. Age only means something while something is WAITING. */
function ageTone(hours: number | null, terminal: boolean): 'ok' | 'warn' | 'bad' | 'done' {
  if (terminal) return 'done'
  if (hours === null) return 'ok'
  if (hours >= 72) return 'bad'
  if (hours >= 24) return 'warn'
  return 'ok'
}

const TONE_COLOR: Record<'ok' | 'warn' | 'bad' | 'done', string> = {
  ok: 'var(--success)',
  warn: 'var(--warning)',
  bad: 'var(--danger)',
  done: 'var(--text-tertiary)',
}

/** Split a stage name onto at most two lines at a word boundary. */
export function wrap(text: string, max = 15): string[] {
  if (text.length <= max) return [text]
  const words = text.split(' ')
  const lines: string[] = ['']
  for (const w of words) {
    const candidate = lines[lines.length - 1] ? `${lines[lines.length - 1]} ${w}` : w
    if (candidate.length <= max || lines[lines.length - 1] === '') lines[lines.length - 1] = candidate
    else lines.push(w)
  }
  return lines.slice(0, 2)
}

export function summarise(items: PipelineItem[], now = Date.now()) {
  const byState = new Map<string, { count: number; oldestHours: number | null; amount: number }>()
  for (const it of items) {
    const cur = byState.get(it.status) ?? { count: 0, oldestHours: null, amount: 0 }
    cur.count += 1
    cur.amount += it.requestedAmount?.amount ?? 0
    if (it.createdAt) {
      const h = (now - new Date(it.createdAt).getTime()) / 3_600_000
      if (Number.isFinite(h)) cur.oldestHours = cur.oldestHours === null ? h : Math.max(cur.oldestHours, h)
    }
    byState.set(it.status, cur)
  }
  return byState
}

function summariseRows(rows: NonNullable<Props['summary']>, now = Date.now()) {
  return new Map(rows.map(r => [r.status, {
    count: r.count,
    oldestHours: r.oldestCreatedAt ? (now - new Date(r.oldestCreatedAt).getTime()) / 3_600_000 : null,
    amount: 0,
  }]))
}

export function OriginationPipeline({ items, cap, lang = 'cs', onSelectStage, selected, summary }: Props) {
  const [flow, setFlow] = useFlowAnimation()
  const spine = happyPath(ORIGINATION_GRAPH)
  const exits = exitStates(ORIGINATION_GRAPH)
  const byState = useMemo(() => {
    if (summary) return summariseRows(summary)
    return summarise(items)
  }, [items, summary])
  const max = Math.max(1, ...[...byState.values()].map(v => v.count))

  const label = (s: string) => {
    const l = STATE_LABELS[s]
    return l ? (lang === 'cs' ? l.cs : l.en) : s
  }
  const t = (cs: string, en: string) => (lang === 'cs' ? cs : en)

  // Geometry: one column per stage, laid out in an SVG so the connectors can carry motion the way
  // the topology pages do. Width is driven by the stage count, and the viewBox scales it down —
  // 13 stages will not fit at a readable fixed width, and a horizontally scrolled pipeline hides
  // exactly the jam the operator opened the page to find.
  const COL = 118
  const H = 158
  const W = spine.length * COL

  // Only meaningful while the counts come from the page. With whole-book totals there is nothing
  // truncated to disclose, and leaving the warning up would be its own kind of lie.
  const capped = !summary && items.length >= cap

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 8, flexWrap: 'wrap' }}>
        <div className="section-title" style={{ margin: 0 }}>{t('Pipeline žádostí', 'Application pipeline')}</div>
        <span style={{ fontSize: 11, color: capped ? 'var(--warning)' : 'var(--text-tertiary)' }} data-testid="cap-note">
          {summary
            ? t('celá kniha žádostí', 'the whole application book')
            : capped
              ? t(
                  `zobrazeno nejnovějších ${items.length} — starší žádosti v číslech NEJSOU`,
                  `showing the newest ${items.length} — older applications are NOT in these numbers`,
                )
              : t(`${items.length} žádostí (vše, co server vrátil)`, `${items.length} applications (everything the server returned)`)}
        </span>
        <button
          onClick={() => setFlow(f => !f)}
          className="btn btn-secondary"
          style={{ marginLeft: 'auto', fontSize: 11 }}
          aria-pressed={flow}
        >
          {flow ? t('Zastavit animaci', 'Pause motion') : t('Spustit animaci', 'Resume motion')}
        </button>
      </div>

      <div className="card" style={{ padding: 12, overflowX: 'auto' }}>
        <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={H} role="img"
             aria-label={t('Pipeline úvěrových žádostí po stavech', 'Loan application pipeline by stage')}>
          <defs>
            <NodeShadow id="pipe-shadow" />
            {spine.slice(0, -1).map((s, i) => (
              <path key={s} id={`pipe-edge-${i}`} d={`M ${i * COL + 78} 46 L ${(i + 1) * COL + 30} 46`} fill="none" />
            ))}
          </defs>

          {/* One continuous rail under the whole pipeline. Without it an empty stage left a visible
              gap and the pipeline read as BROKEN rather than as idle at that step. */}
          <path d={`M 54 46 L ${(spine.length - 1) * COL + 54} 46`} stroke="var(--border)" strokeWidth={1.5} fill="none" />
          {spine.slice(0, -1).map((s, i) => {
            const live = (byState.get(s)?.count ?? 0) > 0
            return (
              <g key={`e-${s}`}>
                <path d={`M ${i * COL + 78} 46 L ${(i + 1) * COL + 30} 46`}
                      stroke={live ? 'var(--accent)' : 'var(--border)'} strokeWidth={live ? 2 : 1.5} fill="none" />
                {flow && live && (
                  <FlowParticle pathId={`pipe-edge-${i}`} color="var(--accent)" dur={2.2} begin={i * 0.18} />
                )}
              </g>
            )
          })}

          {spine.map((s, i) => {
            const st = byState.get(s)
            const count = st?.count ?? 0
            const tone = ageTone(st?.oldestHours ?? null, ORIGINATION_GRAPH.terminal.includes(s))
            const cx = i * COL + 54
            const r = 15 + (count / max) * 13
            const isSel = selected === s
            const lines = wrap(label(s))
            return (
              <g key={s} data-testid={`stage-${s}`} data-count={count} data-tone={tone}
                 onClick={() => onSelectStage?.(isSel ? null : s)}
                 style={{ cursor: onSelectStage ? 'pointer' : 'default' }}>
                <title>{`${label(s)} — ${count}`}</title>
                <circle cx={cx} cy={46} r={r} filter="url(#pipe-shadow)"
                        fill={count ? 'var(--surface)' : 'var(--surface-2)'}
                        stroke={count ? TONE_COLOR[tone] : 'var(--border)'}
                        strokeWidth={isSel ? 3.5 : count ? 2 : 1.2} />
                <text x={cx} y={51} textAnchor="middle" fontSize={count ? 15 : 12} fontWeight={700}
                      fill={count ? 'var(--text)' : 'var(--text-tertiary)'}>{count}</text>
                {/* Two lines rather than an ellipsis: "Čeká na rozhodnu…" and "Připraveno k čer…"
                    are not names anybody can act on, and the stage name is the whole label. */}
                {lines.map((line, li) => (
                  <text key={li} x={cx} y={82 + li * 11} textAnchor="middle" fontSize={9.5} fill="var(--text-secondary)">
                    {line}
                  </text>
                ))}
                {st?.oldestHours != null && count > 0 && tone !== 'done' && (
                  <text x={cx} y={82 + lines.length * 11 + 3} textAnchor="middle" fontSize={9} fill={TONE_COLOR[tone]} fontWeight={600}>
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
        <span style={{ color: 'var(--text-tertiary)' }}>{t('Stáří nejstarší žádosti ve stavu:', 'Age of the oldest application in a stage:')}</span>
        {(['ok', 'warn', 'bad'] as const).map(k => (
          <span key={k} style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <span style={{ width: 9, height: 9, borderRadius: '50%', background: TONE_COLOR[k] }} />
            {k === 'ok' ? t('do 24 h', 'under 24h') : k === 'warn' ? t('24–72 h', '24–72h') : t('nad 72 h', 'over 72h')}
          </span>
        ))}
        <span style={{ marginLeft: 'auto', color: 'var(--text-tertiary)' }}>
          {t('Ukončené: ', 'Ended: ')}
          {exits.map(s => `${label(s)} ${byState.get(s)?.count ?? 0}`).join(' · ')}
        </span>
      </div>
    </div>
  )
}
