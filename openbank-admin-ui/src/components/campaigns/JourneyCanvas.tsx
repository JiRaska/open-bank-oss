// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useLanguage } from '@/lib/i18n/LanguageContext'

/**
 * The campaign journey drawn as a flow, the way every CDP draws one.
 *
 * The first attempt at this screen was a stacked bar with chips under it. In isolation it looked
 * fine; on the real page it read as a grey strip wedged between tables of UUIDs, and a marketer
 * still had to know what `SUPPRESSED_QUIET_HOURS` meant. The feedback was blunt and correct.
 *
 * What a campaign person needs to see, in this order:
 *   1. how many people entered
 *   2. what happened at each step, as a path they can follow left to right
 *   3. where the ones who dropped out went, and why — in words, on the branch that lost them
 *
 * A flow answers all three in one glance because it has the shape of the thing it describes. A bar
 * chart answers (2) only, and only after you have read a legend.
 *
 * Drawn as SVG, following `docs/service-map` — the console already has a hand-drawn canvas for
 * exactly this job, and a third visual language would be worse than either. Colour still comes from
 * the ADR-0208 D2 variables, never a literal.
 */

export interface SuppressionCount {
  reason: string
  count: number
}

export interface StepFunnel {
  stepOrder: number
  reached: number
  delivered: number
  failed: number
  /** Present from campaign-service 1.32.0; absence during rollout must not invent a branch count. */
  skipped?: number
  suppressed: SuppressionCount[]
}

export interface JourneyStep {
  order: number
  template: string
  delaySeconds: number
  channel?: 'EMAIL' | 'PUSH' | 'BANNER'
  condition?: 'IF_PREVIOUS_CONFIRMED' | 'IF_PREVIOUS_NOT_CONFIRMED'
  conditionSourceOrder?: number
}

export interface JourneyDecision {
  sourceStepOrder: number
  evaluationDelaySeconds: number
  confirmedStepOrder: number
  notConfirmedStepOrder: number
}

export interface DecisionPathSelection {
  sourceStepOrder: number
  selected: 'CONFIRMED' | 'NOT_CONFIRMED'
  nextStepOrder: number
}

const NODE_W = 190
const NODE_H = 78
const GAP_X = 96
const ROW_Y = 84
const DROP_Y = 196
const PAD = 28
// Room for the drop labels hanging off the LAST node. Without it the final branch renders past the
// viewBox and its count is simply not on screen — data hidden by layout, which is worse than ugly.
const LABEL_ALLOWANCE = 230

/** Semantic variable per drop reason — the meaning, not a severity ranking. */
const REASON_VAR: Record<string, string> = {
  DRY_RUN: 'var(--text-secondary)',
  SUPPRESSED_CONSENT: 'var(--info)',
  SUPPRESSED_QUIET_HOURS: 'var(--text-secondary)',
  SUPPRESSED_CAP: 'var(--warning)',
  FAILED: 'var(--danger)',
}

export function JourneyCanvas({
  steps,
  funnel,
  audienceSize,
  decisions = [],
  decisionPaths = [],
}: {
  steps: JourneyStep[]
  funnel: StepFunnel[]
  audienceSize: number | null
  decisions?: JourneyDecision[]
  /** Per-enrolment path evidence; absent during a mixed-version rollout remains unknown. */
  decisionPaths?: DecisionPathSelection[]
}) {
  const { t, language } = useLanguage()
  const locale = language === 'cs' ? 'cs-CZ' : 'en-GB'
  const n = (v: number) => v.toLocaleString(locale)

  const reasonLabel = (r: string): string =>
    ({
      DRY_RUN: t('Nazkoušeno', 'Rehearsed'),
      SUPPRESSED_CONSENT: t('Nemá souhlas', 'No consent'),
      SUPPRESSED_QUIET_HOURS: t('Tiché hodiny', 'Quiet hours'),
      SUPPRESSED_CAP: t('Příliš často', 'Too frequent'),
      FAILED: t('Nedoručeno', 'Delivery failed'),
    })[r] ?? r

  const templateLabel = (tpl: string): string =>
    ({ MARKETING_PRODUCT_OFFER: t('Nabídka produktu', 'Product offer') })[tpl] ?? tpl

  const delayLabel = (s: number): string => {
    if (s <= 0) return t('ihned', 'immediately')
    const d = Math.floor(s / 86400)
    if (d >= 1) return t(`čekat ${d} d`, `wait ${d} d`)
    const h = Math.floor(s / 3600)
    if (h >= 1) return t(`čekat ${h} h`, `wait ${h} h`)
    return t(`čekat ${Math.floor(s / 60)} min`, `wait ${Math.floor(s / 60)} min`)
  }

  const channelLabel = (channel?: JourneyStep['channel']): string =>
    channel === 'PUSH' ? t('push', 'push') : channel === 'BANNER' ? t('banner', 'banner') : t('e-mail', 'email')

  const conditionLabel = (step: JourneyStep): string | null => {
    if (!step.condition) return null
    const source = step.conditionSourceOrder === undefined
      ? t('výsledek předchozího kroku', 'previous-step delivery')
      : t(`krok ${step.conditionSourceOrder + 1}`, `step ${step.conditionSourceOrder + 1}`)
    return step.condition === 'IF_PREVIOUS_CONFIRMED'
      ? t(`${source} doručen`, `${source} delivered`)
      : t(`${source} nedoručen`, `${source} not delivered`)
  }

  const rows = Array.isArray(funnel) ? funnel : []
  const byStep = new Map(rows.map(f => [f.stepOrder, f]))
  const ordered = Array.isArray(steps) ? [...steps].sort((a, b) => a.order - b.order) : []

  // Entry node + one node per step.
  const cols = ordered.length + 1
  const maxDrops = Math.max(1, ...rows.map(f => (f.suppressed?.length ?? 0) + (f.failed > 0 ? 1 : 0)))
  const width = PAD * 2 + cols * NODE_W + (cols - 1) * GAP_X + LABEL_ALLOWANCE
  const height = DROP_Y + maxDrops * 26 + 24

  const colX = (i: number) => PAD + i * (NODE_W + GAP_X)
  const anyActivity = rows.some(f => f.reached > 0)
  const pathCount = (sourceStepOrder: number, selected: DecisionPathSelection['selected']) =>
    decisionPaths.filter(path => path.sourceStepOrder === sourceStepOrder && path.selected === selected).length

  return (
    <div className="overflow-x-auto rounded-xl border" style={{ background: 'var(--surface)' }}>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        style={{ width: '100%', minWidth: Math.min(width, 900), height: 'auto', display: 'block' }}
        role="img"
        aria-label={t('Schéma průchodu kampaní', 'Campaign journey diagram')}
      >
        <defs>
          <marker id="jc-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 Z" fill="var(--border-strong)" />
          </marker>
          <filter id="jc-shadow" x="-20%" y="-20%" width="140%" height="140%">
            <feDropShadow dx="0" dy="1" stdDeviation="1.5" floodOpacity="0.10" />
          </filter>
        </defs>

        {/* Entry: the audience, stated as what it is — a segment size before any filtering. */}
        <g filter="url(#jc-shadow)">
          <rect
            x={colX(0)} y={ROW_Y - NODE_H / 2} width={NODE_W} height={NODE_H} rx="14"
            fill="var(--surface-2)" stroke="var(--border-strong)" strokeWidth="1.2"
          />
          <text x={colX(0) + 16} y={ROW_Y - 16} fontSize="11" fill="var(--text-secondary)">
            {t('VSTUP', 'ENTRY')}
          </text>
          <text x={colX(0) + 16} y={ROW_Y + 8} fontSize="24" fontWeight="600" fill="var(--text-primary)">
            {audienceSize === null ? '—' : n(audienceSize)}
          </text>
          <text x={colX(0) + 16} y={ROW_Y + 26} fontSize="11" fill="var(--text-secondary)">
            {t('lidí v segmentu', 'people in segment')}
          </text>
        </g>

        {ordered.map((step, i) => {
          const f = byStep.get(step.order)
          const reached = f?.reached ?? 0
          const delivered = f?.delivered ?? 0
          const skipped = f?.skipped
          // A skipped conditional record proves the *other* reviewed branch was chosen. It is not
          // a delivery attempt, failure or policy suppression, and must not inflate this path.
          const pathTaken = step.condition && skipped !== undefined ? Math.max(0, reached - skipped) : reached
          const branch = conditionLabel(step)
          const drops: SuppressionCount[] = [
            ...(f?.suppressed ?? []),
            ...(f && f.failed > 0 ? [{ reason: 'FAILED', count: f.failed }] : []),
          ]
          const x = colX(i + 1)
          const prevX = colX(i)
          const midX = (prevX + NODE_W + x) / 2

          return (
            <g key={step.order}>
              {/* Edge in, carrying the wait and how many arrived. The number rides the line rather
                  than sitting in a column, so "where did they go" is answered where it is asked. */}
              <path
                d={`M ${prevX + NODE_W} ${ROW_Y} C ${midX} ${ROW_Y} ${midX} ${ROW_Y} ${x} ${ROW_Y}`}
                stroke="var(--border-strong)" strokeWidth="1.6" fill="none" markerEnd="url(#jc-arrow)"
              />
              <text x={midX} y={ROW_Y - 12} fontSize="11" textAnchor="middle" fill="var(--text-secondary)">
                {delayLabel(step.delaySeconds)}
              </text>
              {pathTaken > 0 && (
                <text x={midX} y={ROW_Y + 20} fontSize="12" textAnchor="middle" fontWeight="600" fill="var(--text-primary)">
                  {n(pathTaken)}
                </text>
              )}
              {branch && (
                <text x={midX} y={ROW_Y + 34} fontSize="10" textAnchor="middle" fill="var(--text-secondary)" data-branch={step.condition}>
                  {branch}
                </text>
              )}

              <g filter="url(#jc-shadow)">
                <rect
                  x={x} y={ROW_Y - NODE_H / 2} width={NODE_W} height={NODE_H} rx="14"
                  fill="var(--surface-2)" stroke="var(--border-strong)" strokeWidth="1.2"
                />
                <text x={x + 16} y={ROW_Y - 16} fontSize="11" fill="var(--text-secondary)">
                  {t('KROK', 'STEP')} {step.order} · {channelLabel(step.channel)}
                </text>
                <text x={x + 16} y={ROW_Y + 6} fontSize="14" fontWeight="600" fill="var(--text-primary)">
                  {templateLabel(step.template)}
                </text>
                <text x={x + 16} y={ROW_Y + 26} fontSize="12" fill="var(--text-secondary)">
                  <tspan fill={delivered > 0 ? 'var(--success)' : 'var(--text-secondary)'} fontWeight="600">
                    {n(delivered)}
                  </tspan>
                  {' '}{t('doručeno', 'delivered')}
                </text>
                {branch && skipped !== undefined && (
                  <text x={x + 16} y={ROW_Y + 40} fontSize="10" fill="var(--text-secondary)">
                    {n(skipped)} {t('jinou cestou', 'took another path')}
                  </text>
                )}
              </g>

              {/* Drop branches: one line per reason, labelled in words. A marketer reads "12 nemá
                  souhlas" and knows both the size and the cause without a legend. */}
              {drops.map((d, k) => {
                // Fan the branch origins apart. Sharing one origin drew three curves through the
                // same pixels, which reads as one thick line rather than three outcomes.
                const bx = x + NODE_W / 2 - 12 + k * 12
                const by = DROP_Y + k * 26
                return (
                  <g key={d.reason}>
                    <path
                      d={`M ${bx} ${ROW_Y + NODE_H / 2} C ${bx} ${by} ${bx} ${by} ${bx + 26} ${by}`}
                      stroke={REASON_VAR[d.reason] ?? 'var(--border-strong)'}
                      strokeWidth="1.6" fill="none" strokeDasharray="4 3"
                    />
                    <circle cx={bx + 26} cy={by} r="3.5" fill={REASON_VAR[d.reason] ?? 'var(--border-strong)'} />
                    {/* The enum stays in <title> so the screen and the API cannot drift apart. */}
                    {/* The raw outcome rides in a data attribute, not a <title> child: an SVG
                        <title> inside <text> counts as text content, so the enum would be "on the
                        screen" for anything reading textContent — including the guard that exists
                        to keep enums off it. */}
                    <text
                      x={bx + 36} y={by + 4} fontSize="12" fill="var(--text-secondary)"
                      data-outcome={d.reason}
                    >
                      <tspan fontWeight="600" fill="var(--text-primary)">{n(d.count)}</tspan>
                      {' '}{reasonLabel(d.reason)}
                    </text>
                  </g>
                )
              })}
            </g>
          )
        })}

        {!anyActivity && (
          <text x={width / 2} y={DROP_Y + 40} fontSize="13" textAnchor="middle" fill="var(--text-secondary)">
            {t(
              'Kampaň zatím nikoho neoslovila — po zařazení publika se tu objeví cesta.',
              'Nobody has entered yet — the path appears once the audience is enrolled.',
            )}
          </text>
        )}
      </svg>
      {decisions.length > 0 && (
        <div className="grid gap-2 border-t px-4 py-3 sm:grid-cols-2" data-decision-outcomes>
          {decisions.map(decision => (
            <div key={decision.sourceStepOrder} className="rounded-lg border bg-background px-3 py-2 text-xs">
              <p className="font-medium text-foreground">
                {t(`Rozhodnutí po kroku ${decision.sourceStepOrder}`, `Decision after step ${decision.sourceStepOrder}`)}
              </p>
              <p className="mt-1 text-muted-foreground">
                {t(`${pathCount(decision.sourceStepOrder, 'CONFIRMED')}× potvrzeno → krok ${decision.confirmedStepOrder}; `,
                  `${pathCount(decision.sourceStepOrder, 'CONFIRMED')}× confirmed → step ${decision.confirmedStepOrder}; `)}
                {t(`${pathCount(decision.sourceStepOrder, 'NOT_CONFIRMED')}× nepotvrzeno → krok ${decision.notConfirmedStepOrder}`,
                  `${pathCount(decision.sourceStepOrder, 'NOT_CONFIRMED')}× not confirmed → step ${decision.notConfirmedStepOrder}`)}
              </p>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
